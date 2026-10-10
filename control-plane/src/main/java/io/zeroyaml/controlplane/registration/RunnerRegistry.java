package io.zeroyaml.controlplane.registration;

import java.util.List;

import io.zeroyaml.controlplane.runner.RunnerInfo;
import io.zeroyaml.controlplane.runner.RunnerState;

/**
 * The Control Plane's inventory of registered Runners. The Control Plane owns
 * registration state and availability policy; a Runner only reports its own
 * identity, capabilities, and liveness.
 */
public interface RunnerRegistry {

	/**
	 * Registers runner, or reconciles a duplicate or conflicting request
	 * deterministically. See {@link RegistrationDecision} for the possible outcomes.
	 */
	RegistrationOutcome register(RunnerInfo runner);

	/**
	 * Records a heartbeat for the Runner instance identified by {@code runnerId}
	 * and {@code instanceId}. A {@code runnerId} that is not registered, or that
	 * is registered under a different {@code instanceId}, is reported as
	 * {@link HeartbeatDecision#UNKNOWN_RUNNER} rather than silently accepted.
	 *
	 * <p>This form records liveness only and keeps the availability the entry
	 * already holds. It serves a Runner that does not report availability with
	 * its heartbeat.</p>
	 */
	HeartbeatOutcome heartbeat(String runnerId, String instanceId);

	/**
	 * Records a heartbeat as {@link #heartbeat(String, String)} does and, when it
	 * is acknowledged, replaces the entry's state and {@code acceptingWork} flag
	 * with the reported values. Registration happens before a Runner finishes
	 * its startup checks, so this is how the registry learns whether the Runner
	 * can take work.
	 */
	HeartbeatOutcome heartbeat(String runnerId, String instanceId, RunnerState state, boolean acceptingWork);

	/**
	 * Returns the current liveness judgment for {@code runnerId} based on the
	 * time elapsed since its last acknowledged registration or heartbeat.
	 */
	RunnerLiveness livenessOf(String runnerId);

	/**
	 * Returns a snapshot of every registered Runner a Job may be dispatched to
	 * at the time of the call, in no guaranteed order. A Runner is available
	 * when its liveness is {@link RunnerLiveness#HEALTHY}, its last reported
	 * state is {@link RunnerState#READY}, and it last reported that it accepts
	 * work. The registry owns that policy, so callers that need an eligible
	 * Runner ask here instead of re-deriving it from timestamps and flags.
	 *
	 * <p>The snapshot is not a reservation: a listed Runner can stop sending
	 * heartbeats or stop accepting work immediately afterwards.</p>
	 */
	List<RegisteredRunner> availableRunners();
}
