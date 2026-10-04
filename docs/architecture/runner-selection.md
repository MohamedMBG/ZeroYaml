# Runner Selection

The Control Plane decides where a queued Job runs; a Runner never selects or
reprioritizes its own work. `RunnerSelector` is the seam for that decision, and
`HealthyRunnerSelector` is the first, deliberately small strategy behind it.

The code lives in `io.zeroyaml.controlplane.scheduling`.

## Eligibility

A Runner is eligible exactly when it is registered and the registry reports its
liveness as `HEALTHY`: its last acknowledged registration or heartbeat is within
`zeroyaml.registration.heartbeat-timeout` (default `15s`). The registry owns that
policy, so the selector asks `RunnerRegistry.healthyRunners()` instead of
re-deriving health from timestamps.

The registration snapshot's `state` and `acceptingWork` fields are not used for
eligibility. They are captured once at registration, before the Runner finishes
its own startup checks, and heartbeats never refresh them, so they do not say
whether a Runner can take work now. A healthy Runner that declines a dispatch is
answered by the rejection path described in the
[RunJob contract](./run-job-contract.md).

## Selection rule

Among eligible Runners, the one with the lexicographically smallest `runnerId` is
selected. `runnerId` is the registry key and therefore unique, so the order is
total: the same registry state always yields the same Runner, independent of
registration order or the registry's iteration order.

The strategy is stateless. It does not rotate, count load, or reserve the Runner
it returns, so concurrent callers can be handed the same Runner and one Runner
receives all work while it stays healthy and first in order. Capacity, leases, and
fairness belong to later scheduling work.

## Outcome

`RunnerSelector.select(job)` takes a `QUEUED` Job and returns a
`RunnerSelectionOutcome`. Selecting for a Job in any other state is a caller
error and raises `IllegalStateException`.

| Decision | Meaning | Carries |
| --- | --- | --- |
| `SELECTED` | A healthy Runner was chosen. | A `JobDispatchContext` with the Job identity and the Runner's `runnerId` and `instanceId`. |
| `BLOCKED_NO_HEALTHY_RUNNER` | No registered Runner is currently healthy. | No dispatch context. |

`JobDispatchContext` is the decision handed to dispatch: the Job paired with the
Runner process chosen for it. Dispatch reads its target from the context instead
of choosing a Runner itself.

## Blocked behavior in the first version

A blocked outcome is an explicit result, not an exception and not a silent drop.
Selection never modifies the Job: it stays `QUEUED` with no Runner linked, so the
Job aggregate records its assignment only when the selected Runner reports that it
started, as described in the [Core Job Model](./job-model.md).

There is no queue, retry timer, or background loop in this version. Retrying is the
caller's choice: it asks again later, and the next call sees the registry's state
at that time. A Runner that recovers its heartbeat becomes selectable again
without a new registration.

## Limits

- The selection is a point-in-time snapshot, not a reservation. A selected Runner
  can stop sending heartbeats before dispatch, and the dispatch then fails through
  the existing transport-failure or rejection handling.
- Registry state is process-local and in memory, so selection sees only Runners
  registered with this Control Plane instance since it started.
- Nothing yet connects creation, selection, and dispatch end to end. Executable
  Job creation is issue #23 and the end-to-end pipeline scenario is a later Phase 2
  issue.

## Out of scope

Capacity or load scoring, queues and leases, labels or capability matching,
autoscaling, Kubernetes integration, and distributed scheduling.
