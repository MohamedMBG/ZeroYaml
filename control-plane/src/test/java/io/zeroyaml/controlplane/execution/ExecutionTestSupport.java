package io.zeroyaml.controlplane.execution;

import java.time.Clock;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

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

	/** Builds the PostgreSQL store, for tests that already run against the real migration. */
	public static JobStore jdbcJobStore(JdbcClient jdbc, TransactionTemplate transactions, JsonMapper json) {
		return new JdbcJobStore(jdbc, transactions, json);
	}

	public static PipelineJobCreationService jobCreation(JobStore jobs, Clock clock) {
		return new PipelineJobCreationService(jobs, event -> { }, clock);
	}

	public static JobExecutionStatusRecorder statusRecorder(JobStore jobs, JobCompletionListener completions) {
		return new JobExecutionStatusRecorder(jobs, event -> { }, completions);
	}
}
