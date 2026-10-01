# Repository Connection Model

The Control Plane owns `RepositoryConnection`. A connection records that one
source repository is connected to ZeroYAML, which branch is its default, how
its webhook deliveries are verified, and whether those deliveries may trigger
work. The aggregate itself is a domain model only: it is not a persistence
entity, a GitHub API client, or a webhook handler.

The model lives in `io.zeroyaml.controlplane.domain.repository`. Its
PostgreSQL persistence and application service live in
`io.zeroyaml.controlplane.connection` and are described under
[Persistence](#persistence).

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

## Persistence

Connected repositories are stored in PostgreSQL so they survive a Control
Plane restart (#19). The layers are:

| Layer | Type | Responsibility |
| --- | --- | --- |
| Application service | `RepositoryConnectionService` | Connect, find, and change connections; stamps times from its clock |
| Port | `RepositoryConnectionStore` | Storage contract, independent of the technology |
| Adapter | `JdbcRepositoryConnectionStore` | PostgreSQL implementation using `JdbcClient` |
| Schema | `db/migration/V1__create_repository_connection.sql` | Flyway migration run at startup |

`RepositoryConnection.restore` rebuilds an aggregate from stored values. It
validates them the same way the lifecycle does, so a row edited into a state the
domain could not produce is rejected when read instead of being loaded.

### Table

`repository_connection` has one row per connected repository:

| Column | Content |
| --- | --- |
| `id` | Surrogate key, generated |
| `provider`, `owner`, `name` | Canonical `RepositoryIdentity`, lower-case owner and name |
| `default_branch` | `BranchName` |
| `webhook_secret_reference` | `SecretReference` name only, never the secret value |
| `status` | `ConnectionStatus` name |
| `connected_at`, `status_changed_at` | `timestamptz`, microsecond precision |

Check constraints restrict the provider and status values, require a lower-case
identity, and require `status_changed_at >= connected_at`, so rows written
outside the Control Plane cannot contradict the domain rules.

### Duplicate identities

The `repository_connection_identity_key` unique constraint on
`(provider, owner, name)` enforces the aggregate identity. The adapter inserts
with `ON CONFLICT ... DO NOTHING` and reports a skipped insert as
`DuplicateRepositoryConnectionException`. When several Control Plane
instances connect the same repository at once, exactly one insert succeeds and
the others get the same exception every time. Because the identity is
canonicalized first, `MohamedMBG/ZeroYaml` and `mohamedmbg/zeroyaml` are the
same duplicate.

The constraint covers `DISCONNECTED` rows too. A disconnected repository
therefore cannot be connected again yet; how to reconnect (reactivating the row
or archiving it) is left to the issue that adds a reconnect flow.

### Updates and concurrency

`RepositoryConnectionStore.update` reads the row with `SELECT ... FOR UPDATE`,
applies the change to the rebuilt aggregate, and writes the result in the same
transaction. Concurrent updates of one repository are serialized by the row
lock, including across Control Plane instances, and each change starts from the
latest recorded state. If the change throws, for example with an
`InvalidConnectionTransitionException`, the transaction rolls back and nothing
is written. The service reads its clock only after the lock is granted, so a
transition that waited for another one is never stamped earlier than it.

Returned connections are detached snapshots. Changing one has no effect on the
stored record.

### Timestamps

PostgreSQL `timestamptz` keeps microseconds. The service truncates its clock to
microseconds, and the adapter truncates again before writing. It never rounds,
so a stored time never moves later than the time the domain produced, and the
ordering of `connected_at` and `status_changed_at` survives the round trip.

### Configuration

The datasource is configured from the environment; see the Control Plane
database section of `DEVELOPER_GUIDE.md`. Every connection attempt is bounded
at 5 seconds and every statement at 5 seconds.

## Out of scope

- Reconnecting a disconnected repository.
- GitHub App installation and OAuth flows.
- Webhook delivery handling and signature verification (#16, #17).
- Pipeline inference (#22).
