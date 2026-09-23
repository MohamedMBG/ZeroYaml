package io.zeroyaml.controlplane.domain.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SecretReferenceTest {

	@ParameterizedTest
	@ValueSource(strings = {"ZEROYAML_GITHUB_WEBHOOK_SECRET", "github.webhook-secret", "a"})
	void acceptsIdentifierShapedNames(String name) {
		assertEquals(name, new SecretReference(name).name());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "1SECRET", "_SECRET", "has space", "sha256=abc", "s3cr3t/value", "key+value==",
			"line\nbreak"})
	void rejectsValuesThatAreNotReferenceNames(String name) {
		assertThrows(IllegalArgumentException.class, () -> new SecretReference(name));
	}

	@Test
	void enforcesMaximumLength() {
		new SecretReference("S".repeat(SecretReference.MAX_LENGTH));

		assertThrows(IllegalArgumentException.class,
				() -> new SecretReference("S".repeat(SecretReference.MAX_LENGTH + 1)));
	}

	@Test
	void validationMessageDoesNotEchoRejectedValue() {
		var candidate = "raw secret value";

		var exception = assertThrows(IllegalArgumentException.class, () -> new SecretReference(candidate));

		assertEquals(false, exception.getMessage().contains(candidate));
	}

	@Test
	void webhookConfigurationRequiresSigningSecretReference() {
		assertThrows(NullPointerException.class, () -> new WebhookConfiguration(null));
	}
}
