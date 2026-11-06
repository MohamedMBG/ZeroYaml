package io.zeroyaml.controlplane.orchestration;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import io.zeroyaml.controlplane.connection.RepositoryConnectionLookup;
import io.zeroyaml.controlplane.domain.event.RepositoryPushEvent;
import io.zeroyaml.controlplane.domain.job.JobId;
import io.zeroyaml.controlplane.domain.job.RepositoryReference;
import io.zeroyaml.controlplane.domain.pipeline.inference.PipelineInference;
import io.zeroyaml.controlplane.domain.pipeline.inference.PipelineInferrer;
import io.zeroyaml.controlplane.domain.pipeline.inference.RepositorySnapshot;
import io.zeroyaml.controlplane.execution.JobCompletionListener;
import io.zeroyaml.controlplane.execution.PipelineJobCreationService;

/**
 * Joins a verified push to pipeline execution: connected repository, root file
 * listing, inference, Job creation, and dispatch of the first step. A committed
 * terminal Job status dispatches the next step.
 *
 * <p>All of that work runs on one worker thread with a bounded queue. The
 * webhook answers without waiting for GitHub or the Runner, a status report
 * never waits for the next dispatch, and pushes and step completions for the
 * same pipeline cannot interleave. When the queue is full, a push is refused so
 * the webhook can answer {@code 503}; a lost step completion is logged, and a
 * redelivery of the push resumes the pipeline, because every step is
 * reconciled against its recorded state.</p>
 */
@Component
class PipelineOrchestrator implements RepositoryPushEventHandler, JobCompletionListener {

	static final int QUEUE_CAPACITY = 64;

	private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

	private final RepositoryConnectionLookup connections;
	private final RepositorySource source;
	private final PipelineJobCreationService jobCreation;
	private final PipelineProgression progression;
	private final ExecutorService worker;
	private final PipelineInferrer inferrer = new PipelineInferrer();
	private final AtomicBoolean retryQueued = new AtomicBoolean();
	private final LinkedHashMap<String, RateLimitedPush> rateLimitedPushes = new LinkedHashMap<>();
	private final Clock clock;

	@Autowired
	PipelineOrchestrator(
			RepositoryConnectionLookup connections,
			RepositorySource source,
			PipelineJobCreationService jobCreation,
			PipelineProgression progression
	) {
		this(connections, source, jobCreation, progression, newWorker(), Clock.systemUTC());
	}

	@Scheduled(fixedDelay = 5000)
	void retryQueued() {
		if (!retryQueued.compareAndSet(false, true)) {
			return;
		}
		try {
			worker.execute(() -> {
				try {
					runLogged("queued job retry", () -> { retryRateLimited(); progression.retryQueued(); });
				} finally {
					retryQueued.set(false);
				}
			});
		} catch (RejectedExecutionException ex) {
			retryQueued.set(false);
		}
	}

	PipelineOrchestrator(
			RepositoryConnectionLookup connections,
			RepositorySource source,
			PipelineJobCreationService jobCreation,
			PipelineProgression progression,
			ExecutorService worker
	) {
		this(connections, source, jobCreation, progression, worker, Clock.systemUTC());
	}

