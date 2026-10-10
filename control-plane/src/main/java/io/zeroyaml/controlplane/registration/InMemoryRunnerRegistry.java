package io.zeroyaml.controlplane.registration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

import io.zeroyaml.controlplane.runner.RunnerInfo;
import io.zeroyaml.controlplane.runner.RunnerState;

/**
 * Process-local Runner inventory for the Phase 1 proof. Entries are keyed by
 * {@code runnerId}, the logical identity a Runner reports across restarts.
 *
 * <p>A registration request for a {@code runnerId} that already holds a
 * registered Runner is reconciled deterministically: the same
 * {@code instanceId} re-registering (for example, after a retried request) is
 * idempotent, and a different {@code instanceId} is an identity conflict for
 * as long as the existing entry is kept.
 *
 * <p>Liveness is a simple last-seen policy derived from {@code lastSeenAt}:
 * registration and every acknowledged heartbeat advance it, and
 * {@link #livenessOf(String)} compares the elapsed time against the
 * configured heartbeat timeout. There is no background sweep; a Runner that
 * stops sending heartbeats is only reported {@link RunnerLiveness#UNAVAILABLE}
 * when something asks, which keeps this component free of scheduling policy.
 *
 * <p>The inventory is bounded by eviction rather than by a sweep: every
 * registration first removes the entries that have been
 * {@link RunnerLiveness#UNAVAILABLE} for longer than the configured retention.
 * The map grows only through registration, so pruning there is enough to keep
 * it proportional to the Runners seen recently instead of to every Runner
 * that ever registered. An evicted {@code runnerId} is unknown again: its
 * heartbeats are answered {@link HeartbeatDecision#UNKNOWN_RUNNER}, and a new
 * instance may register under it.
 */
@Component
class InMemoryRunnerRegistry implements RunnerRegistry {

	private final ConcurrentHashMap<String, RegisteredRunner> runnersByRunnerId = new ConcurrentHashMap<>();
	private final Clock clock;
	private final Duration heartbeatTimeout;
	private final Duration evictionAge;

	InMemoryRunnerRegistry(Clock clock, RunnerRegistrationServerProperties properties) {
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(properties, "properties must not be null");
		this.heartbeatTimeout = properties.getHeartbeatTimeout();
		// Retention is counted from the moment an entry becomes UNAVAILABLE, so a
		// healthy Runner is never evicted whatever retention is configured.
		this.evictionAge = heartbeatTimeout.plus(properties.getUnavailableRetention());
	}

	@Override
	public RegistrationOutcome register(RunnerInfo runner) {
		Objects.requireNonNull(runner, "runner must not be null");

		var outcome = new AtomicReference<RegistrationOutcome>();
		var now = clock.instant();
		evictExpiredEntries(now);
		runnersByRunnerId.compute(runner.runnerId(), (runnerId, existing) -> {
			if (existing == null) {
				var registrationId = UUID.randomUUID().toString();
				outcome.set(new RegistrationOutcome(
						RegistrationDecision.ACCEPTED,
						registrationId,
						"Runner " + runnerId + " registered"
				));
				return new RegisteredRunner(registrationId, runner, now, now);
			}

			if (existing.runner().instanceId().equals(runner.instanceId())) {
				outcome.set(new RegistrationOutcome(
						RegistrationDecision.ALREADY_REGISTERED,
						existing.registrationId(),
						"Runner " + runnerId + " instance " + runner.instanceId() + " is already registered"
				));
				// A repeated registration is itself a liveness signal, so it counts as a heartbeat too.
				return existing.seenAt(now);
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

	@Override
	public HeartbeatOutcome heartbeat(String runnerId, String instanceId) {
		var now = clock.instant();
		return recordHeartbeat(runnerId, instanceId, existing -> existing.seenAt(now));
	}

	@Override
	public HeartbeatOutcome heartbeat(String runnerId, String instanceId, RunnerState state, boolean acceptingWork) {
		Objects.requireNonNull(state, "state must not be null");

		var now = clock.instant();
		return recordHeartbeat(runnerId, instanceId, existing -> existing.reportingAt(now, state, acceptingWork));
	}

	private HeartbeatOutcome recordHeartbeat(
			String runnerId, String instanceId, UnaryOperator<RegisteredRunner> acknowledge) {
		Objects.requireNonNull(runnerId, "runnerId must not be null");
		Objects.requireNonNull(instanceId, "instanceId must not be null");

		var outcome = new AtomicReference<HeartbeatOutcome>();
		runnersByRunnerId.computeIfPresent(runnerId, (id, existing) -> {
			if (!existing.runner().instanceId().equals(instanceId)) {
				outcome.set(new HeartbeatOutcome(
						HeartbeatDecision.UNKNOWN_RUNNER,
						"Runner " + id + " is registered under a different instance"
				));
				return existing;
			}

			outcome.set(new HeartbeatOutcome(HeartbeatDecision.ACKNOWLEDGED, "Runner " + id + " heartbeat acknowledged"));
			return acknowledge.apply(existing);
		});

		if (outcome.get() == null) {
			outcome.set(new HeartbeatOutcome(
					HeartbeatDecision.UNKNOWN_RUNNER,
					"Runner " + runnerId + " is not registered"
			));
		}

		return outcome.get();
	}

	@Override
	public RunnerLiveness livenessOf(String runnerId) {
		Objects.requireNonNull(runnerId, "runnerId must not be null");

		var registered = runnersByRunnerId.get(runnerId);
		if (registered == null) {
			return RunnerLiveness.UNKNOWN;
		}

		return livenessAt(registered, clock.instant());
	}

	@Override
	public List<RegisteredRunner> availableRunners() {
		// One instant for the whole scan, so every entry is judged against the same moment.
		var now = clock.instant();
		return runnersByRunnerId.values().stream()
				.filter(registered -> livenessAt(registered, now) == RunnerLiveness.HEALTHY)
				.filter(registered -> registered.runner().state() == RunnerState.READY)
				.filter(registered -> registered.runner().acceptingWork())
				.toList();
	}

	private RunnerLiveness livenessAt(RegisteredRunner registered, Instant now) {
		var elapsed = Duration.between(registered.lastSeenAt(), now);
		return elapsed.compareTo(heartbeatTimeout) > 0 ? RunnerLiveness.UNAVAILABLE : RunnerLiveness.HEALTHY;
	}

	/**
	 * Removes every entry last seen longer ago than the eviction age. Removal is
	 * conditional on the entry being unchanged, so a heartbeat that lands while
	 * the scan runs keeps its Runner registered.
	 */
	private void evictExpiredEntries(Instant now) {
		runnersByRunnerId.forEach((runnerId, registered) -> {
			if (Duration.between(registered.lastSeenAt(), now).compareTo(evictionAge) > 0) {
				runnersByRunnerId.remove(runnerId, registered);
			}
		});
	}
}
