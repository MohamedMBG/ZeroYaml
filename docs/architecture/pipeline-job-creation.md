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
  pipeline gives another identity.

## Recording and idempotency (application)

`io.zeroyaml.controlplane.execution.PipelineJobCreationService.createJobs`
translates the pipeline and records each Job through `JobStore.addIfAbsent`.
It returns a `PipelineJobCreation` with every step's recorded Job, in order,
and how many of them this call created.

The pipeline identity is derived from the repository location and revision
(see [`pipeline-inference.md`](./pipeline-inference.md)), and the Job identity
from the pipeline identity and the step name. Therefore:

| Input | Result |
| --- | --- |
| First pipeline for a revision | Every Job recorded; `createdCount` equals the step count |
| Repeated webhook delivery or inference retry for the same revision | Nothing recorded; the existing Jobs are returned as recorded, whatever state they reached |
| Concurrent calls for the same pipeline | Each Job is recorded exactly once, because the check and insert are one atomic store operation |
| Some Jobs already recorded (an earlier call failed part-way) | Only the missing Jobs are recorded |
| A step whose derived identity is recorded with a different repository or command | `PipelineJobConflictException`; nothing is recorded |

The last case means one pipeline identity now describes another plan, for
example after an inference rule changed for an already-processed revision.
Reusing the recorded Job would run a plan nobody decided on, and replacing it
would rewrite history, so the call is refused. Every step is checked before any
Job is recorded, so a conflicting pipeline does not leave a partial set of Jobs.

Each call writes one log line with the pipeline identity, the revision, and the
created and total counts. The repository location is not logged, because it
can embed credentials.

## Limits

- Job state, including the recorded Jobs, is held by the in-memory `JobStore`
  and lost on restart until durable persistence lands (#28). Idempotency
  therefore holds per Control Plane process.
- The link from a Job back to its step is returned to the caller and can be
  recomputed from the identities, but it is not stored. Storing it is part of
  persistence (#28).
- Nothing calls the service from the webhook path yet. Obtaining the repository
  file listing, calling inference, and handing the Jobs to selection and
  dispatch are #30.

## Out of scope

Runner selection, gRPC dispatch, Docker execution, sequencing the execution of
later steps on earlier results, and pipeline visualization.