	PipelineOrchestrator(RepositoryConnectionLookup connections, RepositorySource source,
			PipelineJobCreationService jobCreation, PipelineProgression progression, ExecutorService worker, Clock clock) {
		this.connections = Objects.requireNonNull(connections, "connections must not be null");
		this.source = Objects.requireNonNull(source, "source must not be null");
		this.jobCreation = Objects.requireNonNull(jobCreation, "jobCreation must not be null");
		this.progression = Objects.requireNonNull(progression, "progression must not be null");
		this.worker = Objects.requireNonNull(worker, "worker must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	@Override
	public void handle(RepositoryPushEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		worker.execute(() -> runLogged("push delivery " + event.deliveryId(), () -> process(event, 0)));
	}

	@Override
	public void jobCompleted(JobId jobId) {
		Objects.requireNonNull(jobId, "jobId must not be null");
		try {
			worker.execute(() -> runLogged("completion of job " + jobId.value(), () -> progression.advanceAfter(jobId)));
		}
		catch (RejectedExecutionException ex) {
			log.error("The next step after job {} was not dispatched because pipeline work is {}; "
					+ "redeliver the push to resume the pipeline", jobId.value(),
					worker.isShutdown() ? "shutting down" : "at capacity");
		}
	}

	@PreDestroy
	void shutdown() throws InterruptedException {
		worker.shutdown();
		if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
			log.warn("Pipeline work did not stop within 10s of shutdown");
		}
	}

	private void retryRateLimited() {
		var due = rateLimitedPushes.values().stream().filter(push -> !push.retryAt().isAfter(clock.instant())).toList();
		for (var push : due) {
			rateLimitedPushes.remove(push.event().deliveryId());
			process(push.event(), push.attempt());
		}
	}

	private void process(RepositoryPushEvent event, int attempt) {
		var repository = event.repository();
		var connection = connections.find(repository);
		if (connection.isEmpty()) {
			log.info("Push delivery {} for {} is ignored: the repository is not connected",
					event.deliveryId(), repository.fullName());
			return;
		}
		if (!connection.get().status().acceptsEvents()) {
			log.info("Push delivery {} for {} is ignored: the connection is {}",
					event.deliveryId(), repository.fullName(), connection.get().status());
			return;
		}

		var reference = new RepositoryReference(source.location(repository), event.commitSha().value());
		final RepositorySnapshot snapshot;
		try {
			snapshot = source.rootSnapshot(repository, event.commitSha());
		}
		catch (RepositoryRateLimitedException ex) {
			if (attempt < 2 && ex.retryAfter().compareTo(Duration.ofHours(12)) <= 0
					&& (rateLimitedPushes.containsKey(event.deliveryId()) || rateLimitedPushes.size() < QUEUE_CAPACITY)) {
				var delay = ex.retryAfter().multipliedBy(1L << attempt);
				rateLimitedPushes.put(event.deliveryId(), new RateLimitedPush(event, attempt + 1, clock.instant().plus(delay)));
				log.warn("Push delivery {} was rate limited; a retry is scheduled", event.deliveryId());
			} else {
				log.warn("Push delivery {} was rate limited and cannot be retried automatically; redeliver it later", event.deliveryId());
			}
			return;
		}
		catch (RepositorySnapshotUnavailableException ex) {
			log.warn("No pipeline for push delivery {} of {} at {}: {}",
					event.deliveryId(), repository.fullName(), event.commitSha().value(), ex.getMessage());
			return;
		}

		switch (inferrer.infer(reference, snapshot)) {
			case PipelineInference.Inferred inferred -> {
				var pipeline = inferred.pipeline();
				var creation = jobCreation.createJobs(pipeline);
				log.info("Push delivery {} of {} at {} maps to pipeline {} with {} steps",
						event.deliveryId(), repository.fullName(), event.commitSha().value(),
						pipeline.id().value(), creation.jobs().size());
				progression.advance(pipeline.id(), pipeline.source());
			}
			case PipelineInference.Unsupported unsupported -> log.info(
					"No pipeline for push delivery {} of {} at {}: {}",
					event.deliveryId(), repository.fullName(), event.commitSha().value(), unsupported.reason());
			case PipelineInference.Ambiguous ambiguous -> log.info(
					"No pipeline for push delivery {} of {} at {}: several build files are present {}",
					event.deliveryId(), repository.fullName(), event.commitSha().value(), ambiguous.candidates());
		}
	}

	private record RateLimitedPush(RepositoryPushEvent event, int attempt, Instant retryAt) { }

	private static void runLogged(String work, Runnable task) {
		try {
			task.run();
		}
		catch (RuntimeException ex) {
			// The worker must survive one failed task; the failure is reported, not hidden.
			log.error("Pipeline work for {} failed", work, ex);
		}
	}

	private static ExecutorService newWorker() {
		return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
				runnable -> {
					var thread = new Thread(runnable, "pipeline-orchestrator");
					thread.setDaemon(true);
					return thread;
				},
				new ThreadPoolExecutor.AbortPolicy());
	}
}
