package io.zeroyaml.controlplane.domain.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BranchNameTest {

	@ParameterizedTest
	@ValueSource(strings = {"main", "Main", "release/1.0", "feat/issue-18_model", "v1.2.3"})
	void keepsValidBranchNamesExactly(String value) {
		assertEquals(value, new BranchName(value).value());
		assertEquals(value, new BranchName(value).toString());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "has space", "tab\tname", "-main", ".hidden", "/main", "main/", "main.",
			"main.lock", "refs/heads/main", "a..b", "a//b", "a/.b", "a@{1}", "@", "a~1", "a^", "a:b", "a?",
			"a*", "a[b", "a\\b", "a\u007Fb"})
	void rejectsInvalidBranchNames(String value) {
		assertThrows(IllegalArgumentException.class, () -> new BranchName(value));
	}

	@Test
	void enforcesMaximumLength() {
		new BranchName("b".repeat(BranchName.MAX_LENGTH));

		assertThrows(IllegalArgumentException.class, () -> new BranchName("b".repeat(BranchName.MAX_LENGTH + 1)));
	}
}
