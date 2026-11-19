package io.zeroyaml.controlplane.grpc;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.zeroyaml.controlplane.registration.RunnerRegistrationServerProperties;

/**
 * Owns the legacy gRPC endpoint for registration, heartbeats, and execution
 * status reports. Log ingress uses a separate authenticated TLS listener and
 * is deliberately not registered as a BindableService bean on this endpoint.
 *
 * <p>Starting is bound to application startup so that a Runner can register as
 * soon as the Control Plane is ready, and a bound-port failure fails startup
 * with an actionable message rather than leaving the endpoint silently
 * unreachable.</p>
 *
 * <p>The endpoint keeps its original {@code zeroyaml.registration.port}
 * setting, so existing deployments and Runner configuration stay valid.</p>
 *
 * <p>Calls run on a fixed pool with a bounded queue instead of grpc-java's
 * unbounded cached executor, because this listener is plaintext and
 * unauthenticated until Phase 6 transport security. Status reports wait on the
 * Job store, so more threads than {@value #WORKER_THREADS} would only wait.
 * {@link ConcurrentCallLimit} refuses calls beyond {@value #MAX_CONCURRENT_CALLS}
 * with {@code RESOURCE_EXHAUSTED}; it runs on the executor, so a refused call
 * still takes a queue slot for its start task, and an admitted call holds at
 * most two (see {@link ConcurrentCallLimit}). The queue therefore holds four
 * times the call limit, and a task beyond it is rejected, which resets that
 * stream. A Runner does not retry a refused heartbeat or status report: the next
 * heartbeat is sent on schedule, and a lost status report is recovered by
 * reconciliation.</p>
 */
@Component
class RunnerFacingGrpcServer implements SmartLifecycle {

	static final int WORKER_THREADS = 8;
	static final int MAX_CONCURRENT_CALLS = 64;
	/**
	 * Concurrent streams one connection may open. A Runner issues a heartbeat and
	 * at most a few status reports at a time; streams beyond the cap wait on the
	 * client side under HTTP/2 flow control, so one connection cannot fill the queue.
	 */
	static final int MAX_CALLS_PER_CONNECTION = 8;
	static final int MAX_QUEUED_TASKS = 4 * MAX_CONCURRENT_CALLS;

	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

	private final RunnerRegistrationServerProperties properties;
	private final List<BindableService> services;
	private final int workerThreads;
	private final int maxConcurrentCalls;
	private final int maxQueuedTasks;
	private final Object lifecycleLock = new Object();

	private Server server;
	private ExecutorService executor;

	@Autowired
	RunnerFacingGrpcServer(RunnerRegistrationServerProperties properties, List<BindableService> services) {
		this(properties, services, WORKER_THREADS, MAX_CONCURRENT_CALLS, MAX_QUEUED_TASKS);
	}

	/** Smaller bounds let tests saturate the listener without opening hundreds of calls. */
	RunnerFacingGrpcServer(RunnerRegistrationServerProperties properties, List<BindableService> services,
			int workerThreads, int maxConcurrentCalls, int maxQueuedTasks) {
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		Objects.requireNonNull(services, "services must not be null");
		if (services.isEmpty()) {
			// Serving nothing would let the Control Plane start while every Runner
			// call fails as unimplemented, which is harder to diagnose than a
			// startup failure.
			throw new IllegalStateException("No gRPC service is available to serve runner calls");
		}
		this.services = List.copyOf(services);
		this.workerThreads = workerThreads;
		this.maxConcurrentCalls = maxConcurrentCalls;
		this.maxQueuedTasks = maxQueuedTasks;
	}

	@Override
	public void start() {
		synchronized (lifecycleLock) {
			if (server != null) {
				return;
			}

			executor = BoundedGrpcExecutor.create("runner-facing-grpc-", workerThreads, maxQueuedTasks);
			var callLimit = new ConcurrentCallLimit("Runner-facing gRPC endpoint", maxConcurrentCalls);
			var builder = NettyServerBuilder.forPort(properties.getPort())
					.executor(executor)
					.maxConcurrentCallsPerConnection(MAX_CALLS_PER_CONNECTION)
					.addStreamTracerFactory(callLimit.streamTracerFactory());
			services.forEach(service -> builder.addService(ServerInterceptors.intercept(service, callLimit)));

			try {
				server = builder.build().start();
			} catch (IOException | RuntimeException exception) {
				executor.shutdownNow();
				executor = null;
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
				// The server no longer schedules work once it has terminated or been forced down.
				executor.shutdownNow();
				server = null;
				executor = null;
			}
		}
	}

	@Override
	public boolean isRunning() {
		synchronized (lifecycleLock) {
			return server != null;
		}
	}

	/** Bound port, including an OS-assigned port for isolated transport tests. */
	int port() {
		synchronized (lifecycleLock) {
			return server.getPort();
		}
	}
}
