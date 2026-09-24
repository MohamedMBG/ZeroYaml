# Job Status Reporting Contract

`JobExecutionStatusService.ReportJobStatus` is the versioned seam that carries
execution facts from a Runner back to the Control Plane. It closes the loop
opened by [`RunJob`](./run-job-contract.md): dispatch says who took the work,
status reporting says what that work did.

The division of responsibility is the same as everywhere else in ZeroYAML:

- The **Runner** reports what it observed. It never decides Job state, retries,
  or pipeline outcome.
- The **Control Plane** owns the authoritative Job state, validates every
  transition, and answers each report with how it reconciled it.

The service is implemented by the Control Plane and served on the same
Runner-facing gRPC endpoint as `RunnerRegistrationService`, so a Runner needs
one Control Plane address.

## Reported states

`JobExecutionState` is smaller than the Job lifecycle on purpose.

| State | Meaning |
| --- | --- |
| `JOB_EXECUTION_RUNNING` | The Runner started executing the Job. |
| `JOB_EXECUTION_SUCCEEDED` | The command ran to completion successfully. |
| `JOB_EXECUTION_FAILED` | The execution did not succeed; a reason says why. |

There is no queued state, because queueing is a Control Plane decision a Runner
never observes, and no cancelled state, because a cancelled or timed-out
execution is a failure with the matching reason. The Job lifecycle keeps its own
`CANCELLED` state for cancellations the Control Plane decides.

## Request

| Field | Meaning |
| --- | --- |
| `protocol_version` | Contract version understood by the caller, currently `runner.v1`. |
| `job_id` | Job identity as dispatched in `RunJobRequest`. |
| `runner_id`, `instance_id` | Runner process that observed the execution. |
| `state` | Reported execution state. |
| `started_at` | When execution began. Repeated on every report, including terminal ones. |
| `completed_at` | When execution ended. Terminal reports only. |
| `result.exit_code` | Code the command exited with. Absent when it produced none. |
| `result.failure_reason` | Why the execution failed. Failed reports only. |
| `result.failure_message` | Operator-facing diagnostic without credentials or command output. |

`exit_code` has explicit presence. An absent code means no command produced one,
for example when the Runner could not start it or stopped it on timeout, so it
must never be read as a successful `0`.

Failure reasons are `NON_ZERO_EXIT`, `TIMEOUT`, `CANCELLED`, and
`EXECUTION_ERROR`. New reasons may appear at any time.

## Reconciliation

Every report is reconciled against the Job the Control Plane already holds. The
answer says what happened to the Job, and only `JOB_STATUS_REPORT_APPLIED`
changes it.

| Result | When |
| --- | --- |
| `JOB_STATUS_REPORT_APPLIED` | The report moved the Job to a new state. |
| `JOB_STATUS_REPORT_DUPLICATE` | The Job already records this report or has moved past it. |
| `JOB_STATUS_REPORT_UNKNOWN_JOB` | The Control Plane does not know the Job. |
| `JOB_STATUS_REPORT_CONFLICT` | The report contradicts the recorded state. |

The response also carries `job_state`, the authoritative Job lifecycle state
after reconciliation. A Runner may use it to learn that its Job was already
cancelled, but it derives no policy from it.

### Duplicate and late reports

The call is safe to repeat, which is what makes an unacknowledged report
harmless:

- a repeated running or terminal report is a `DUPLICATE` and changes nothing;
- a running report that arrives after the Job already reached an outcome is a
  late report, and is also a `DUPLICATE`;
- a terminal report that arrives after the Control Plane cancelled the Job is a
  `DUPLICATE`; the cancellation is the Control Plane's own decision and stands;
- a second, *different* terminal outcome for one Job is a `CONFLICT`. The Job
  keeps the outcome it already recorded, and the contradiction is logged rather
  than silently dropped.

### Lost reports

A lost running report must not strand a finished execution. Because every
terminal report repeats `started_at`, a terminal report for a Job that is still
queued starts the Job from the reported start time and then completes it, so the
Job reaches its outcome with a truthful timeline.

A Job that was never queued is a different case: it is a `CONFLICT`, because
queueing is the Control Plane's own decision and a report cannot invent it.

### Identity

Only the process that executes a Job observes these facts, so a report from a
process the Job is not assigned to is a `CONFLICT` and leaves the Job untouched.

## Failure behavior

Failures are split by who must fix them:

- A request the Control Plane cannot interpret fails with the gRPC status
  `INVALID_ARGUMENT` and produces no response message. This covers an
  unsupported `protocol_version`, a malformed Job identity, a missing reporting
  process, an unspecified state, a missing `started_at`, a terminal report
  without `completed_at`, a completion before the start, a running report that
  carries an outcome, and an oversized `failure_message`.
- A well-formed report returns `OK` with the reconciliation result above, even
  when it changed nothing, because that is a state decision the Runner must be
  able to record rather than retry blindly.

Terminal reports are deliberately forgiving about detail. A failure reason this
Control Plane version does not know is recorded as `UNKNOWN`, and a missing
diagnostic is replaced with a standard one, because refusing a terminal report
would leave the Job without an outcome.

## Retries

Neither side retries a report today. The Runner sends one report per transition
and logs the answer; a lost report is recovered by the rules above rather than by
resending. Bounded retry with backoff is a separate reliability concern tracked
for a later phase, and the duplicate handling described here is what will make it
safe.

## Compatibility

The protobuf package is `zeroyaml.runner.v1`. Evolution is additive: new fields
take new field numbers, removed field numbers are never reused, and new
`JobExecutionFailureReason` and `JobStatusReportResult` values may appear at any
time. A caller must treat an unknown result value as a report it cannot
interpret, and must never treat `JOB_STATUS_REPORT_RESULT_UNSPECIFIED` as an
applied report.
