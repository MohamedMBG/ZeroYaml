# ZeroYAML Project Progress

Last updated: 2026-09-22 (Phase 1 closed; Phase 2 opened)

Current phase: 2

Phase gate: work in a later phase is locked until the current phase parent issue and every issue labeled with the current phase are closed. Advance this value by one only after the gate is satisfied.

## Current state

Phase 1 — Foundation & Core Runtime is complete. Issue #33 and every child issue (#1-#15) are closed: the control-plane and runner foundations are bootstrapped, the shared runner gRPC contract is checked in, and the Control Plane can call `RunnerService.Ping` through a generated Java client. Runner startup configuration is externalized, so local and future deployment environments provide explicit endpoint, identity, and version settings. Runner shutdown drains in-flight RPCs within a bounded timeout and releases the listener instead of dropping work. The Control Plane hosts a `RunnerRegistrationService` gRPC server backed by an in-memory registry, and the Runner registers once at startup before serving; a Control Plane that is unreachable or that reports an identity conflict leaves the Runner status unavailable instead of silently reporting readiness. The registration service also exposes `Heartbeat`, reusing the `(runner_id, instance_id)` identity pair: a registered Runner sends a heartbeat at a configurable interval, and the Control Plane derives `HEALTHY` / `UNAVAILABLE` liveness for each registry entry from the elapsed time since its last acknowledged registration or heartbeat, with no background sweep or distributed failure detector. The core Job model and the `RunJob` gRPC contract are defined and the Runner handles `RunJob` dispatch (still rejecting every job with `JOB_REJECTION_RUNNER_UNAVAILABLE`, since execution is out of Phase 1 scope). Local infrastructure is available for development. CI/CD validates both services, requires progress updates on pull requests, protects `main` behind reviewed changes, and blocks pull requests for later phases.

