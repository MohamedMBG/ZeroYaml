package io.zeroyaml.controlplane.runner;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Static execution capabilities reported by a Runner.
 */
public record RunnerCapabilities(
		String operatingSystem,
		String architecture,
		boolean dockerAvailable,
		List<String> supportedExecutors,
		Map<String, String> labels
) {

	public RunnerCapabilities {
		Objects.requireNonNull(operatingSystem, "operatingSystem must not be null");
		Objects.requireNonNull(architecture, "architecture must not be null");
		supportedExecutors = List.copyOf(Objects.requireNonNull(supportedExecutors, "supportedExecutors must not be null"));
		labels = Map.copyOf(Objects.requireNonNull(labels, "labels must not be null"));
	}
}
