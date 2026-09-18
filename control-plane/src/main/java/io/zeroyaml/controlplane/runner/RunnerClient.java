package io.zeroyaml.controlplane.runner;

/**
 * Control Plane boundary for the small set of Runner calls needed by this service.
 * Keeping callers behind this interface prevents application code from depending on
 * generated gRPC classes directly.
 */
public interface RunnerClient {

	RunnerPingResult ping(String message);

	RunnerInfo getInfo();
}
