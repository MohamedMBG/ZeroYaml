# ZeroYAML Project Progress

Last updated: 2026-09-17

## Current state

Phase 1 — Foundation is in progress. The control-plane and runner foundations are bootstrapped, the runner gRPC contract is checked in, and local infrastructure is available for development. CI/CD now validates both services, requires progress updates on pull requests, and protects `main` behind reviewed changes.

## Completed

- Bootstrapped the Spring Boot control-plane service.
- Bootstrapped the Go runner service.
- Added the runner gRPC service contract and generated bindings.
- Added local infrastructure for development.
- Added CI for the Go runner and Java control plane.
- Protected `main` with required checks and two approving reviews.
- Set `main` as the default branch.

## In progress

- Implement the first end-to-end control-plane to runner workflow.
- Expand automated coverage as features land.

## Next steps

- Document deployment and operational procedures.
- Define operational readiness criteria for the first end-to-end workflow.

## Risks and blockers

- No known blockers at this time.

## Update policy

Every pull request must update this file so that the current state, completed work, in-progress work, next steps, and risks remain visible.
