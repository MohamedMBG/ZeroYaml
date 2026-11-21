package io.zeroyaml.controlplane.orchestration;

import static io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.RUNNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.zeroyaml.controlplane.PostgresTestContainer;
import io.zeroyaml.controlplane.domain.job.ExecutionDefinition;
import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.job.JobId;
import io.zeroyaml.controlplane.domain.job.JobStatus;
import io.zeroyaml.controlplane.domain.job.RepositoryReference;
import io.zeroyaml.controlplane.domain.pipeline.Pipeline;
import io.zeroyaml.controlplane.domain.pipeline.PipelineId;
import io.zeroyaml.controlplane.domain.pipeline.PipelineStep;
import io.zeroyaml.controlplane.execution.ExecutionTestSupport;
import io.zeroyaml.controlplane.execution.JobExecutionFailureReason;
import io.zeroyaml.controlplane.execution.JobExecutionStatusRecorder;
import io.zeroyaml.controlplane.execution.JobStatusReport;
import io.zeroyaml.controlplane.execution.JobStatusReportDecision;
import io.zeroyaml.controlplane.execution.JobStore;
import io.zeroyaml.controlplane.execution.PipelineJobReference;
import io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.FixedRunnerSelector;
import io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.RecordingRunnerClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs dependency-aware scheduling against the PostgreSQL Job store and the
 * real migrations: dependencies are read back from the database, Runner reports
 * drive the next scheduling decision, and skipped Jobs are committed. Only Runner
 * selection and the Runner transport are replaced.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportTestcontainers(PostgresTestContainer.class)
@Import(DependencySchedulingIntegrationTest.JsonConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DependencySchedulingIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-21T10:00:00.123456Z");
	private static final RepositoryReference SOURCE = new RepositoryReference(
			URI.create("https://example.test/acme/graph.git"), "fedcba9876543210fedcba9876543210fedcba98");
	private static final PipelineId PIPELINE = new PipelineId(UUID.fromString("3b0f6a52-8e1c-4e57-9a1d-0c2f4e6b8d71"));

	@Autowired private JdbcClient jdbc;
	@Autowired private TransactionTemplate transactions;
	@Autowired private JsonMapper json;

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final FixedRunnerSelector selector = new FixedRunnerSelector();
	private final RecordingRunnerClient runner = new RecordingRunnerClient();
	private JobStore jobs;
	private PipelineProgression progression;
	private JobExecutionStatusRecorder recorder;

	@BeforeEach
	void startWithEmptyStore() {
		jdbc.sql("DELETE FROM job").update();
		jdbc.sql("DELETE FROM pipeline_run").update();
		restartControlPlane();
	}

	@Test
	void runsADiamondInDependencyOrderAcrossAControlPlaneRestart() {
		var steps = createPipeline(
				step("build"),
				step("unit", "build"),
				step("integration", "build"),
				step("package", "unit", "integration"));

		progression.advance(PIPELINE, SOURCE);
		assertEquals(List.of(steps.get(0)), dispatchedIds());

		succeed(steps.get(0));
		assertEquals(List.of(steps.get(0), steps.get(1), steps.get(2)), dispatchedIds());

		succeed(steps.get(1));
		assertEquals(JobStatus.CREATED, status(steps.get(3)));

		// Nothing about the graph is kept in memory: a new process schedules from the database alone.
		restartControlPlane();
		succeed(steps.get(2));
		assertEquals(List.of(steps.get(3)), dispatchedIds());

		succeed(steps.get(3));
		assertEquals(List.of(JobStatus.SUCCEEDED, JobStatus.SUCCEEDED, JobStatus.SUCCEEDED, JobStatus.SUCCEEDED),
				steps.stream().map(this::status).toList());
	}

	@Test
	void aFailedPrerequisiteCommitsSkippedDependentsWhileIndependentStepsComplete() {
		var steps = createPipeline(
				step("build"),
				step("lint"),
				step("unit", "build"),
				step("integration", "build"),
				step("package", "unit", "integration"),
				step("report", "lint"));
		progression.advance(PIPELINE, SOURCE);
		assertEquals(List.of(steps.get(0), steps.get(1)), dispatchedIds());

		succeed(steps.get(0));
		fail(steps.get(2));

		assertEquals(JobStatus.SKIPPED, status(steps.get(4)));
		assertEquals(JobStatus.QUEUED, status(steps.get(3)), "integration does not depend on unit");

		succeed(steps.get(3));
		succeed(steps.get(1));
		succeed(steps.get(5));

		assertEquals(List.of("SUCCEEDED", "SUCCEEDED", "FAILED", "SUCCEEDED", "SKIPPED", "SUCCEEDED"),
				jdbc.sql("SELECT status FROM job ORDER BY step_position").query(String.class).list());
		assertTrue(jdbc.sql("""
				SELECT queued_at IS NULL AND started_at IS NULL AND runner_id IS NULL AND completed_at IS NOT NULL
				FROM job WHERE step_name = 'package'
				""").query(Boolean.class).single());
		assertEquals(5, runner.dispatched.size(), "package must never be dispatched");
	}

	private void restartControlPlane() {
		jobs = ExecutionTestSupport.jdbcJobStore(jdbc, transactions, json);
		progression = new PipelineProgression(jobs, selector, runner, clock);
		recorder = ExecutionTestSupport.statusRecorder(jobs, progression::advanceAfter);
		runner.dispatched.clear();
	}

	private List<JobId> createPipeline(PipelineStep... steps) {
		return ExecutionTestSupport.jobCreation(jobs, clock)
				.createJobs(new Pipeline(PIPELINE, SOURCE, List.of(steps))).jobs().stream()
				.map(PipelineJobReference::jobId).toList();
	}

	private void succeed(JobId id) {
		start(id);
		assertEquals(JobStatusReportDecision.APPLIED, recorder.record(
				JobStatusReport.succeeded(id, RUNNER, NOW.plusSeconds(1), NOW.plusSeconds(2), 0)).decision());
	}

	private void fail(JobId id) {
		start(id);
		assertEquals(JobStatusReportDecision.APPLIED, recorder.record(JobStatusReport.failed(id, RUNNER,
				NOW.plusSeconds(1), NOW.plusSeconds(2), 1, JobExecutionFailureReason.NON_ZERO_EXIT,
				"job command exited with status 1")).decision());
	}

	private void start(JobId id) {
		assertEquals(JobStatusReportDecision.APPLIED,
				recorder.record(JobStatusReport.running(id, RUNNER, NOW.plusSeconds(1))).decision());
	}

	private List<JobId> dispatchedIds() {
		return runner.dispatched.stream().map(Job::id).toList();
	}

	private JobStatus status(JobId id) {
		return jobs.find(id).orElseThrow().status();
	}

	private static PipelineStep step(String name, String... dependsOn) {
		return new PipelineStep(name, new ExecutionDefinition(List.of("make", name), "."), List.of(dependsOn));
	}

	@TestConfiguration
	static class JsonConfiguration {
		@Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
	}
}
