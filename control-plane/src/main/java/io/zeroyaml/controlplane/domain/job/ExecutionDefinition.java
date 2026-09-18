package io.zeroyaml.controlplane.domain.job;

import java.util.List;
import java.util.Objects;

/**
 * Concrete command information for one Job. This is intentionally not a
 * workflow language or a pipeline-definition model.
 *
 * @param command ordered executable arguments; the first item is the executable
 * @param workingDirectory directory in which the command must run
 */
public record ExecutionDefinition(List<String> command, String workingDirectory) {

	public ExecutionDefinition {
		Objects.requireNonNull(command, "command must not be null");
		if (command.isEmpty()) {
			throw new IllegalArgumentException("command must contain at least one argument");
		}
		if (command.stream().anyMatch(argument -> argument == null || argument.isBlank())) {
			throw new IllegalArgumentException("command arguments must not be blank");
		}
		command = List.copyOf(command);
		workingDirectory = requireNonBlank(workingDirectory, "workingDirectory");
	}

	private static String requireNonBlank(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}
}
