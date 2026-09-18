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
import io.zeroyaml.contracts.runner.v1.RunnerCapabilities;
import io.zeroyaml.contracts.runner.v1.RunnerInfo;
import io.zeroyaml.contracts.runner.v1.RunnerStatus;
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
	void returnsTheRunnerIdentityAndCapabilities() throws IOException {
		var serverName = InProcessServerBuilder.generateName();
		server = InProcessServerBuilder
				.forName(serverName)
				.directExecutor()
				.addService(new PingService())
				.build()
				.start();
		channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

		var client = new GrpcRunnerClient(channel, Duration.ofSeconds(1));

		var response = client.getInfo();

		assertEquals("runner-dev-01", response.runnerId());
		assertEquals("instance-1", response.instanceId());
		assertEquals("0.1.0", response.runnerVersion());
		assertEquals("runner.v1", response.protocolVersion());
		assertEquals(RunnerState.UNAVAILABLE, response.state());
		assertEquals(false, response.acceptingWork());
		assertEquals("windows", response.capabilities().operatingSystem());
		assertEquals("amd64", response.capabilities().architecture());
		assertEquals(true, response.capabilities().dockerAvailable());
		assertEquals(java.util.List.of("docker"), response.capabilities().supportedExecutors());
		assertEquals("local", response.capabilities().labels().get("region"));
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

	@Test
	void mapsAnUnavailableRunnerGetInfoToAnExplicitClientException() {
		channel = InProcessChannelBuilder
				.forName("missing-runner-info-" + UUID.randomUUID())
				.directExecutor()
				.build();
		var client = new GrpcRunnerClient(channel, Duration.ofSeconds(1));

		var exception = assertThrows(RunnerClientException.class, client::getInfo);

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

		@Override
		public void getInfo(io.zeroyaml.contracts.runner.v1.GetInfoRequest request,
				StreamObserver<RunnerInfo> responseObserver) {
			responseObserver.onNext(RunnerInfo.newBuilder()
					.setRunnerId("runner-dev-01")
					.setInstanceId("instance-1")
					.setRunnerVersion("0.1.0")
					.setProtocolVersion("runner.v1")
					.setStatus(RunnerStatus.RUNNER_STATUS_UNAVAILABLE)
					.setAcceptingWork(false)
					.setCapabilities(RunnerCapabilities.newBuilder()
							.setOperatingSystem("windows")
							.setArchitecture("amd64")
							.setDockerAvailable(true)
							.addSupportedExecutors("docker")
							.putLabels("region", "local")
							.build())
					.build());
			responseObserver.onCompleted();
		}
	}
}
