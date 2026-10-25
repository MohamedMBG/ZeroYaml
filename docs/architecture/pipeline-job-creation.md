# Pipeline Job Creation

A `Pipeline` is a decision; a Runner executes Jobs. Job creation is the
Control Plane seam that turns the first into the second (#23):

```text
PipelineInferrer ──> Pipeline ──> PipelineJobTranslator ──> PipelineJob list ──> PipelineJobCreationService ──> JobStore
     (#22)            (#21)        (domain, pure)          (Job + step link)       (application, idempotent)
```

Runner selection (#24) and `RunJob` dispatch are not part of this seam. The
created Jobs stay in `CREATED`; queuing them, selecting a Runner, and
dispatching belong to the end-to-end coordination work (#30).

## Translation (domain)

`io.zeroyaml.controlplane.domain.pipeline.PipelineJobTranslator` builds one Job
per step and records no state:

| Job field | Source |
| --- | --- |
| `id` | Name-based UUID over `pipeline-job:<pipeline id>/<step name>` |
| `repository` | `Pipeline.source()`: repository location and commit revision, unchanged |
| `execution` | `PipelineStep.execution()`: command arguments and working directory, unchanged |
| `createdAt` | The caller's timestamp, shared by every Job of the call |
| `status` | `CREATED` |

Each Job is returned as a `PipelineJob`, which links it to its pipeline, its
step name, and its 1-based position. The Job model itself stays unaware of
pipelines.

- **Order** is the order of `Pipeline.steps()`, never sorted or reordered. It
  does not depend on timestamps, which are equal within one call.
- **Validity** comes from the model: a pipeline with no steps, a duplicate step
  name, an invalid step name, or an empty or blank command cannot be
  constructed, so every step that reaches translation is executable.
- **Identity** is deterministic. Step names are unique within a pipeline, so the
  identities in one pipeline are distinct. The same step name in another
  pipeline gives another identity. The identity does not cover the plan:
  two different plans under one pipeline identity share the identity of an
  equally named step, which is why recording binds a pipeline identity to one
  plan first.

## Recording and idempotency (application)

`io.zeroyaml.controlplane.execution.PipelineJobCreationService.createJobs`
records a pipeline in two steps:

1. **Bind the plan.** `PipelineStore.recordIfAbsent` atomically binds the
   pipeline identity to this exact plan (source and ordered steps), or returns
   the plan already bound to it. A different plan is refused with
   `PipelineJobConflictException` here, before any Job is recorded.
2. **Record the Jobs.** Each translated Job is recorded through
   `JobStore.recordIfAbsent`, which atomically records it or returns the Job
   already recorded under its identity. That is one store operation per step.

It returns a `PipelineJobCreation` with a `PipelineJobReference` for every
step's recorded Job, in order, and how many of them this call created. A
reference carries the pipeline identity, the step name and position, and the
Job identity, never the Job itself: the recorded Job is a mutable aggregate
owned by the `JobStore`, so callers read it through `JobStore.find` and change
it only through `JobStore.update`, which serializes changes per Job.

The pipeline identity is derived from the repository location and revision
(see [`pipeline-inference.md`](./pipeline-inference.md)), and the Job identity
from the pipeline identity and the step name. Therefore:

| Input | Result |
| --- | --- |
| First pipeline for a revision | Every Job recorded; `createdCount` equals the step count |
| Repeated webhook delivery or inference retry with an equal plan | Nothing recorded; the existing Jobs are returned as recorded, whatever state they reached |
| Concurrent calls with an equal plan | Each Job is recorded exactly once |
| Equal plan after an earlier call failed part-way | Only the missing Jobs are recorded |
| Same pipeline identity with another repository, a changed command, or added or removed steps | `PipelineJobConflictException`; nothing is recorded |
| Different plans for one identity racing each other | The first to bind wins; every call with another plan gets `PipelineJobConflictException` and records nothing |

A changed plan for an already-processed revision typically follows a change
of inference rules. Reusing the recorded Jobs would run a plan nobody decided
on, mixing both plans would leave Jobs that belong to neither (a dropped step's
Job would stay recorded but appear in no result), and replacing them would
rewrite history, so the call is refused.

A Job with a derived identity but another definition can only be recorded
outside this service once the plan is bound. The service treats that as a
broken invariant and fails with `IllegalStateException` without changing the
recorded Job.

Each call writes one log line with the pipeline identity, the revision, and the
created and total counts. The repository location is not logged, because it
can embed credentials.

## Limits

- Pipelines and Jobs are held by the in-memory `PipelineStore` and `JobStore`
  and lost on restart until durable persistence lands (#28). Idempotency and
  plan binding therefore hold per Control Plane process.
- The in-memory `PipelineStore` keeps the 10,000 most recently bound plans and
  evicts the oldest beyond that, so a burst of pushes cannot stop Job creation.
  An evicted pipeline can be bound again. Its recorded Jobs are still checked
  against the plan, so a changed plan cannot reuse a Job identity with another
  definition; only a step dropped from the changed plan can stay recorded
  outside the returned list. Jobs are not evicted, at most 50 per pipeline,
  until #28 replaces both stores.
- A pipeline with an already-recorded Job whose definition differs is detected
  before any Job of the call is recorded, so the call fails without a partial
  set. Both this and a refused plan are logged with the pipeline identity and
  revision.
- A plan bound to a pipeline identity cannot be replaced, even deliberately.
  Re-running a revision with new inference rules needs an explicit supersede or
  re-run decision, which belongs to the Phase 3 pipeline lifecycle.
- `PipelineJobConflictException` is unchecked. Once the webhook path calls this
  service, a redelivered revision whose plan changed under new inference rules
  would surface as a server error unless the caller maps it. Defining that
  mapping, together with the full-store `IllegalStateException`, belongs to #30.
- The link from a Job back to its step is returned to the caller and can be
  recomputed from the bound plan and the identities, but it is not stored as
  such. Storing it is part of persistence (#28).
- Nothing calls the service from the webhook path yet. Obtaining the repository
  file listing, calling inference, and handing the Jobs to selection and
  dispatch are #30.

## Out of scope

Runner selection, gRPC dispatch, Docker execution, sequencing the execution of
later steps on earlier results, and pipeline visualization.
