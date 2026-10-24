package io.zeroyaml.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.zeroyaml.controlplane.domain.job.ExecutionDefinition;
import io.zeroyaml.controlplane.domain.job.JobStatus;
import io.zeroyaml.controlplane.domain.job.RepositoryReference;
import io.zeroyaml.controlplane.domain.pipeline.Pipeline;
import io.zeroyaml.controlplane.domain.pipeline.PipelineId;
import io.zeroyaml.controlplane.domain.pipeline.PipelineJob;
import io.zeroyaml.controlplane.domain.pipeline.PipelineJobTranslator;
import io.zeroyaml.controlplane.domain.pipeline.PipelineStep;

/**
 * Covers recording the Jobs of a pipeline, including repeated input that must
 * not create duplicate Jobs.
 */
class PipelineJobCreationServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
	private static final RepositoryReference SOURCE =
			new RepositoryReference(URI.create("https://example.test/acme/service.git"), "0123456789abcdef");
	private static final PipelineId PIPELINE_ID = new PipelineId(UUID.fromString("7d3c5a1e-0000-4000-8000-000000000001"));

	private final JobStore jobs = new InMemoryJobStore();
	private final PipelineJobCreationService service =
			new PipelineJobCreationService(jobs, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void recordsOneJobPerStepOfAMultiStepPipeline() {
		var pipeline = pipeline(step("build", "./mvnw", "package"), step("test", "./mvnw", "verify"));

		var creation = service.createJobs(pipeline);

		assertEquals(PIPELINE_ID, creation.pipelineId());
		assertEquals(2, creation.createdCount());
		assertFalse(creation.alreadyRecorded());
		assertEquals(List.of("build", "test"), creation.jobs().stream().map(PipelineJob::stepName).toList());
		assertEquals(List.of(1, 2), creation.jobs().stream().map(PipelineJob::position).toList());
		for (var pipelineJob : creation.jobs()) {
			var recorded = jobs.find(pipelineJob.job().id()).orElseThrow();
			assertSame(pipelineJob.job(), recorded);
			assertEquals(SOURCE, recorded.repository());
			assertEquals(JobStatus.CREATED, recorded.status());
			assertEquals(NOW, recorded.createdAt());
		}
	}

	@Test
	void repeatedDeliveryReturnsRecordedJobsWithoutDuplicates() {
		var pipeline = pipeline(step("build", "make"), step("test", "make", "test"));
		var first = service.createJobs(pipeline);
		first.jobs().get(0).job().queue(NOW.plusSeconds(1));

		var repeated = service.createJobs(pipeline);

		assertTrue(repeated.alreadyRecorded());
		assertEquals(0, repeated.createdCount());
		for (var i = 0; i < first.jobs().size(); i++) {
			assertSame(first.jobs().get(i).job(), repeated.jobs().get(i).job());
		}
		// The repeat neither resets progress nor replaces the recorded Job.
		assertEquals(JobStatus.QUEUED, repeated.jobs().get(0).job().status());
	}

	@Test
	void equalPipelineRebuiltForTheSameRevisionMapsToTheSameJobs() {
		var first = service.createJobs(pipeline(step("build", "make")));

		var retried = service.createJobs(pipeline(step("build", "make")));

		assertTrue(retried.alreadyRecorded());
		assertSame(first.jobs().get(0).job(), retried.jobs().get(0).job());
	}

	@Test
	void recordsOnlyTheMissingJobsAfterAPartialEarlierAttempt() {
		var pipeline = pipeline(step("build", "make"), step("test", "make", "test"));
		var buildOnly = service.createJobs(pipeline(step("build", "make")));

		var creation = service.createJobs(pipeline);

		assertEquals(1, creation.createdCount());
		assertSame(buildOnly.jobs().get(0).job(), creation.jobs().get(0).job());
		assertTrue(jobs.find(creation.jobs().get(1).job().id()).isPresent());
	}

	@Test
	void refusesAStepWhoseJobIdentityIsRecordedWithADifferentCommand() {
		service.createJobs(pipeline(step("build", "make")));
		var changed = pipeline(step("lint", "make", "lint"), step("build", "make", "all"));

		assertThrows(PipelineJobConflictException.class, () -> service.createJobs(changed));

		// The check runs before recording, so the earlier non-conflicting step was not recorded either.
		var lintJobId = new PipelineJobTranslator()
				.translate(changed, NOW).get(0).job().id();
		assertTrue(jobs.find(lintJobId).isEmpty());
	}

	@Test
	void concurrentDeliveriesRecordEachJobExactlyOnce() throws Exception {
		var pipeline = pipeline(step("build", "make"), step("test", "make", "test"));
		var callers = 8;
		var start = new CountDownLatch(1);
		Callable<PipelineJobCreation> deliver = () -> {
			start.await();
			return service.createJobs(pipeline);
		};

		var executor = Executors.newFixedThreadPool(callers);
		try {
			var futures = IntStream.range(0, callers).mapToObj(i -> executor.submit(deliver)).toList();
			start.countDown();

			var totalCreated = 0;
			for (var future : futures) {
				totalCreated += future.get(10, TimeUnit.SECONDS).createdCount();
			}
			assertEquals(2, totalCreated);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void rejectsMissingPipeline() {
		assertThrows(NullPointerException.class, () -> service.createJobs(null));
	}

	private static Pipeline pipeline(PipelineStep... steps) {
		return new Pipeline(PIPELINE_ID, SOURCE, List.of(steps));
	}

	private static PipelineStep step(String name, String... command) {
		return new PipelineStep(name, new ExecutionDefinition(List.of(command), "."));
	}
}
