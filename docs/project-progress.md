# ZeroYAML Project Progress

Last updated: 2026-09-17

## Current state

Phase 1 — Foundation is in progress. The control-plane and runner foundations are bootstrapped, the runner gRPC contract is checked in, and local infrastructure is available for development. CI/CD governance is being established so changes land through reviewed pull requests.

## Completed

- Bootstrapped the Spring Boot control-plane service.
- Bootstrapped the Go runner service.
- Added the runner gRPC service contract and generated bindings.
- Added local infrastructure for development.

## In progress

- Establish automated CI for the control plane and runner.
- Enforce reviewed pull requests and progress tracking on `main`.

## Next steps

- Implement the first end-to-end control-plane to runner workflow.
- Add focused unit and integration coverage as features land.
- Document deployment and operational procedures.

## Risks and blockers

- No known blockers at this time.

## Update policy

Every pull request must update this file so that the current state, completed work, in-progress work, next steps, and risks remain visible.
