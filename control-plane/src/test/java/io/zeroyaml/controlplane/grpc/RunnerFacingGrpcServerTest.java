package io.zeroyaml.controlplane.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.zeroyaml.contracts.runner.v1.AppendJobLogRequest;
import io.zeroyaml.contracts.runner.v1.AppendJobLogResponse;
import io.zeroyaml.contracts.runner.v1.JobLogServiceGrpc;
import io.zeroyaml.controlplane.registration.RunnerRegistrationServerProperties;

/**
 * Saturation is checked over a real Netty transport with deliberately small bounds, because the
 * executor and the call limit only matter once calls pile up behind a blocked handler.
 */
class RunnerFacingGrpcServerTest {
	private final CountDownLatch entered = new CountDownLatch(1);
	private final CountDownLatch release = new CountDownLatch(1);
	private final AtomicInteger started = new AtomicInteger();
	private final JobLogServiceGrpc.JobLogServiceImplBase blockingService = new JobLogServiceGrpc.JobLogServiceImplBase() {
		@Override
		public void appendJobLog(AppendJobLogRequest request, StreamObserver<AppendJobLogResponse> observer) {
			started.incrementAndGet();
			entered.countDown();
			awaitQuietly(release);
			observer.onNext(AppendJobLogResponse.getDefaultInstance());
			observer.onCompleted();
		}
	};
	private RunnerFacingGrpcServer server;
	private ManagedChannel channel;

	@AfterEach
	void tearDown() {
		release.countDown();
		if (channel != null) {
			channel.shutdownNow();
		}
		if (server != null) {
			server.stop();
		}
	}

	@Test
	void callsPilingBehindABlockedWorkerNeverStartMoreHandlersThanWorkers() throws Exception {
		startServer(1, 64, 1);
		var stub = JobLogServiceGrpc.newFutureStub(channel);
		var first = stub.withDeadlineAfter(5, TimeUnit.SECONDS).appendJobLog(AppendJobLogRequest.getDefaultInstance());
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		var piled = new ArrayList<Future<AppendJobLogResponse>>();
		for (var i = 0; i < 6; i++) {
			piled.add(stub.withDeadlineAfter(1, TimeUnit.SECONDS).appendJobLog(AppendJobLogRequest.getDefaultInstance()));
		}
		// The only worker is blocked, so no piled call can finish before its client deadline.
		for (var call : piled) {
			assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
		}
		// grpc-java's default cached executor would have started a handler for each of them.
		assertEquals(1, started.get());

		release.countDown();
		first.get(5, TimeUnit.SECONDS);
		appendJobLog();
	}

	@Test
	void refusesCallsBeyondTheConcurrentLimitAndRecoversWhenTheyFinish() throws Exception {
		startServer(2, 1, 16);
		var first = JobLogServiceGrpc.newFutureStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
				.appendJobLog(AppendJobLogRequest.getDefaultInstance());
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		var refused = assertThrows(StatusRuntimeException.class, this::appendJobLog);
		assertEquals(Status.Code.RESOURCE_EXHAUSTED, refused.getStatus().getCode());
		assertTrue(refused.getStatus().getDescription().contains("Runner-facing gRPC endpoint"));
		assertEquals(1, started.get());

		release.countDown();
		first.get(5, TimeUnit.SECONDS);
		appendJobLog();
	}

	@Test
	void failedStartOnABoundPortReportsThePort() {
		startServer(1, 1, 1);
		var properties = new RunnerRegistrationServerProperties();
		properties.setPort(server.port());
		var conflicting = new RunnerFacingGrpcServer(properties, List.of(blockingService), 1, 1, 1);

		var failure = assertThrows(IllegalStateException.class, conflicting::start);

		assertTrue(failure.getMessage().contains(String.valueOf(server.port())));
		assertFalse(conflicting.isRunning());
	}

	@Test
	void refusesToStartWithoutAnyService() {
		assertThrows(IllegalStateException.class,
				() -> new RunnerFacingGrpcServer(new RunnerRegistrationServerProperties(), List.of()));
	}

	private void startServer(int workerThreads, int maxConcurrentCalls, int maxQueuedTasks) {
		var properties = new RunnerRegistrationServerProperties();
		properties.setPort(0);
		server = new RunnerFacingGrpcServer(properties, List.of(blockingService),
				workerThreads, maxConcurrentCalls, maxQueuedTasks);
		server.start();
		// An IP literal skips the DNS service-config lookup that delays channels on some Windows hosts.
		channel = Grpc.newChannelBuilderForAddress("127.0.0.1", server.port(), InsecureChannelCredentials.create()).build();
	}

	private AppendJobLogResponse appendJobLog() {
		return JobLogServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
				.appendJobLog(AppendJobLogRequest.getDefaultInstance());
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
