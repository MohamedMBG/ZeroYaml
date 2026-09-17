package io.zeroyaml.controlplane.runner;

import io.grpc.Status;

/**
 * Makes Runner RPC failures explicit while retaining the gRPC status code so a
 * caller can distinguish conditions such as an unavailable Runner and a deadline.
 */
public class RunnerClientException extends RuntimeException {

	private final Status.Code statusCode;

	public RunnerClientException(Status.Code statusCode, String message, Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
	}

	public Status.Code getStatusCode() {
		return statusCode;
	}
}
