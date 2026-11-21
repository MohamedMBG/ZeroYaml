-- Dependency-aware scheduling (#170) decides which Jobs of a run may start from
-- the recorded state alone, including after a Control Plane restart, so each
-- pipeline Job stores the names of the steps it depends on. It also records a
-- Job that never ran because a dependency ended without success as SKIPPED.
ALTER TABLE job ADD COLUMN step_dependencies JSONB;

-- Runs recorded before this migration were executed strictly in step order: a
-- step started only after every step recorded before it in its run succeeded.
-- Each existing step therefore depends on every other step name recorded
-- earlier in its run, in (step_position, id) order, which keeps the order those
-- runs were promised. A step recorded first depends on nothing.
UPDATE job
SET step_dependencies = COALESCE((
    SELECT jsonb_agg(earlier.step_name ORDER BY earlier.first_position, earlier.step_name)
    FROM (
        SELECT other.step_name, MIN(other.step_position) AS first_position
        FROM job other
        WHERE other.pipeline_run_id = job.pipeline_run_id
          AND other.step_name <> job.step_name
          AND (other.step_position, other.id) < (job.step_position, job.id)
        GROUP BY other.step_name
    ) earlier
), '[]'::jsonb)
WHERE pipeline_run_id IS NOT NULL;

-- A pipeline Job always has a dependency list, possibly empty; a Job outside a
-- pipeline has none.
ALTER TABLE job ADD CONSTRAINT job_step_dependencies_check CHECK (
    (pipeline_run_id IS NULL) = (step_dependencies IS NULL)
    AND (step_dependencies IS NULL OR jsonb_typeof(step_dependencies) = 'array')
);

ALTER TABLE job DROP CONSTRAINT job_status_check;
ALTER TABLE job ADD CONSTRAINT job_status_check CHECK (
    status IN ('CREATED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED')
);

-- A skipped Job was never queued or started; only its completion is recorded.
ALTER TABLE job DROP CONSTRAINT job_lifecycle_check;
ALTER TABLE job ADD CONSTRAINT job_lifecycle_check CHECK (
    (status = 'CREATED' AND queued_at IS NULL AND started_at IS NULL AND completed_at IS NULL)
    OR (status = 'QUEUED' AND queued_at IS NOT NULL AND started_at IS NULL AND completed_at IS NULL)
    OR (status = 'RUNNING' AND queued_at IS NOT NULL AND started_at IS NOT NULL AND completed_at IS NULL)
    OR (status IN ('SUCCEEDED', 'FAILED') AND queued_at IS NOT NULL AND started_at IS NOT NULL AND completed_at IS NOT NULL)
    OR (status = 'CANCELLED' AND completed_at IS NOT NULL)
    OR (status = 'SKIPPED' AND queued_at IS NULL AND started_at IS NULL AND completed_at IS NOT NULL)
);

-- A skipped Job was never dispatched, so, like a created Job, it has no Runner.
ALTER TABLE job DROP CONSTRAINT job_assignment_check;
ALTER TABLE job ADD CONSTRAINT job_assignment_check CHECK (
    (runner_id IS NULL) = (runner_instance_id IS NULL)
    AND (started_at IS NULL OR runner_id IS NOT NULL)
    AND (status NOT IN ('CREATED', 'SKIPPED') OR runner_id IS NULL)
);

-- Rollback: application code that predates this migration cannot read SKIPPED.
-- Before restoring the V3 constraints, change SKIPPED rows to CANCELLED (their
-- columns already satisfy the CANCELLED rule) and drop step_dependencies.
-- job_failure_check is unchanged: a SKIPPED Job, like every state other than
-- SUCCEEDED and FAILED, has no failure or exit code.
