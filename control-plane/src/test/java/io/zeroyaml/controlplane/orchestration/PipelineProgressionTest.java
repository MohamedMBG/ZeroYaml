package io.zeroyaml.controlplane.orchestration;

import static io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.RUNNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import io.zeroyaml.controlplane.domain.job.ExecutionDefinition;
import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.job.JobId;
import io.zeroyaml.controlplane.domain.job.JobStatus;
import io.zeroyaml.controlplane.domain.job.RepositoryReference;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;
import io.zeroyaml.controlplane.domain.pipeline.Pipeline;
import io.zeroyaml.controlplane.domain.pipeline.PipelineId;
import io.zeroyaml.controlplane.domain.pipeline.PipelineStep;
import io.zeroyaml.controlplane.execution.ExecutionTestSupport;
import io.zeroyaml.controlplane.execution.JobStore;
import io.zeroyaml.controlplane.execution.PipelineJobReference;
import io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.FixedRunnerSelector;
import io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.RecordingRunnerClient;
import io.zeroyaml.controlplane.runner.JobDispatchResult;
import io.zeroyaml.controlplane.runner.JobRejectionReason;
import io.zeroyaml.controlplane.runner.RunnerClientException;

/** Covers how each dispatch outcome moves, keeps, or stops a pipeline. */
class PipelineProgressionTest {

	private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
	private static final RepositoryReference SOURCE = new RepositoryReference(
			URI.create("https://github.com/acme/app.git"), "0123456789abcdef0123456789abcdef01234567");
	private static final PipelineId PIPELINE = new PipelineId(UUID.fromString(
			"7d1f0e7c-3f65-4c69-9d33-4b8e5d2b7a10"));

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final JobStore jobs = ExecutionTestSupport.inMemoryJobStore();
	private final FixedRunnerSelector selector = new FixedRunnerSelector();
	private final RecordingRunnerClient runner = new RecordingRunnerClient();
	private final PipelineProgression progression = new PipelineProgression(jobs, selector, runner, clock);

	@Test
	void recordsTheAssignmentBeforeTheRunnerIsCalled() {
		var steps = createPipeline();
		runner.answer = job -> {
			// A Runner may report RUNNING before it acknowledges; the assignment must already be there.
			var recorded = jobs.find(job.id()).orElseThrow();
			assertEquals(JobStatus.QUEUED, recorded.status());
			assertEquals(RUNNER, recorded.runnerAssignment().orElseThrow());
			return JobDispatchResult.accepted(job.id(), RUNNER, "accepted");
		};

		progression.advance(PIPELINE, SOURCE);

		assertEquals(List.of(steps.get(0)), dispatchedIds());
		assertEquals(JobStatus.CREATED, status(steps.get(1)));
	}

	@Test
	void keepsAJobQueuedWithoutAssignmentUntilARunnerIsAvailable() {
		var steps = createPipeline();
		selector.available = false;

		progression.advance(PIPELINE, SOURCE);

		assertEquals(JobStatus.QUEUED, status(steps.get(0)));
		assertTrue(job(steps.get(0)).runnerAssignment().isEmpty());
		assertTrue(runner.dispatched.isEmpty());

		selector.available = true;
		progression.advance(PIPELINE, SOURCE);

		assertEquals(List.of(steps.get(0)), dispatchedIds());
		assertEquals(RUNNER, job(steps.get(0)).runnerAssignment().orElseThrow());
	}

	@Test
	void retriesAQueuedJobAfterAControlPlaneRestart() {
		var steps = createPipeline();
		selector.available = false;
		progression.advance(PIPELINE, SOURCE);
		selector.available = true;

		new PipelineProgression(jobs, selector, runner, clock).retryQueued();

		assertEquals(List.of(steps.getFirst()), dispatchedIds());
	}

	@Test
	void aFailedRetryDoesNotBlockLaterJobs() {
		var store = mock(JobStore.class);
		var first = JobId.newId();
		var second = JobId.newId();
		when(store.findQueuedUnassignedAfter(null)).thenReturn(List.of(first, second));
		when(store.find(first)).thenThrow(new IllegalStateException("test failure"));
		when(store.find(second)).thenReturn(Optional.empty());

		new PipelineProgression(store, selector, runner, clock).retryQueued();

		verify(store).find(second);
	}

