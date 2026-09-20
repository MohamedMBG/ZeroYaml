package io.zeroyaml.controlplane.registration;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.Server;
import io.grpc.ServerBuilder;

/**
 * Owns the lifecycle of the gRPC server that exposes {@link GrpcRunnerRegistrationService}
 * to Runners. Starting is bound to application startup so that a Runner can
 * register as soon as the Control Plane is ready, and a bound-port failure
 * fails startup with an actionable message rather than leaving registration
 * silently unreachable.
 */
@Component
class RunnerRegistrationServer implements SmartLifecycle {

	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

	private final RunnerRegistrationServerProperties properties;
	private final GrpcRunnerRegistrationService registrationService;
	private final Object lifecycleLock = new Object();

	private Server server;

	RunnerRegistrationServer(RunnerRegistrationServerProperties properties, GrpcRunnerRegistrationService registrationService) {
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.registrationService = Objects.requireNonNull(registrationService, "registrationService must not be null");
	}

	@Override
	public void start() {
		synchronized (lifecycleLock) {
			if (server != null) {
				return;
			}

			try {
				server = ServerBuilder.forPort(properties.getPort())
						.addService(registrationService)
						.build()
						.start();
			} catch (IOException exception) {
				throw new IllegalStateException(
						"Failed to start the Runner registration gRPC server on port " + properties.getPort(),
						exception
				);
			}
		}
	}

	@Override
	public void stop() {
		synchronized (lifecycleLock) {
			if (server == null) {
				return;
			}

			server.shutdown();
			try {
				if (!server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					server.shutdownNow();
				}
			} catch (InterruptedException exception) {
				server.shutdownNow();
				Thread.currentThread().interrupt();
			} finally {
				server = null;
			}
		}
	}

	@Override
	public boolean isRunning() {
		synchronized (lifecycleLock) {
			return server != null;
		}
	}
}
