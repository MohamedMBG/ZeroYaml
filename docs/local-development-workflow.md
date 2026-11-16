# Local End-to-End Development Workflow

This document is the reproducible path from a clean checkout to a verified
local ZeroYAML environment: which services to start and in which order, how to
configure them, how to send the supported signed GitHub `push` delivery and
follow it through one executed pipeline, what each boundary is expected to log
and persist, and what to check when a boundary fails.

It is a runbook. Reference material it would otherwise duplicate stays in
[`DEVELOPER_GUIDE.md`](../DEVELOPER_GUIDE.md) and in
[`docs/architecture/`](./architecture); this document links to it at the step
where it is needed.

Out of scope: production deployment, Kubernetes, and user-facing product
documentation.

## 1. The rule every step follows

> The Control Plane decides and coordinates; the Runner executes.

The Control Plane terminates the GitHub webhook, authenticates the delivery,
owns connected-repository metadata, owns authoritative Job state, and decides
what runs. The Runner executes one dispatched Job in Docker and reports what
that execution did. A Runner never learns scheduling, retry, or pipeline
policy, and the Control Plane never runs a Job command itself. A local
observation that contradicts that split is a defect, not a configuration
question.

## 2. What the documented path covers today

Every step of the push-to-execution chain is wired on `main`.
[Section 7](#7-trigger-the-supported-push-scenario) runs it as one continuous
scenario: a signed `push` for a connected repository is listed, inferred into
a `build` and `test` pipeline, recorded, dispatched step by step to the local
Runner, executed in Docker, and reconciled into PostgreSQL. The scenario uses
the same fixture shape as the automated end-to-end test
`FirstPipelineEndToEndTest` ([#31](https://github.com/MohamedMBG/ZeroYaml/issues/31)),
which is authoritative where the two disagree.

| Step | State | Where |
| --- | --- | --- |
| Local infrastructure starts | Wired | [Section 4](#4-startup-order) |
| Control Plane starts, applies migrations, serves HTTP and the Runner-facing gRPC endpoint | Wired | [Section 4](#4-startup-order) |
| Runner starts, probes Docker, registers, heartbeats | Wired | [Section 4](#4-startup-order) |
| Connected repository metadata is persisted | Wired; no HTTP API yet | [Section 6](#6-connect-a-repository) |
| A signed `push` delivery is authenticated and accepted | Wired | [Section 7](#7-trigger-the-supported-push-scenario) |
| An accepted branch push is normalized into a `RepositoryPushEvent` | Wired | [Section 7](#7-trigger-the-supported-push-scenario) |
| The commit's root listing is read and a pipeline is inferred | Wired ([#22](https://github.com/MohamedMBG/ZeroYaml/issues/22), [#30](https://github.com/MohamedMBG/ZeroYaml/issues/30)); the listing comes from the GitHub Git Trees API, or from the local fixture in [Section 4.2](#42-push-fixture) | [Section 7](#7-trigger-the-supported-push-scenario) |
| Executable Jobs are created and recorded | Wired ([#23](https://github.com/MohamedMBG/ZeroYaml/issues/23), [#30](https://github.com/MohamedMBG/ZeroYaml/issues/30)) | [Section 7.2](#72-follow-the-pipeline-state) |
| A healthy Runner is selected and each step is dispatched in order | Wired ([#24](https://github.com/MohamedMBG/ZeroYaml/issues/24), [#30](https://github.com/MohamedMBG/ZeroYaml/issues/30)); one configured Runner endpoint | [Section 7.1](#71-signed-local-delivery) |
| A dispatched Job runs in Docker and reports running and terminal status | Wired ([#25](https://github.com/MohamedMBG/ZeroYaml/issues/25), [#27](https://github.com/MohamedMBG/ZeroYaml/issues/27)) | [Section 7.1](#71-signed-local-delivery); [Section 8](#8-dispatch-a-job-by-hand) isolates it |
| Status reports move each Job through `RUNNING` to `SUCCEEDED` or `FAILED` in PostgreSQL | Wired ([#27](https://github.com/MohamedMBG/ZeroYaml/issues/27), [#28](https://github.com/MohamedMBG/ZeroYaml/issues/28)) | [Section 7.2](#72-follow-the-pipeline-state) |
| Recorded Job state survives a Control Plane restart | Wired ([#28](https://github.com/MohamedMBG/ZeroYaml/issues/28)) | [Section 9](#persistence-checks) |
| Job logs are streamed and captured | Wired, off until its mTLS listener is configured ([#26](https://github.com/MohamedMBG/ZeroYaml/issues/26)); asserted by the automated test only | [Section 9](#job-logs) |
| Lifecycle events are published | Wired, off by default ([#29](https://github.com/MohamedMBG/ZeroYaml/issues/29)) | [Section 9](#job-lifecycle-events) |
| One push produces one executed pipeline | Wired ([#30](https://github.com/MohamedMBG/ZeroYaml/issues/30)) | [Section 7](#7-trigger-the-supported-push-scenario) |
| An automated test covers the whole chain | Wired, opt-in ([#31](https://github.com/MohamedMBG/ZeroYaml/issues/31)) | [Section 11](#11-automated-coverage) |

The local scenario does not contact GitHub. A fixture HTTP server answers the
Git Trees API request for the pushed commit, the Control Plane's location
template points the Runner at a local `file://` repository, and that
repository's `mvnw` is a stand-in that prints its arguments instead of running
Maven. The default `alpine:3.22` Job image therefore suffices, and no JDK, token,
or public tunnel is needed. Driving the same chain from a real GitHub
repository is described in
[Section 7.5](#75-delivery-from-github-itself).

## 3. Prerequisites

| Tool | Needed for |
| --- | --- |
| Git | Checkout, and the local repository used as a Job source |
| Docker Desktop with Compose | Infrastructure, the fixture Git Trees API, Control Plane tests, and every Job execution |
| Java 21 | Control Plane |
| Go, version from `runner/go.mod` | Runner |
| `curl` | Sending a local webhook delivery |
| `perl` with the core `Digest::SHA` module | Signing a local delivery from bash ([Section 7.1](#71-signed-local-delivery)); ships with Linux and macOS |
| `openssl` | Generating the webhook secret from bash ([Section 4.3](#43-control-plane)) |
| `grpcurl` | Only for the hand dispatch in [Section 8](#8-dispatch-a-job-by-hand) |
| `uuidgen` | Only for the bash hand dispatch in [Section 8](#8-dispatch-a-job-by-hand); ships with macOS, and with the `util-linux` package on Linux, which minimal images can omit |

No host PostgreSQL client is needed: every persistence check runs `psql`
inside the `zeroyaml-postgres` container. The scenario pulls
`postgres:16-alpine`, `busybox:1.37` for the fixture API, and the Runner's
`alpine/git:v2.49.1` checkout and `alpine:3.22` Job images; pre-pulling them
avoids a first execution failing on a slow pull.

PowerShell blocks are for Windows and are written for Windows PowerShell 5.1
and PowerShell 7. Bash blocks from earlier revisions of this document were
executed only under Git Bash on Windows, and the bash blocks for the push
fixture and the continuous scenario have not been executed at all. None has
been run on Linux or macOS, where `openssl`, `curl`, `mktemp`, `perl`,
`uuidgen`, and `grpcurl` can behave differently, so treat them as unverified
until a run there is recorded. [Section 11](#11-automated-coverage) records
what was executed.

Verify the toolchain before starting anything:

```powershell
git --version
java -version
go version
docker --version
docker compose version
```

`infra/compose.yaml` also defines Redis and Kafka. This workflow starts
PostgreSQL only. Redis has no consumer. Kafka is needed only for the optional
Job lifecycle event stream, which is off by default; see
[Job lifecycle events](#job-lifecycle-events).

## 4. Startup order

The order matters in one direction only: the Control Plane needs PostgreSQL to
start at all, and the Runner needs the Control Plane to accept it into the
registry. A Runner started earlier does not crash — it stays unavailable and
rejects every dispatch until it is restarted after the Control Plane is up,
because startup registration and the Docker probe each run once. The push
fixture is read only when a push arrives, so it can be prepared at any point
before [Section 7](#7-trigger-the-supported-push-scenario); its settings,
however, are read when the Control Plane starts.

Use three shells: one for the Control Plane, one for the Runner, and one for
the fixture, the delivery, and the checks. Each block below says which.

### 4.1 Infrastructure

```powershell
docker compose -f .\infra\compose.yaml up -d postgres
docker compose -f .\infra\compose.yaml ps
```

```bash
docker compose -f ./infra/compose.yaml up -d postgres
docker compose -f ./infra/compose.yaml ps
```

Expect `zeroyaml-postgres` running with `5432` published. Its data lives in the
`postgres-data` volume, so the schema and connected-repository rows survive a
restart of the stack.

### 4.2 Push fixture

In the third shell. This step mirrors the fixture of `FirstPipelineEndToEndTest`
and creates two things:

- a local Git repository, `local/demo`, below the Runner's local source root.
  Its root holds a `pom.xml`, so inference selects the Maven pipeline, and an
  `mvnw` that prints `fixture-` followed by its arguments and exits `0`. The
  Runner runs it as `sh mvnw ...`, so the file needs no executable bit;
- a directory that a `busybox` HTTP server publishes on `127.0.0.1:8099` in the
  layout of the Git Trees API: the file
  `repos/local/demo/git/trees/<commit>` holds the root listing of that commit.
  The Control Plane reads only the `tree` entries of type `blob` and the
  `truncated` flag, and checks no content type, so a static file is enough. A
  commit without a file is answered `404`, exactly like an unknown commit on
  GitHub.

```powershell
$demo = 'C:\zeroyaml-sources\local\demo'
New-Item -ItemType Directory -Force -Path $demo | Out-Null
Push-Location $demo
git init --initial-branch=main
[IO.File]::WriteAllText("$demo\pom.xml", "<project/>`n")
[IO.File]::WriteAllText("$demo\mvnw", "#!/bin/sh`necho fixture-`$*`nexit 0`n")
git add pom.xml mvnw
git commit -m "fixture: passing build and test"
$revision = git rev-parse --verify HEAD
Pop-Location
if (-not $revision) { throw 'No commit exists in the fixture repository. Set git user.name and user.email, then run this block again.' }

$trees = 'C:\zeroyaml-github-fixture\repos\local\demo\git\trees'
New-Item -ItemType Directory -Force -Path $trees | Out-Null
[IO.File]::WriteAllText("$trees\$revision", '{"truncated":false,"tree":[{"type":"blob","path":"pom.xml"},{"type":"blob","path":"mvnw"}]}')

docker run -d --name zeroyaml-github-fixture -p 127.0.0.1:8099:8099 `
  -v C:\zeroyaml-github-fixture:/www:ro busybox:1.37 httpd -f -p 8099 -h /www
curl.exe -s -o NUL -w "%{http_code}`n" "http://127.0.0.1:8099/repos/local/demo/git/trees/$revision"
```

```bash
demo="$HOME/zeroyaml-sources/local/demo"
mkdir -p "$demo"
cd "$demo"
git init --initial-branch=main
printf '<project/>\n' > pom.xml
printf '#!/bin/sh\necho fixture-$*\nexit 0\n' > mvnw
git add pom.xml mvnw
git commit -m "fixture: passing build and test"
revision="$(git rev-parse --verify HEAD)"
cd -
[ -n "$revision" ] || echo 'No commit exists in the fixture repository. Set git user.name and user.email, then run this block again.' >&2

trees="$HOME/zeroyaml-github-fixture/repos/local/demo/git/trees"
mkdir -p "$trees"
printf '%s' '{"truncated":false,"tree":[{"type":"blob","path":"pom.xml"},{"type":"blob","path":"mvnw"}]}' > "$trees/$revision"

docker run -d --name zeroyaml-github-fixture -p 127.0.0.1:8099:8099 \
  -v "$HOME/zeroyaml-github-fixture:/www:ro" busybox:1.37 httpd -f -p 8099 -h /www
curl -s -o /dev/null -w '%{http_code}\n' "http://127.0.0.1:8099/repos/local/demo/git/trees/$revision"
```

The last command prints `200`. The fixture is published on the loopback
address only, because the Control Plane accepts a plain-HTTP API base URL only
for `localhost`, `127.0.0.1`, or `[::1]`.

Both blocks can be run again. `git commit` then reports that there is nothing
to commit and the existing commit is reused, and `docker run` reports that the
name `zeroyaml-github-fixture` is in use; the running container keeps serving
the directory, including files added later, so it needs no restart.

### 4.3 Control Plane

Both required secrets come from the environment; read
[Section 5](#5-configuration-and-secret-handling) before running this.

The webhook secret is generated once into a file in the home directory, outside
the checkout, and loaded from there. The signed delivery in
[Section 7.1](#71-signed-local-delivery) is sent from a different shell and
needs the identical value; loading it from the file in both shells shares it
without ever printing it.

The two `ZEROYAML_GITHUB_*` settings point the Control Plane at the fixture
from [Section 4.2](#42-push-fixture): the Git Trees API is read from
`127.0.0.1:8099` instead of `api.github.com`, and the location handed to the
Runner is the `file://` repository instead of `https://github.com/...`.
Without them the Control Plane still starts, but a push for `local/demo` is
listed against GitHub and records no Job.

```powershell
$secretFile = Join-Path $HOME '.zeroyaml-webhook-secret'
if (-not (Test-Path $secretFile)) {
  $bytes = New-Object byte[] 32
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  $rng.GetBytes($bytes)
  $rng.Dispose()
  [IO.File]::WriteAllText($secretFile, (($bytes | ForEach-Object { $_.ToString('x2') }) -join ''))
}

$env:ZEROYAML_DATABASE_PASSWORD = 'zeroyaml'
$env:ZEROYAML_GITHUB_WEBHOOK_SECRET = [IO.File]::ReadAllText($secretFile)
$env:ZEROYAML_GITHUB_API_BASE_URL = 'http://127.0.0.1:8099'
$env:ZEROYAML_GITHUB_REPOSITORY_LOCATION_TEMPLATE = 'file:///C:/zeroyaml-sources/{owner}/{name}'

Push-Location control-plane
.\mvnw.cmd --batch-mode spring-boot:run
Pop-Location
```

```bash
secret_file="$HOME/.zeroyaml-webhook-secret"
[ -f "$secret_file" ] || (umask 077; openssl rand -hex 32 | tr -d '\n' > "$secret_file")

export ZEROYAML_DATABASE_PASSWORD='zeroyaml'
export ZEROYAML_GITHUB_WEBHOOK_SECRET="$(cat "$secret_file")"
export ZEROYAML_GITHUB_API_BASE_URL='http://127.0.0.1:8099'
export ZEROYAML_GITHUB_REPOSITORY_LOCATION_TEMPLATE="file://$HOME/zeroyaml-sources/{owner}/{name}"

cd control-plane && ./mvnw --batch-mode spring-boot:run
```

`{owner}` and `{name}` are literal placeholders that the Control Plane fills
from the push; the template is rejected at startup unless it contains both and
produces an absolute `https`, `http`, or `file` URI.

`RandomNumberGenerator` is a cryptographically secure source on Windows
PowerShell 5.1 and PowerShell 7 alike; `Get-Random` is not, so it is not used
for a secret.

Expected during startup:

- Flyway applies `V1__create_repository_connection.sql`,
  `V2__create_pipeline_run_and_job.sql`, and
  `V3__allow_job_assignment_before_start.sql` before the service accepts
  traffic, and logs `Successfully applied 3 migrations to schema "public", now
  at version v3` on a fresh database. Later starts log `Schema "public" is up
  to date. No migration necessary.`;
- the HTTP port is bound on `8080`, the Spring Boot default, because
  `application.properties` sets no `server.port`. When another process already
  holds `8080`, set `SERVER_PORT` in this shell before starting and use that
  port in every `localhost:8080` URL below;
- the runner-facing gRPC endpoint is bound on `zeroyaml.registration.port`
  (default `50052`). It hosts `RunnerRegistrationService` and
  `JobExecutionStatusService` on one listener, so a Runner is configured with a
  single Control Plane address.

Confirm it is serving:

```powershell
curl.exe -s http://localhost:8080/actuator/health
```

A missing webhook secret or database password stops startup with an actionable
message; both are listed in [Section 10](#10-troubleshooting).

### 4.4 Runner

In the second shell. `ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT` enables `file://`
Job sources and confines them to that directory; the location template from
[Section 4.3](#43-control-plane) must produce locations below it. The Control
Plane address is set to `127.0.0.1` rather than the default `localhost`, as in
the automated test, because resolving `localhost` can outlast the single
registration attempt on Windows (see [gRPC](#grpc)).

```powershell
New-Item -ItemType Directory -Force -Path 'C:\zeroyaml-sources' | Out-Null
$env:ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT = 'C:\zeroyaml-sources'
$env:ZEROYAML_CONTROLPLANE_ADDRESS = '127.0.0.1:50052'

Push-Location runner
go run ./cmd/runner
Pop-Location
```

```bash
mkdir -p "$HOME/zeroyaml-sources"
export ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT="$HOME/zeroyaml-sources"
export ZEROYAML_CONTROLPLANE_ADDRESS='127.0.0.1:50052'

cd runner && go run ./cmd/runner
```

Expected output, in this order (timestamps omitted):

```text
level=INFO msg="docker daemon available" docker_version=...
level=INFO msg="job log delivery disabled; configure ZEROYAML_JOB_LOGS_ADDRESS and TLS files to enable"
Runner local-runner registered with the Control Plane at 127.0.0.1:50052 - registration <uuid> - result accepted
ZeroYAML Runner gRPC server listening on :50051 - runner local-runner - instance <uuid> - version 0.1.0 - protocol runner.v1
```

The second line is expected: Job log delivery needs an mTLS listener that this
workflow does not configure. See [Job logs](#job-logs).

A Runner accepts Jobs only when registration was accepted **and** the Docker
daemon answered the startup probe. If either fails, it keeps serving `Ping` and
`GetInfo` for diagnosis but rejects every dispatch with
`JOB_REJECTION_RUNNER_UNAVAILABLE` rather than failing later inside the
sandbox. The heartbeat loop starts only after an accepted registration and then
sends a heartbeat every `ZEROYAML_RUNNER_HEARTBEAT_INTERVAL` (default `5s`);
the Control Plane reports the registry entry `UNAVAILABLE` once
`zeroyaml.registration.heartbeat-timeout` (default `15s`) passes without one.

Each Runner process generates a new instance identifier, and the Control Plane
registry keeps the first instance registered under a `ZEROYAML_RUNNER_ID` for
as long as the Control Plane process lives. A Runner restarted after an
accepted registration is therefore rejected with an identity conflict and stays
unavailable. Restart the Control Plane before restarting such a Runner, or
start the Runner under a different `ZEROYAML_RUNNER_ID`. Releasing or replacing
a stale registration is tracked as
[#271](https://github.com/MohamedMBG/ZeroYaml/issues/271).

Startup registration is a single attempt with no retry, so a Runner whose
attempt fails stays unavailable until it is restarted. Bounded retry is tracked
as [#272](https://github.com/MohamedMBG/ZeroYaml/issues/272).

`:50051` is the Runner's configurable default, not a fixed port. Override it
with `ZEROYAML_RUNNER_GRPC_ADDRESS` and point the Control Plane at the new
value with `ZEROYAML_RUNNER_PORT`.

## 5. Configuration and secret handling

The complete property and environment-variable tables are in
[`DEVELOPER_GUIDE.md`](../DEVELOPER_GUIDE.md#10-prerequisites). The values this
workflow will not start without:

| Setting | Component | Local value |
| --- | --- | --- |
| `ZEROYAML_GITHUB_WEBHOOK_SECRET` | Control Plane | Generated per developer; at least 16 characters |
| `ZEROYAML_DATABASE_PASSWORD` | Control Plane | `zeroyaml`, matching `infra/compose.yaml` |
| `ZEROYAML_DATABASE_URL` | Control Plane | Default `jdbc:postgresql://localhost:5432/zeroyaml` |
| `ZEROYAML_GITHUB_API_BASE_URL` | Control Plane | `http://127.0.0.1:8099`, the fixture; default `https://api.github.com` |
| `ZEROYAML_GITHUB_REPOSITORY_LOCATION_TEMPLATE` | Control Plane | `file:///C:/zeroyaml-sources/{owner}/{name}` (Windows); default `https://github.com/{owner}/{name}.git` |
| `ZEROYAML_GITHUB_API_TOKEN` | Control Plane | Unset for the fixture and public repositories; a read-only token only for a private repository |
| `SERVER_PORT` | Control Plane | Unset; `8080` is used |
| `ZEROYAML_CONTROLPLANE_ADDRESS` | Runner | `127.0.0.1:50052`; default `localhost:50052` |
| `ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT` | Runner | Absolute directory holding local Job sources; empty disables `file://` |
| `ZEROYAML_RUNNER_JOB_IMAGE` | Runner | Unset; the default `alpine:3.22` runs the fixture wrapper but has no JDK for a real Maven build |

Rules for the two repository settings are in
[pipeline orchestration](./architecture/pipeline-orchestration.md#repository-listing).

### Handling the webhook secret safely

- Generate a high-entropy value per developer and per environment. The Control
  Plane refuses to start on a secret shorter than 16 characters, because a
  guessable secret makes signature verification decorative.
- Keep it in the shell environment or an approved secret store. It has no value
  in `application.properties` on purpose, and it must never be committed,
  pasted into an issue or pull request, or written to a tracked file.
- Set the identical value on the GitHub webhook. A mismatch is
  indistinguishable from a forged delivery and is answered `401`.
- Never reuse a shared or production secret for local work, and rotate the
  local value if it was ever echoed into a terminal transcript or a log file.
- The verifier authenticates every delivery against this one configured secret.
  Per-repository secret resolution is not wired: the
  `webhook_secret_reference` column records only the *name* of a secret, never
  its value, and nothing reads it during verification yet.
- `ZEROYAML_GITHUB_API_TOKEN` follows the same rules: environment or secret
  store only, read-only scope, and never echoed. The Control Plane never logs it
  or includes it in a failure message.

Startup validation is deliberate. Invalid configuration fails at startup with
an actionable message instead of failing on the first delivery.

## 6. Connect a repository

Issues [#18](https://github.com/MohamedMBG/ZeroYaml/issues/18) and
[#19](https://github.com/MohamedMBG/ZeroYaml/issues/19) delivered the
`RepositoryConnection` aggregate and its PostgreSQL store. No HTTP API exposes
them yet (the Control Plane's REST endpoints are the GitHub webhook and the
token-protected Job log reader), so a local connection record is inserted
directly. Webhook authentication does not depend on this record, but pipeline
orchestration does: a push runs a pipeline only when its repository has a
connection in status `ACTIVE`. Connect the fixture repository from
[Section 4.2](#42-push-fixture):

```powershell
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "INSERT INTO repository_connection (provider, owner, name, default_branch, webhook_secret_reference, status, connected_at, status_changed_at) VALUES ('GITHUB', 'local', 'demo', 'main', 'local-dev-webhook-secret', 'ACTIVE', now(), now());"
```

The table constraints mirror the domain rules, so a row that contradicts them
is refused: `provider` must be `GITHUB`, `owner` and `name` must be lowercase,
`status` must be one of `PENDING`, `ACTIVE`, `SUSPENDED`, `DISCONNECTED`,
`status_changed_at` must not precede `connected_at`, and one repository can be
connected only once. `webhook_secret_reference` holds a name only; storing a
secret value there would be a security defect.

Read it back:

```powershell
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "SELECT provider, owner, name, default_branch, status FROM repository_connection;"
```

The table layout, the duplicate rule, and the locking behavior are documented
in
[`repository-connection-model.md`](./architecture/repository-connection-model.md#persistence).

## 7. Trigger the supported push scenario

`push` is the only supported event. The Control Plane answers `202 Accepted`
for a verified `push` whose payload normalizes, `200 OK` with outcome `IGNORED`
for `ping` and every other verified event, and rejects an invalid envelope or
signature before any downstream processing. A verified `push` that lacks a
field normalization needs is answered `400` with outcome `REJECTED`. The full
validation order and response contract are in
[`github-webhook-ingress.md`](./architecture/github-webhook-ingress.md).

Normalization reads these payload fields and nothing else:

| Field | Read for | Requirement |
| --- | --- | --- |
| `ref` | Every push | A non-blank string |
| `deleted` | Every push | A boolean |
| `ref` (branch rule) | A non-deleted, non-tag push | `refs/heads/<branch>` with a valid branch name; any other ref is rejected |
| `after` | A non-deleted branch push | The pushed commit, 40 or 64 hexadecimal characters |
| `repository.owner.login`, `repository.name` | A non-deleted branch push | Non-blank strings |
| `head_commit.timestamp` | A non-deleted branch push | An ISO-8601 time with an offset, such as `2026-10-26T10:00:00Z` |

Normalization stops after `ref` and `deleted` when `deleted` is `true` or `ref`
starts with `refs/tags/`. Such a push is answered `202` without an event, even
when `after`, `repository`, or `head_commit` is missing, and a deletion is not
checked against the branch rule, because neither case has a commit to execute.

### 7.1 Signed local delivery

This reproduces exactly how GitHub signs a delivery, needs no public tunnel,
and is the form a reviewer can run. It sends the delivery GitHub would send for
a push of the fixture commit from [Section 4.2](#42-push-fixture): the
repository is `local/demo` and `after` is the current `HEAD` of the fixture
repository, read again here so this block also works in a fresh shell. Run it
in the third shell once [Section 6](#6-connect-a-repository) has connected the
repository.

The body is written to a file and the digest is computed over that file's
bytes, then the same file is sent with `--data-binary`. Passing the JSON as a
command-line argument is avoided on purpose: Windows PowerShell 5.1 strips the
embedded double quotes from arguments handed to a native executable, so the
bytes sent would no longer match the bytes signed and the Control Plane would
answer `401`. The secret is loaded from the file created in
[Section 4.3](#43-control-plane), because this shell is not the one that
started the Control Plane.

```powershell
$revision = git -C C:\zeroyaml-sources\local\demo rev-parse --verify HEAD
$secretFile = Join-Path $HOME '.zeroyaml-webhook-secret'
$secret = [IO.File]::ReadAllText($secretFile)
$payloadFile = Join-Path ([IO.Path]::GetTempPath()) 'zeroyaml-push.json'
[IO.File]::WriteAllText($payloadFile, '{"ref":"refs/heads/main","deleted":false,"after":"' + $revision + '","repository":{"name":"demo","owner":{"login":"local"}},"head_commit":{"timestamp":"2026-10-26T10:00:00Z"}}')
$deliveryId = [guid]::NewGuid().ToString()

$hmac = New-Object System.Security.Cryptography.HMACSHA256 -ArgumentList (,[Text.Encoding]::UTF8.GetBytes($secret))
$digest = ($hmac.ComputeHash([IO.File]::ReadAllBytes($payloadFile)) |
  ForEach-Object { $_.ToString('x2') }) -join ''

curl.exe -i -X POST http://localhost:8080/webhooks/github `
  -H "Content-Type: application/json" `
  -H "X-GitHub-Event: push" `
  -H "X-GitHub-Delivery: $deliveryId" `
  -H "X-Hub-Signature-256: sha256=$digest" `
  --data-binary "@$payloadFile"
```

```bash
revision="$(git -C "$HOME/zeroyaml-sources/local/demo" rev-parse --verify HEAD)"
payload_file="$(mktemp)"
printf '{"ref":"refs/heads/main","deleted":false,"after":"%s","repository":{"name":"demo","owner":{"login":"local"}},"head_commit":{"timestamp":"2026-10-26T10:00:00Z"}}' "$revision" > "$payload_file"
delivery_id="$(openssl rand -hex 16)"

digest="$(perl -MDigest::SHA=hmac_sha256_hex -e '
  local $/;
  open my $key, "<:raw", $ARGV[0] or die "secret file: $!";
  open my $body, "<:raw", $ARGV[1] or die "payload file: $!";
  print hmac_sha256_hex(scalar <$body>, scalar <$key>);
' "$HOME/.zeroyaml-webhook-secret" "$payload_file")"

curl -i -X POST http://localhost:8080/webhooks/github \
  -H "Content-Type: application/json" \
  -H "X-GitHub-Event: push" \
  -H "X-GitHub-Delivery: $delivery_id" \
  -H "X-Hub-Signature-256: sha256=$digest" \
  --data-binary "@$payload_file"
```

The bash block hands `perl` the two file paths and lets it read the key itself.
`openssl dgst -hmac "$secret"` is avoided on purpose: it puts the secret on the
command line, where any local user can read it from the process list, and
`openssl` offers no way to read an HMAC key from a file. GitHub sends a GUID as
the delivery identifier; the Control Plane accepts 1 to 64 letters, digits, and
hyphens, so the random hex value is valid.

Expected response:

```text
HTTP/1.1 202

{"deliveryId":"<delivery>","event":"push","outcome":"ACCEPTED","message":"delivery accepted for processing"}
```

The webhook answers before any pipeline work starts: the push is queued for the
`pipeline-orchestrator` worker thread, so a `202` proves authentication and
normalization only. What happens next is visible in the logs and in PostgreSQL.

Expected Control Plane log lines for the passing fixture, in this order
(timestamps, process, and thread columns omitted):

```text
INFO  .NormalizingGitHubWebhookDeliveryHandler : Normalized GitHub push delivery <delivery> for repository local/demo on branch main at commit <revision>
INFO  i.z.c.g.webhook.GitHubWebhookController  : Accepted GitHub delivery <delivery> for event push (198 payload bytes)
INFO  i.z.c.e.PipelineJobCreationService       : Pipeline <pipeline>: recorded 2 of 2 Jobs
INFO  i.z.c.o.PipelineOrchestrator             : Push delivery <delivery> of local/demo at <revision> maps to pipeline <pipeline> with 2 steps
INFO  i.z.c.orchestration.PipelineProgression  : Dispatched job <build-job> (pipeline <pipeline> step 1 'build') to runner local-runner instance <instance>
INFO  i.z.c.e.JobExecutionStatusRecorder       : Job <build-job> reported RUNNING by runner local-runner instance <instance>: Job <build-job> is running
INFO  i.z.c.e.JobExecutionStatusRecorder       : Job <build-job> reported SUCCEEDED by runner local-runner instance <instance>: Job <build-job> reached SUCCEEDED
INFO  i.z.c.orchestration.PipelineProgression  : Dispatched job <test-job> (pipeline <pipeline> step 2 'test') to runner local-runner instance <instance>
INFO  i.z.c.e.JobExecutionStatusRecorder       : Job <test-job> reported RUNNING by runner local-runner instance <instance>: Job <test-job> is running
INFO  i.z.c.e.JobExecutionStatusRecorder       : Job <test-job> reported SUCCEEDED by runner local-runner instance <instance>: Job <test-job> reached SUCCEEDED
INFO  i.z.c.orchestration.PipelineProgression  : Pipeline <pipeline> finished: all 2 steps succeeded
```

The controller logs `Accepted` only after the delivery handler has queued the
push, so the handler line comes first; the worker thread can start before the
`Accepted` line is written. `test` is dispatched only after `build` reached
`SUCCEEDED`. The pipeline and Job identifiers are name-based UUIDs derived from
the repository source and commit, so the same commit always maps to the same
pipeline and Jobs. No line contains the payload, the signature, the repository
location, or the command.

Expected Runner records for each step, `build` first and then `test`
(timestamps omitted):

```text
level=INFO msg="run job acknowledged" job_id=<job> protocol_version=runner.v1 runner_id=local-runner instance_id=<instance> acceptance=JOB_ACCEPTED
level=INFO msg="job status report acknowledged" job_id=<job> reported_state=running runner_id=local-runner instance_id=<instance> decision=applied job_state=running
level=INFO msg="job execution starting" execution_id=<id>
level=INFO msg="job execution finished" job_id=<job> execution_id=<id> outcome=succeeded duration=<d> exit_code=0
level=INFO msg="job status report acknowledged" job_id=<job> reported_state=succeeded runner_id=local-runner instance_id=<instance> decision=applied job_state=succeeded
```

The dispatch acknowledgment and the running report can appear in either order,
because execution starts in a separate goroutine before the acknowledgment is
logged. Neither process logs the command output. With Job log delivery
disabled, the `fixture-...` lines that `mvnw` prints are not retained anywhere;
see [Job logs](#job-logs).

Each negative check below proves a webhook gate rather than the happy path, so
each is worth running once. Rows that change the body need a new digest
computed over the new bytes.

| Change to the request | Expected |
| --- | --- |
| Corrupt one character of the digest | `401`, outcome `REJECTED`, `WARN` log, no handler line |
| Drop the `X-Hub-Signature-256` header | `401`, outcome `REJECTED` |
| `X-GitHub-Event: ping`, same body and digest | `200`, outcome `IGNORED`, message `ping acknowledged; no pipeline work is started` |
| `X-GitHub-Event: pull_request`, same body and digest | `200`, outcome `IGNORED` |
| Drop the `X-GitHub-Delivery` header | `400`, outcome `REJECTED` |
| `Content-Type: text/plain` | `415`, outcome `REJECTED` |
| Body above `zeroyaml.github.webhook.max-payload-size` | `413`, outcome `REJECTED` |
| Remove `head_commit` from the body, signed again | `400`, outcome `REJECTED`, message naming the field: ``payload field `head_commit` must be an object`` |
| `{"ref":"refs/heads/main","after":"<sha>"}`, signed again | `400`, outcome `REJECTED`: ``payload field `deleted` must be a boolean`` |
| `"ref":"refs/tags/v1"` or `"deleted":true`, signed again | `202`, outcome `ACCEPTED`, and the handler logs that the delivery `has no executable branch commit; no repository event was emitted` |

The signature covers the body bytes only, so changing a header never requires a
new digest. An unverified delivery is rejected before the event allow-list is
consulted, so an unauthenticated caller cannot learn which events the endpoint
acts on. Normalization runs only after the signature is verified, and none of
the rejected or ignored deliveries reaches pipeline orchestration.

### 7.2 Follow the pipeline state

In the same shell, which still holds `$revision` or `revision`:

```powershell
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "SELECT step_position, step_name, status, runner_id, failure_code, exit_code, queued_at IS NOT NULL AS queued, started_at IS NOT NULL AS started, completed_at IS NOT NULL AS completed FROM job WHERE revision = '$revision' ORDER BY step_position;"
```

```bash
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "SELECT step_position, step_name, status, runner_id, failure_code, exit_code, queued_at IS NOT NULL AS queued, started_at IS NOT NULL AS started, completed_at IS NOT NULL AS completed FROM job WHERE revision = '$revision' ORDER BY step_position;"
```

Once both steps are terminal, usually within a few seconds when the images are
already pulled:

```text
 step_position | step_name |  status   |  runner_id   | failure_code | exit_code | queued | started | completed
---------------+-----------+-----------+--------------+--------------+-----------+--------+---------+-----------
             1 | build     | SUCCEEDED | local-runner |              |         0 | t      | t       | t
             2 | test      | SUCCEEDED | local-runner |              |         0 | t      | t       | t
```

These are the facts `FirstPipelineEndToEndTest` asserts for its passing
fixture: two Jobs named `build` and `test` in that order, both `SUCCEEDED`,
attributed to the Runner, with queued, started, and completed times recorded.
Queried earlier, the same rows show `build` as `QUEUED` or `RUNNING` and
`test` as `CREATED`, because a step is dispatched only after the previous one
succeeds. [Section 9](#job-lifecycle) describes each state.

Docker checks for the same run:

```powershell
# While a step runs: its checkout container, then its Job container.
docker ps --filter label=io.zeroyaml.managed=true

# After the pipeline ends, on any outcome: nothing is left behind.
docker ps -a --filter label=io.zeroyaml.managed=true
docker volume ls --filter label=io.zeroyaml.managed=true
```

Both post-run commands are expected to list nothing. Every container and the
workspace volume of each step are removed whether its command succeeded,
failed, timed out, or was cancelled. The workspace strategy, resource bounds,
result mapping, and known limits are documented in
[`runner-execution-sandbox.md`](./architecture/runner-execution-sandbox.md).

### 7.3 Failure path

The failing fixture of the automated test changes only the wrapper: it exits
`7` when called with the `test` arguments, so `build` still succeeds and
`test` fails with a known exit code. Commit that change, publish the listing
for the new commit, then run the [Section 7.1](#71-signed-local-delivery)
block again unchanged; it reads the new `HEAD`.

```powershell
$demo = 'C:\zeroyaml-sources\local\demo'
[IO.File]::WriteAllText("$demo\mvnw", "#!/bin/sh`necho fixture-`$*`n[ `"`$*`" = `"--batch-mode test`" ] && exit 7`nexit 0`n")
git -C $demo commit -am "fixture: failing test"
$revision = git -C $demo rev-parse --verify HEAD
[IO.File]::WriteAllText("C:\zeroyaml-github-fixture\repos\local\demo\git\trees\$revision", '{"truncated":false,"tree":[{"type":"blob","path":"pom.xml"},{"type":"blob","path":"mvnw"}]}')
```

```bash
demo="$HOME/zeroyaml-sources/local/demo"
printf '#!/bin/sh\necho fixture-$*\n[ "$*" = "--batch-mode test" ] && exit 7\nexit 0\n' > "$demo/mvnw"
git -C "$demo" commit -am "fixture: failing test"
revision="$(git -C "$demo" rev-parse --verify HEAD)"
printf '%s' '{"truncated":false,"tree":[{"type":"blob","path":"pom.xml"},{"type":"blob","path":"mvnw"}]}' > "$HOME/zeroyaml-github-fixture/repos/local/demo/git/trees/$revision"
```

After the delivery, the [Section 7.2](#72-follow-the-pipeline-state) query
shows the new commit's rows:

```text
 step_position | step_name |  status   |  runner_id   | failure_code  | exit_code | queued | started | completed
---------------+-----------+-----------+--------------+---------------+-----------+--------+---------+-----------
             1 | build     | SUCCEEDED | local-runner |               |         0 | t      | t       | t
             2 | test      | FAILED    | local-runner | NON_ZERO_EXIT |         7 | t      | t       | t
```

Adding `failure_message` to the query shows `job command exited with code 7`.
The rows of the passing commit are unchanged, because the new commit is a new
pipeline. The Control Plane logs `Job <test-job> reported FAILED by runner
local-runner instance <instance>: Job <test-job> reached FAILED` and no
`finished` line. The Runner logs the failure at `WARN` with the message, and
the terminal report is acknowledged with the failure reason:

```text
level=WARN msg="job execution finished" job_id=<test-job> execution_id=<id> outcome=failed duration=<d> exit_code=7 message="job command exited with code 7"
level=INFO msg="job status report acknowledged" job_id=<test-job> reported_state=failed runner_id=local-runner instance_id=<instance> failure_reason=non_zero_exit decision=applied job_state=failed
```

Had `build` failed instead, `test` would never be dispatched: it would move
from `CREATED` to `CANCELLED`, logged as `Job <test-job> (pipeline <pipeline>
step 2 'test') is cancelled because step 1 'build' did not succeed`.

### 7.4 Repeated and ignored pushes

Every push below is answered `202`, because the webhook acknowledges before
orchestration runs. The outcome is visible only in the Control Plane log and
in the `job` table.

| Change | Expected |
| --- | --- |
| Run only the `curl` command of [Section 7.1](#71-signed-local-delivery) again, which resends the same bytes, digest, and delivery identifier | The log repeats `maps to pipeline <pipeline> with 2 steps`, and for a passing commit `finished: all 2 steps succeeded`, but no `recorded` line follows, no row is added, and nothing is dispatched. Job creation is idempotent and progression finds every step already terminal |
| `after` set to a commit that has no file in the fixture directory, signed again | `WARN ... No pipeline for push delivery <delivery> of local/demo at <commit>: GitHub API found no readable tree for the pushed commit of local/demo (status 404)`; no row |
| Connection set to `SUSPENDED`: `UPDATE repository_connection SET status = 'SUSPENDED', status_changed_at = now() WHERE owner = 'local' AND name = 'demo';` | `INFO ... Push delivery <delivery> for local/demo is ignored: the connection is SUSPENDED`; no row. Set `status = 'ACTIVE'` the same way to resume |
| No connection row for the repository | `INFO ... Push delivery <delivery> for local/demo is ignored: the repository is not connected`; no row |

Ingress does not suppress a repeated delivery identifier; the idempotent Job
creation is what keeps a redelivery from running a pipeline twice. Durable
duplicate handling is tracked as
[#203](https://github.com/MohamedMBG/ZeroYaml/issues/203).

### 7.5 Delivery from GitHub itself

> Not executed end to end. Unlike [Section 7.1](#71-signed-local-delivery),
> this procedure has not been run against a real repository webhook.

To drive the same chain from a real repository, the repository must be public,
because the Runner clones it over HTTPS without credentials, and its root must
hold a `pom.xml`. Then:

1. Connect it as in [Section 6](#6-connect-a-repository), with its owner and
   name in lowercase.
2. Start the Control Plane without the two `ZEROYAML_GITHUB_*` fixture
   settings, so the listing comes from `https://api.github.com` and the Runner
   clones `https://github.com/{owner}/{name}.git`. An unauthenticated listing
   is subject to GitHub's rate limit; a read-only `ZEROYAML_GITHUB_API_TOKEN`
   raises it.
3. Start the Runner with `ZEROYAML_RUNNER_JOB_IMAGE` set to a pinned image that
   provides JDK 21, and Maven as well when the repository has no `mvnw`. The
   default `alpine:3.22` has neither, so `build` would fail. Raise
   `ZEROYAML_RUNNER_JOB_TIMEOUT` (default `10m`) if dependency downloads take
   longer.
4. Expose port `8080` through a tunnel of your choice and configure the
   repository webhook with the tunnel URL plus `/webhooks/github`, content type
   `application/json`, the same secret as `ZEROYAML_GITHUB_WEBHOOK_SECRET`, and
   the `push` event only.

GitHub sends one `ping` when the webhook is created: a `200` with outcome
`IGNORED` confirms the secret matches. Each push then follows
[Section 7.1](#71-signed-local-delivery) from the `Normalized` line on.
GitHub's **Recent Deliveries** view shows each push with the Control Plane's
response body, and any delivery can be redelivered from there instead of
pushing again.

## 8. Dispatch a Job by hand

This section is a diagnostic, not part of the supported scenario. It sends one
`RunJob` straight to the Runner with `grpcurl`, bypassing GitHub, inference,
and orchestration. Use it when the push in
[Section 7](#7-trigger-the-supported-push-scenario) records Jobs that never
finish, to tell a Runner, Docker, or status-report fault apart from an
orchestration fault. It verifies the Runner sandbox, the status-report
contract, and the Control Plane's reconciliation of a report.

[Section 8.1](#81-dispatch-an-unrecorded-job) dispatches a Job the Control
Plane has never recorded, so every report is refused.
[Section 8.2](#82-record-the-job-first-and-follow-its-state) records the Job in
PostgreSQL first, so the same reports move it to a terminal state.

### 8.1 Dispatch an unrecorded Job

Prepare a source repository below `ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT`. Start in
the repository root: both blocks return there, and the dispatch that follows
resolves `-import-path proto` against it.

```powershell
New-Item -ItemType Directory -Force -Path C:\zeroyaml-sources\demo | Out-Null
Push-Location C:\zeroyaml-sources\demo
git init --initial-branch=main
'hello' | Out-File -Encoding ascii greeting.txt
git add greeting.txt
git commit -m "add greeting"
$revision = git rev-parse --verify HEAD
Pop-Location
if (-not $revision) { throw 'No commit exists in the demo repository. Set git user.name and user.email, then run this block again.' }
```

```bash
mkdir -p "$HOME/zeroyaml-sources/demo"
cd "$HOME/zeroyaml-sources/demo"
git init --initial-branch=main
echo 'hello' > greeting.txt
git add greeting.txt
git commit -m "add greeting"
revision="$(git rev-parse --verify HEAD)"
cd -
[ -n "$revision" ] || echo 'No commit exists in the demo repository. Set git user.name and user.email, then run this block again.' >&2
```

Both blocks can be run again: on a later run `git commit` reports that there is
nothing to commit and the existing commit is reused. `--verify` matters when no
commit was ever created, for example because `user.name` and `user.email` are
not configured: a plain `git rev-parse HEAD` then prints the literal `HEAD`,
which the Runner accepts as a revision and fails only later, inside the
checkout, as `execution_error`.

Run the dispatch below in the same shell, PowerShell or bash, and from the
repository root. `$revision` exists only in the shell that prepared the
repository, and an empty or null revision is refused with `INVALID_ARGUMENT`;
from any other directory `grpcurl` cannot find `runner/v1/runner.proto`.

Dispatch it. The Runner registers no gRPC reflection service, so `grpcurl` is
pointed at the contract in `proto/`:

```powershell
$request = @{
  protocol_version = 'runner.v1'
  job = @{
    job_id     = [guid]::NewGuid().ToString()
    repository = @{ location = 'file:///C:/zeroyaml-sources/demo'; revision = $revision }
    execution  = @{ command = @('sh', '-c', 'cat greeting.txt'); working_directory = '.' }
  }
} | ConvertTo-Json -Depth 5

$request | grpcurl -plaintext -import-path proto -proto runner/v1/runner.proto `
  -d '@' localhost:50051 zeroyaml.runner.v1.RunnerService/RunJob
```

The `@` is quoted because PowerShell rejects a bare `@` argument with a parser
error.

```bash
grpcurl -plaintext -import-path proto -proto runner/v1/runner.proto -d @ \
  localhost:50051 zeroyaml.runner.v1.RunnerService/RunJob <<JSON
{
  "protocol_version": "runner.v1",
  "job": {
    "job_id": "$(uuidgen)",
    "repository": { "location": "file://$HOME/zeroyaml-sources/demo", "revision": "$revision" },
    "execution": { "command": ["sh", "-c", "cat greeting.txt"], "working_directory": "." }
  }
}
JSON
```

Expected acknowledgment. It reports acceptance only, never the outcome, because
a dispatch must not block on job duration:

```json
{
  "jobId": "<uuid>",
  "acceptance": "JOB_ACCEPTED",
  "message": "job accepted by the runner",
  "runnerId": "local-runner",
  "instanceId": "<uuid>"
}
```

Expected Runner records: the dispatch acknowledgment, a running status report,
the execution result, and one terminal report. The dispatch acknowledgment and
status-report acknowledgments omit the repository location, revision, command
arguments, and failure message. The execution-result record includes a
`message` field when execution fails. The dispatch acknowledgment and running
status report can appear in either order because execution starts in a separate
goroutine before the dispatch acknowledgment is logged.

```text
level=INFO msg="run job acknowledged" job_id=<uuid> protocol_version=runner.v1 runner_id=local-runner instance_id=<uuid> acceptance=JOB_ACCEPTED
level=WARN msg="job status report acknowledged" job_id=<uuid> reported_state=running runner_id=local-runner instance_id=<uuid> decision=unknown_job job_state=unspecified
level=INFO msg="job execution finished" job_id=<uuid> execution_id=<id> outcome=succeeded duration=<d> exit_code=0
level=WARN msg="job status report acknowledged" job_id=<uuid> reported_state=succeeded runner_id=local-runner instance_id=<uuid> decision=unknown_job job_state=unspecified
```

Expected Control Plane records:

```text
WARN  i.z.c.e.JobExecutionStatusRecorder : Job <uuid> reported RUNNING by runner local-runner instance <uuid> was refused: ...
WARN  i.z.c.e.JobExecutionStatusRecorder : Job <uuid> reported SUCCEEDED by runner local-runner instance <uuid> was refused: ...
```

`decision=unknown_job` is the correct answer here, not a defect. The Control
Plane owns authoritative Job state and never invents a Job from a report, and
this Job was never recorded. For a Job that orchestration records and
dispatches, the same two reports are answered `applied`, as
[Section 7.1](#71-signed-local-delivery) shows, and
[Section 8.2](#82-record-the-job-first-and-follow-its-state) reproduces by
hand. The Docker checks of [Section 7.2](#72-follow-the-pipeline-state) apply
to this execution unchanged.

For the failure path, dispatch `sh -c 'exit 3'`. The terminal report then
carries state `failed` with reason `non_zero_exit` and exit code `3`, and the
Runner still removes every resource. The Runner's `job execution finished`
WARN record includes the execution failure `message` alongside the outcome and
exit code; status-report acknowledgment records omit that message.

### 8.2 Record the Job first and follow its state

Status reports update a Job only when PostgreSQL already holds it and the Job
is assigned to the reporting Runner process. The Control Plane records that
assignment when it dispatches a Job itself
([pipeline orchestration](./architecture/pipeline-orchestration.md)). A report
for a queued Job without an assignment is refused as never dispatched. A Job
dispatched by hand therefore needs one `QUEUED` row that already carries the
Runner's identity, and the dispatch reuses its identifier.

Set `$instanceId` (PowerShell) or `instance_id` (bash) to the `instanceId` of
the [Section 8.1](#81-dispatch-an-unrecorded-job) acknowledgment. It stays
valid until the Runner restarts. Then run this in the shell that prepared
`$revision`, from the repository root:

```powershell
$jobId = [guid]::NewGuid().ToString()
"INSERT INTO job (id, repository_location, revision, command_json, working_directory, status, created_at, queued_at, runner_id, runner_instance_id) VALUES ('$jobId', 'file:///C:/zeroyaml-sources/demo', '$revision', '[""sh"",""-c"",""cat greeting.txt""]', '.', 'QUEUED', now() - interval '1 minute', now() - interval '1 minute', 'local-runner', '$instanceId');" |
  docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml

$request = @{
  protocol_version = 'runner.v1'
  job = @{
    job_id     = $jobId
    repository = @{ location = 'file:///C:/zeroyaml-sources/demo'; revision = $revision }
    execution  = @{ command = @('sh', '-c', 'cat greeting.txt'); working_directory = '.' }
  }
} | ConvertTo-Json -Depth 5

$request | grpcurl -plaintext -import-path proto -proto runner/v1/runner.proto `
  -d '@' localhost:50051 zeroyaml.runner.v1.RunnerService/RunJob
```

```bash
job_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml <<SQL
INSERT INTO job (id, repository_location, revision, command_json, working_directory, status, created_at, queued_at,
                 runner_id, runner_instance_id)
VALUES ('$job_id', 'file://$HOME/zeroyaml-sources/demo', '$revision', '["sh","-c","cat greeting.txt"]', '.', 'QUEUED',
        now() - interval '1 minute', now() - interval '1 minute', 'local-runner', '$instance_id');
SQL

grpcurl -plaintext -import-path proto -proto runner/v1/runner.proto -d @ \
  localhost:50051 zeroyaml.runner.v1.RunnerService/RunJob <<JSON
{
  "protocol_version": "runner.v1",
  "job": {
    "job_id": "$job_id",
    "repository": { "location": "file://$HOME/zeroyaml-sources/demo", "revision": "$revision" },
    "execution": { "command": ["sh", "-c", "cat greeting.txt"], "working_directory": "." }
  }
}
JSON
```

The SQL is sent on standard input rather than as a `-c` argument, so the double
quotes in `command_json` reach `psql` intact in every shell. The creation time
is set one minute in the past on purpose. The Control Plane
refuses a report whose start time precedes the Job's `created_at`, because that
would record a Job that ran before it existed. The start time comes from the
Runner host and `now()` from the database container, and the Docker Desktop VM
clock can run ahead of the host, so an unadjusted row can turn a valid report
into a refusal.

Expected Runner records:

```text
level=INFO msg="job status report acknowledged" job_id=<uuid> reported_state=running runner_id=local-runner instance_id=<uuid> decision=applied job_state=running
level=INFO msg="job execution finished" job_id=<uuid> execution_id=<id> outcome=succeeded duration=<d> exit_code=0
level=INFO msg="job status report acknowledged" job_id=<uuid> reported_state=succeeded runner_id=local-runner instance_id=<uuid> decision=applied job_state=succeeded
```

Expected Control Plane record for the running report, followed by one for the
terminal report:

```text
INFO  i.z.c.e.JobExecutionStatusRecorder : Job <uuid> reported RUNNING by runner local-runner instance <uuid>: Job <uuid> is running
```

Read the state back:

```powershell
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "SELECT id, status, runner_id, failure_code, exit_code FROM job;"
```

The row shows `SUCCEEDED` with runner `local-runner` and exit code `0`. With
the command `sh -c 'exit 3'` in both the row and the dispatch, the Runner logs
`outcome=failed ... exit_code=3 message="job command exited with code 3"`, the
terminal report is acknowledged with `failure_reason=non_zero_exit
decision=applied job_state=failed`, and the row shows `FAILED` with failure
code `NON_ZERO_EXIT` and exit code `3`.

Keep these rows for now. The restart check under
[Persistence checks](#persistence-checks) reads them back, and
[Removing the test Job rows](#removing-the-test-job-rows) deletes them once
that check is done.

## 9. Expected states

### Job lifecycle

The Control Plane owns these states
([`JobStatus`](../control-plane/src/main/java/io/zeroyaml/controlplane/domain/job/JobStatus.java),
mirrored to the Runner as `JobLifecycleState`):

| State | Meaning | Reached by | Where this workflow shows it |
| --- | --- | --- | --- |
| `CREATED` | The Job exists and has not been offered to a Runner | Job creation from a pipeline (`PipelineJobCreationService`) | `test` while `build` runs ([Section 7.2](#72-follow-the-pipeline-state)); the first step only briefly, before it is queued |
| `QUEUED` | The Job is waiting for a Runner | Orchestration queues and assigns a step before `RunJob`; a Runner never reports it | Until the running report arrives; for as long as no Runner is available; and as the hand-inserted row in [Section 8.2](#82-record-the-job-first-and-follow-its-state) |
| `RUNNING` | The Runner reported `JOB_EXECUTION_RUNNING` | An accepted `RunJob`, then a running report | While a step executes ([Section 7.2](#72-follow-the-pipeline-state)); a report for an unrecorded Job is refused as `unknown_job` instead ([Section 8.1](#81-dispatch-an-unrecorded-job)) |
| `SUCCEEDED` | The command exited `0` | Terminal report `JOB_EXECUTION_SUCCEEDED` | Both steps of the passing fixture ([Section 7.2](#72-follow-the-pipeline-state)) |
| `FAILED` | Non-zero exit, timeout, cancellation, or execution error | Terminal report `JOB_EXECUTION_FAILED` with a failure reason | `test` of the failing fixture, `NON_ZERO_EXIT` with exit code `7` ([Section 7.3](#73-failure-path)) |
| `CANCELLED` | The Control Plane stopped the Job | A Control Plane decision: a later step after a failed or cancelled step, or a dispatch the Runner refused for good. A Runner reports a cancelled execution as `FAILED` with reason `cancelled` and never reports `CANCELLED` | Only when `build` fails, or when a dispatch is refused, for example with `FAILED_PRECONDITION` ([Troubleshooting](#pipeline-orchestration)) |

A pipeline has no status column of its own. It has succeeded when every step
is `SUCCEEDED`, and it has failed when one step is `FAILED` or `CANCELLED`;
the Control Plane logs `Pipeline <pipeline> finished: all <n> steps succeeded`
only for the first case.

A report is applied only when it advances the Job. A duplicate, late, or
contradicting report is answered `duplicate`, `unknown_job`, or `conflict` and
leaves state untouched, which is what makes the call safe to repeat. A terminal
report repeats the execution start time, so a lost running report cannot leave
a finished execution without a Job outcome. The full reconciliation and failure
rules are in
[`job-status-contract.md`](./architecture/job-status-contract.md).

### Runner liveness

| State | Meaning |
| --- | --- |
| `HEALTHY` | A registration or heartbeat was acknowledged within `zeroyaml.registration.heartbeat-timeout` |
| `UNAVAILABLE` | That window passed without one |
| `UNKNOWN` | No Runner ever registered under this `runner_id` in the life of this Control Plane process |

Liveness is derived on demand from the last acknowledged contact. There is no
background sweep and no distributed failure detector.

`UNKNOWN` is the state of a Runner that started before the Control Plane, or
whose registration never reached it, and of every Runner after a Control Plane
restart until it registers again. A Runner rejected with an identity conflict
is different: its `runner_id` has an entry, owned by the earlier instance, so
the liveness reported is that instance's.

Runner selection ([#24](https://github.com/MohamedMBG/ZeroYaml/issues/24))
consumes liveness at every dispatch: only a `HEALTHY` Runner that passed its
startup Docker probe is selected. No HTTP endpoint or metric reports liveness,
so locally it shows in two ways. On the Runner side, an accepted registration
line followed by no heartbeat warnings means `HEALTHY`. On the Control Plane
side, a step that finds no such Runner logs `Job <job> (pipeline <pipeline>
step <n>) stays queued: ...` and is retried by a scan every five seconds. The
derivation itself is covered by the Control Plane `registration` tests
([Section 11](#11-automated-coverage)).

### Persistence checks

| Check | Command | Expected |
| --- | --- | --- |
| Schema applied | `docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "\dt"` | `repository_connection`, `pipeline_run`, `job`, and Flyway's `flyway_schema_history` |
| Migration recorded | `... -c "SELECT version, description, success FROM flyway_schema_history;"` | `1`, `2`, and `3`, each with `success = t` |
| Connection row | `... -c "SELECT provider, owner, name, status FROM repository_connection;"` | The row from [Section 6](#6-connect-a-repository) |
| Connection and Jobs survive a database restart | `docker compose -f ./infra/compose.yaml down`, then `up -d postgres`, then repeat the row check and count the `job` rows | The rows are still present, because `down` without `-v` removes the container and keeps the `postgres-data` volume |
| Recorded Job state survives a Control Plane restart | `... -c "SELECT id, status, started_at, completed_at FROM job;"` before and after restarting the Control Plane | The rows from [Section 7](#7-trigger-the-supported-push-scenario), and from [Section 8.2](#82-record-the-job-first-and-follow-its-state) if it ran, are unchanged |

The database check recreates PostgreSQL, not the Control Plane, because the
rows live in the database and a Control Plane restart cannot lose them.
Database calls made while PostgreSQL is down fail until it is back.

The Control Plane check has a side effect: it empties the in-memory Runner
registry, so the Runner from [Section 4.4](#44-runner) logs
`msg="runner heartbeat reported unknown runner"` with `message="Runner
local-runner is not registered"` from then on and must be restarted to
register again. Until then, a new push records its Jobs but leaves the first
one `QUEUED` without a Runner.

#### Removing the test Job rows

Run this only after the restart checks above, because it deletes rows they
read. Rows that orchestration recorded for the fixture are ordinary pipeline
history and can stay; a redelivered push never duplicates them. Rows inserted
by hand in [Section 8.2](#82-record-the-job-first-and-follow-its-state) are
local test data. Remove only those rows, so Job history from other work in a
reused database is kept. Run the command from the shell that inserted the row,
which still holds its identifier:

```powershell
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "DELETE FROM job WHERE id = '$jobId';"
```

```bash
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "DELETE FROM job WHERE id = '$job_id';"
```

Each run of the Section 8.2 block replaces the identifier held in the shell, so
the succeeded and the failed run leave only the last one there. List the rows
inserted from the demo source, then set `$jobId` or `job_id` to each earlier
identifier and repeat the delete:

```powershell
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "SELECT id, status, created_at FROM job WHERE repository_location = 'file:///C:/zeroyaml-sources/demo';"
```

```bash
docker exec -i zeroyaml-postgres psql -U zeroyaml -d zeroyaml -c "SELECT id, status, created_at FROM job WHERE repository_location = 'file://$HOME/zeroyaml-sources/demo';"
```

To discard every local row instead, remove the whole database as described in
[Section 12](#12-shut-down).

### Job logs

Job log streaming ([#26](https://github.com/MohamedMBG/ZeroYaml/issues/26)) is
wired but off in this workflow. The Runner delivers a Job's stdout and stderr
only over a dedicated mTLS listener, which needs a server certificate, a
client certificate, and a CA on both sides; without them the Runner logs `job
log delivery disabled` at startup and drops the captured output. Reading
retained logs through `GET /api/v1/jobs/{jobId}/logs` additionally needs a
bearer token of at least 32 characters and native HTTPS on the Control Plane.
The settings and the trust model are in
[`runner-job-logs.md`](./architecture/runner-job-logs.md).

Logs are therefore verified by the automated test rather than by hand.
`FirstPipelineEndToEndTest` generates throwaway certificates, enables the
listener on both sides, and asserts for every Job a final log marker that is
neither truncated nor incomplete, plus the `fixture---batch-mode -DskipTests
package` and `fixture---batch-mode test` lines on stdout. Retained logs live in
Control Plane memory only and are lost on restart.

### Job lifecycle events

The Control Plane can publish `job.accepted`, `job.started`, `job.succeeded`,
and `job.failed` to the Kafka topic `zeroyaml.job-lifecycle.v1` after each
transition is committed. Publication is off by default, and nothing in this
workflow needs it. The envelope, key, and failure behavior are documented in
[`job-lifecycle-events.md`](./architecture/job-lifecycle-events.md).

The producer can be verified now against a real broker, using the same
`apache/kafka:4.0.0` image as `infra/compose.yaml`, which Testcontainers
starts:

```powershell
Push-Location control-plane
.\mvnw.cmd --batch-mode test "-Dtest=KafkaJobLifecycleEventPublisherIntegrationTest"
Pop-Location
```

To watch the stream from a running Control Plane, start the broker, enable
publication in the Control Plane shell from [Section 4.3](#43-control-plane)
before starting it, and attach the console consumer included in the image:

```powershell
docker compose -f .\infra\compose.yaml up -d kafka
$env:ZEROYAML_JOB_EVENTS_ENABLED = 'true'
# $env:ZEROYAML_KAFKA_BOOTSTRAP_SERVERS defaults to localhost:9092.

docker exec -it zeroyaml-kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server localhost:9092 --topic zeroyaml.job-lifecycle.v1 `
  --from-beginning --property print.key=true
```

```bash
docker compose -f ./infra/compose.yaml up -d kafka
export ZEROYAML_JOB_EVENTS_ENABLED=true

docker exec -it zeroyaml-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic zeroyaml.job-lifecycle.v1 \
  --from-beginning --property print.key=true
```

The Control Plane logs `Publishing job lifecycle events to topic
zeroyaml.job-lifecycle.v1` at startup. The topic is created on the first
event, so until then the consumer may warn that the topic is unknown. Each
line printed is the Job identity followed by one JSON envelope.

An event is published only for a Job that the Control Plane records. With
publication enabled, the push in
[Section 7.1](#71-signed-local-delivery) publishes `job.accepted` for each of
its two Jobs when they are created, then `job.started` and `job.succeeded` or
`job.failed` for each step as its reports are applied. Queueing and
cancellation publish nothing, and a redelivered push publishes nothing new.
[Section 8.1](#81-dispatch-an-unrecorded-job) records no Job, so it produces
no event; [Section 8.2](#82-record-the-job-first-and-follow-its-state)
publishes `job.started` and a terminal event but no `job.accepted`. Neither
stream has been observed in a recorded manual run, so the integration test
above remains the executable check.

With publication enabled and Kafka stopped, the Control Plane still starts,
acknowledges webhooks, and records reports. It logs
`Failed to publish <type> event ...` once the producer gives up, which can
take up to 30 s.

## 10. Troubleshooting

### GitHub webhook

| Symptom | Cause | Action |
| --- | --- | --- |
| Startup fails with `zeroyaml.github.webhook.secret must be set to at least 16 characters` | The secret is missing or too short | Set `ZEROYAML_GITHUB_WEBHOOK_SECRET` in the shell that starts the Control Plane; it is never read from a tracked file |
| Startup fails with `Web server failed to start. Port 8080 was already in use.` | Another process or container publishes `8080` | Set `SERVER_PORT` to a free port in the Control Plane shell and use it in the delivery URL |
| `401`, outcome `REJECTED`, no handler log line | The signature is missing, malformed, or was computed with a different secret | Recompute the digest over the exact bytes sent, with no trailing newline, and confirm the GitHub webhook secret matches the environment value |
| `400`, outcome `REJECTED` | `X-GitHub-Event` or `X-GitHub-Delivery` is missing, or the body is not a JSON object | Send both headers and a JSON object body |
| `400`, outcome `REJECTED`, message ``payload field `<name>` must be ...`` | A verified `push` lacks a field that normalization needs, or the field has the wrong type | Send every field listed in [Section 7](#7-trigger-the-supported-push-scenario); a payload written for an older version of this document lacks `deleted`, `repository`, and `head_commit` |
| `415` | The content type is not JSON | Use `Content-Type: application/json`, and set the same on the GitHub webhook |
| `413` | The payload is above `zeroyaml.github.webhook.max-payload-size` | Raise the limit, up to GitHub's 25 MB cap, or send a smaller payload |
| `200`, outcome `IGNORED`, for a push | `X-GitHub-Event` is not `push` | `push` is the only supported event; `ping` and all others are ignored by design |
| `202` and a `Normalized GitHub push delivery` line, but no pipeline runs | Orchestration ignored the push or could not plan it | A following `PipelineOrchestrator` line names the reason; see [Pipeline orchestration](#pipeline-orchestration) below |
| `503` for a push | Pipeline work is at capacity (64 waiting tasks) | Redeliver the push once the Control Plane catches up |
| `202` and a `has no executable branch commit` line | The push was a tag or a branch deletion | Expected; only a branch push carries a commit to execute |
| Status reports for a recorded Job answered `unknown_job`, or refused for a start before creation | The Job row is missing, uses another identifier, or its `created_at` is later than the reported start | Insert the row before dispatching, reuse its `id` as `job_id`, and backdate `created_at` as in [Section 8.2](#82-record-the-job-first-and-follow-its-state) |
| GitHub reports a delivery error while local `curl` works | The tunnel is down or the URL is wrong | Fix the tunnel, then redeliver from **Recent Deliveries** rather than pushing again |

### Pipeline orchestration

Each line below is logged by `PipelineOrchestrator` or `PipelineProgression`
after a `202`. The webhook response never reports these outcomes.

| Symptom | Cause | Action |
| --- | --- | --- |
| `Push delivery <delivery> for <owner>/<name> is ignored: the repository is not connected` | No `repository_connection` row matches the payload's owner and name | Insert the row from [Section 6](#6-connect-a-repository); owner and name are stored in lowercase |
| `... is ignored: the connection is SUSPENDED` (or `PENDING`, `DISCONNECTED`) | Only `ACTIVE` connections accept events | Set the status to `ACTIVE` |
| `No pipeline for push delivery ...: GitHub API found no readable tree for the pushed commit of local/demo (status 404)` | The fixture directory has no file named after the pushed commit, or the owner, name, or commit in the payload differs from the fixture path | Write the listing for that commit as in [Section 4.2](#42-push-fixture), or resend with the commit that has one |
| `No pipeline for push delivery ...: GitHub API is unreachable for local/demo: <exception>`, or a line saying the API did not answer in time | The fixture container is not running, or `ZEROYAML_GITHUB_API_BASE_URL` points elsewhere | `docker start zeroyaml-github-fixture`, and check the base URL in the Control Plane shell |
| `No pipeline for push delivery ...: No supported build file found at the repository root; supported: pom.xml` | The listing has no `pom.xml` | Add `pom.xml` to the repository and to the listing |
| `No pipeline for push delivery ...: GitHub API refused access ... (status 403)` or a rate-limit line | A real GitHub listing was refused or rate limited | Wait, or supply a read-only `ZEROYAML_GITHUB_API_TOKEN`; rate-limited pushes are retried at most twice |
| `Job <job> (pipeline <pipeline> step 1) stays queued: ...` | No `HEALTHY` Runner that passed its Docker probe is registered | Start or restart the Runner as in [Section 4.4](#44-runner); a scan retries the step every five seconds, without a redelivery |
| `Job <job> (...) is cancelled: runner refused the job with FAILED_PRECONDITION; dispatching it again to this runner cannot succeed` | The location from `ZEROYAML_GITHUB_REPOSITORY_LOCATION_TEMPLATE` is not below `ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT`, the root is unset, or the scheme is unsupported | Align the template and the root, restart the affected process, and push a new commit; the cancelled Jobs of that commit are not re-dispatched by a redelivery. The Runner log names the exact refusal ([Docker](#docker)) |
| `Job <job> (...) is cancelled: runner <id> instance <id> accepted the job, but it was assigned to runner ...` | `zeroyaml.runner.host` and `zeroyaml.runner.port` reach a different Runner process than the one registered | Run one Runner, at the address the Control Plane dispatches to (`localhost:50051` by default) |
| `Dispatch of job <job> ... ended with DEADLINE_EXCEEDED; it stays queued and assigned` | The Runner did not acknowledge within `zeroyaml.runner.dispatch-deadline` | The Job is not resent automatically, because the Runner may be running it. Recovery is Phase 5 work ([#210](https://github.com/MohamedMBG/ZeroYaml/issues/210)); locally, push a new commit |
| `build` of a real repository fails with `NON_ZERO_EXIT`, exit code `127` when the repository has no `mvnw` | The default `alpine:3.22` Job image has neither a JDK for the Maven wrapper nor `mvn` | Set `ZEROYAML_RUNNER_JOB_IMAGE` to a pinned image with JDK 21, as in [Section 7.5](#75-delivery-from-github-itself); the fixture wrapper needs neither |

### gRPC

| Symptom | Cause | Action |
| --- | --- | --- |
| `Runner registration with the Control Plane at localhost:50052 failed - runner local-runner stays unavailable` | The Control Plane is not started, or listens elsewhere | Start the Control Plane first, or set `ZEROYAML_CONTROLPLANE_ADDRESS` to its `zeroyaml.registration.port` |
| The same message ending in `DeadlineExceeded` while the Control Plane is up and listening | Resolving `localhost` took longer than `ZEROYAML_RUNNER_REGISTRATION_TIMEOUT` (default `5s`); observed on Windows | Set `ZEROYAML_CONTROLPLANE_ADDRESS` to `127.0.0.1:50052`, or raise the timeout; bounded retry is tracked as [#272](https://github.com/MohamedMBG/ZeroYaml/issues/272) |
| `rejected registration for runner local-runner - identity conflict` | This `ZEROYAML_RUNNER_ID` is already registered under a different instance: another Runner is running, or this Runner was restarted after an accepted registration | Stop the other Runner if there is one, then restart the Control Plane to clear its in-memory registry, or give this Runner a distinct `ZEROYAML_RUNNER_ID`; a lasting fix is tracked as [#271](https://github.com/MohamedMBG/ZeroYaml/issues/271) |
| Every dispatch answered `JOB_REJECTION_RUNNER_UNAVAILABLE`, message `runner status is ... and it is not accepting work` | Registration was not accepted, or Docker was unavailable at startup | Fix the cause, then restart the Runner; both checks run once at startup. If the earlier registration was accepted, restart the Control Plane first to avoid an identity conflict |
| A dispatch answered `JOB_REJECTION_RUNNER_UNAVAILABLE`, message `runner is already running its maximum number of concurrent jobs` | Every execution slot is in use; `ZEROYAML_RUNNER_MAX_CONCURRENT_JOBS` defaults to `1` | Wait for the running Job's terminal report and dispatch again, or raise the limit. Do not restart the Runner: that cancels the healthy running Job |
| A dispatch answered `JOB_REJECTION_RUNNER_UNAVAILABLE`, message `runner is shutting down and accepts no new jobs` | The Runner received `SIGINT` or `SIGTERM` and is draining | Dispatch again once the Runner has been started again |
| Dispatch answered `JOB_REJECTION_UNSUPPORTED_PROTOCOL_VERSION` | `protocol_version` is not `runner.v1` | Send the version declared in `proto/runner/v1/runner.proto` |
| Dispatch fails with `INVALID_ARGUMENT` and no response body | A contract violation, such as a missing job identity or an empty command | Fix the request; a caller defect is deliberately not answered with a rejection |
| Heartbeats answered `HEARTBEAT_UNKNOWN_RUNNER` | The Control Plane restarted and lost its in-memory registry | Restart the Runner so it registers again |
| Control Plane startup fails with `Failed to start the runner-facing gRPC server on port 50052` | The port is already bound | Stop the other process, or change `zeroyaml.registration.port` and `ZEROYAML_CONTROLPLANE_ADDRESS` together |
| `grpcurl` reports `server does not support the reflection API` | The Runner registers no reflection service | Pass `-import-path proto -proto runner/v1/runner.proto` |

### Docker

| Symptom | Cause | Action |
| --- | --- | --- |
| `docker daemon unavailable - runner will not accept jobs` | The daemon is not running, or did not answer within 5 s | Start Docker Desktop, confirm `docker version`, then restart the Control Plane and the Runner; the Runner's registration is accepted even when the probe fails, so restarting it alone ends in an identity conflict |
| Terminal report with reason `execution_error` | The workspace or the executor could not be prepared, for example a missing image | Pre-pull `ZEROYAML_RUNNER_JOB_IMAGE` and `ZEROYAML_RUNNER_CHECKOUT_IMAGE` |
| Dispatch fails with `FAILED_PRECONDITION`, naming the scheme | The repository scheme is not `https`, `http`, or `file` | Use a supported scheme; other git transports can run arbitrary commands and are refused |
| Dispatch fails with `FAILED_PRECONDITION`: `file repository locations are disabled on this runner` | `ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT` is unset | Set it to an existing absolute directory and restart the Runner |
| Dispatch fails with `FAILED_PRECONDITION`: `file repository location is outside the configured local source root` | The path escapes the root, including through a symlink | Move the source below the root; containment is checked after symlinks are resolved |
| Terminal report with reason `timeout` | The execution exceeded `ZEROYAML_RUNNER_JOB_TIMEOUT` | Raise the timeout or shorten the command; the checkout counts toward the same budget |
| Containers or volumes left behind after a crash | The Runner exited before cleanup finished | Remove them by label: `docker rm -f $(docker ps -aq --filter label=io.zeroyaml.managed=true)`, then `docker volume rm $(docker volume ls -q --filter label=io.zeroyaml.managed=true)`. `docker volume prune` with the same filter is not enough: workspace volumes are named, and since Docker Engine 23 `prune` removes only anonymous volumes unless `--all` is passed |
| `go test ./...` fails in `internal/sandbox` | Container tests were opted into without a usable daemon | Unset `ZEROYAML_RUNNER_DOCKER_TESTS`, or start Docker |
| Control Plane tests cannot start a container | Testcontainers needs a reachable daemon | Start Docker; these tests use a disposable `postgres:16-alpine` and never the compose database |
| `docker run` for `zeroyaml-github-fixture` fails with `port is already allocated` | Another process publishes `127.0.0.1:8099` | Publish the fixture on a free loopback port and set `ZEROYAML_GITHUB_API_BASE_URL` to match before starting the Control Plane |

### PostgreSQL

| Symptom | Cause | Action |
| --- | --- | --- |
| Startup fails with `no password was provided` | `ZEROYAML_DATABASE_PASSWORD` is unset | Export it in the shell that starts the Control Plane |
| Startup fails with a refused connection after about 5 s | The database is unreachable; connection acquisition is bounded at 5 s on purpose | `docker compose -f ./infra/compose.yaml up -d postgres`, then confirm `5432` is published |
| `password authentication failed for user "zeroyaml"` | The password differs from the compose value | Align the environment value with `infra/compose.yaml`, or point `ZEROYAML_DATABASE_URL` at the right database |
| Startup fails with a Flyway validation error | The volume holds a schema from an edited migration | Applied migrations are immutable: add a new versioned migration, or discard local data with `docker compose -f ./infra/compose.yaml down -v` |
| An insert is rejected by a check constraint | The row contradicts the domain rules | Use lowercase `owner` and `name`, provider `GITHUB`, a valid status, and `status_changed_at >= connected_at` |
| An insert is rejected by `repository_connection_identity_key` | The repository is already connected | Update the existing row; one repository can be connected only once |
| Requests fail after about 5 s | The statement timeout is bounded at 5 s | Investigate the slow statement; an unreachable or stalled database fails fast by design |

## 11. Automated coverage

Run both suites from the repository root before proposing a change:

```powershell
Push-Location control-plane
.\mvnw.cmd --batch-mode verify
Pop-Location

Push-Location runner
go test ./...
Pop-Location
```

Container-backed Runner tests are opt-in, because they need a daemon, `git`,
and the configured images:

```powershell
Push-Location runner
$env:ZEROYAML_RUNNER_DOCKER_TESTS = '1'
go test -count=1 -run TestDockerExecution ./internal/sandbox/
Remove-Item Env:ZEROYAML_RUNNER_DOCKER_TESTS
Pop-Location
```

The first-pipeline end-to-end test is opt-in, because it builds and starts a
real Runner and needs Go, Git, and a Docker daemon. CI enables it in the
Control Plane job. From the repository root:

```powershell
$env:ZEROYAML_E2E_TESTS = '1'
Push-Location control-plane
.\mvnw.cmd --batch-mode '-Dtest=FirstPipelineEndToEndTest' test
Pop-Location
Remove-Item Env:ZEROYAML_E2E_TESTS
```

How [Section 7](#7-trigger-the-supported-push-scenario) maps onto
`FirstPipelineEndToEndTest`:

| This document | The test |
| --- | --- |
| `docker compose ... up -d postgres` | A disposable Testcontainers `postgres:16-alpine` |
| `busybox` fixture on `127.0.0.1:8099` ([Section 4.2](#42-push-fixture)) | An in-process HTTP server that answers only the Git Trees paths of its two fixture commits and `404` otherwise |
| Repository `local/demo` with one passing commit, then one failing commit | Repositories `fixture/success` and `fixture/failure`, each with one commit; the same `pom.xml` and wrapper, exiting `7` for `--batch-mode test` |
| `ZEROYAML_GITHUB_API_BASE_URL`, `ZEROYAML_GITHUB_REPOSITORY_LOCATION_TEMPLATE` | `zeroyaml.github.repository.api-base-url` and `location-template`, pointed at the fixture server and a `file:` URI below the Runner's source root |
| `go run ./cmd/runner` with `ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT` and `ZEROYAML_CONTROLPLANE_ADDRESS=127.0.0.1:50052` | A Runner built from `runner/` with the same settings, runner ID `e2e-runner`, free ports, and the `alpine:3.22` Job image |
| Job log delivery disabled | The mTLS Job log listener enabled with throwaway certificates; final markers and stdout asserted |
| `INSERT INTO repository_connection` with status `ACTIVE` | The same insert for both fixture repositories |
| Signed delivery, `202` ([Section 7.1](#71-signed-local-delivery)) | Signed delivery with `X-Hub-Signature-256`; `202` asserted |
| `build` and `test` rows, statuses, runner, timestamps ([Sections 7.2](#72-follow-the-pipeline-state) and [7.3](#73-failure-path)) | The same columns asserted; `NON_ZERO_EXIT`, exit code `7`, and a non-blank failure message for the failing `test` |
| No Docker resources left behind | The test removes any remaining containers and volumes of the execution IDs its Runner logged |

The test does not cover the ignored and repeated pushes of
[Section 7.4](#74-repeated-and-ignored-pushes) end to end; the Control Plane
`orchestration` tests and `PipelineJobCreationServiceTest` cover them
in-process.

What the suites cover, per step of this document:

| Step | Covered by |
| --- | --- |
| Webhook envelope, signature, event allow-list, response contract | Control Plane `github.webhook` tests |
| Push normalization, including tag pushes, deletions, and rejected fields | Control Plane `github.webhook` tests |
| Repository connection persistence and constraints | Control Plane `connection` tests, against a Testcontainers PostgreSQL |
| Git Trees listing, its limits, and its configuration | Control Plane `GitHubRepositorySourceTest` and `GitHubRepositoryPropertiesTest` |
| Pipeline inference, Job creation, Runner selection, and step progression | Control Plane `PipelineInferrerTest`, `PipelineJobCreationServiceTest`, `RunnerSelectionIntegrationTest`, and `PipelineOrchestratorTest` |
| Job and pipeline run persistence | Control Plane `JdbcJobStoreTest`, against a Testcontainers PostgreSQL |
| Registration, heartbeat, and derived liveness | Control Plane `registration` tests; Runner `registrationclient` and `heartbeat` tests |
| `RunJob` validation, acceptance, and rejection | Runner `grpcserver` tests, including over the transport |
| Container execution, cleanup, and result mapping | Runner `sandbox` tests; `TestDockerExecution*` for real containers |
| Status reporting and reconciliation | Runner `jobstatus` tests; Control Plane `execution` tests |
| Job log capture, delivery, and retention | Runner `joblogs` and `sandbox` tests; Control Plane `execution` tests |
| Lifecycle event payloads, publish-after-commit, and producer failure handling | Control Plane `events` tests, including a Testcontainers Kafka broker; `JdbcJobStoreTest` for commit visibility |
| A signed push through to executed, persisted, and logged Jobs | `FirstPipelineEndToEndTest`, opt-in through `ZEROYAML_E2E_TESTS=1` |

#### Manual execution record

The continuous scenario of Section 7 was executed by hand against `main` at
commit `0dbb6b7` on Windows on 2026-11-16, with PowerShell 7 and Docker Desktop
(engine 29.2.1). The PowerShell steps ran with the documented commands and
values, with three local deviations: the source root, fixture directory, and
webhook secret file lived below a temporary directory instead of
`C:\zeroyaml-sources`, `C:\zeroyaml-github-fixture`, and the home directory;
the Control Plane used `SERVER_PORT=8085` because `8080` was taken; and the
Runner was a Linux build of `runner/cmd/runner` started in a container with
the Docker socket mounted, rather than through `go run`, so the source root
and location template used the Docker Desktop host path. That run
covered the startup lines, the fixture listing, the passing and failing
pipelines with the rows and log lines shown, the redelivery, the three ignored
pushes of [Section 7.4](#74-repeated-and-ignored-pushes), the absence of
leftover Docker resources, and the connection and Job rows surviving a
database restart. It did not cover the bash blocks, Windows PowerShell 5.1, a
Runner started with `go run` on the host, Job logs, lifecycle events, or a
delivery from GitHub itself.

The hand dispatch of [Section 8](#8-dispatch-a-job-by-hand) and the webhook
gate checks were last executed on Windows on 2026-10-26, with PowerShell 7.6.7,
Git Bash with `perl`, and `grpcurl` run from the `fullstorydev/grpcurl:v1.9.3`
container, including the recorded Job surviving a Control Plane restart.

`FirstPipelineEndToEndTest` is authoritative. When behavior changes and this
document disagrees with the test, correct the document.

## 12. Shut down

```powershell
# Stop the Runner, then the Control Plane: Ctrl+C in each shell.
docker rm -f zeroyaml-github-fixture
docker compose -f .\infra\compose.yaml down
```

Removing the fixture container keeps `C:\zeroyaml-github-fixture` and the
fixture repository, so [Section 4.2](#42-push-fixture) can be run again later.
Delete both directories to start from a clean fixture.

The Runner stops on `SIGINT` or `SIGTERM` by closing its listener, draining
in-flight RPCs, and cancelling running executions, all inside one
`ZEROYAML_RUNNER_SHUTDOWN_TIMEOUT` budget measured from the signal. If that
budget runs out it logs `runner exited before every job execution finished` and
exits, leaving labelled Docker resources to be removed as described under
[Docker](#docker).

`down` keeps the `postgres-data` volume, so connected-repository rows survive.
Add `-v` to discard the local database as well.
