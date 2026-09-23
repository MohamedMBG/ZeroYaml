package io.zeroyaml.controlplane.github.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * Startup validation of the webhook configuration. A misconfigured payload
 * limit or a missing secret must stop the application rather than surface later
 * as a rejected or, worse, an unauthenticated delivery.
 */
class GitHubWebhookPropertiesTest {

	private static final String USABLE_SECRET = "a-usable-webhook-secret";

	@Test
	void acceptsAPayloadLimitAndSecretThatAreBothUsable() {
		var properties = properties(DataSize.ofMegabytes(5), USABLE_SECRET);

		assertDoesNotThrow(properties::validate);
	}

	@Test
	void rejectsAPayloadLimitOutsideTheSupportedRange() {
		for (var size : Arrays.asList(null, DataSize.ofBytes(0), DataSize.ofBytes(-1), DataSize.ofMegabytes(26))) {
			var properties = properties(size, USABLE_SECRET);

			var failure = assertThrows(IllegalStateException.class, properties::validate);

			assertTrue(failure.getMessage().contains("zeroyaml.github.webhook.max-payload-size"));
		}
	}

	@Test
	void acceptsAPayloadLimitExactlyAtGitHubsCap() {
		var properties = properties(GitHubWebhookProperties.GITHUB_PAYLOAD_CAP, USABLE_SECRET);

		assertDoesNotThrow(properties::validate);
	}

	@Test
	void rejectsAMissingOrTooShortSecret() {
		var tooShort = "x".repeat(GitHubWebhookProperties.MINIMUM_SECRET_LENGTH - 1);

		for (var secret : Arrays.asList(null, "", "   ", tooShort)) {
			var properties = properties(DataSize.ofMegabytes(5), secret);

			var failure = assertThrows(IllegalStateException.class, properties::validate);

			assertTrue(failure.getMessage().contains("zeroyaml.github.webhook.secret"));
			assertTrue(failure.getMessage().contains("ZEROYAML_GITHUB_WEBHOOK_SECRET"));
		}
	}

	@Test
	void acceptsASecretExactlyAtTheMinimumLength() {
		var properties = properties(
				DataSize.ofMegabytes(5), "x".repeat(GitHubWebhookProperties.MINIMUM_SECRET_LENGTH));

		assertDoesNotThrow(properties::validate);
	}

	@Test
	void keepsTheSecretOutOfTheFailureMessage() {
		var properties = properties(DataSize.ofMegabytes(5), "short-secret");

		var failure = assertThrows(IllegalStateException.class, properties::validate);

		assertFalse(failure.getMessage().contains("short-secret"), "startup failures are logged");
	}

	private static GitHubWebhookProperties properties(DataSize maxPayloadSize, String secret) {
		var properties = new GitHubWebhookProperties();
		properties.setMaxPayloadSize(maxPayloadSize);
		properties.setSecret(secret);
		return properties;
	}
}
