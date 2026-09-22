package io.zeroyaml.controlplane.registration;

/**
 * The Control Plane's decision for one Runner heartbeat request.
 */
public enum HeartbeatDecision {
	ACKNOWLEDGED,
	UNKNOWN_RUNNER
}
