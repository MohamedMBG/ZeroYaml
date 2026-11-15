package io.zeroyaml.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ExecutionCleanupTest {

	@Test
	void selectsOnlyValidExecutionStartRecords() {
		var output = """
				time=2026-01-01T00:00:00Z level=INFO msg="job execution starting" execution_id=0123456789ab
				time=2026-01-01T00:00:01Z level=INFO msg="job execution finished" execution_id=abcdef012345
				time=2026-01-01T00:00:02Z level=INFO msg="job execution starting" execution_id=not-an-id
				time=2026-01-01T00:00:03Z level=INFO msg="job execution starting" execution_id=0123456789ab
				""";
		assertEquals(List.of("0123456789ab"), FirstPipelineEndToEndTest.executionIds(output));
		assertEquals(List.of(), FirstPipelineEndToEndTest.executionIds(""));
	}
}
