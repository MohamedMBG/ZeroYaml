package io.zeroyaml.controlplane.orchestration;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.job.JobId;
import io.zeroyaml.controlplane.domain.job.JobStatus;
import io.zeroyaml.controlplane.domain.job.RepositoryReference;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;
import io.zeroyaml.controlplane.domain.pipeline.PipelineId;
import io.zeroyaml.controlplane.execution.JobStore;
import io.zeroyaml.controlplane.execution.PipelineJobReference;
import io.zeroyaml.controlplane.runner.RunnerClient;
import io.zeroyaml.controlplane.runner.RunnerClientException;
import io.zeroyaml.controlplane.runner.JobRejectionReason;
import io.zeroyaml.controlplane.scheduling.RunnerSelectionOutcome;
import io.zeroyaml.controlplane.scheduling.RunnerSelector;

/**
 * Moves a pipeline forward one step at a time: the next step is dispatched only
 * after every earlier step succeeded, and the steps after a failed or cancelled
 * step are cancelled.
 *
 * <p>Every call reads the recorded state of all steps and acts on it, so calling
 * it again for the same pipeline, for example after a redelivered push, never
 * dispatches a step twice. Callers serialize calls, including retry scans.</p>
 *
 * <h2>Dispatch</h2>
 *
 * <p>A step is queued, a Runner is selected, and the Job is assigned to that
 * Runner in one {@link JobStore#update}, before {@code RunJob} is sent. The
 * Runner may report the start of execution before its acknowledgment arrives,
 * and the recorded assignment is what makes that report, and the Job's log
 * writes, attributable. The outcome of the dispatch is then handled as:</p>
 *
 * <ul>
 *   <li>accepted by the assigned Runner process: nothing more; status reports
 *       drive the Job from here;</li>
 *   <li>rejected as unavailable: clear the assignment so a later call can retry;</li>
 *   <li>accepted by another process, otherwise rejected, or refused with
 *       {@code INVALID_ARGUMENT} or {@code FAILED_PRECONDITION}: the Job is
 *       cancelled with the reason logged, and so are the later steps;</li>
 *   <li>any other transport failure: whether the Runner started the Job is
 *       unknown, so it stays queued and assigned. If the Runner did start it,
 *       its reports are still accepted.</li>
 * </ul>
 *
 * <p>When no Runner is eligible, the Job stays queued without an assignment.
 * The next call for the pipeline selects again.</p>
 */
@Component
class PipelineProgression {

	private static final Logger log = LoggerFactory.getLogger(PipelineProgression.class);

	private final JobStore jobs;
	private final RunnerSelector selector;
	private final RunnerClient runner;
	private final Clock clock;
	private JobId retryCursor;

