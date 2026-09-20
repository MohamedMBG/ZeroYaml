package io.zeroyaml.controlplane.registration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import io.zeroyaml.controlplane.runner.RunnerInfo;

/**
 * Process-local Runner inventory for the Phase 1 proof. Entries are keyed by
 * {@code runnerId}, the logical identity a Runner reports across restarts.
 *
 * <p>A registration request for a {@code runnerId} that already holds a
 * registered Runner is reconciled deterministically: the same
 * {@code instanceId} re-registering (for example, after a retried request) is
 * idempotent, and a different {@code instanceId} is an identity conflict.
 * Replacing a registered instance requires a lease or heartbeat policy, which
 * is out of scope for this first contract.
 */
@Component
class InMemoryRunnerRegistry implements RunnerRegistry {

	private final ConcurrentHashMap<String, RegisteredRunner> runnersByRunnerId = new ConcurrentHashMap<>();

	@Override
	public RegistrationOutcome register(RunnerInfo runner) {
		Objects.requireNonNull(runner, "runner must not be null");

		var outcome = new AtomicReference<RegistrationOutcome>();
		runnersByRunnerId.compute(runner.runnerId(), (runnerId, existing) -> {
			if (existing == null) {
				var registrationId = UUID.randomUUID().toString();
				outcome.set(new RegistrationOutcome(
						RegistrationDecision.ACCEPTED,
						registrationId,
						"Runner " + runnerId + " registered"
				));
				return new RegisteredRunner(registrationId, runner, Instant.now());
			}

			if (existing.runner().instanceId().equals(runner.instanceId())) {
				outcome.set(new RegistrationOutcome(
						RegistrationDecision.ALREADY_REGISTERED,
						existing.registrationId(),
						"Runner " + runnerId + " instance " + runner.instanceId() + " is already registered"
				));
				return existing;
			}

			outcome.set(new RegistrationOutcome(
					RegistrationDecision.IDENTITY_CONFLICT,
					"",
					"Runner " + runnerId + " is already registered under instance " + existing.runner().instanceId()
			));
			return existing;
		});

		return outcome.get();
	}
}
