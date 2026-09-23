package io.zeroyaml.controlplane.domain.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobTest {

	private static final Instant CREATED_AT = Instant.parse("2026-09-18T10:00:00Z");
	private static final Instant QUEUED_AT = Instant.parse("2026-09-18T10:01:00Z");
	private static final Instant STARTED_AT = Instant.parse("2026-09-18T10:02:00Z");
	private static final Instant COMPLETED_AT = Instant.parse("2026-09-18T10:03:00Z");

	@Test
	void createsJobWithRequiredContextAndCreatedState() {
		var repository = repository();
		var execution = execution();

		var job = Job.create(new JobId(UUID.randomUUID()), repository, execution, CREATED_AT);

		assertEquals(JobStatus.CREATED, job.status());
		assertEquals(repository, job.repository());
		assertEquals(execution, job.execution());
		assertEquals(CREATED_AT, job.createdAt());
		assertTrue(job.startedAt().isEmpty());
		assertTrue(job.completedAt().isEmpty());
		assertTrue(job.runnerAssignment().isEmpty());
		assertTrue(job.failure().isEmpty());
	}

	@Test
	void followsSuccessfulLifecycleAndLinksRunnerAtStart() {
		var job = newJob();
		var assignment = new RunnerAssignment("runner-dev-01", "instance-1");

		job.queue(QUEUED_AT);
		job.start(assignment, STARTED_AT);
		job.succeed(COMPLETED_AT);

		assertEquals(JobStatus.SUCCEEDED, job.status());
		assertEquals(STARTED_AT, job.startedAt().orElseThrow());
		assertEquals(COMPLETED_AT, job.completedAt().orElseThrow());
		assertEquals(assignment, job.runnerAssignment().orElseThrow());
		assertTrue(job.failure().isEmpty());
		assertTrue(job.status().isTerminal());
	}

	@Test
	void followsFailureLifecycleAndRetainsFailureInformation() {
		var job = newJob();
		var failure = new JobFailure("EXECUTION_FAILED", "The command exited with status 1");

		job.queue(QUEUED_AT);
		job.start(new RunnerAssignment("runner-dev-01", "instance-1"), STARTED_AT);
		job.fail(failure, COMPLETED_AT);

		assertEquals(JobStatus.FAILED, job.status());
		assertEquals(failure, job.failure().orElseThrow());
		assertEquals(COMPLETED_AT, job.completedAt().orElseThrow());
	}

	@Test
	void retainsTheExitCodeOfAFailedCommandAndKeepsAnAbsentCodeAbsent() {
		var withExitCode = new JobFailure("NON_ZERO_EXIT", "The command exited with code 2", 2);
		var withoutExitCode = new JobFailure("TIMEOUT", "The job exceeded its time budget");

		assertEquals(OptionalInt.of(2), withExitCode.exit());
		// A stopped command produces no code, and an absent code must never be
		// read as a successful 0.
		assertEquals(OptionalInt.empty(), withoutExitCode.exit());
	}

	@Test
	void rejectsIllegalTransitionsPredictably() {
		var job = newJob();

		var startException = assertThrows(
				InvalidJobTransitionException.class,
				() -> job.start(new RunnerAssignment("runner-dev-01", "instance-1"), STARTED_AT)
		);
		assertEquals(JobStatus.CREATED, startException.getCurrentStatus());
		assertEquals(JobStatus.RUNNING, startException.getRequestedStatus());

		job.queue(QUEUED_AT);
		assertThrows(InvalidJobTransitionException.class, () -> job.queue(STARTED_AT));
		assertEquals(JobStatus.QUEUED, job.status());

		job.start(new RunnerAssignment("runner-dev-01", "instance-1"), STARTED_AT);
		job.succeed(COMPLETED_AT);
		assertThrows(InvalidJobTransitionException.class, () -> job.cancel(COMPLETED_AT));
		assertEquals(JobStatus.SUCCEEDED, job.status());
}

	@Test
	void rejectsOutOfOrderTimestampsWithoutChangingState() {
		var job = newJob();

		assertThrows(IllegalArgumentException.class, () -> job.queue(CREATED_AT.minusSeconds(1)));
		assertEquals(JobStatus.CREATED, job.status());

		job.queue(QUEUED_AT);
		job.start(new RunnerAssignment("runner-dev-01", "instance-1"), STARTED_AT);
		assertThrows(IllegalArgumentException.class, () -> job.succeed(STARTED_AT.minusSeconds(1)));
		assertEquals(JobStatus.RUNNING, job.status());
		assertFalse(job.completedAt().isPresent());
}

	@Test
	void validatesRequiredValueObjectFields() {
		assertThrows(IllegalArgumentException.class,
				() -> new RepositoryReference(URI.create("relative/repository"), "main"));
		assertThrows(IllegalArgumentException.class,
				() -> new RepositoryReference(URI.create("https://git.example/repository"), " "));
		assertThrows(IllegalArgumentException.class,
				() -> new ExecutionDefinition(List.of(), "/workspace"));
		assertThrows(IllegalArgumentException.class,
				() -> new ExecutionDefinition(List.of("go", " "), "/workspace"));
		assertThrows(IllegalArgumentException.class,
				() -> new ExecutionDefinition(List.of("go", "test"), " "));
		assertThrows(IllegalArgumentException.class,
				() -> new RunnerAssignment("runner-dev-01", " "));
		assertThrows(IllegalArgumentException.class,
				() -> new JobFailure(" ", "failed"));
	}

	private static Job newJob() {
		return Job.create(repository(), execution(), CREATED_AT);
	}

	private static RepositoryReference repository() {
		return new RepositoryReference(URI.create("https://git.example/acme/service"), "abc123");
	}

	private static ExecutionDefinition execution() {
		return new ExecutionDefinition(List.of("go", "test", "./..."), "/workspace/service");
	}
}