	PipelineProgression(JobStore jobs, RunnerSelector selector, RunnerClient runner, Clock clock) {
		this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
		this.selector = Objects.requireNonNull(selector, "selector must not be null");
		this.runner = Objects.requireNonNull(runner, "runner must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	/** Reconciles a bounded page of queued Jobs, including after a process restart. */
	void retryQueued() {
		var pending = jobs.findQueuedUnassignedAfter(retryCursor);
		if (pending.isEmpty()) {
			retryCursor = null;
			return;
		}
		for (var id : pending) {
			try {
				advanceAfter(id);
			} catch (RuntimeException ex) {
				log.error("Could not retry queued job {}", id.value(), ex);
			} finally {
				retryCursor = id;
			}
		}
	}

	/** Advances the pipeline that the completed Job belongs to, if it belongs to one. */
	void advanceAfter(JobId completedJob) {
		Objects.requireNonNull(completedJob, "completedJob must not be null");
		var job = jobs.find(completedJob);
		if (job.isEmpty()) {
			return;
		}
		var source = job.get().repository();
		jobs.findPipelineJob(completedJob)
				.ifPresent(reference -> advance(reference.pipelineId(), source));
	}

	/** Dispatches the first step that has not run yet, or cancels the steps after a failed one. */
	void advance(PipelineId pipelineId, RepositoryReference source) {
		Objects.requireNonNull(pipelineId, "pipelineId must not be null");
		Objects.requireNonNull(source, "source must not be null");

		var statuses = jobs.findPipelineStepStatuses(pipelineId, source);
		var steps = statuses.stream().map(JobStore.PipelineStepStatus::reference).toList();
		for (var index = 0; index < steps.size(); index++) {
			var step = steps.get(index);
			var status = statuses.get(index).status();
			switch (status) {
				case SUCCEEDED -> {
					// Earlier step done; look at the next one.
				}
				case CREATED, QUEUED -> {
					if (!dispatch(step)) {
						cancel(steps.subList(index + 1, steps.size()), step);
					}
					return;
				}
				case RUNNING -> {
					return;
				}
				case FAILED, CANCELLED -> {
					cancel(steps.subList(index + 1, steps.size()), step);
					return;
				}
			}
		}
		if (!steps.isEmpty()) {
			log.info("Pipeline {} finished: all {} steps succeeded", pipelineId.value(), steps.size());
		}
	}

	/** Returns false when the step was abandoned, so the later steps cannot run either. */
	private boolean dispatch(PipelineJobReference step) {
		var claim = jobs.update(step.jobId(), job -> claim(job, step)).orElseThrow();
		if (!(claim instanceof Claim.Assigned assigned)) {
			return true;
		}

		var job = assigned.job();
		try {
			var result = runner.dispatchJob(job);
			if (!result.isAccepted()) {
				if (result.rejection().orElseThrow() == JobRejectionReason.RUNNER_UNAVAILABLE) {
					jobs.update(step.jobId(), current -> {
						if (current.status() == JobStatus.QUEUED
								&& current.runnerAssignment().filter(assigned.runner()::equals).isPresent()) {
							current.clearRejectedAssignment(assigned.runner());
						}
						return current.status();
					});
					return true;
				}
				return abandon(step, "runner rejected the dispatch: " + result.rejection().orElseThrow() + " (" + result.message() + ")");
			}
			if (!result.runnerAssignment().orElseThrow().equals(assigned.runner())) {
				// Only one Runner endpoint is configured. A different process answering
				// means the selected Runner is not the one behind it, and that process's
				// reports would be refused, so the Job cannot finish.
				var answered = result.runnerAssignment().orElseThrow();
				return abandon(step, "runner " + answered.runnerId() + " instance " + answered.instanceId()
						+ " accepted the job, but it was assigned to runner " + assigned.runner().runnerId()
						+ " instance " + assigned.runner().instanceId()
						+ "; the configured zeroyaml.runner endpoint must reach the registered runner");
			}
			log.info("Dispatched job {} (pipeline {} step {} '{}') to runner {} instance {}",
					job.id().value(), step.pipelineId().value(), step.position(), step.stepName(),
					assigned.runner().runnerId(), assigned.runner().instanceId());
			return true;
		}
		catch (RunnerClientException ex) {
			if (ex.getStatusCode() == Status.Code.INVALID_ARGUMENT || ex.getStatusCode() == Status.Code.FAILED_PRECONDITION) {
				return abandon(step, "runner refused the job with " + ex.getStatusCode()
						+ "; dispatching it again to this runner cannot succeed");
			}
			log.warn("Dispatch of job {} (pipeline {} step {}) to runner {} ended with {}; it stays queued and "
							+ "assigned, because the runner may have started it",
					job.id().value(), step.pipelineId().value(), step.position(), assigned.runner().runnerId(),
					ex.getStatusCode());
			return true;
		}
	}

	private Claim claim(Job job, PipelineJobReference step) {
		if (job.status() == JobStatus.CREATED) {
			job.queue(clock.instant());
		}
		if (job.status() != JobStatus.QUEUED || job.runnerAssignment().isPresent()) {
			return new Claim.Skipped();
		}
		return switch (selector.select(job)) {
			case RunnerSelectionOutcome.Selected selected -> {
				job.assign(selected.dispatch().runner());
				yield new Claim.Assigned(job, selected.dispatch().runner());
			}
			case RunnerSelectionOutcome.NoAvailableRunner blocked -> {
				log.warn("Job {} (pipeline {} step {}) stays queued: {}",
						job.id().value(), step.pipelineId().value(), step.position(), blocked.message());
				yield new Claim.Skipped();
			}
		};
	}

	private boolean abandon(PipelineJobReference step, String reason) {
		log.warn("Job {} (pipeline {} step {} '{}') is cancelled: {}",
				step.jobId().value(), step.pipelineId().value(), step.position(), step.stepName(), reason);
		jobs.update(step.jobId(), job -> {
			if (job.status() == JobStatus.QUEUED) {
				job.cancel(clock.instant());
			}
			return job.status();
		});
		return false;
	}

	private void cancel(List<PipelineJobReference> later, PipelineJobReference failedStep) {
		for (var step : later) {
			var cancelled = jobs.update(step.jobId(), job -> {
				if (job.status() != JobStatus.CREATED) {
					return false;
				}
				job.cancel(clock.instant());
				return true;
			}).orElse(false);
			if (cancelled) {
				log.info("Job {} (pipeline {} step {} '{}') is cancelled because step {} '{}' did not succeed",
						step.jobId().value(), step.pipelineId().value(), step.position(), step.stepName(),
						failedStep.position(), failedStep.stepName());
			}
		}
	}

	private sealed interface Claim {

		record Assigned(Job job, RunnerAssignment runner) implements Claim { }

		record Skipped() implements Claim { }
	}
}
