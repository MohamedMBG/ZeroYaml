package io.zeroyaml.controlplane.execution;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.zeroyaml.controlplane.domain.pipeline.Pipeline;
import io.zeroyaml.controlplane.domain.pipeline.PipelineId;

/**
 * Process-local pipeline record used while durable persistence is still out of
 * scope. Pipelines are lost on restart, like the Jobs in {@link InMemoryJobStore}.
 *
 * <p>Growth: one entry per distinct pipeline, never evicted until restart, and
 * each pipeline holds at most 50 steps. Evicting an entry would let a changed plan
 * reuse the identity of Jobs that still exist, so the store refuses new
 * pipelines beyond {@value #MAX_PIPELINES} and fails loudly instead of
 * exhausting the heap. Together with the step limit this bounds the Jobs
 * recorded through pipeline creation until durable persistence replaces this
 * store.</p>
 */
@Component
class InMemoryPipelineStore implements PipelineStore {

	static final int MAX_PIPELINES = 10_000;

	private final ConcurrentHashMap<PipelineId, Pipeline> pipelinesById = new ConcurrentHashMap<>();

	/**
	 * Synchronized so the size check and the insert are one step; contention is
	 * acceptable because this runs once per webhook delivery.
	 *
	 * @throws IllegalStateException when {@value #MAX_PIPELINES} pipelines are already recorded
	 */
	@Override
	public synchronized Pipeline recordIfAbsent(Pipeline pipeline) {
		Objects.requireNonNull(pipeline, "pipeline must not be null");

		var existing = pipelinesById.get(pipeline.id());
		if (existing != null) {
			return existing;
		}
		if (pipelinesById.size() >= MAX_PIPELINES) {
			throw new IllegalStateException("Pipeline store is full at " + MAX_PIPELINES + " pipelines");
		}
		pipelinesById.put(pipeline.id(), pipeline);
		return pipeline;
	}
}
