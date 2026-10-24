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
 * reuse the identity of Jobs that still exist, so the bound is the process
 * lifetime until durable persistence replaces this store.</p>
 */
@Component
class InMemoryPipelineStore implements PipelineStore {

	private final ConcurrentHashMap<PipelineId, Pipeline> pipelinesById = new ConcurrentHashMap<>();

	@Override
	public Pipeline recordIfAbsent(Pipeline pipeline) {
		Objects.requireNonNull(pipeline, "pipeline must not be null");

		var existing = pipelinesById.putIfAbsent(pipeline.id(), pipeline);
		return existing == null ? pipeline : existing;
	}
}
