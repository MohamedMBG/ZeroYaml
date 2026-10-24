package io.zeroyaml.controlplane.execution;

import io.zeroyaml.controlplane.domain.pipeline.PipelineId;

/**
 * Raised when Jobs are requested for a pipeline whose identity is already
 * recorded with a different plan: another repository, other steps, or other
 * commands.
 *
 * <p>Pipeline identities are derived from the repository revision, so this
 * means the same revision now yields another plan, for example after an
 * inference rule changed. Reusing the recorded Jobs would run a plan nobody
 * decided on, mixing both plans would leave Jobs that belong to neither, and
 * replacing them would rewrite history, so creation is refused and nothing is
 * recorded.</p>
 */
public class PipelineJobConflictException extends RuntimeException {

	PipelineJobConflictException(PipelineId pipelineId) {
		super("Pipeline " + pipelineId.value() + " is already recorded with a different plan");
	}
}
