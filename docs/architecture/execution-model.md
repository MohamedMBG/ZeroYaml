# Execution Model v1

Execution model v1 is the versioned contract for what a pipeline run is made
of, which states its pipeline and Jobs move through, and which outcomes end
them. The Control Plane owns it; the Runner sees only the Job lifecycle, through
the `runner.v1` contract.

The model builds on the [pipeline model](./pipeline-model.md) and the
[Job model](./job-model.md). Those documents describe the values; this one
fixes the parts that both services and later Phase 3 work rely on.

## Version

The version is `v1`, the same as the `zeroyaml.runner.v1` protobuf package that
carries the Job lifecycle to the Runner. The two move together:

- Adding a step or Job field that both services can ignore safely is a
  compatible change within `v1`.
- Adding, removing, or renaming a Job lifecycle state, or changing an allowed
  transition, is a cross-service contract change. It needs a proto change, both
  service test suites, and a documented compatibility impact.
- An incompatible change, such as reusing a state for a different meaning,
  needs a new model version and a new protobuf package. A removed state's enum
  number is never reused.

## Pipeline plan

A `Pipeline` is the immutable plan for one repository revision: 1 to 50
`PipelineStep` values in a fixed order. Each step has a unique name, one
`ExecutionDefinition`, and `dependsOn`, the names of the steps whose Jobs must
succeed before its Job may start.

Dependency rules, checked when the values are constructed:

- A dependency uses the step name format and is listed at most once per step.
- A step must not depend on itself.
- Every dependency must name an earlier step of the same pipeline.

Because dependencies point only backwards, the supplied order is always a valid
execution order and the dependency graph can never contain a cycle. An empty
`dependsOn` means the step waits for nothing. The inferred Maven pipeline is
`build`, then `test` with `dependsOn = [build]`.

