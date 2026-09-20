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

The request carries no scheduling, retry, timeout, or capability policy. Those
decisions stay in the Control Plane.

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
  command, or a working directory outside the repository, fails with the gRPC
  status `INVALID_ARGUMENT` and produces no response message. It is a caller
  defect, and no Job becomes known to the Runner.
- A well-formed request that the Runner declines returns `OK` with
  `JOB_REJECTED` and a reason. The refusal is a Runner state decision that the
  Control Plane records and acts on.

Current rejection reasons:

- `JOB_REJECTION_UNSUPPORTED_PROTOCOL_VERSION`: the Runner does not implement
  the caller's protocol version, so it will not guess field meanings.
- `JOB_REJECTION_RUNNER_UNAVAILABLE`: the Runner is not accepting work. The
  Control Plane may dispatch the Job to another Runner.

The Runner validates every dispatch at its remote boundary and never assumes
that the caller validated it first. The working directory must be relative to
the checked-out repository and must not traverse above it, because the dispatch
contract grants no access to directories outside the workspace.

## Current Runner behavior

The Runner has no execution service yet, so it reports `accepting_work = false`
and rejects every dispatch with `JOB_REJECTION_RUNNER_UNAVAILABLE`. Nothing in
this contract executes a command; container execution, log streaming, and result
reporting are separate contracts.

## Compatibility

The protobuf package is `zeroyaml.runner.v1`. Evolution is additive: new fields
take new field numbers, removed field numbers are never reused, and new
`JobRejectionReason` values may appear at any time. A caller must therefore
treat an unknown rejection reason as a refusal it cannot interpret and must
treat `JOB_ACCEPTANCE_UNSPECIFIED` as a protocol error rather than as an
acceptance. Additional execution inputs belong in new fields on
`JobSpecification` or `JobExecution`, never in structured text encoded inside an
existing field.
