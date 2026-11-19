package io.zeroyaml.controlplane.grpc;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the executor a gRPC listener runs its calls on, replacing grpc-java's
 * default cached pool, which grows without bound under load.
 *
 * <p>The pool has a fixed thread count and a bounded queue. A task beyond the
 * queue is rejected, which makes grpc-java reset that stream instead of queueing
 * it. Do not replace the rejection with a caller-runs policy: the caller is the
 * Netty event loop, and blocking work there would stall every connection on that
 * loop.</p>
 */
public final class BoundedGrpcExecutor {

	private BoundedGrpcExecutor() {
	}

	/**
	 * @param threadNamePrefix prefix of the daemon worker threads, followed by a counter
	 * @param threads          fixed number of worker threads
	 * @param queueCapacity    tasks that may wait for a worker before further tasks are rejected
	 */
	public static ExecutorService create(String threadNamePrefix, int threads, int queueCapacity) {
		var count = new AtomicInteger();
		ThreadFactory factory = task -> {
			var thread = new Thread(task, threadNamePrefix + count.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
		return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
	}
}
