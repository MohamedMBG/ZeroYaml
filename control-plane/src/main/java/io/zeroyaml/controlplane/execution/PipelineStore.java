package io.zeroyaml.controlplane.execution;

import io.zeroyaml.controlplane.domain.pipeline.Pipeline;

/**
 * The Control Plane's record of the pipelines it created Jobs for.
 *
 * <p>A pipeline identity is derived from the repository location and revision,
 * not from the plan, so the same identity can describe two different plans, for example
 * after an inference rule changed. Recording binds each identity to exactly one
 * plan before any of its Jobs are recorded, which is what keeps Jobs of two
 * plans from mixing under one identity. Durable persistence is tracked as its
 * own work; the current implementation keeps pipelines in memory.</p>
 */
public interface PipelineStore {

	/**
	 * Records {@code pipeline} unless a pipeline with the same identity is already
	 * recorded. The check and the insert are one atomic step, so concurrent callers
	 * recording plans under the same identity agree on a single winner.
	 *
	 * @param pipeline pipeline to record
	 * @throws PipelineStoreFullException when the store cannot hold another pipeline
	 * @return the recorded pipeline: equal to {@code pipeline} when this call recorded it or an
	 *         equal plan was recorded before, otherwise the different plan already bound to its identity
	 */
	Pipeline recordIfAbsent(Pipeline pipeline);
}
