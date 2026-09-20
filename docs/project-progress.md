# ZeroYAML Project Progress

Last updated: 2026-09-20 (RunJob dispatch contract)

Current phase: 1

Phase gate: work in a later phase is locked until the current phase parent issue and every issue labeled with the current phase are closed. Advance this value by one only after the gate is satisfied.

## Current state

Phase 1 — Foundation is in progress and is the only active delivery phase. The control-plane and runner foundations are bootstrapped, the shared runner gRPC contract is checked in, and the Control Plane can call `RunnerService.Ping` through a generated Java client. Runner startup configuration is externalized, so local and future deployment environments provide explicit endpoint, identity, and version settings. Runner shutdown behavior is being added for issue #7 so a termination signal drains in-flight RPCs within a bounded timeout and releases the listener instead of dropping work. Local infrastructure is available for development. CI/CD validates both services, requires progress updates on pull requests, protects `main` behind reviewed changes, and blocks pull requests for later phases. The engineering board contains only ZeroYAML phases and issues; Phases 2–7 are locked backlog work, not completed work.

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

## In progress

- Add bounded graceful shutdown to the Runner gRPC server for issue #7.
- Define the Runner identity and registration contract for issue #9. This branch now includes the versioned identity, capability, status, and registration result contract, Runner `GetInfo` reporting, and the Control Plane client mapping. Docker remains unavailable until execution support is verified; registration transport wiring, persistence, heartbeat, and availability scheduling remain pending.
- Add Runner gRPC service tests for issue #8. Ping is now covered directly and through the generated gRPC client over an in-memory connection, including the echoed message, the reported runner version, an absent message, a malformed request payload, and an unknown method. The tests need no port, no Docker, and no externally running Runner.
- Fix the project board sync workflow for issue #247. The `issue-assigned-to-in-progress` job crashed on every run (`Cannot find module '.../project-board-sync.js'`) because it never checked out the repository, unlike the sibling PR job. Added the missing checkout step; the failed run against issue #10 was corrected manually and the item's Status was confirmed `In Progress`.
- Define the core Job model for issue #12. The Control Plane domain now represents provider-neutral repository context, concrete execution arguments, Runner linkage, timestamps, failure information, and explicit lifecycle transitions without adding persistence or a workflow DSL.
- Document the Phase 1 architecture and operating contract for issue #15. The overview records current Runner gRPC behavior, registration and heartbeat ownership, the Job/RunJob boundary, protocol generation, and local verification limits.
- Define the `RunJob` gRPC contract for issue #13. The shared protocol now carries one executable Job from the Control Plane to a Runner with job identity, repository context, execution arguments, and a protocol version, and answers with an acceptance or an explicit rejection reason. The Runner validates every dispatch at its remote boundary, and the Control Plane maps the acknowledgment onto its own dispatch result. Execution itself is not implemented, so the Runner still reports `accepting_work = false` and rejects every dispatch with `JOB_REJECTION_RUNNER_UNAVAILABLE`. Docker execution, Runner selection, pipeline inference, log streaming, and result persistence remain out of scope.
- Implement the next Control Plane to Runner integration steps: registration, heartbeat, and job dispatch.
- Expand automated coverage as features land.

## Roadmap

- Phase 1 — Foundation & Core Runtime: in progress.
- Phase 2 — GitHub Integration & First E2E Pipeline: locked until Phase 1 is complete.
- Phase 3 — Pipeline Lifecycle & Job Orchestration: locked.
- Phase 4 — Developer Feedback, Logs & Artifacts: locked.
- Phase 5 — Reliability & Recovery: locked.
- Phase 6 — Scale, Isolation & Security: locked.
- Phase 7 — Production Deployment & Operations: locked.

## Next steps

- Finish the first end-to-end control-plane to runner workflow.
- Finish every Phase 1 issue and close the Phase 1 parent issue.
- Only then advance `Current phase` to `2` and move Phase 2 work into active delivery.

## Risks and blockers

- No known blockers at this time.

## Update policy

Every pull request must update this file so that the current state, completed work, in-progress work, next steps, and risks remain visible.
