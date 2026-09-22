package io.zeroyaml.controlplane.registration;

/**
 * The Control Plane's liveness judgment for a {@code runnerId}. This is a
 * simple last-seen policy, not a distributed failure detector: a single missed
 * heartbeat past the configured timeout is enough to become
 * {@link #UNAVAILABLE}, and the next acknowledged heartbeat makes the same
 * entry {@link #HEALTHY} again without a new registration.
 */
public enum RunnerLiveness {
	/** No Runner has ever registered under this {@code runnerId}. */
	UNKNOWN,

	/** A heartbeat was recorded within the configured timeout. */
	HEALTHY,

	/** No heartbeat was recorded within the configured timeout. */
	UNAVAILABLE
}
