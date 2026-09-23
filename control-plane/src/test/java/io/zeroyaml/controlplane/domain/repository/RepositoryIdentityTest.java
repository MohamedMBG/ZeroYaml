package io.zeroyaml.controlplane.domain.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryIdentityTest {

	@Test
	void createsCanonicalGitHubIdentity() {
		var identity = RepositoryIdentity.github("MohamedMBG", "ZeroYaml");

		assertEquals(RepositoryProvider.GITHUB, identity.provider());
		assertEquals("mohamedmbg", identity.owner());
		assertEquals("zeroyaml", identity.name());
		assertEquals("mohamedmbg/zeroyaml", identity.fullName());
	}

	@Test
	void treatsIdentitiesDifferingOnlyByCaseAsDuplicates() {
		var first = RepositoryIdentity.github("MohamedMBG", "ZeroYaml");
		var second = RepositoryIdentity.github("mohamedmbg", "ZEROYAML");

		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
	}

	@Test
	void distinguishesDifferentRepositories() {
		var identity = RepositoryIdentity.github("octo-org", "service");

		assertNotEquals(identity, RepositoryIdentity.github("octo-org", "service-2"));
		assertNotEquals(identity, RepositoryIdentity.github("other-org", "service"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "octo-org", "user123", "a1-b2-c3", "abcdefghijklmnopqrstuvwxyz0123456789abc"})
	void acceptsValidOwners(String owner) {
		assertEquals(owner.toLowerCase(), RepositoryIdentity.github(owner, "repo").owner());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "-octo", "octo-", "octo--org", "octo_org", "octo.org", "octo/org",
			"abcdefghijklmnopqrstuvwxyz0123456789abcd"})
	void rejectsInvalidOwners(String owner) {
		assertThrows(IllegalArgumentException.class, () -> RepositoryIdentity.github(owner, "repo"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"repo", "my.repo", "my_repo", "my-repo", ".github", "a"})
	void acceptsValidNames(String name) {
		assertEquals(name, RepositoryIdentity.github("octo", name).name());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", ".", "..", "my repo", "my/repo", "repo?", "répo"})
	void rejectsInvalidNames(String name) {
		assertThrows(IllegalArgumentException.class, () -> RepositoryIdentity.github("octo", name));
	}

	@Test
	void rejectsOverlongName() {
		assertThrows(IllegalArgumentException.class, () -> RepositoryIdentity.github("octo", "r".repeat(101)));
	}

	@Test
	void rejectsMissingProvider() {
		assertThrows(NullPointerException.class, () -> new RepositoryIdentity(null, "octo", "repo"));
	}
}
