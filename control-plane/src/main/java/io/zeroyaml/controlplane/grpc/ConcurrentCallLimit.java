package io.zeroyaml.controlplane.grpc;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import io.grpc.Context;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;

/**
 * Caps concurrent calls on one gRPC listener. A call over the limit is answered
 * with {@code RESOURCE_EXHAUSTED} at once instead of waiting for a worker, so a
 * burst never accumulates threads or queued work behind a slow dependency such
 * as the Job store or the log retention lock. Neither Runner client retries a
 * refused call: a lost log chunk is reported in its final marker, and a lost
 * status report is recovered by reconciliation.
 *
 * <p>A permit is released exactly once, when the call closes, completes, or is
 * cancelled, including when the next handler fails to start the call. Those
 * callbacks run on the server executor, and grpc-java discards a call event
 * whose task the executor rejects, so they alone could leak a permit under
 * saturation. The server must therefore also register
 * {@link #streamTracerFactory()}: its tracer releases the permit when the
 * transport closes the stream, which does not depend on the executor.</p>
 */
public final class ConcurrentCallLimit implements ServerInterceptor {
	private static final Context.Key<StreamCloseRelease> STREAM_CLOSE = Context.key("zeroyaml-call-limit-stream-close");
	private final Semaphore permits;
	private final String refusalDescription;

	/**
	 * @param listenerName names the listener in the refusal sent to the caller, for example "Job log ingress"
	 */
	public ConcurrentCallLimit(String listenerName, int maxConcurrentCalls) {
		if (maxConcurrentCalls < 1) {
			throw new IllegalArgumentException("maxConcurrentCalls must be positive");
		}
		this.permits = new Semaphore(maxConcurrentCalls);
		this.refusalDescription = Objects.requireNonNull(listenerName, "listenerName must not be null")
				+ " is at its concurrent call limit";
	}

	/** Stream tracers that release a call's permit when the transport closes its stream. */
	public ServerStreamTracer.Factory streamTracerFactory() {
		return new ServerStreamTracer.Factory() {
			@Override
			public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
				return new StreamCloseRelease();
			}
		};
	}

	/** Permits not held by an open call; exposed for leak tests. */
	int availablePermits() { return permits.availablePermits(); }

	@Override
	public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
			Metadata headers, ServerCallHandler<ReqT, RespT> next) {
		if (!permits.tryAcquire()) {
			call.close(Status.RESOURCE_EXHAUSTED.withDescription(refusalDescription), new Metadata());
			return new ServerCall.Listener<>() { };
		}
		var released = new AtomicBoolean();
		Runnable release = () -> {
			if (released.compareAndSet(false, true)) {
				permits.release();
			}
		};
		var streamClose = STREAM_CLOSE.get();
		if (streamClose != null) {
			streamClose.onClose(release);
		}
		var tracked = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
			@Override
			public void close(Status status, Metadata trailers) {
				try {
					super.close(status, trailers);
				} finally {
					release.run();
				}
			}
		};
		ServerCall.Listener<ReqT> listener;
		try {
			listener = next.startCall(tracked, headers);
		} catch (RuntimeException exception) {
			release.run();
			throw exception;
		}
		return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
			@Override
			public void onCancel() {
				try {
					super.onCancel();
				} finally {
					release.run();
				}
			}

			@Override
			public void onComplete() {
				try {
					super.onComplete();
				} finally {
					release.run();
				}
			}
		};
	}

	/**
	 * Runs a release action once the transport has closed the stream. The
	 * transport reports the close on its own thread, possibly before the call
	 * was started on the executor, so an action registered after the close
	 * runs immediately.
	 */
	private static final class StreamCloseRelease extends ServerStreamTracer {
		private Runnable action;
		private boolean closed;

		@Override
		public Context filterContext(Context context) {
			return context.withValue(STREAM_CLOSE, this);
		}

		void onClose(Runnable release) {
			synchronized (this) {
				if (!closed) {
					action = release;
					return;
				}
			}
			release.run();
		}

		@Override
		public void streamClosed(Status status) {
			Runnable release;
			synchronized (this) {
				closed = true;
				release = action;
				action = null;
			}
			if (release != null) {
				release.run();
			}
		}
	}
}
