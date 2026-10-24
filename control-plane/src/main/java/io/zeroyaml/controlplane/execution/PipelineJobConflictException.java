package io.zeroyaml.controlplane.execution;

import io.zeroyaml.controlplane.domain.job.JobId;

/**
 * Raised when a pipeline step maps to a Job identity that is already recorded
 * with a different repository or execution definition.
 *
 * <p>Job identities are derived from the pipeline identity and the step name,
 * so this means the same pipeline identity now describes a different plan, for
 * example after an inference rule changed for an already-processed revision.
 * Reusing the recorded Job would execute a plan nobody decided on, and
 * replacing it would rewrite history, so creation is refused instead.</p>
 */
public class PipelineJobConflictException extends RuntimeException {

	PipelineJobConflictException(JobId jobId, String stepName) {
		super("Job " + jobId.value() + " for step '" + stepName
				+ "' is already recorded with a different repository or execution definition");
	}
}
