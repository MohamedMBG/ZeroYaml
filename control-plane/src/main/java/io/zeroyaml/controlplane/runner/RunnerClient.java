package io.zeroyaml.controlplane.runner;

import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;

/**
 * Control Plane boundary for the small set of Runner calls needed by this service.
 * Keeping callers behind this interface prevents application code from depending on
 * generated gRPC classes directly.
 */
public interface RunnerClient {

	RunnerPingResult ping(String message);

	RunnerInfo getInfo();

	/**
	 * Dispatches one executable Job to the Runner and returns its acknowledgment.
	 *
	 * <p>The call completes as soon as the Runner answers; it does not wait for
	 * execution. A Runner that declines a well-formed dispatch returns a rejected
	 * {@link JobDispatchResult}, while a transport failure or a request the
	 * Runner rejects as invalid raises {@link RunnerClientException}.</p>
	 *
	 * <p>The dispatch names {@code target}, the Runner process the Control Plane
	 * selected. A different process that receives it declines with
	 * {@link JobRejectionReason#NOT_TARGET_RUNNER}, so the selection holds even
	 * when the connection leads elsewhere. An acceptance from any process other
	 * than {@code target} is a protocol violation and raises
	 * {@link IllegalStateException} instead of being recorded.</p>
	 *
	 * @param job Job to execute
	 * @param target Runner process selected to execute the Job
	 * @return the Runner acknowledgment for that Job
	 */
	JobDispatchResult dispatchJob(Job job, RunnerAssignment target);
}
