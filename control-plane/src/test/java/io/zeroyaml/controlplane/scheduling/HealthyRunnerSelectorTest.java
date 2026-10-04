package io.zeroyaml.controlplane.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.zeroyaml.controlplane.domain.job.ExecutionDefinition;
import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.job.JobStatus;
import io.zeroyaml.controlplane.domain.job.RepositoryReference;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;
import io.zeroyaml.controlplane.registration.HeartbeatOutcome;
import io.zeroyaml.controlplane.registration.RegisteredRunner;
import io.zeroyaml.controlplane.registration.RegistrationOutcome;
import io.zeroyaml.controlplane.registration.RunnerLiveness;
import io.zeroyaml.controlplane.registration.RunnerRegistry;
import io.zeroyaml.controlplane.runner.RunnerCapabilities;
import io.zeroyaml.controlplane.runner.RunnerInfo;
import io.zeroyaml.controlplane.runner.RunnerState;

class HealthyRunnerSelectorTest {

	private static final Instant CREATED_AT = Instant.parse("2026-10-04T10:00:00Z");
	private static final Instant QUEUED_AT = Instant.parse("2026-10-04T10:01:00Z");

	@Test
	void selectsTheOnlyHealthyRunnerAndAttachesItToTheDispatchContext() {
		var job = queuedJob();
		var selector = new HealthyRunnerSelector(registryOf(registered("runner-1", "instance-1")));

		var outcome = selector.select(job);

		assertEquals(RunnerSelectionDecision.SELECTED, outcome.decision());
		assertTrue(outcome.isSelected());
		assertEquals(job.id(), outcome.jobId());
		var context = outcome.selectedDispatch().orElseThrow();
		assertEquals(job.id(), context.jobId());
		assertEquals(new RunnerAssignment("runner-1", "instance-1"), context.runner());
	}

	@Test
	void selectsTheSmallestRunnerIdAmongSeveralHealthyRunners() {
		var selector = new HealthyRunnerSelector(registryOf(
				registered("runner-c", "instance-c"),
				registered("runner-a", "instance-a"),
				registered("runner-b", "instance-b")));

		var outcome = selector.select(queuedJob());

		assertEquals(new RunnerAssignment("runner-a", "instance-a"), outcome.selectedDispatch().orElseThrow().runner());
	}

	@Test
	void selectsTheSameRunnerRegardlessOfTheOrderTheRegistryListsThem() {
		var runners = new ArrayList<>(List.of(
				registered("runner-a", "instance-a"),
				registered("runner-b", "instance-b"),
				registered("runner-c", "instance-c")));
		var job = queuedJob();

		var expected = new RunnerAssignment("runner-a", "instance-a");
		for (var attempt = 0; attempt < runners.size(); attempt++) {
			Collections.rotate(runners, 1);
			var selector = new HealthyRunnerSelector(registryOf(runners.toArray(RegisteredRunner[]::new)));

			assertEquals(expected, selector.select(job).selectedDispatch().orElseThrow().runner());
		}
		Collections.reverse(runners);
		var reversed = new HealthyRunnerSelector(registryOf(runners.toArray(RegisteredRunner[]::new)));
		assertEquals(expected, reversed.select(job).selectedDispatch().orElseThrow().runner());
	}

	@Test
	void selectsTheSameRunnerForRepeatedCallsAgainstUnchangedRegistryState() {
		var selector = new HealthyRunnerSelector(registryOf(
				registered("runner-b", "instance-b"),
				registered("runner-a", "instance-a")));
		var job = queuedJob();

		var first = selector.select(job);
		var second = selector.select(job);

		assertEquals(first, second);
	}

	@Test
	void blocksTheJobWhenNoRunnerIsHealthy() {
		var job = queuedJob();
		var selector = new HealthyRunnerSelector(registryOf());

		var outcome = selector.select(job);

		assertEquals(RunnerSelectionDecision.BLOCKED_NO_HEALTHY_RUNNER, outcome.decision());
		assertFalse(outcome.isSelected());
		assertEquals(job.id(), outcome.jobId());
		assertTrue(outcome.selectedDispatch().isEmpty());
		assertTrue(outcome.message().contains(job.id().toString()));
	}

