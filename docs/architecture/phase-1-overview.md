# Phase 1 Architecture and Operating Contract

This document describes the Phase 1 boundary between the Control Plane and the
Runner. It records the behavior that exists in the repository and calls out
integration points that are intentionally still pending. The central rule is:

> The Control Plane decides and coordinates; the Runner executes.

## Ownership boundary

```text
+----------------------+                 +----------------------+
|     Control Plane    |                 |        Runner        |
|  Java 21 / Spring    |                 |       Go / gRPC      |
|                      |                 |                      |
|  - orchestration     |                 |  - process identity  |
|  - Runner registry   |                 |  - capabilities      |
|  - liveness policy   |                 |  - execution         |
|  - Job decisions     |                 |  - local lifecycle   |
+----------+-----------+                 +-----------+----------+
           |                                         |
           |      runner.v1 gRPC contract            |
           +-----------------------------------------+
```

The Control Plane owns decisions about whether a Runner is known, eligible,
live, and suitable for a Job. The Runner reports process facts and performs
work assigned by the Control Plane. A Runner must not choose pipeline policy or
reinterpret a Job as a workflow definition.

The current local connection is plaintext gRPC on port `50051`. That value is a
local default only: the Control Plane uses `zeroyaml.runner.host` and
`zeroyaml.runner.port`, while the Runner uses `ZEROYAML_RUNNER_GRPC_ADDRESS`.
Deployment security and endpoint selection are outside this Phase 1 contract.

## Phase 1 flow

The implemented and planned parts of the foundation fit together as follows:

```text
 Runner process starts
        |
        | implemented: creates stable runner_id, new instance_id,
        | reports capabilities and UNAVAILABLE state through GetInfo
        v
 +------------------+       Register       +----------------------+
 |      Runner      | -------------------> |    Control Plane     |
 |                  |   contract exists;   |                      |
 | Ping / GetInfo   |    registry pending   | registry + policy    |
 +--------+---------+                       |                      |
          |                                 +----------+-----------+
          | Heartbeat                                   |
          +------------------------------------------->|
                                                 lastSeenAt / liveness
                                                         |
                                         Job decision / RunJob
                                                         |
                                                         v
                                              +------------------+
                                              |      Runner      |
                                              | execute concrete |
                                              | command request  |
                                              +--------+---------+
                                                       |
                                             result / state report
                                                       |
                                                       v
                                              Control Plane Job
```

The `Register` and heartbeat arrows describe ownership and the intended
integration sequence, not completed runtime behavior. `RunnerService.Ping` and
`RunnerService.GetInfo` are implemented and covered by tests. The registration
service has a protobuf contract, but the Control Plane registry, persistence,
lease expiry, and registration transport wiring are not implemented yet.

## Runner registration

Registration is a Control Plane boundary. The Runner announces one process
instance using `RegisterRunnerRequest.runner`, which contains the same identity
and capability facts returned by `GetInfo`.

The identity has two parts:

| Value | Owner and meaning |
| --- | --- |
| `runner_id` | Operator-configured logical identity. It remains stable across process restarts. |
| `instance_id` | Cryptographically random identity generated once per process start. A restart receives a new value. |

The pair `(runner_id, instance_id)` identifies one process. The Control Plane
registry must own the active association and must not silently replace an
active instance using the same `runner_id`. The contract defines these results:

- `REGISTRATION_ACCEPTED` — a new process association was accepted.
- `REGISTRATION_ALREADY_REGISTERED` — the same pair registered again; the
  operation is idempotent.
- `REGISTRATION_IDENTITY_CONFLICT` — the logical identity is already held by a
  different active process.

`RunnerCapabilities` and `RunnerStatus` are Runner-reported facts. The current
Runner reports operating system and architecture and does not claim Docker or
an executor until those capabilities are verified. Because execution is not
implemented yet, the current Runner reports `UNAVAILABLE` and
`accepting_work = false`.

Registration does not make a Runner eligible by itself. The Control Plane must
apply its own compatibility, registry, liveness, and scheduling rules before
assigning work.

## Heartbeat and liveness

Liveness policy belongs to the Control Plane. `RunnerRegistrationService.Heartbeat`
implements it as a last-seen policy, not a distributed failure detector:

1. A registered Runner sends a heartbeat identified by `(runner_id, instance_id)`
   at a configurable interval (`ZEROYAML_RUNNER_HEARTBEAT_INTERVAL`, default `5s`),
   each attempt bounded by its own timeout (`ZEROYAML_RUNNER_HEARTBEAT_TIMEOUT`,
   default `5s`).
2. The Control Plane advances `lastSeenAt` for that registry entry only after a
   heartbeat from the matching `(runner_id, instance_id)` pair is received.
