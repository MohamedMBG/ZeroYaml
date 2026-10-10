package io.zeroyaml.controlplane.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.zeroyaml.controlplane.domain.job.ExecutionDefinition;
import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.job.RepositoryReference;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;
import io.zeroyaml.controlplane.runner.RunnerCapabilities;
import io.zeroyaml.controlplane.runner.RunnerInfo;
import io.zeroyaml.controlplane.runner.RunnerState;
import io.zeroyaml.controlplane.scheduling.RunnerSelectionOutcome;
import io.zeroyaml.controlplane.scheduling.RunnerSelector;

/**
 * Exercises the Runner selector Spring wires from the scheduling package against
 * the real {@link InMemoryRunnerRegistry}, driven only through the calls a Runner
 * makes: registration and heartbeats. It lives in the registration package because
 * the registry and its clock double are package-private; the selector is reached
 * through its public {@link RunnerSelector} interface.
 */
class RunnerSelectionIntegrationTest {

	private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(15);
	private static final Instant STARTED_AT = Instant.parse("2026-10-04T10:00:00Z");

	private MutableClock clock;
	private RunnerRegistry registry;
	private AnnotationConfigApplicationContext context;
	private RunnerSelector selector;

	@BeforeEach
	void wireTheSelectorOntoTheRealRegistry() {
		clock = new MutableClock(STARTED_AT);
		var properties = new RunnerRegistrationServerProperties();
		properties.setHeartbeatTimeout(HEARTBEAT_TIMEOUT);
		registry = new InMemoryRunnerRegistry(clock, properties);

		context = new AnnotationConfigApplicationContext();
		context.registerBean(RunnerRegistry.class, () -> registry);
		context.scan(RunnerSelector.class.getPackageName());
		context.refresh();
		selector = context.getBean(RunnerSelector.class);
	}

	@AfterEach
	void closeTheContext() {
		context.close();
	}

	@Test
	void doesNotSelectARunnerThatRegisteredButHasNotReportedAvailabilityYet() {
		// Registration is sent before the Runner knows whether it can take work.
		registry.register(runner("runner-1", "instance-1"));

		assertBlocked(selector.select(queuedJob()));
	}

	@Test
	void selectsARunnerOnceItsHeartbeatReportsItReadyAndAcceptingWork() {
		registerAvailable("runner-1", "instance-1");

		assertEquals(new RunnerAssignment("runner-1", "instance-1"), selectedRunner(selector.select(queuedJob())));
	}

	@Test
	void skipsAHealthyRunnerThatDoesNotAcceptWorkEvenWhenItSortsFirst() {
		// A Runner without a Docker daemon keeps sending heartbeats but rejects every dispatch.
		registry.register(runner("runner-a", "instance-a"));
		registry.heartbeat("runner-a", "instance-a", RunnerState.READY, false);
		registerAvailable("runner-b", "instance-b");

		assertEquals(new RunnerAssignment("runner-b", "instance-b"), selectedRunner(selector.select(queuedJob())));
	}

	@Test
	void stopsSelectingARunnerThatReportsItIsDraining() {
		registerAvailable("runner-1", "instance-1");

		registry.heartbeat("runner-1", "instance-1", RunnerState.DRAINING, false);

		assertBlocked(selector.select(queuedJob()));
	}

	@Test
	void keepsTheReportedAvailabilityWhenALaterHeartbeatCarriesLivenessOnly() {
		registerAvailable("runner-1", "instance-1");

		registry.heartbeat("runner-1", "instance-1");

		assertEquals(new RunnerAssignment("runner-1", "instance-1"), selectedRunner(selector.select(queuedJob())));
	}

	@Test
	void selectsARunnerLastSeenExactlyTheHeartbeatTimeoutAgo() {
		registerAvailable("runner-1", "instance-1");

		clock.advance(HEARTBEAT_TIMEOUT);

		assertEquals(new RunnerAssignment("runner-1", "instance-1"), selectedRunner(selector.select(queuedJob())));
	}

	@Test
	void doesNotSelectARunnerLastSeenLongerAgoThanTheHeartbeatTimeout() {
		registerAvailable("runner-1", "instance-1");

		clock.advance(HEARTBEAT_TIMEOUT.plusNanos(1));

		assertBlocked(selector.select(queuedJob()));
	}

	@Test
	void selectsTheFreshRunnerWhenAStaleOneSortsFirst() {
		registerAvailable("runner-a", "instance-a");
		registerAvailable("runner-b", "instance-b");

		clock.advance(HEARTBEAT_TIMEOUT);
		registry.heartbeat("runner-b", "instance-b", RunnerState.READY, true);
		clock.advance(Duration.ofSeconds(1));

		assertEquals(new RunnerAssignment("runner-b", "instance-b"), selectedRunner(selector.select(queuedJob())));
	}

	@Test
	void selectsARunnerAgainOnceItsHeartbeatResumes() {
		registerAvailable("runner-1", "instance-1");
		clock.advance(HEARTBEAT_TIMEOUT.plusSeconds(1));
		assertBlocked(selector.select(queuedJob()));

		registry.heartbeat("runner-1", "instance-1", RunnerState.READY, true);

		assertEquals(new RunnerAssignment("runner-1", "instance-1"), selectedRunner(selector.select(queuedJob())));
	}

	private void registerAvailable(String runnerId, String instanceId) {
		registry.register(runner(runnerId, instanceId));
		registry.heartbeat(runnerId, instanceId, RunnerState.READY, true);
	}

	private static RunnerAssignment selectedRunner(RunnerSelectionOutcome outcome) {
		return assertInstanceOf(RunnerSelectionOutcome.Selected.class, outcome).dispatch().runner();
	}

	private static void assertBlocked(RunnerSelectionOutcome outcome) {
		assertInstanceOf(RunnerSelectionOutcome.NoAvailableRunner.class, outcome);
	}

	private static Job queuedJob() {
		var job = Job.create(
				new RepositoryReference(URI.create("https://git.example/acme/service"), "abc123"),
				new ExecutionDefinition(List.of("go", "test", "./..."), "/workspace/service"),
				STARTED_AT);
		job.queue(STARTED_AT);
		return job;
	}

	/** The identity a Runner sends at startup: not yet ready and not yet accepting work. */
	private static RunnerInfo runner(String runnerId, String instanceId) {
		return new RunnerInfo(
				runnerId,
				instanceId,
				"0.1.0",
				"runner.v1",
				RunnerState.UNAVAILABLE,
				false,
				new RunnerCapabilities("linux", "amd64", true, List.of("docker"), Map.of()));
	}
}
