package io.zeroyaml.controlplane.scheduling;

import java.util.Objects;

import io.zeroyaml.controlplane.domain.job.JobId;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;

/**
 * The Control Plane's decision of where one Job runs: the Job paired with the
 * Runner process chosen to execute it. Dispatch code reads the target from here
 * instead of choosing a Runner itself, so the Runner never decides what it runs.
 *
 * <p>The decision is enforced on the wire: {@code runner} is passed to
 * {@link io.zeroyaml.controlplane.runner.RunnerClient#dispatchJob}, which names
 * it in the dispatch, and any other Runner process that receives that dispatch
 * declines it.</p>
 *
 * <p>The context records a decision only. The Job stays queued until the
 * selected Runner reports that it started, which is when the Job aggregate
 * records the assignment.</p>
 *
 * @param jobId Job the decision applies to
 * @param runner Runner process selected to execute the Job
 */
public record JobDispatchContext(JobId jobId, RunnerAssignment runner) {

	public JobDispatchContext {
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(runner, "runner must not be null");
	}
}