3. The Control Plane derives `HEALTHY` / `UNAVAILABLE` on demand by comparing the
   elapsed time since `lastSeenAt` against `zeroyaml.registration.heartbeat-timeout`
   (default `15s`); there is no background sweep.
4. A heartbeat for an unrecognized `runner_id`, or for a different `instance_id`
   than the one currently registered, is answered `HEARTBEAT_UNKNOWN_RUNNER` and
   never revives or replaces a different active instance.
5. A recovered Runner becomes `HEALTHY` again on its next acknowledged heartbeat
   without creating a duplicate registry entry.

`GetInfo` remains a point-in-time report, separate from this liveness state.
Persistence across Control Plane restarts, a background expiry sweep, and using
liveness in Runner-selection scheduling remain out of scope for this contract.
See the [Runner identity and registration contract](./runner-identity.md) for
the full heartbeat contract and result semantics.

## Job and RunJob boundaries

`Job` is a Control Plane domain aggregate for one concrete executable unit of
work. It contains provider-neutral repository context, an immutable source
revision, an argument list, a working directory, lifecycle timestamps, Runner
linkage, and structured failure information. Its lifecycle is:

```text
CREATED -> QUEUED -> RUNNING -> SUCCEEDED
   |         |          |
   +---------+----------+----> CANCELLED
                         +---> FAILED
```

The aggregate enforces legal transitions and timestamp ordering. The
application layer, not the aggregate, selects a Runner and sends execution.
The command is represented as ordered arguments rather than a shell script;
there is no generic workflow or YAML replacement DSL in this model. See
[`job-model.md`](./job-model.md) for the field and transition details.

`RunJob` is the versioned transport operation that turns one selected `Job`
into one concrete Runner execution request. It is not a second aggregate and it
does not move scheduling policy into the Runner. Its boundary is:

```text
Control Plane application
  1. validate a concrete Job
  2. choose an eligible Runner using registry and liveness policy
  3. record the RunnerAssignment and transition the Job to RUNNING
  4. send one execution request through the versioned gRPC boundary

Runner
  5. execute the supplied command in its owned execution environment
  6. return execution state/result

Control Plane application
  7. transition the Job to SUCCEEDED, FAILED, or CANCELLED
```

Steps 4 and 6 are the dispatch seam. `RunnerService.RunJob` carries the request
and returns an acceptance or an explicit rejection reason; see
[`run-job-contract.md`](./run-job-contract.md) for its fields and failure
semantics. There is still no Runner executor, job dispatcher, or result
transport, and the Runner reports `accepting_work = false`, so every dispatch is
rejected today. The steps above define the ownership boundary for the next
integration work; they do not claim an implemented end-to-end workflow.

## Protocol source of truth

The canonical Control Plane-to-Runner contract is
[`proto/runner/v1/runner.proto`](../../proto/runner/v1/runner.proto). Its
protobuf package is `zeroyaml.runner.v1`. The current services are:

| Service | Current responsibility | Current status |
| --- | --- | --- |
| `RunnerService.Ping` | Basic request/response reachability probe | Implemented |
| `RunnerService.GetInfo` | Runner identity, capabilities, and reported state | Implemented |
| `RunnerRegistrationService.Register` | Registration result contract | Contract only; Control Plane registry pending |

Generated bindings are consumers of that file:

- Go bindings are under `runner/gen/runner/v1/`.
- Java bindings are generated by the Maven protobuf plugin during the Control
  Plane build.

Never edit generated files manually. Add new fields with new field numbers and
never reuse a removed field number. Contract changes require verification of
both Java and Go consumers. A client that cannot understand a required
protocol version must reject it explicitly rather than guessing at semantics.

## Local foundation verification

Run the deterministic service checks from the repository root:

```powershell
Push-Location runner
go test ./...
Pop-Location

Push-Location control-plane
.\mvnw.cmd --batch-mode verify
Pop-Location
```

The Go tests cover Runner identity, gRPC service behavior, generated-client
transport, and bounded shutdown. The Maven tests cover the Control Plane Job
model and generated Java client mappings using an in-process gRPC server. These
tests also cover the `RunJob` dispatch contract on both sides: Runner-side
validation and acceptance decisions, and the Control Plane request and
acknowledgment mapping. They do not prove registration, heartbeat, or job
execution, because those paths are not implemented.

To observe the current Runner process locally, use a separate terminal:

```powershell
Push-Location runner
go run ./cmd/runner
# Press Ctrl+C to exercise bounded graceful shutdown.
Pop-Location
```

The default listener is `:50051`. Set
`$env:ZEROYAML_RUNNER_GRPC_ADDRESS` before `go run` when another local service
already uses that port. The current process exposes `Ping` and `GetInfo`; it
does not register itself or accept Job execution requests.

