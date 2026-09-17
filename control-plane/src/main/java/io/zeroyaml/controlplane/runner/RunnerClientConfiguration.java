package io.zeroyaml.controlplane.runner;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Owns the reusable network channel to the Runner and exposes the protocol-neutral
 * {@link RunnerClient} interface to the rest of the Control Plane.
 */
@Configuration
class RunnerClientConfiguration {

	@Bean(destroyMethod = "shutdownNow")
	ManagedChannel runnerManagedChannel(RunnerClientProperties properties) {
		// The current Runner only serves plaintext gRPC on its configured local port.
		return ManagedChannelBuilder
				.forAddress(properties.getHost(), properties.getPort())
				.usePlaintext()
				.build();
	}

	@Bean
	RunnerClient runnerClient(ManagedChannel runnerManagedChannel, RunnerClientProperties properties) {
		// Keep the generated stub behind the application-facing RunnerClient boundary.
		return new GrpcRunnerClient(runnerManagedChannel, properties.getPingDeadline());
	}
}
