package io.zeroyaml.controlplane.orchestration;

import java.time.Clock;
import java.util.EnumMap;
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
import io.zeroyaml.controlplane.domain.pipeline.PipelineSchedule;
import io.zeroyaml.controlplane.domain.pipeline.PipelineSchedule.StepDecision;
import io.zeroyaml.controlplane.execution.JobStore;
import io.zeroyaml.controlplane.execution.PipelineJobReference;
import io.zeroyaml.controlplane.runner.RunnerClient;
import io.zeroyaml.controlplane.runner.RunnerClientException;
import io.zeroyaml.controlplane.runner.JobRejectionReason;
import io.zeroyaml.controlplane.scheduling.RunnerSelectionOutcome;
import io.zeroyaml.controlplane.scheduling.RunnerSelector;

/**
 * Moves a pipeline forward in dependency order: a step is dispatched only after
 * every step it depends on succeeded, and a step whose dependency ended without
 * success is recorded as {@link JobStatus#SKIPPED}.
 *
 * <p>Every call reads the recorded state and dependencies of all steps and acts
 * on the {@link PipelineSchedule} computed from them, so calling it again for
 * the same pipeline, for example after a redelivered push or a restart, never
 * dispatches a step twice. Callers serialize calls, including retry scans.</p>
 *
 * <h2>Scheduling</h2>
 *
 * <p>Within one call, every step that is ready is dispatched, in step order, so
 * independent steps start together and the order of dispatch is the same every
 * time. Skips are recorded first, in step order. A step that waits for a
 * dependency is left untouched; the completion of that dependency calls this
 * class again. A failed, cancelled, or skipped step affects only the steps that
 * depend on it, directly or transitively; independent steps keep running.</p>
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
 *       cancelled with the reason logged, and the steps that depend on it are
 *       skipped;</li>
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

	/**
	 * Records the skips and dispatches the ready steps of one pipeline run.
	 *
	 * <p>An abandoned dispatch cancels its Job, which can make further steps
	 * skippable, so the schedule is computed again until a pass abandons nothing.
	 * An abandoned Job is cancelled, or has already left the queue through a
	 * concurrent status report; either way it is never dispatched again, which
	 * bounds the passes by the number of steps.</p>
	 */
	void advance(PipelineId pipelineId, RepositoryReference source) {
		Objects.requireNonNull(pipelineId, "pipelineId must not be null");
		Objects.requireNonNull(source, "source must not be null");

		var abandoned = true;
		while (abandoned) {
			abandoned = advanceOnce(pipelineId, source);
		}
	}

	/** Returns true when a dispatch was abandoned, so the schedule must be computed again. */
	private boolean advanceOnce(PipelineId pipelineId, RepositoryReference source) {
		var steps = jobs.findPipelineStepStatuses(pipelineId, source);
		if (steps.isEmpty()) {
			return false;
		}
		var schedule = PipelineSchedule.of(steps.stream()
				.map(step -> new PipelineSchedule.StepState(step.reference().stepName(), step.dependsOn(), step.status()))
				.toList());
		var decisions = schedule.decisions();

		for (var index = 0; index < steps.size(); index++) {
			if (decisions.get(index) instanceof StepDecision.Skip skip) {
				skip(steps.get(index).reference(), skip.unsuccessfulDependencies());
			}
		}
		var abandoned = false;
		for (var index = 0; index < steps.size(); index++) {
			var step = steps.get(index).reference();
			switch (decisions.get(index)) {
				case StepDecision.Ready ready -> abandoned |= !dispatch(step);
				case StepDecision.Blocked blocked -> log.debug(
						"Job {} (pipeline {} step {} '{}') waits for {}", step.jobId().value(),
						step.pipelineId().value(), step.position(), step.stepName(), blocked.waitingOn());
				case StepDecision.Skip skip -> {
					// Recorded above, before any dispatch.
				}
				case StepDecision.Running running -> {
					// Its completion calls this class again.
				}
				case StepDecision.Settled settled -> {
					// Nothing left to do for this step.
				}
			}
		}
		if (!abandoned && schedule.isComplete()) {
			logFinished(pipelineId, steps, decisions);
		}
		return abandoned;
	}

	/** Returns false when the step was abandoned, so the steps that depend on it cannot run either. */
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
			return new Claim.NotAssigned();
		}
		return switch (selector.select(job)) {
			case RunnerSelectionOutcome.Selected selected -> {
				job.assign(selected.dispatch().runner());
				yield new Claim.Assigned(job, selected.dispatch().runner());
			}
			case RunnerSelectionOutcome.NoAvailableRunner blocked -> {
				log.warn("Job {} (pipeline {} step {}) stays queued: {}",
						job.id().value(), step.pipelineId().value(), step.position(), blocked.message());
				yield new Claim.NotAssigned();
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

	private void skip(PipelineJobReference step, List<String> unsuccessfulDependencies) {
		var skipped = jobs.update(step.jobId(), job -> {
			if (job.status() != JobStatus.CREATED) {
				return false;
			}
			job.skip(clock.instant());
			return true;
		}).orElse(false);
		if (skipped) {
			log.info("Job {} (pipeline {} step {} '{}') is skipped because its dependencies {} did not succeed",
					step.jobId().value(), step.pipelineId().value(), step.position(), step.stepName(),
					unsuccessfulDependencies);
		}
	}

	private static void logFinished(PipelineId pipelineId, List<JobStore.PipelineStepStatus> steps,
			List<StepDecision> decisions) {
		var counts = new EnumMap<JobStatus, Integer>(JobStatus.class);
		for (var index = 0; index < steps.size(); index++) {
			// A step decided as skipped in this pass is recorded as skipped already.
			var status = decisions.get(index) instanceof StepDecision.Skip ? JobStatus.SKIPPED : steps.get(index).status();
			counts.merge(status, 1, Integer::sum);
		}
		log.info("Pipeline {} finished: {} steps, {} succeeded, {} failed, {} cancelled, {} skipped",
				pipelineId.value(), steps.size(), counts.getOrDefault(JobStatus.SUCCEEDED, 0),
				counts.getOrDefault(JobStatus.FAILED, 0), counts.getOrDefault(JobStatus.CANCELLED, 0),
				counts.getOrDefault(JobStatus.SKIPPED, 0));
	}

	private sealed interface Claim {

		record Assigned(Job job, RunnerAssignment runner) implements Claim { }

		record NotAssigned() implements Claim { }
	}
}
