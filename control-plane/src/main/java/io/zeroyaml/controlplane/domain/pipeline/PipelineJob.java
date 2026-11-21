package io.zeroyaml.controlplane.domain.pipeline;

import java.util.List;
import java.util.Objects;

import io.zeroyaml.controlplane.domain.job.Job;

/**
 * One executable Job created from one {@link PipelineStep}, together with the
 * link back to the step it came from.
 *
 * <p>The Job model stays unaware of pipelines, so this value is what makes a Job
 * traceable: it names the pipeline, the step, and the step's position in the
 * pipeline order. It also carries the step's dependencies, so the scheduling
 * decision for the Job can be made again from what was recorded, for example
 * after a Control Plane restart.</p>
 *
 * @param pipelineId pipeline the step belongs to
 * @param position 1-based position of the step in {@link Pipeline#steps()}
 * @param stepName name of the step the Job executes
 * @param job Job executing the step
 * @param dependsOn names of the steps whose Jobs must succeed before this Job may start, as
 *        {@link PipelineStep#dependsOn()}; copied defensively and possibly empty
 */
public record PipelineJob(PipelineId pipelineId, int position, String stepName, Job job, List<String> dependsOn) {

	public PipelineJob {
		Objects.requireNonNull(pipelineId, "pipelineId must not be null");
		if (position < 1) {
			throw new IllegalArgumentException("position must be at least 1");
		}
		Objects.requireNonNull(stepName, "stepName must not be null");
		Objects.requireNonNull(job, "job must not be null");
		Objects.requireNonNull(dependsOn, "dependsOn must not be null");
		// copyOf also rejects a null entry.
		dependsOn = List.copyOf(dependsOn);
	}

	/**
	 * Creates a pipeline Job whose step depends on no other step.
	 *
	 * @param pipelineId pipeline the step belongs to
	 * @param position 1-based position of the step in {@link Pipeline#steps()}
	 * @param stepName name of the step the Job executes
	 * @param job Job executing the step
	 */
	public PipelineJob(PipelineId pipelineId, int position, String stepName, Job job) {
		this(pipelineId, position, stepName, job, List.of());
	}
}
