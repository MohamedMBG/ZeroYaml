package io.zeroyaml.controlplane.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.zeroyaml.contracts.runner.v1.PingRequest;
import io.zeroyaml.contracts.runner.v1.PingResponse;
import io.zeroyaml.contracts.runner.v1.RunnerServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the generated gRPC client against an in-process Runner, keeping the
 * protocol integration test fast and independent of an external Runner process.
 */
class GrpcRunnerClientTest {

	private ManagedChannel channel;
	private Server server;

	@AfterEach
	void tearDown() {
		if (channel != null) {
			channel.shutdownNow();
		}
		if (server != null) {
			server.shutdownNow();
		}
	}

	@Test
	void returnsTheRunnerPingResponse() throws IOException {
		var serverName = InProcessServerBuilder.generateName();
		// Direct executors make this focused protocol test deterministic and synchronous.
		server = InProcessServerBuilder
				.forName(serverName)
				.directExecutor()
				.addService(new PingService())
				.build()
				.start();
		channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

		var client = new GrpcRunnerClient(channel, Duration.ofSeconds(1));

		var response = client.ping("control-plane");

		assertEquals("pong: control-plane", response.message());
		assertEquals("test-runner", response.runnerVersion());
	}

	@Test
	void mapsAnUnavailableRunnerToAnExplicitClientException() {
		// No server is registered under this name, which produces gRPC UNAVAILABLE.
		channel = InProcessChannelBuilder
				.forName("missing-runner-" + UUID.randomUUID())
				.directExecutor()
				.build();
		var client = new GrpcRunnerClient(channel, Duration.ofSeconds(1));

		var exception = assertThrows(RunnerClientException.class, () -> client.ping("control-plane"));

		assertEquals(Status.Code.UNAVAILABLE, exception.getStatusCode());
	}

	private static final class PingService extends RunnerServiceGrpc.RunnerServiceImplBase {

		@Override
		public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
			responseObserver.onNext(PingResponse.newBuilder()
					.setMessage("pong: " + request.getMessage())
					.setRunnerVersion("test-runner")
					.build());
			responseObserver.onCompleted();
		}
	}
}
