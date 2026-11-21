# Core Job Model

The Control Plane owns `Job`. A Job represents one concrete executable unit of
work after pipeline decisions have been made. It is not a workflow language,
pipeline definition, or persistence entity.

## Model contents

| Concept | Representation |
| --- | --- |
| Identity | `JobId`, backed by a UUID |
| Repository context | Provider-neutral URI plus immutable source revision |
| Execution definition | Ordered command arguments and working directory |
| Runner linkage | Stable Runner ID plus process-specific instance ID, set at dispatch or at start |
| Timestamps | Creation, start, and completion `Instant` values |
| Failure | Structured failure code and safe diagnostic message |

The command is represented as an argument list rather than a shell script. This
keeps the model focused on one execution request and avoids introducing a
generic workflow or YAML replacement DSL.

## Lifecycle

```text
CREATED ──> QUEUED ──> RUNNING ──> SUCCEEDED
   │  │        │          │
   │  └────────┴──────────┼──────> CANCELLED
   │                      └──────> FAILED
   └─────────────────────────────> SKIPPED
```

Legal transitions are:

- `CREATED -> QUEUED`
- `CREATED -> CANCELLED`
- `CREATED -> SKIPPED`
- `QUEUED -> RUNNING`
- `QUEUED -> CANCELLED`
- `RUNNING -> SUCCEEDED`
- `RUNNING -> FAILED`
- `RUNNING -> CANCELLED`

`SKIPPED` records a pipeline Job that never ran because a step it depends on
ended without success; see
[dependency-aware scheduling](./execution-model.md#dependency-aware-scheduling).
It is reachable only from `CREATED`, because a Job is queued only after its
dependencies succeeded. Terminal states cannot transition again. `JobStatus.canTransitionTo` is the
single source of these transitions, as defined by
[execution model v1](./execution-model.md), and invalid transitions raise
`InvalidJobTransitionException`. Timestamps must be ordered: creation must
precede start, and start must precede completion when a start exists.

The application layer remains responsible for choosing a Runner and sending an
execution request. The Job aggregate only records the selected Runner process
and enforces its own lifecycle invariants.

`assign` binds a queued, unassigned Job to the Runner process it is dispatched
to, before the Job starts. An assigned Job can start only on that same process;
`start` with a different assignment, a second `assign`, or an `assign` outside
`QUEUED` raises `IllegalStateException`. A Job that is never dispatched receives
its assignment at start.
