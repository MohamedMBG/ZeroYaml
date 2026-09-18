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
| Runner linkage | Stable Runner ID plus process-specific instance ID after start |
| Timestamps | Creation, start, and completion `Instant` values |
| Failure | Structured failure code and safe diagnostic message |

The command is represented as an argument list rather than a shell script. This
keeps the model focused on one execution request and avoids introducing a
generic workflow or YAML replacement DSL.

## Lifecycle

```text
CREATED ──> QUEUED ──> RUNNING ──> SUCCEEDED
   │           │          │
   └───────────┴──────────┼──────> CANCELLED
                          └──────> FAILED
```

Legal transitions are:

- `CREATED -> QUEUED`
- `CREATED -> CANCELLED`
- `QUEUED -> RUNNING`
- `QUEUED -> CANCELLED`
- `RUNNING -> SUCCEEDED`
- `RUNNING -> FAILED`
- `RUNNING -> CANCELLED`

Terminal states cannot transition again. Invalid transitions raise
`InvalidJobTransitionException`. Timestamps must be ordered: creation must
precede start, and start must precede completion when a start exists.

The application layer remains responsible for choosing a Runner and sending an
execution request. The Job aggregate only records the selected Runner process
and enforces its own lifecycle invariants.
