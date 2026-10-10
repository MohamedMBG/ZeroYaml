# RunJob Dispatch Contract

`RunnerService.RunJob` is the versioned seam that carries one executable Job
from the Control Plane to a Runner. The Control Plane decides what runs and
where; the Runner only executes the supplied request.

The contract is deliberately small. It carries a single concrete command, not a
pipeline definition, so that protobuf does not become an accidental workflow
language.

## Request

| Field | Meaning |
| --- | --- |
| `protocol_version` | Contract version understood by the caller, currently `runner.v1`. |
| `job.job_id` | Control Plane Job identity, stable for the lifetime of the Job. |
| `job.repository.location` | Absolute, provider-neutral repository URI. The scheme states how the Runner resolves it. |
| `job.repository.revision` | Immutable source revision, so a Job stays reproducible. |
| `job.execution.command` | Ordered argument list; the first element is the executable. |
| `job.execution.working_directory` | Directory inside the checked-out repository in which the command runs. |
| `target_runner_id`, `target_instance_id` | Runner process the Control Plane selected for the Job. Set together. |

The request carries no scheduling, retry, timeout, or capability policy. Those
decisions stay in the Control Plane. The target pair is the result of such a
decision, not the policy behind it: it lets the receiving Runner confirm that it
is the process the Control Plane chose, as described in
[Runner selection](./runner-selection.md).

## Response

`RunJobResponse` acknowledges the dispatch only. It never carries command
output, exit codes, or execution progress, so a dispatch never blocks on job
duration.

| Field | Meaning |
| --- | --- |
| `job_id` | Echo of the dispatched Job, used to correlate the acknowledgment. |
| `acceptance` | `JOB_ACCEPTED` or `JOB_REJECTED`. |
| `rejection_reason` | Present only for `JOB_REJECTED`. |
| `message` | Operator-facing diagnostic without credentials or payloads. |
| `runner_id`, `instance_id` | Runner process that answered. |

## Failure behavior

Failures are split by who must fix them:

- A request that violates the contract, such as a missing `job_id`, an empty
  command, a working directory outside the repository, or only one half of the
  target pair, fails with the gRPC
  status `INVALID_ARGUMENT` and produces no response message. It is a caller
  defect, and no Job becomes known to the Runner.
- A well-formed request that the Runner declines returns `OK` with
  `JOB_REJECTED` and a reason. The refusal is a Runner state decision that the
  Control Plane records and acts on.
- A well-formed request that this Runner cannot map to a container execution,
  such as a repository scheme it does not fetch, fails with
  `FAILED_PRECONDITION`. Dispatching it again to the same Runner cannot
  succeed.

Current rejection reasons:

- `JOB_REJECTION_UNSUPPORTED_PROTOCOL_VERSION`: the Runner does not implement
  the caller's protocol version, so it will not guess field meanings.
- `JOB_REJECTION_RUNNER_UNAVAILABLE`: the Runner is not accepting work. The
  Control Plane may dispatch the Job to another Runner.
- `JOB_REJECTION_NOT_TARGET_RUNNER`: the dispatch names a different
  `(runner_id, instance_id)` than the process that received it. The answering
  process did not start the Job and must not be recorded as its Runner. The
  check runs before Runner state is examined, so a misaddressed dispatch is
  never reported as an unavailable Runner.

A Control Plane that receives `JOB_ACCEPTED` from a process other than the
target it named treats the answer as a protocol violation and does not record
the acceptance, because only a Runner that ignored the target can produce it.

The Runner validates every dispatch at its remote boundary and never assumes
that the caller validated it first. The working directory must be relative to
the checked-out repository and must not traverse above it, because the dispatch
contract grants no access to directories outside the workspace.

## Current Runner behavior

A Runner accepts a Job only when it is `READY`, its Docker daemon answered at
startup, and it has a free execution slot; a busy or shutting-down Runner
rejects the Job with `JOB_REJECTION_RUNNER_UNAVAILABLE`. An acknowledgment means
the Runner started the execution in the Docker sandbox described in
[`runner-execution-sandbox.md`](./runner-execution-sandbox.md). The execution
result travels separately through `JobExecutionStatusService`, and log
streaming is a separate contract.

## Compatibility

The protobuf package is `zeroyaml.runner.v1`. Evolution is additive: new fields
take new field numbers, removed field numbers are never reused, and new
`JobRejectionReason` values may appear at any time. A caller must therefore
treat an unknown rejection reason as a refusal it cannot interpret and must
treat `JOB_ACCEPTANCE_UNSPECIFIED` as a protocol error rather than as an
acceptance. Additional execution inputs belong in new fields on
`JobSpecification` or `JobExecution`, never in structured text encoded inside an
existing field.

`target_runner_id` and `target_instance_id` (fields 3 and 4 of `RunJobRequest`)
and `JOB_REJECTION_NOT_TARGET_RUNNER` were added this way. A caller that
predates them sends both target fields empty, and the Runner then applies no
target check, so an older Control Plane keeps working against a newer Runner. A
Runner that predates them ignores the target; the Control Plane's check on the
accepting process covers that direction.