Phase 2 — GitHub Integration & First E2E Pipeline is now the active delivery phase (parent issue #34). Its child issues (#16-#32) are open backlog: GitHub webhook endpoint and signature verification, repository connection model and metadata persistence, GitHub event normalization, minimal pipeline model and inference, executable Job creation from a pipeline, minimal healthy Runner selection, the Runner Docker execution sandbox, execution status reporting, log streaming, execution/Job state persistence, lifecycle event publishing, the first end-to-end pipeline scenario, an end-to-end test, and local workflow documentation. None of this Phase 2 work has started yet. Phases 3-7 remain locked backlog.

## Completed

- Bootstrapped the Spring Boot control-plane service.
- Bootstrapped the Go runner service.
- Added the runner gRPC service contract and generated bindings.
- Added the generated Java gRPC client and Control Plane Ping integration for the Runner.
- Externalized Runner startup configuration into environment variables with validated defaults.
- Added local infrastructure for development.
- Added CI for the Go runner and Java control plane.
- Protected `main` with required checks and two approving reviews.
- Set `main` as the default branch.
- Synced the GitHub Projects board status automatically for issue #246: a workflow sets the project item Status to `In Progress` when an issue is assigned, and to `In Review` when a pull request referencing it with `Closes`/`Fixes`/`Resolves #N` is opened. Setting Status to `Done` on merge is handled separately by the project's built-in "Item closed" workflow.

- Added bounded graceful shutdown to the Runner gRPC server for issue #7.
- Implemented the Runner startup registration flow for issue #9. The Control Plane exposes `RunnerRegistrationService.Register` on a dedicated gRPC server (`zeroyaml.registration.port`, default 50052) backed by an in-memory, thread-safe registry keyed by `runnerId`. Registering the same `instanceId` again is idempotent (`ALREADY_REGISTERED`, same registration ID); a different `instanceId` for an already-registered `runnerId` is rejected as `IDENTITY_CONFLICT` deterministically. The Runner dials the Control Plane once at startup (`ZEROYAML_CONTROLPLANE_ADDRESS`, `ZEROYAML_RUNNER_REGISTRATION_TIMEOUT`) before its own gRPC server starts serving; on acceptance or an idempotent duplicate the Runner's reported status becomes ready, and on any transport failure or rejection it stays unavailable so `GetInfo`/`Ping` never report availability that was not earned.
- Added Runner gRPC service tests for issue #8. Ping is covered directly and through the generated gRPC client over an in-memory connection, including the echoed message, the reported runner version, an absent message, a malformed request payload, and an unknown method. The tests need no port, no Docker, and no externally running Runner.
- Fixed the project board sync workflow for issue #247. The `issue-assigned-to-in-progress` job crashed on every run (`Cannot find module '.../project-board-sync.js'`) because it never checked out the repository, unlike the sibling PR job. Added the missing checkout step; the failed run against issue #10 was corrected manually and the item's Status was confirmed `In Progress`.
- Defined the core Job model for issue #12. The Control Plane domain represents provider-neutral repository context, concrete execution arguments, Runner linkage, timestamps, failure information, and explicit lifecycle transitions without adding persistence or a workflow DSL.
- Documented the Phase 1 architecture and operating contract for issue #15. The overview records current Runner gRPC behavior, registration and heartbeat ownership, the Job/RunJob boundary, protocol generation, and local verification limits.
- Defined the `RunJob` gRPC contract for issue #13. The shared protocol carries one executable Job from the Control Plane to a Runner with job identity, repository context, execution arguments, and a protocol version, and answers with an acceptance or an explicit rejection reason. The Runner validates every dispatch at its remote boundary, and the Control Plane maps the acknowledgment onto its own dispatch result.
- Implemented basic Runner `RunJob` handling for issue #14. The Runner handler answers a request whose context is already cancelled or past its deadline with `CANCELLED` or `DEADLINE_EXCEEDED` instead of an acknowledgment, starts no goroutines, and writes one structured log record per dispatch with the job, protocol version, answering process, and outcome. Log records exclude repository location, revision, and command arguments, and bound caller-supplied identifiers. Tests cover acceptance, unavailable rejection over the transport, invalid requests, cancellation, deadline expiry, log fields, and payload redaction.
- Added Runner heartbeat and liveness for issue #11. `RunnerRegistrationService.Heartbeat` reuses the registration identity pair; the Control Plane records `lastSeenAt` per registry entry on registration and every acknowledged heartbeat, and derives `HEALTHY` / `UNAVAILABLE` liveness on demand against a configurable `zeroyaml.registration.heartbeat-timeout` (default `15s`). A heartbeat for an unrecognized `runner_id` or a mismatched `instance_id` is answered `HEARTBEAT_UNKNOWN_RUNNER` rather than accepted. The Runner sends heartbeats at a configurable interval (`ZEROYAML_RUNNER_HEARTBEAT_INTERVAL`, default `5s`, each attempt bounded by `ZEROYAML_RUNNER_HEARTBEAT_TIMEOUT`, default `5s`) once startup registration reports it ready, and stops on shutdown; a failed or declined heartbeat is logged and never stops the loop.
- Closed issue #33 (Phase 1 parent) after verifying every Phase 1 acceptance criterion against the state of `main`; all child issues #1-#15 are closed.

## In progress

- Phase 2 child issues (#16-#32) are open backlog; none have started implementation yet.

## Roadmap

- Phase 1 — Foundation & Core Runtime: complete.
- Phase 2 — GitHub Integration & First E2E Pipeline: active delivery phase.
- Phase 3 — Pipeline Lifecycle & Job Orchestration: locked.
- Phase 4 — Developer Feedback, Logs & Artifacts: locked.
- Phase 5 — Reliability & Recovery: locked.
- Phase 6 — Scale, Isolation & Security: locked.
- Phase 7 — Production Deployment & Operations: locked.

## Next steps

- Start Phase 2 work: GitHub webhook endpoint and signature verification (#16, #17) are the entry point the rest of the phase depends on.
- Define the repository connection model and persist connected repository metadata (#18, #19).
- Normalize supported GitHub events (#20) and define the minimal pipeline model (#21) before pipeline inference (#22) and Job creation (#23).

## Risks and blockers

- No known blockers at this time.

## Update policy

Every pull request must update this file so that the current state, completed work, in-progress work, next steps, and risks remain visible.
