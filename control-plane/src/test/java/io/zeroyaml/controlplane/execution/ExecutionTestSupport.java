package io.zeroyaml.controlplane.execution;

import java.time.Clock;

/**
 * Builds execution components for tests in other packages, without widening
 * the production constructors.
 */
public final class ExecutionTestSupport {

	private ExecutionTestSupport() {
	}

	public static JobStore inMemoryJobStore() {
		return new InMemoryJobStore();
	}

	public static PipelineJobCreationService jobCreation(JobStore jobs, Clock clock) {
		return new PipelineJobCreationService(jobs, event -> { }, clock);
	}

	public static JobExecutionStatusRecorder statusRecorder(JobStore jobs, JobCompletionListener completions) {
		return new JobExecutionStatusRecorder(jobs, event -> { }, completions);
	}
}
