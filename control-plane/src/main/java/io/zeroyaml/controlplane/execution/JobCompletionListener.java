package io.zeroyaml.controlplane.execution;

import io.zeroyaml.controlplane.domain.job.JobId;

/**
 * Receives the identity of a Job whose terminal status was just committed.
 *
 * <p>It is called on the thread that applied the Runner's report, while that
 * Runner still waits for the report's answer. An implementation must therefore
 * return quickly and must not call the Runner itself; follow-up work, such as
 * dispatching the next pipeline step, belongs on another thread.</p>
 */
@FunctionalInterface
public interface JobCompletionListener {

	void jobCompleted(JobId jobId);
}
