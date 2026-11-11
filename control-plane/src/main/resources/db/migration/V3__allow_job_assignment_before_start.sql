-- A Job is bound to its Runner when it is dispatched, before that Runner reports
-- the start of execution (#30). A queued Job may therefore carry an assignment;
-- a started Job must, and a Job that was never queued must not.
--
-- Every row valid under V2 is valid under this constraint. Rolling back to the
-- V2 constraint fails while a queued or cancelled-before-start Job still holds an
-- assignment; clear runner_id and runner_instance_id on those rows first.
ALTER TABLE job DROP CONSTRAINT job_assignment_check;

ALTER TABLE job ADD CONSTRAINT job_assignment_check CHECK (
    (runner_id IS NULL) = (runner_instance_id IS NULL)
    AND (started_at IS NULL OR runner_id IS NOT NULL)
    AND (status <> 'CREATED' OR runner_id IS NULL)
);