Each step's dependencies are recorded with its Job (`job.step_dependencies`),
so a run is scheduled from the database alone, including after a Control Plane
restart. How they are used is described in
[Dependency-aware scheduling](#dependency-aware-scheduling).

## Dependency-aware scheduling

`PipelineSchedule` (in `io.zeroyaml.controlplane.domain.pipeline`) decides what
each recorded step of a run needs next. It is a pure function of the recorded
step names, dependencies, and Job states, so the same state always gives the
same decisions, and nothing about the graph is kept in memory between calls.

| Recorded Job state | Dependencies | Decision |
| --- | --- | --- |
| `CREATED` | all `SUCCEEDED`, or none | `Ready`: dispatch now |
| `CREATED` | at least one `FAILED`, `CANCELLED`, `SKIPPED`, or itself to be skipped | `Skip`: record `SKIPPED`, naming those dependencies |
| `CREATED` | otherwise | `Blocked`: wait, naming the dependencies still pending |
| `QUEUED` | not consulted | `Ready`: the dispatch is still open |
| `RUNNING` | not consulted | `Running` |
| terminal | not consulted | `Settled` |

Rules that follow from the table:

- A Job starts only after every step it depends on succeeded.
- Skipping is transitive: when `build` fails, `test` (which depends on `build`)
  and `package` (which depends on `test`) are both skipped in the same pass.
- A failure affects only the steps that depend on it, directly or transitively.
  Independent steps keep running, and a step whose dependencies all succeeded
  still runs after an unrelated step failed.
- A dependency that names no recorded step can never succeed, so the step is
  skipped. Validated plans cannot produce one; rejecting such plans before
  dispatch is #173.
- When a run records more than one Job for a step name, for example after its
  plan changed for the same revision, a dependency on that name is satisfied
  only when every one of them succeeded.

`PipelineProgression` applies the schedule after a push, after each terminal
status report, and on the queued-Job retry scan. Within one call it records
every skip first and then dispatches every ready step, both in step order, so
the order of dispatch is deterministic and independent steps are dispatched
together. A dispatch that is abandoned (cancelled) can make more steps
skippable, so the schedule is computed again until a pass abandons nothing.
Skipping publishes no lifecycle event, as with cancellation. See
[Pipeline orchestration](./pipeline-orchestration.md#step-progression).

## Job lifecycle

Each step becomes one Job. `JobStatus.canTransitionTo` is the single source of
the allowed transitions, and `Job` enforces it.

| From | Allowed targets |
| --- | --- |
| `CREATED` | `QUEUED`, `CANCELLED`, `SKIPPED` |
| `QUEUED` | `RUNNING`, `CANCELLED` |
| `RUNNING` | `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `SUCCEEDED`, `FAILED`, `CANCELLED`, `SKIPPED` | none |

Only `RUNNING` reaches `SUCCEEDED` or `FAILED`, because an outcome is observed
only for an execution that started. Any non-terminal state may be cancelled.
Only `CREATED` reaches `SKIPPED`, because a Job is queued only after its
dependencies succeeded. A terminal state allows no transition, including to
itself.

`SKIPPED` and `CANCELLED` are different decisions. `CANCELLED` records that the
Control Plane stopped a Job, for example after a dispatch that can never
succeed. `SKIPPED` records that a Job was never eligible to start because a step
it depends on ended without success. A skipped Job has a completion time and
nothing else: no queue time, start time, Runner assignment, or failure.

## Pipeline lifecycle

`PipelineStatus.canTransitionTo` defines the run's transitions.

| From | Allowed targets |
| --- | --- |
| `PENDING` | `RUNNING`, `CANCELLED` |
| `RUNNING` | `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `SUCCEEDED`, `FAILED`, `CANCELLED` | none |

A run is `PENDING` until the Job of one of its steps starts, and `RUNNING` from
then until it ends. `SUCCEEDED` and `FAILED` need a started Job, as in the Job
lifecycle. A run whose first Job is cancelled before it starts goes from
`PENDING` to `CANCELLED`. Scheduling (#170) decides when a run has nothing left
to execute; deriving and recording the run status from its Job outcomes belongs
to #171, because cancellation decides whether a run that both failed and was
cancelled ends `FAILED` or `CANCELLED`.

## Terminal outcomes

`SUCCEEDED`, `FAILED`, and `CANCELLED` are the terminal states of a run. A Job
has the same three plus `SKIPPED`. Each terminal Job keeps its outcome data: a
failed Job keeps its `JobFailure` (stable code, safe message, and the exit code
when one exists), and a cancelled or skipped Job never gets a fabricated
failure. A run's `PipelineOutcome` names
the failed step and its `JobFailure`, or the cancelled step.

## Cross-service contract

The Job lifecycle crosses the service boundary once, in the
`JobLifecycleState` that a status report answer carries back to the Runner
([status contract](./job-status-contract.md)). The three representations
correspond one to one by name:

| Control Plane `JobStatus` | `runner.v1` `JobLifecycleState` | Runner `jobstatus.LifecycleState` |
| --- | --- | --- |
| `CREATED` | `JOB_LIFECYCLE_CREATED` | `created` |
| `QUEUED` | `JOB_LIFECYCLE_QUEUED` | `queued` |
| `RUNNING` | `JOB_LIFECYCLE_RUNNING` | `running` |
| `SUCCEEDED` | `JOB_LIFECYCLE_SUCCEEDED` | `succeeded` |
| `FAILED` | `JOB_LIFECYCLE_FAILED` | `failed` |
| `CANCELLED` | `JOB_LIFECYCLE_CANCELLED` | `cancelled` |
| `SKIPPED` | `JOB_LIFECYCLE_SKIPPED` | `skipped` |

`JOB_LIFECYCLE_STATE_UNSPECIFIED` maps to the Runner's `unspecified` and to no
Control Plane state. Contract tests on both sides fail the build when the
mapping drifts: `JobLifecycleStateContractTest` in the Control Plane and
`TestLifecycleMappingCoversEveryRunnerV1State` in the Runner.

`JOB_LIFECYCLE_SKIPPED = 7` was added for #170. It is a compatible addition
within `v1`: no field or number changed, and a Runner built before it reads the
unknown value as `unspecified`. A Runner never receives a skipped Job, so it
sees the state only in the answer to a report the Control Plane refuses.

The pipeline plan and pipeline lifecycle do not cross the boundary. A Runner
receives one Job at a time through `RunJob` and never decides dependencies,
ordering, or run outcome.
