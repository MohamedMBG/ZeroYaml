package io.zeroyaml.controlplane.runner;

/**
 * Version of the Control Plane-to-Runner protobuf contract implemented by this
 * service. It is sent with every dispatch so that a Runner can refuse work it
 * cannot interpret instead of guessing field meanings, and it is required on
 * every status report a Runner sends back for the same reason.
 */
public final class RunnerProtocolVersion {

	public static final String CURRENT = "runner.v1";

	private RunnerProtocolVersion() {
	}
}
