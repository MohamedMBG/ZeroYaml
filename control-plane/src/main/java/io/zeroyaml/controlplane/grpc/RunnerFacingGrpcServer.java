package io.zeroyaml.controlplane.grpc;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.zeroyaml.controlplane.registration.RunnerRegistrationServerProperties;

/**
 * Owns the lifecycle of the single gRPC endpoint Runners call, and hosts every
 * Control Plane gRPC service on it. Runners are configured with one Control
 * Plane address, so registration, heartbeats, and execution status reports
 * share this listener rather than one port per service.
 *
 * <p>Starting is bound to application startup so that a Runner can register as
 * soon as the Control Plane is ready, and a bound-port failure fails startup
 * with an actionable message rather than leaving the endpoint silently
 * unreachable.</p>
 *
 * <p>The endpoint keeps its original {@code zeroyaml.registration.port}
 * setting, so existing deployments and Runner configuration stay valid.</p>
 */
@Component
class RunnerFacingGrpcServer implements SmartLifecycle {

	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

	private final RunnerRegistrationServerProperties properties;
	private final List<BindableService> services;
	private final Object lifecycleLock = new Object();

	private Server server;

	RunnerFacingGrpcServer(RunnerRegistrationServerProperties properties, List<BindableService> services) {
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		Objects.requireNonNull(services, "services must not be null");
		if (services.isEmpty()) {
			// Serving nothing would let the Control Plane start while every Runner
			// call fails as unimplemented, which is harder to diagnose than a
			// startup failure.
			throw new IllegalStateException("No gRPC service is available to serve runner calls");
		}
		this.services = List.copyOf(services);
	}

	@Override
	public void start() {
		synchronized (lifecycleLock) {
			if (server != null) {
				return;
			}

			var builder = ServerBuilder.forPort(properties.getPort());
			services.forEach(builder::addService);

			try {
				server = builder.build().start();
			} catch (IOException exception) {
				throw new IllegalStateException(
						"Failed to start the runner-facing gRPC server on port " + properties.getPort(),
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
