package io.zeroyaml.controlplane.runner;

/**
 * Whether a Runner took responsibility for a dispatched Job.
 */
public enum JobDispatchOutcome {
	ACCEPTED,
	REJECTED
}
