package io.zeroyaml.controlplane.runner;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.zeroyaml.contracts.runner.v1.PingRequest;
import io.zeroyaml.contracts.runner.v1.RunnerServiceGrpc;

/**
 * gRPC implementation of {@link RunnerClient}. It translates generated protocol
 * types and gRPC transport errors into Control Plane types at this boundary.
 */
public class GrpcRunnerClient implements RunnerClient {

	private final RunnerServiceGrpc.RunnerServiceBlockingStub runnerService;
	private final Duration pingDeadline;

	public GrpcRunnerClient(ManagedChannel channel, Duration pingDeadline) {
		this(RunnerServiceGrpc.newBlockingStub(channel), pingDeadline);
	}

	GrpcRunnerClient(RunnerServiceGrpc.RunnerServiceBlockingStub runnerService, Duration pingDeadline) {
		this.runnerService = Objects.requireNonNull(runnerService, "runnerService must not be null");
		this.pingDeadline = requirePositive(pingDeadline);
	}

	@Override
	public RunnerPingResult ping(String message) {
		try {
			// Apply a deadline to every call so an unreachable Runner cannot block indefinitely.
			var response = runnerService
					.withDeadlineAfter(pingDeadline.toNanos(), TimeUnit.NANOSECONDS)
					.ping(PingRequest.newBuilder().setMessage(message).build());
			return new RunnerPingResult(response.getMessage(), response.getRunnerVersion());
		} catch (StatusRuntimeException exception) {
			// Preserve the status code for retry and availability decisions outside this adapter.
			throw new RunnerClientException(
					exception.getStatus().getCode(),
					"Runner Ping failed with status " + exception.getStatus().getCode(),
					exception
			);
		}
	}

	private static Duration requirePositive(Duration value) {
		Objects.requireNonNull(value, "pingDeadline must not be null");
		// Reject invalid configuration before it can create a gRPC call without a usable deadline.
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("pingDeadline must be positive");
		}
		return value;
	}
}
