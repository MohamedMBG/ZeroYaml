# ZeroYAML Project Progress

Last updated: 2026-09-17

Current phase: 1

Phase gate: work in a later phase is locked until the current phase parent issue and every issue labeled with the current phase are closed. Advance this value by one only after the gate is satisfied.

## Current state

Phase 1 — Foundation is in progress and is the only active delivery phase. The control-plane and runner foundations are bootstrapped, the shared runner gRPC contract is checked in, and the Control Plane can call `RunnerService.Ping` through a generated Java client. Runner startup configuration is being externalized for issue #6 so local and future deployment environments can provide explicit endpoint, identity, and version settings. Local infrastructure is available for development. CI/CD validates both services, requires progress updates on pull requests, protects `main` behind reviewed changes, and blocks pull requests for later phases. The engineering board contains only ZeroYAML phases and issues; Phases 2–7 are locked backlog work, not completed work.

## Completed

- Bootstrapped the Spring Boot control-plane service.
- Bootstrapped the Go runner service.
- Added the runner gRPC service contract and generated bindings.
- Added the generated Java gRPC client and Control Plane Ping integration for the Runner.
- Added local infrastructure for development.
- Added CI for the Go runner and Java control plane.
- Protected `main` with required checks and two approving reviews.
- Set `main` as the default branch.

## In progress

- Externalize Runner runtime configuration for issue #6.
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
