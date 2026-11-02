# Runner Selection

The Control Plane decides where a queued Job should run. `RunnerSelector` is
the application-facing policy boundary, and `HealthyRunnerSelector` chooses the
eligible Runner with the lexicographically smallest `runnerId`.

The code lives in `io.zeroyaml.controlplane.scheduling`. This change implements
the selection decision for issue #24. Pipeline orchestration (#30) calls it
before each step is dispatched; see
[Pipeline orchestration](./pipeline-orchestration.md).

## Eligibility

`RunnerRegistry.availableRunners()` returns an immutable snapshot of registered
Runners satisfying both conditions:

- Liveness is `HEALTHY`: the last acknowledged registration or heartbeat is no
  older than `zeroyaml.registration.heartbeat-timeout` (default `15s`). An entry
  exactly at the timeout is healthy; the next instant is unavailable.
- `capabilities.dockerAvailable` is true in the registration snapshot. The Runner
  probes Docker before registration, so a Docker-less Runner that keeps sending
  heartbeats cannot take precedence over a capable one.

The registry judges all entries at one instant. The selector uses that snapshot
without repeating the liveness calculation.

Before ranking that snapshot, the selector excludes entries with a blank
`runnerId` or `instanceId`. Registration currently accepts empty and whitespace-only
identities, but a dispatch assignment requires both to be non-blank. Such an entry
cannot prevent a valid Runner from being selected; if every available entry has
a malformed identity, selection returns `NoAvailableRunner` and leaves the Job
queued and unassigned.

Registration carries `state = UNAVAILABLE` and `accepting_work = false` because
it precedes the registration acknowledgment. The existing heartbeat carries
identity only, so those two snapshot fields never become current readiness
signals. Filtering on either would exclude every Runner. Docker availability is
already known before registration and needs no protocol extension.

## Selection and outcome

`RunnerSelector.select(job)` requires a `QUEUED` Job. Another Job state raises
`IllegalStateException`. Among eligible Runners, the unique registry key
`runnerId` supplies a total ordering: the same registry state produces the same
selection regardless of registration or map iteration order.

`RunnerSelectionOutcome` is a sealed interface with two cases:

| Case | Meaning | Carries |
| --- | --- | --- |
| `Selected` | A healthy Docker-capable Runner was chosen. | A `JobDispatchContext` pairing the Job identity with the Runner's `runnerId` and `instanceId`. |
| `NoAvailableRunner` | No eligible Runner is registered. | The Job identity and a blocked explanation. |

Both outcomes leave the Job queued and unassigned. Selection sends no RPC and
does not mutate the registry. A blocked caller can ask again later; no timer,
queue, or automatic retry is added. A Runner whose heartbeat resumes becomes
eligible again, including after a long partition and another Runner registering
in the meantime. Registry entries are not evicted; stale-registration replacement
and recovery remain issue #271.

## Dispatch boundary

`JobDispatchContext` records the selected identity only. The existing
`RunnerClient.dispatchJob(Job)` still calls the single configured
`zeroyaml.runner.*` endpoint. Orchestration stores the selected identity as the
Job's assignment and compares it with the acknowledgment. There are no
target fields or target checks added to `RunJob`, and no changes to heartbeat or
status-report contracts.

With multiple registered Runners, the selected process can differ from the one
behind the configured endpoint. Orchestration (#30) therefore records the
selected assignment before `RunJob` is sent and enforces it on both sides: an
acknowledgment from any other Runner process cancels the Job, and status
reports from any other process are a `CONFLICT`. Routing to several Runner
endpoints needs its own issue.

## Limits

- Selection is a point-in-time policy decision, not a reachability check,
  readiness guarantee, or reservation. Startup, shutdown, network failures, and
  Docker changes after registration can make a selected Runner unable to run.
- No capacity is tracked. A busy Runner can reject a dispatch, and the stateless
  selector can choose it again. Capacity, retry coordination, and fairness need
  separate work.
- The registry is process-local, in memory, and retains all registrations until
  the Control Plane restarts. Selection scans those entries once per call.
- Issue #24 declares issue #23 as a prerequisite, and #23 remains open. This PR
  repairs the existing selection proposal without implementing Job creation.
  Merge must wait for the prerequisite or a recorded maintainer decision about
  that dependency; this change does not claim either condition is satisfied.

## Out of scope

Dispatch routing and target enforcement, heartbeat protocol extensions, registry
eviction, queues, leases, load scoring, general capability matching, autoscaling,
Kubernetes integration, and distributed scheduling.