	@Test
	void keepsAJobQueuedWhenTheRunnerIsBusyAndAllowsRedelivery() {
		var steps = createPipeline();
		runner.answer = job -> JobDispatchResult.rejected(
				job.id(), JobRejectionReason.RUNNER_UNAVAILABLE, RUNNER, "runner is busy");

		progression.advance(PIPELINE, SOURCE);

		assertEquals(JobStatus.QUEUED, status(steps.get(0)));
		assertTrue(job(steps.get(0)).runnerAssignment().isEmpty());
		assertEquals(JobStatus.CREATED, status(steps.get(1)));
		runner.answer = job -> JobDispatchResult.accepted(job.id(), RUNNER, "accepted");
		progression.retryQueued();
		assertEquals(RUNNER, job(steps.get(0)).runnerAssignment().orElseThrow());
		assertEquals(2, runner.dispatched.size());
	}

	@Test
	void cancelsTheJobWhenAnotherRunnerProcessAcceptsIt() {
		var steps = createPipeline();
		runner.answer = job -> JobDispatchResult.accepted(
				job.id(), new RunnerAssignment("runner-1", "instance-2"), "accepted");

		progression.advance(PIPELINE, SOURCE);

		assertEquals(JobStatus.CANCELLED, status(steps.get(0)));
		assertEquals(JobStatus.CANCELLED, status(steps.get(1)));
	}

	@Test
	void cancelsTheJobWhenTheRunnerCannotEverRunIt() {
		var steps = createPipeline();
		runner.answer = job -> {
			throw new RunnerClientException(Status.Code.FAILED_PRECONDITION, "unsupported repository scheme", null);
		};

		progression.advance(PIPELINE, SOURCE);

		assertEquals(JobStatus.CANCELLED, status(steps.get(0)));
		assertEquals(JobStatus.CANCELLED, status(steps.get(1)));
	}

	@Test
	void keepsTheJobAssignedAndDoesNotResendItWhenTheDispatchOutcomeIsUnknown() {
		var steps = createPipeline();
		runner.answer = job -> {
			throw new RunnerClientException(Status.Code.DEADLINE_EXCEEDED, "deadline exceeded", null);
		};

		progression.advance(PIPELINE, SOURCE);
		progression.advance(PIPELINE, SOURCE);

		assertEquals(JobStatus.QUEUED, status(steps.get(0)));
		assertEquals(RUNNER, job(steps.get(0)).runnerAssignment().orElseThrow());
		assertEquals(JobStatus.CREATED, status(steps.get(1)));
		assertEquals(1, runner.dispatched.size());
	}

	@Test
	void dispatchesNothingWhileAStepRunsAndTheNextStepOnceItSucceeded() {
		var steps = createPipeline();
		progression.advance(PIPELINE, SOURCE);
		jobs.update(steps.get(0), job -> { job.start(RUNNER, NOW.plusSeconds(1)); return job.status(); });

		progression.advance(PIPELINE, SOURCE);
		assertEquals(1, runner.dispatched.size());

		jobs.update(steps.get(0), job -> { job.succeed(NOW.plusSeconds(2), 0); return job.status(); });
		progression.advanceAfter(steps.get(0));

		assertEquals(steps, dispatchedIds());
	}

	@Test
	void ignoresACompletedJobThatBelongsToNoPipeline() {
		var job = Job.create(SOURCE, new ExecutionDefinition(List.of("true"), "."), NOW);
		jobs.add(job);

		progression.advanceAfter(job.id());
		progression.advanceAfter(JobId.newId());

		assertTrue(runner.dispatched.isEmpty());
	}

	private List<JobId> createPipeline() {
		var pipeline = new Pipeline(PIPELINE, SOURCE, List.of(
				new PipelineStep("build", new ExecutionDefinition(List.of("mvn", "package"), ".")),
				new PipelineStep("test", new ExecutionDefinition(List.of("mvn", "test"), "."))));
		return ExecutionTestSupport.jobCreation(jobs, clock).createJobs(pipeline).jobs().stream()
				.map(PipelineJobReference::jobId).toList();
	}

	private List<JobId> dispatchedIds() {
		return runner.dispatched.stream().map(Job::id).toList();
	}

	private Job job(JobId id) {
		return jobs.find(id).orElseThrow();
	}

	private JobStatus status(JobId id) {
		return job(id).status();
	}
}
