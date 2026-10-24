package io.zeroyaml.controlplane.execution;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

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
 * <p>Idempotency: Job identities are derived from the pipeline identity and the
 * step name, and the pipeline identity from the repository revision, so a
 * repeated webhook delivery or an inference retry for the same revision maps to
 * Jobs that are already recorded. Those are returned as recorded and never
 * duplicated or reset, whatever state they have reached. Recording uses
 * {@link JobStore#addIfAbsent}, so concurrent calls for the same pipeline record
 * each Job exactly once. A call that finds only some Jobs recorded, for example
 * after an earlier call failed part-way, records the missing ones.</p>
 *
 * <p>A recorded Job whose repository or execution differs from the step it is
 * derived from is a {@link PipelineJobConflictException}. All steps are checked
 * before any Job is recorded, so a conflicting pipeline leaves the store
 * unchanged.</p>
 */
@Service
public class PipelineJobCreationService {

	private static final Logger log = LoggerFactory.getLogger(PipelineJobCreationService.class);

	private final JobStore jobs;
	private final Clock clock;
	private final PipelineJobTranslator translator = new PipelineJobTranslator();

	@Autowired
	PipelineJobCreationService(JobStore jobs) {
		this(jobs, Clock.systemUTC());
	}

	/**
	 * Accepts an explicit clock so tests can control creation times. The
	 * registration package already exposes a {@link Clock} bean, so a second one
	 * is not registered here; that would make injection by type ambiguous.
	 */
	PipelineJobCreationService(JobStore jobs, Clock clock) {
		this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	/**
	 * Records one Job per step of {@code pipeline}, skipping Jobs that are
	 * already recorded.
	 *
	 * @param pipeline validated pipeline to execute
	 * @return the recorded Job of every step, in pipeline order, and how many were new
	 * @throws PipelineJobConflictException when a step's Job identity is recorded with a different
	 *         definition; nothing is recorded in that case
	 */
	public PipelineJobCreation createJobs(Pipeline pipeline) {
		Objects.requireNonNull(pipeline, "pipeline must not be null");

		var planned = translator.translate(pipeline, clock.instant());
		// Check every step first so a conflict cannot leave the pipeline partially recorded.
		planned.forEach(this::findRecorded);

		var recorded = new ArrayList<PipelineJob>(planned.size());
		var createdCount = 0;
		for (var pipelineJob : planned) {
			if (jobs.addIfAbsent(pipelineJob.job())) {
				recorded.add(pipelineJob);
				createdCount++;
			} else {
				// A concurrent call may have recorded it after the check above, so look it up again.
				var existing = findRecorded(pipelineJob).orElseThrow(() -> new IllegalStateException(
						"Job " + pipelineJob.job().id().value() + " was reported as recorded but cannot be found"));
				recorded.add(new PipelineJob(
						pipelineJob.pipelineId(), pipelineJob.position(), pipelineJob.stepName(), existing));
			}
		}

		var creation = new PipelineJobCreation(pipeline.id(), recorded, createdCount);
		logCreation(pipeline, creation);
		return creation;
	}

	/**
	 * Returns the recorded Job for {@code planned}, or empty when none is
	 * recorded yet.
	 *
	 * @throws PipelineJobConflictException when the recorded Job runs something else
	 */
	private Optional<Job> findRecorded(PipelineJob planned) {
		var expected = planned.job();
		var existing = jobs.find(expected.id());
		if (existing.isPresent() && !sameDefinition(existing.get(), expected)) {
			throw new PipelineJobConflictException(expected.id(), planned.stepName());
		}
		return existing;
	}

	private static boolean sameDefinition(Job recorded, Job expected) {
		return recorded.repository().equals(expected.repository())
				&& recorded.execution().equals(expected.execution());
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