	@Test
	void leavesTheJobQueuedAndUnassignedWhetherOrNotARunnerIsSelected() {
		var withRunner = queuedJob();
		var withoutRunner = queuedJob();

		new HealthyRunnerSelector(registryOf(registered("runner-1", "instance-1"))).select(withRunner);
		new HealthyRunnerSelector(registryOf()).select(withoutRunner);

		assertEquals(JobStatus.QUEUED, withRunner.status());
		assertTrue(withRunner.runnerAssignment().isEmpty());
		assertEquals(JobStatus.QUEUED, withoutRunner.status());
		assertTrue(withoutRunner.runnerAssignment().isEmpty());
	}

	@Test
	void doesNotFilterOnTheRegistrationSnapshotState() {
		// The snapshot is captured at registration, before the Runner finishes its startup
		// checks, so STARTING and acceptingWork=false must not make a healthy Runner ineligible.
		var starting = registered("runner-1", "instance-1", RunnerState.STARTING, false);
		var selector = new HealthyRunnerSelector(registryOf(starting));

		assertTrue(selector.select(queuedJob()).isSelected());
	}

	@Test
	void rejectsAJobThatIsNotQueued() {
		var created = Job.create(repository(), execution(), CREATED_AT);
		var selector = new HealthyRunnerSelector(registryOf(registered("runner-1", "instance-1")));

		var failure = assertThrows(IllegalStateException.class, () -> selector.select(created));

		assertTrue(failure.getMessage().contains("QUEUED"));
		assertTrue(failure.getMessage().contains("CREATED"));
	}

	@Test
	void rejectsANullJob() {
		var selector = new HealthyRunnerSelector(registryOf());

		assertThrows(NullPointerException.class, () -> selector.select(null));
	}

	@Test
	void rejectsABlockedOutcomeThatCarriesADispatchContext() {
		var job = queuedJob();
		var context = new JobDispatchContext(job.id(), new RunnerAssignment("runner-1", "instance-1"));

		assertThrows(IllegalArgumentException.class, () -> new RunnerSelectionOutcome(
				job.id(), RunnerSelectionDecision.BLOCKED_NO_HEALTHY_RUNNER, context, "blocked"));
	}

	@Test
	void rejectsASelectedOutcomeForADifferentJob() {
		var context = new JobDispatchContext(queuedJob().id(), new RunnerAssignment("runner-1", "instance-1"));

		assertThrows(IllegalArgumentException.class, () -> new RunnerSelectionOutcome(
				queuedJob().id(), RunnerSelectionDecision.SELECTED, context, "selected"));
	}

	private static Job queuedJob() {
		var job = Job.create(repository(), execution(), CREATED_AT);
		job.queue(QUEUED_AT);
		return job;
	}

	private static RepositoryReference repository() {
		return new RepositoryReference(URI.create("https://git.example/acme/service"), "abc123");
	}

	private static ExecutionDefinition execution() {
		return new ExecutionDefinition(List.of("go", "test", "./..."), "/workspace/service");
	}

	private static RegisteredRunner registered(String runnerId, String instanceId) {
		return registered(runnerId, instanceId, RunnerState.READY, true);
	}

	private static RegisteredRunner registered(
			String runnerId, String instanceId, RunnerState state, boolean acceptingWork) {
		var info = new RunnerInfo(
				runnerId,
				instanceId,
				"0.1.0",
				"runner.v1",
				state,
				acceptingWork,
				new RunnerCapabilities("linux", "amd64", true, List.of(), Map.of()));
		return new RegisteredRunner("registration-" + runnerId, info, CREATED_AT, CREATED_AT);
	}

	private static RunnerRegistry registryOf(RegisteredRunner... healthy) {
		return new FixedHealthyRegistry(List.of(healthy));
	}

	/**
	 * Registry double that reports a fixed healthy set, so selection is verified against exact
	 * registry state and listing order. Liveness derivation itself is covered by the registry's own tests.
	 */
	private record FixedHealthyRegistry(List<RegisteredRunner> healthy) implements RunnerRegistry {

		@Override
		public RegistrationOutcome register(RunnerInfo runner) {
			throw new UnsupportedOperationException("selection must not register Runners");
		}

		@Override
		public HeartbeatOutcome heartbeat(String runnerId, String instanceId) {
			throw new UnsupportedOperationException("selection must not record heartbeats");
		}

		@Override
		public RunnerLiveness livenessOf(String runnerId) {
			throw new UnsupportedOperationException("selection must use healthyRunners()");
		}

		@Override
		public List<RegisteredRunner> healthyRunners() {
			return healthy;
		}
	}
}
