package io.zeroyaml.controlplane.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.zeroyaml.controlplane.domain.pipeline.Pipeline;
import io.zeroyaml.controlplane.domain.pipeline.PipelineId;

/**
 * Process-local pipeline record used while durable persistence is still out of
 * scope. Pipelines are lost on restart, like the Jobs in {@link InMemoryJobStore}.
 *
 * <p>Growth: the store keeps the {@value #MAX_PIPELINES} most recently bound
 * pipelines and evicts the oldest beyond that, so a burst of pushes can never
 * stop Job creation. An evicted pipeline can be bound again; the Jobs already
 * recorded for it are still checked against the plan by
 * {@link PipelineJobCreationService}, so a changed plan cannot reuse a Job
 * identity with another definition. Only a step dropped from the changed plan
 * can then stay recorded outside the returned list. Durable persistence
 * replaces this store.</p>
 */
@Component
class InMemoryPipelineStore implements PipelineStore {

	static final int MAX_PIPELINES = 10_000;

	// Insertion order is eviction order; reads do not re-order entries.
	private final Map<PipelineId, Pipeline> pipelinesById = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<PipelineId, Pipeline> eldest) {
			return size() > MAX_PIPELINES;
		}
	};

	/**
	 * Synchronized so the lookup and the insert are one step; contention is
	 * acceptable because this runs once per webhook delivery.
	 */
	@Override
	public synchronized Pipeline recordIfAbsent(Pipeline pipeline) {
		Objects.requireNonNull(pipeline, "pipeline must not be null");

		return pipelinesById.computeIfAbsent(pipeline.id(), id -> pipeline);
	}
}
