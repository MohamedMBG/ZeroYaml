package io.zeroyaml.controlplane.domain.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import io.zeroyaml.controlplane.domain.job.ExecutionDefinition;

/**
 * One executable step of a {@link Pipeline}.
 *
 * <p>A step carries exactly what one Runner execution needs, the command and the
 * directory it runs in, plus the names of the steps whose Jobs must succeed
 * before its Job may start. It is intentionally not a general workflow step, so
 * it has no conditions, matrices, or secrets. Because the execution part is the
 * Job model's {@link ExecutionDefinition}, a step maps onto one Job without
 * translation.</p>
 *
 * <p>A step checks only that its dependency names are well-formed and distinct.
 * That every dependency names an earlier step of the same pipeline is checked
 * by {@link Pipeline}.</p>
 *
 * @param name identity of the step within its pipeline: 1 to 64 characters, lowercase letters,
 *        digits, {@code '.'}, {@code '_'} and {@code '-'}, starting and ending with a letter or digit;
 *        the value is never normalized, so two spellings are two different steps
 * @param execution concrete command and working directory
 * @param dependsOn names of the steps that must succeed first, in the supplied order; copied
 *        defensively and possibly empty
 */
public record PipelineStep(String name, ExecutionDefinition execution, List<String> dependsOn) {

	private static final int MAX_NAME_LENGTH = 64;
	private static final Pattern NAME_FORMAT = Pattern.compile("[a-z0-9]([a-z0-9._-]*[a-z0-9])?");

	public PipelineStep {
		name = requireValidName(name);
		Objects.requireNonNull(execution, "execution must not be null");
		Objects.requireNonNull(dependsOn, "dependsOn must not be null");
		// copyOf also rejects a null entry.
		dependsOn = List.copyOf(dependsOn);
		var seen = new HashSet<String>();
		for (var dependency : dependsOn) {
			requireValidName(dependency);
			if (dependency.equals(name)) {
				throw new IllegalArgumentException("step must not depend on itself: " + name);
			}
			if (!seen.add(dependency)) {
				throw new IllegalArgumentException("step " + name + " lists dependency more than once: " + dependency);
			}
		}
	}

	/**
	 * Creates a step that depends on no other step.
	 *
	 * @param name identity of the step within its pipeline
	 * @param execution concrete command and working directory
	 */
	public PipelineStep(String name, ExecutionDefinition execution) {
		this(name, execution, List.of());
	}

	private static String requireValidName(String name) {
		if (name == null || name.length() > MAX_NAME_LENGTH || !NAME_FORMAT.matcher(name).matches()) {
			throw new IllegalArgumentException(
					"name must be 1 to " + MAX_NAME_LENGTH + " lowercase letters, digits, '.', '_' or '-', "
							+ "starting and ending with a letter or digit");
		}
		return name;
	}
}
