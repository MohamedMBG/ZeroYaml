# Repository Connection Model

The Control Plane owns `RepositoryConnection`. A connection records that one
source repository is connected to ZeroYAML, which branch is its default, how
its webhook deliveries are verified, and whether those deliveries may trigger
work. It is a domain model only: it is not a persistence entity, a GitHub API
client, or a webhook handler.

The model lives in `io.zeroyaml.controlplane.domain.repository`.

## Model contents

| Concept | Representation |
| --- | --- |
| Identity | `RepositoryIdentity`: provider plus canonical owner and name |
| Default branch | `BranchName`, validated against Git ref rules |
| Webhook metadata | `WebhookConfiguration` holding a `SecretReference` |
| Lifecycle | `ConnectionStatus` with guarded transitions |
| Timestamps | `connectedAt` and `statusChangedAt` `Instant` values |

## Identity and duplicate prevention

`RepositoryIdentity` is the aggregate identity. GitHub resolves owner and
repository names case-insensitively, so both values are lower-cased on
construction; `MohamedMBG/ZeroYaml` and `mohamedmbg/zeroyaml` are the same
identity. Owners must follow GitHub login rules (1-39 alphanumeric characters
or single inner hyphens) and names must be 1-100 characters from letters,
digits, `.`, `_`, and `-`, excluding `.` and `..`.

`RepositoryConnection.equals` and `hashCode` use only the identity. Two
connection objects for the same repository are equal regardless of status,
branch, or webhook metadata, so a `Set` or a future persistence unique key
rejects the second one instead of silently creating a duplicate connection.

A rename or ownership transfer on GitHub yields a new identity. Tracking the
provider's numeric repository ID across renames is outside the Phase 2 model.

## Secret handling

The domain model never stores secret material. `WebhookConfiguration` holds a
`SecretReference`: the name of an environment variable or future secret-store
entry, for example `ZEROYAML_GITHUB_WEBHOOK_SECRET`. References must be 1-128
characters, start with a letter, and contain only letters, digits, `_`, `.`,
or `-`. This keeps them usable as keys and makes it harder to store a raw
secret by mistake. Validation errors never echo the rejected value.

The adapter that verifies webhook signatures resolves the reference to the
actual value at its own boundary. Because the aggregate only carries the
reference, its `toString` output is safe to log.

## Lifecycle

```text
PENDING ──> ACTIVE <──> SUSPENDED
   │          │             │
   └──────────┴─────────────┴──> DISCONNECTED
```

Legal transitions are:

- `PENDING -> ACTIVE`
- `PENDING -> DISCONNECTED`
- `ACTIVE -> SUSPENDED`
- `ACTIVE -> DISCONNECTED`
- `SUSPENDED -> ACTIVE`
- `SUSPENDED -> DISCONNECTED`

`DISCONNECTED` is terminal; reconnecting requires a new connection. Invalid
transitions raise `InvalidConnectionTransitionException`. Transition
timestamps must not precede the previous status change. Only `ACTIVE`
connections accept webhook events (`ConnectionStatus.acceptsEvents()`).

The default branch and webhook configuration can change while the connection
is not disconnected, for example when the provider reports a new default
branch or the signing secret is rotated to a new reference.

## Out of scope

- PostgreSQL persistence and migrations (#19).
- GitHub App installation and OAuth flows.
- Webhook delivery handling and signature verification (#16, #17).
- Pipeline inference (#22).
