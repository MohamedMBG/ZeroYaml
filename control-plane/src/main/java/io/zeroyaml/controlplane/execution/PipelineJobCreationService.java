package io.zeroyaml.controlplane.execution;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.pipeline.Pipeline;
import io.zeroyaml.controlplane.domain.pipeline.PipelineJob;
import io.zeroyaml.controlplane.domain.pipeline.PipelineJobTranslator;

/**
 * Creates and records the executable Jobs for an inferred pipeline.
 *
 * <p>This is the orchestration seam between a decided plan and execution. The
 * domain {@link PipelineJobTranslator} builds one Job per step; this service
 * records them in the {@link JobStore}. It neither selects a Runner nor
 * dispatches anything, and the Jobs stay in
 * {@link io.zeroyaml.controlplane.domain.job.JobStatus#CREATED}.</p>
 *
 * <p>Plan binding: before any Job is recorded, the pipeline is claimed through
 * {@link PipelineStore#recordIfAbsent}, which atomically binds its identity to
 * exactly one plan. A pipeline whose identity is already bound to a different
 * plan (another repository, other steps, or other commands) is refused with a
 * {@link PipelineJobConflictException} before anything is recorded, also when
 * the two plans race. Jobs of two plans therefore never mix under one identity,
 * and a step dropped from a later plan cannot leave a recorded Job outside the
 * returned list.</p>
 *
 * <p>Idempotency: Job identities are derived from the pipeline identity and the
 * step name, and the pipeline identity from the repository revision, so a
 * repeated webhook delivery or an inference retry for the same revision maps to
 * Jobs that are already recorded. Those are returned as recorded and never
 * duplicated or reset, whatever state they have reached. Each Job is recorded
 * through {@link JobStore#recordIfAbsent}, so concurrent calls for the same plan
 * record each Job exactly once, and a call that finds only some Jobs recorded,
 * for example after an earlier call failed part-way, records the missing ones.</p>
 */
@Service
public class PipelineJobCreationService {

	private static final Logger log = LoggerFactory.getLogger(PipelineJobCreationService.class);

	private final PipelineStore pipelines;
	private final JobStore jobs;
	private final Clock clock;
	private final PipelineJobTranslator translator = new PipelineJobTranslator();

	@Autowired
	PipelineJobCreationService(PipelineStore pipelines, JobStore jobs) {
		this(pipelines, jobs, Clock.systemUTC());
	}

	/**
	 * Accepts an explicit clock so tests can control creation times. The
	 * registration package already exposes a {@link Clock} bean, so a second one
	 * is not registered here; that would make injection by type ambiguous.
	 */
	PipelineJobCreationService(PipelineStore pipelines, JobStore jobs, Clock clock) {
		this.pipelines = Objects.requireNonNull(pipelines, "pipelines must not be null");
		this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	/**
	 * Records one Job per step of {@code pipeline}, skipping Jobs that are
	 * already recorded.
	 *
	 * @param pipeline validated pipeline to execute
	 * @return the recorded Job of every step, in pipeline order, and how many were new
	 * @throws PipelineJobConflictException when the pipeline identity is bound to a different
	 *         plan; nothing is recorded in that case
	 */
	public PipelineJobCreation createJobs(Pipeline pipeline) {
		Objects.requireNonNull(pipeline, "pipeline must not be null");

		if (!pipelines.recordIfAbsent(pipeline).equals(pipeline)) {
			throw new PipelineJobConflictException(pipeline.id());
		}

		var planned = translator.translate(pipeline, clock.instant());
		var recorded = new ArrayList<PipelineJob>(planned.size());
		var createdCount = 0;
		for (var pipelineJob : planned) {
			var job = jobs.recordIfAbsent(pipelineJob.job());
			if (job == pipelineJob.job()) {
				createdCount++;
			} else {
				requireSameDefinition(job, pipelineJob.job());
			}
			recorded.add(new PipelineJob(pipelineJob.pipelineId(), pipelineJob.position(), pipelineJob.stepName(), job));
		}

		var creation = new PipelineJobCreation(pipeline.id(), recorded, createdCount);
		logCreation(pipeline, creation);
		return creation;
	}

	/**
	 * The plan is bound to its pipeline identity before this runs, so a recorded
	 * Job with a derived identity but another definition can only have been
	 * recorded outside pipeline Job creation. That is a broken invariant, not a
	 * conflicting request.
	 */
	private static void requireSameDefinition(Job recorded, Job expected) {
		if (!recorded.repository().equals(expected.repository()) || !recorded.execution().equals(expected.execution())) {
			throw new IllegalStateException(
					"Job " + expected.id().value() + " is recorded with a definition that its pipeline step does not have");
		}
	}

	/**
	 * Records one audit line per call. The repository location is left out
	 * because it can embed credentials; the revision and identities are enough
	 * to correlate the Jobs with the push that produced them.
	 */
	private static void logCreation(Pipeline pipeline, PipelineJobCreation creation) {
		if (creation.alreadyRecorded()) {
			log.info("Pipeline {} at revision {}: all {} Jobs were already recorded",
					pipeline.id().value(), pipeline.source().revision(), creation.jobs().size());
		} else {
			log.info("Pipeline {} at revision {}: recorded {} of {} Jobs",
					pipeline.id().value(), pipeline.source().revision(), creation.createdCount(),
					creation.jobs().size());
		}
	}
}
