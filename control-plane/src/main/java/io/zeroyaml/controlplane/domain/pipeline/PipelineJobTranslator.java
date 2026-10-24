package io.zeroyaml.controlplane.domain.pipeline;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.job.JobId;

/**
 * Turns a {@link Pipeline} into the executable Jobs that carry it out: one Job
 * per step, in step order.
 *
 * <p>Each Job runs the step's {@link PipelineStep#execution()} against the
 * pipeline's {@link Pipeline#source()}, so the repository location and revision
 * are carried unchanged. Jobs start in
 * {@link io.zeroyaml.controlplane.domain.job.JobStatus#CREATED}; queuing them,
 * selecting a Runner, and dispatching belong to the application boundary.</p>
 *
 * <p>Identity rule: a Job identity is a name-based UUID over the pipeline
 * identity and the step name. Pipeline identities are themselves derived from
 * the source (see the inference strategy), so a repeated webhook delivery or an
 * inference retry for the same revision yields the same Job identities, which
 * is what lets the application layer recognize Jobs it already recorded. Step
 * names are unique within a pipeline, so the identities within one pipeline are
 * distinct. The identity does not cover the plan itself: two different plans
 * with the same pipeline identity share Job identities for equally named steps,
 * so the application layer must bind a pipeline identity to one plan before
 * recording its Jobs.</p>
 *
 * <p>The translation is a pure function of its arguments: it records nothing,
 * selects no Runner, and performs no I/O.</p>
 */
public final class PipelineJobTranslator {

	/**
	 * Creates the Jobs for every step of {@code pipeline}.
	 *
	 * @param pipeline validated pipeline to execute
	 * @param createdAt creation timestamp shared by every Job
	 * @return one Job per step, ordered as {@link Pipeline#steps()}; unmodifiable
	 */
	public List<PipelineJob> translate(Pipeline pipeline, Instant createdAt) {
		Objects.requireNonNull(pipeline, "pipeline must not be null");
		Objects.requireNonNull(createdAt, "createdAt must not be null");

		var jobs = new ArrayList<PipelineJob>(pipeline.steps().size());
		var position = 1;
		for (var step : pipeline.steps()) {
			var job = Job.create(jobIdFor(pipeline.id(), step), pipeline.source(), step.execution(), createdAt);
			jobs.add(new PipelineJob(pipeline.id(), position++, step.name(), job));
		}
		return List.copyOf(jobs);
	}

	private static JobId jobIdFor(PipelineId pipelineId, PipelineStep step) {
		// The '/' separator cannot appear in a step name, so distinct pairs never share a seed.
		var seed = "pipeline-job:" + pipelineId.value() + "/" + step.name();
		return new JobId(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
	}
}
