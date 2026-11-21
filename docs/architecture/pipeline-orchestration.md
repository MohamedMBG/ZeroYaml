# Pipeline Orchestration

Pipeline orchestration joins the Phase 2 building blocks into the first
end-to-end scenario (#30). A verified GitHub push for a connected repository
becomes an inferred pipeline. Its steps become Jobs, and each Job is dispatched
to a healthy Runner once the steps it depends on succeeded (#170). The Runner executes the Job in Docker and reports
status and logs back.

```text
POST /webhooks/github ──> NormalizingGitHubWebhookDeliveryHandler ──> RepositoryPushEventHandler
   (#16, #17)                 (#20, RepositoryPushEvent)                  (PipelineOrchestrator)
                                                                               │ worker thread
   RepositoryConnectionLookup ──> RepositorySource ──> PipelineInferrer ──> PipelineJobCreationService
          (#18, #19)              (Git Trees API)          (#22)                   (#23, #28)
                                                                               │
                                     PipelineProgression ──> RunnerSelector ──> RunnerClient.dispatchJob
                                                                  (#24)             (RunJob, #13)
   ReportJobStatus (#27) ──> JobExecutionStatusRecorder ──> JobCompletionListener ──> next step
```

The Control Plane decides which pipeline runs, where each step runs, and when
each step starts. The Runner executes only the Job it is given.

The code lives in `io.zeroyaml.controlplane.orchestration`. The GitHub listing
lives in `io.zeroyaml.controlplane.github.repository`.

## Push handling

`NormalizingGitHubWebhookDeliveryHandler` hands every normalized branch push to
`RepositoryPushEventHandler`. Tag pushes and branch deletions are normalized
away before that point. `PipelineOrchestrator` queues the push and returns at
once, so the webhook answers `202 Accepted` without waiting for GitHub or the
Runner.

The push is then processed on the orchestrator's worker thread:

1. The repository connection is looked up. A push for a repository that is not
   connected, or whose connection does not accept events, is logged and
   ignored. Every branch of an accepted repository is processed; the default
   branch is not a filter.
2. The commit's root file listing is read through the `RepositorySource`.
3. `PipelineInferrer` maps the listing to a pipeline. An unsupported or
   ambiguous repository is logged and records no Job.
4. `PipelineJobCreationService` records one Job per step. A redelivered push
   records nothing new, because Job creation is idempotent.
5. `PipelineProgression` dispatches every step that is ready.

## Repository listing

`GitHubRepositorySource` calls the GitHub Git Trees API without recursion:

```text
GET {api-base-url}/repos/{owner}/{name}/git/trees/{commit-sha}
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2022-11-28
Authorization: Bearer <token>        (only when a token is configured)
```

Only `blob` entries are used, because inference reads root files only.
Directories and submodules are skipped. The listing is refused, and no Job is
recorded, when any of the following happens:

- the API answers anything other than `200`;
- the tree is marked `truncated`;
- the body is not a JSON tree or contains an unsafe path;
- the body exceeds `max-tree-response-size`;
- the whole exchange takes longer than `api-timeout`.

Redirects are not followed. Failure messages name the status and the setting to
fix. They never include the response body or the token.

The Runner does not use the API. It checks the source out from the location that
`location-template` produces. The template is recorded in the Job's
`RepositoryReference`.

| Setting | Environment variable | Default | Rule |
| --- | --- | --- | --- |
| `zeroyaml.github.repository.api-base-url` | `ZEROYAML_GITHUB_API_BASE_URL` | `https://api.github.com` | `https`; `http` only for `localhost`, `127.0.0.1`, or `[::1]`; no user info, query, or fragment. |
| `zeroyaml.github.repository.api-token` | `ZEROYAML_GITHUB_API_TOKEN` | none | Optional read-only token for private repositories. Never stored in a tracked file. |
| `zeroyaml.github.repository.api-timeout` | - | `10s` | Greater than zero, at most `1m`. |
| `zeroyaml.github.repository.max-tree-response-size` | - | `1MB` | Greater than zero, at most `32MB`. |
| `zeroyaml.github.repository.location-template` | `ZEROYAML_GITHUB_REPOSITORY_LOCATION_TEMPLATE` | `https://github.com/{owner}/{name}.git` | Must contain `{owner}` and `{name}`; must produce an absolute `https`, `http`, or `file` URI without user info, query, or fragment. Plain HTTP is allowed only for loopback hosts. |

The Control Plane refuses to start when a setting breaks its rule. A local run
can point the template at a directory the Runner serves. For example,
`file:///srv/zeroyaml/sources/{owner}/{name}` works when
`ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT` is `/srv/zeroyaml/sources`.

## Step progression

`PipelineProgression` reconciles a pipeline against its recorded Jobs and
dependencies instead of remembering what it did. It reads every step's status
and dependencies, computes the
[dependency-aware schedule](./execution-model.md#dependency-aware-scheduling),
and acts on each decision:

| Decision | Action |
| --- | --- |
| `Skip` | Record the Job as `SKIPPED` and log the dependencies that did not succeed. |
| `Ready`, Job `CREATED` or `QUEUED` without an assignment | Dispatch the step. |
| `Ready`, Job `QUEUED` with an assignment | Nothing; the dispatch outcome is still open. |
| `Blocked` | Nothing; the completion of a pending dependency runs progression again. |
| `Running` or `Settled` | Nothing. |

Skips are recorded first and dispatches follow, both in step order. Every ready
step is dispatched in the same call, so steps that do not depend on each other
run in parallel when Runner capacity allows. When a dispatch is abandoned, its
Job is cancelled and the schedule is computed again, so the steps that depend
on it are skipped in the same call.

A step is therefore dispatched only after every step it depends on
`SUCCEEDED`, and a failed, cancelled, or skipped step stops only the steps that
depend on it. For the inferred Maven pipeline, where `test` depends on `build`,
this is the same order as before #170. When nothing is left to execute, the
pipeline is logged as finished with the number of succeeded, failed,
cancelled, and skipped steps. Running progression twice dispatches nothing
twice and skips nothing twice.

Progression runs after a push, and after `JobExecutionStatusRecorder` commits a
terminal status for a Job. The recorder calls `JobCompletionListener` once the
transition is committed; it does not wait for the next dispatch.

## Dispatch

Each dispatch has two phases.

First, inside one Job store update, the step is queued, a Runner is selected,
and the selection is recorded as the Job's assignment. When no Runner is
available, the Job stays `QUEUED` without an assignment and a warning is
logged. A bounded scan runs at five-second intervals and retries unassigned
queued Jobs, including after a Control Plane restart; a redelivered push also
retries them. Paging and other work can delay a retry beyond one interval.

GitHub rate-limit responses schedule up to two retries of the push, with at
least one minute of delay and exponential backoff. Numeric `Retry-After` and
`X-RateLimit-Reset` values can lengthen that delay. Pending retries are bounded
to 64 and live only in the Control Plane process; shutdown, excessive delays,
or exhausted attempts require redelivery.

Second, `RunJob` is sent to the configured Runner endpoint. The assignment
already exists, so a Runner that reports `RUNNING` before its acknowledgment
arrives is accepted. The outcome is then handled as follows:

| Outcome | Result |
| --- | --- |
| Accepted by the assigned Runner process | Job stays `QUEUED` until the running report. |
| Accepted by any other Runner process | Job is cancelled; the steps that depend on it are skipped. |
| Rejected as Runner unavailable | Job stays queued without an assignment; a later progression can retry. |
| Other Runner rejection | Job is cancelled and the rejection reason is logged; the steps that depend on it are skipped. |
| `INVALID_ARGUMENT` or `FAILED_PRECONDITION` | Job is cancelled, because the Runner can never run it; the steps that depend on it are skipped. |
| Any other failure, such as `DEADLINE_EXCEEDED` or `UNAVAILABLE` | Job stays `QUEUED` and assigned and is not resent, because the Runner may already be executing it. |

Each cancellation is logged with the Job, pipeline, step, and reason.

## Assignment and trust

The assignment recorded at dispatch is the only source of Runner attribution:

- `Job.assign` binds a queued, unassigned Job to one Runner process. An
  assigned Job can start only on that process.
- `JobExecutionStatusRecorder` refuses a running or terminal report for a queued
  Job without an assignment, because that Job was never dispatched. It also
  refuses reports from any process other than the assigned one. Both cases are
  `CONFLICT`. See [Job status contract](./job-status-contract.md).
- `JobLogRecorder` accepts log records only from the assigned process. See
  [Runner job logs](./runner-job-logs.md).

An unauthenticated status report therefore cannot create or replace an
assignment. `V3__allow_job_assignment_before_start.sql` lets a `QUEUED` or
`CANCELLED` row keep its assignment. See [Job persistence](./job-persistence.md).

## Concurrency and capacity

All pushes and completions run on one daemon thread named
`pipeline-orchestrator`, with a queue of 64 waiting tasks. Pushes and completions
for one pipeline can therefore never interleave, and a slow GitHub API or Runner
never blocks a webhook request or a status report.

When the queue is full:

- a push is refused, and the webhook answers `503 Service Unavailable` so
  GitHub can redeliver it;
- a completion is logged as an error. Redelivering the push resumes the
  pipeline.

Each GitHub call is bounded by `api-timeout`, and each dispatch by
`zeroyaml.runner.dispatch-deadline`. On shutdown, the worker stops accepting new
tasks and is given up to 10 seconds to drain without interruption. Work still
pending when the process exits can be lost.

The Runner frees its concurrency slot before it sends the terminal report.
Without that, dispatching the next step could be refused as busy by the
execution that just finished. When several steps are ready at once and the
Runner has fewer free slots, it refuses the rest as unavailable; those Jobs stay
queued without an assignment and the five-second retry scan dispatches them as
slots free up. See
[Runner execution sandbox](./runner-execution-sandbox.md).

## First scenario

For a repository with a root `pom.xml`, inference (#22) produces two steps:

| Step | Command with `mvnw` at the root | Command without it |
| --- | --- | --- |
| `build` | `sh mvnw --batch-mode -DskipTests package` | `mvn --batch-mode -DskipTests package` |
| `test` | `sh mvnw --batch-mode test` | `mvn --batch-mode test` |

The steps run in the Runner's configured Job image. The default `alpine:3.22`
has no JDK, so set `ZEROYAML_RUNNER_JOB_IMAGE` to a pinned image that provides
one. A command that exits non-zero fails its Job with `NON_ZERO_EXIT` and the
exit code, and the `test` step, which depends on `build`, is skipped.

`PipelineOrchestratorTest` covers this scenario in-process. It replaces only the
repository provider, the Runner selector, and the Runner transport. The run
against a real Runner and Docker is the automated test of #31.

## Limits

- Orchestration state is not durable. A Control Plane restart loses waiting
  pushes and completions. A redelivered push resumes a pipeline whose step
  waited for a Runner or whose completion was lost.
- A Job whose `RunJob` outcome is unknown stays `QUEUED` and assigned.
  Recovering it is Phase 5 work (#210).
- Dispatch reaches one configured Runner endpoint. With several registered
  Runners, a selection that does not match the endpoint's process cancels the
  Job instead of routing it.
- Queueing and cancellation publish no lifecycle event.
- `ReportJobStatus` is still unauthenticated. A sender that knows a dispatched
  Job's `runner_id` and `instance_id` can report status for it. Transport
  authentication is Phase 6 work.
- Independent steps are dispatched together, but there is one configured
  Runner endpoint, so parallelism is bounded by that Runner's concurrency.
  Retries, timeouts, and superseding an earlier push are later work (#171 and
  later phases).
