package io.zeroyaml.controlplane.runner;

/**
 * Runner lifecycle states reported at the Control Plane boundary.
 */
public enum RunnerState {
	STARTING,
	READY,
	DRAINING,
	UNAVAILABLE
}
