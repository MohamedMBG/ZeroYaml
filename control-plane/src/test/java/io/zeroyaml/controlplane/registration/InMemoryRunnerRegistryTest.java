package io.zeroyaml.controlplane.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.zeroyaml.controlplane.runner.RunnerCapabilities;
import io.zeroyaml.controlplane.runner.RunnerInfo;
import io.zeroyaml.controlplane.runner.RunnerState;

class InMemoryRunnerRegistryTest {

	@Test
	void acceptsTheFirstRegistrationForARunnerId() {
		var registry = new InMemoryRunnerRegistry();

		var outcome = registry.register(runner("runner-1", "instance-1"));

		assertEquals(RegistrationDecision.ACCEPTED, outcome.decision());
		assertFalse(outcome.registrationId().isBlank());
	}

	@Test
	void treatsTheSameInstanceRegisteringAgainAsAlreadyRegistered() {
		var registry = new InMemoryRunnerRegistry();

		var first = registry.register(runner("runner-1", "instance-1"));
		var second = registry.register(runner("runner-1", "instance-1"));

		assertEquals(RegistrationDecision.ALREADY_REGISTERED, second.decision());
		assertEquals(first.registrationId(), second.registrationId());
	}

	@Test
	void treatsADifferentInstanceForTheSameRunnerIdAsAnIdentityConflict() {
		var registry = new InMemoryRunnerRegistry();

		registry.register(runner("runner-1", "instance-1"));
		var conflict = registry.register(runner("runner-1", "instance-2"));

		assertEquals(RegistrationDecision.IDENTITY_CONFLICT, conflict.decision());
		assertTrue(conflict.registrationId().isEmpty());
	}

	@Test
	void registersDifferentRunnerIdsIndependently() {
		var registry = new InMemoryRunnerRegistry();

		var first = registry.register(runner("runner-1", "instance-1"));
		var second = registry.register(runner("runner-2", "instance-1"));

		assertEquals(RegistrationDecision.ACCEPTED, first.decision());
		assertEquals(RegistrationDecision.ACCEPTED, second.decision());
		assertNotEquals(first.registrationId(), second.registrationId());
	}

	@Test
	void registersConcurrentAttemptsForTheSameRunnerIdExactlyOnce() throws InterruptedException {
		var registry = new InMemoryRunnerRegistry();
		var attempts = 16;
		var readyLatch = new CountDownLatch(attempts);
		var startLatch = new CountDownLatch(1);
		var accepted = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(attempts);

		try {
			for (int i = 0; i < attempts; i++) {
				pool.submit(() -> {
					readyLatch.countDown();
					try {
						startLatch.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}

					if (registry.register(runner("runner-1", "instance-1")).decision() == RegistrationDecision.ACCEPTED) {
						accepted.incrementAndGet();
					}
				});
			}

			readyLatch.await();
			startLatch.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}

		assertEquals(1, accepted.get());
	}

	private static RunnerInfo runner(String runnerId, String instanceId) {
		return new RunnerInfo(
				runnerId,
				instanceId,
				"0.1.0",
				"runner.v1",
				RunnerState.STARTING,
				false,
				new RunnerCapabilities("windows", "amd64", false, List.of(), Map.of())
		);
	}
}
