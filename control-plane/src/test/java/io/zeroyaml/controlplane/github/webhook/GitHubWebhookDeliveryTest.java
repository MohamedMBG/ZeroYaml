package io.zeroyaml.controlplane.github.webhook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class GitHubWebhookDeliveryTest {

	private static final byte[] BODY = "{\"secret\":\"payload-value\"}".getBytes(StandardCharsets.UTF_8);

	@Test
	void keepsTheRawBodyIsolatedFromCallers() {
		var input = BODY.clone();
		var delivery = delivery(input, "sha256=abc");

		input[0] = 'X';
		var firstRead = delivery.rawBody();
		firstRead[1] = 'Y';

		assertArrayEquals(BODY, delivery.rawBody());
		assertEquals(BODY.length, delivery.payloadSize());
	}

	@Test
	void reportsAbsentOptionalHeadersAsEmpty() {
		var delivery = new GitHubWebhookDelivery("delivery-1", "push", null, null, null, null, BODY);

		assertTrue(delivery.signature256().isEmpty());
		assertTrue(delivery.hookId().isEmpty());
		assertTrue(delivery.installationTargetType().isEmpty());
		assertTrue(delivery.installationTargetId().isEmpty());
	}

	@Test
	void neverRendersThePayloadOrSignatureInText() {
		var text = delivery(BODY, "sha256=top-secret-signature").toString();

		assertFalse(text.contains("payload-value"), text);
		assertFalse(text.contains("top-secret-signature"), text);
		assertTrue(text.contains("delivery-1"), text);
	}

	@Test
	void comparesByContent() {
		assertEquals(delivery(BODY, "sha256=abc"), delivery(BODY.clone(), "sha256=abc"));
		assertEquals(delivery(BODY, "sha256=abc").hashCode(), delivery(BODY.clone(), "sha256=abc").hashCode());
	}

	@Test
	void requiresTheIdentifyingFieldsAndBody() {
		assertThrows(NullPointerException.class, () -> new GitHubWebhookDelivery(null, "push", null, null, null, null, BODY));
		assertThrows(NullPointerException.class, () -> new GitHubWebhookDelivery("d", null, null, null, null, null, BODY));
		assertThrows(NullPointerException.class, () -> new GitHubWebhookDelivery("d", "push", null, null, null, null, null));
	}

	@Test
	void refusesAPayloadLimitOutsideGitHubsRange() {
		for (var invalid : new DataSize[] {null, DataSize.ofBytes(0), DataSize.ofMegabytes(26)}) {
			var properties = new GitHubWebhookProperties();
			properties.setMaxPayloadSize(invalid);

			var failure = assertThrows(IllegalStateException.class, properties::validate);
			assertTrue(failure.getMessage().contains("zeroyaml.github.webhook.max-payload-size"));
		}

		var properties = new GitHubWebhookProperties();
		properties.setMaxPayloadSize(DataSize.ofMegabytes(25));
		properties.validate();
	}

	private static GitHubWebhookDelivery delivery(byte[] body, String signature) {
		return new GitHubWebhookDelivery("delivery-1", "push", signature, "hook-1", "repository", "42", body);
	}
}
