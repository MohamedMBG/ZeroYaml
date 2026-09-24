package io.zeroyaml.controlplane.github.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Signature verification against deterministic fixtures.
 *
 * <p>The anchor fixture is GitHub's own published example: the payload
 * {@code Hello, World!} signed with the secret
 * {@code It's a Secret to Everybody} yields
 * {@code sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17}.
 * Using GitHub's vector proves the implementation agrees with the service that
 * produces the header, not merely with itself.</p>
 */
class GitHubWebhookSignatureVerifierTest {

	private static final String SECRET = "It's a Secret to Everybody";
	private static final String PAYLOAD = "Hello, World!";
	private static final String DIGEST = "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
	private static final String SIGNATURE = "sha256=" + DIGEST;

	private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";

	private final GitHubWebhookSignatureVerifier verifier = verifierWithSecret(SECRET);

	@Test
	void acceptsTheSignatureGitHubComputesForTheSameSecretAndPayload() {
		assertDoesNotThrow(() -> verifier.verify(delivery(SIGNATURE, PAYLOAD)));
	}

	@Test
	void acceptsAnUpperCaseDigestBecauseHexadecimalIsCaseInsensitive() {
		var upperCase = "sha256=" + DIGEST.toUpperCase(Locale.ROOT);

		assertDoesNotThrow(() -> verifier.verify(delivery(upperCase, PAYLOAD)));
	}

	@Test
	void acceptsAnEmptyPayloadThatWasSignedAsSent() {
		// An empty body still has a defined HMAC, so verification must not special-case it.
		var signature = "sha256=66a0c074deaa0f489ead6537e0d32f9a344b90bbeda705b6ed45ecd3b413fb40";

		assertDoesNotThrow(() -> verifier.verify(delivery(signature, "")));
	}

	@Test
	void rejectsAPayloadThatChangedAfterItWasSigned() {
		var rejection = assertThrows(
				GitHubWebhookRejectedException.class,
				() -> verifier.verify(delivery(SIGNATURE, "Hello, World?"))
		);

		assertEquals(HttpStatus.UNAUTHORIZED, rejection.status());
		assertEquals("signature does not match the configured webhook secret", rejection.getMessage());
	}

	@Test
	void rejectsASignatureProducedWithADifferentSecret() {
		var otherSecret = verifierWithSecret("a-different-webhook-secret");

		var rejection = assertThrows(
				GitHubWebhookRejectedException.class,
				() -> otherSecret.verify(delivery(SIGNATURE, PAYLOAD))
		);

		assertEquals(HttpStatus.UNAUTHORIZED, rejection.status());
	}

	@Test
	void rejectsADigestThatDiffersOnlyInItsLastCharacter() {
		// A near miss fails exactly like any other mismatch; the comparison never
		// reveals how much of a guessed signature was correct.
		var nearMiss = "sha256=" + DIGEST.substring(0, DIGEST.length() - 1) + "8";

		assertThrows(GitHubWebhookRejectedException.class, () -> verifier.verify(delivery(nearMiss, PAYLOAD)));
	}

	@Test
	void rejectsADeliveryWithoutASignatureHeader() {
		var rejection = assertThrows(
				GitHubWebhookRejectedException.class,
				() -> verifier.verify(delivery(null, PAYLOAD))
		);

		assertEquals(HttpStatus.UNAUTHORIZED, rejection.status());
		assertTrue(rejection.getMessage().startsWith("X-Hub-Signature-256 header is missing"));
	}

	@Test
	void rejectsAMalformedSignatureHeader() {
		var headers = List.of(
				DIGEST,
				"sha1=" + DIGEST,
				"SHA256=" + DIGEST,
				"sha256=" + DIGEST.substring(1),
				"sha256=" + DIGEST + "7",
				"sha256=zz" + DIGEST.substring(2),
				"sha256= " + DIGEST,
				"sha256=",
				"sha256"
		);

		for (var header : headers) {
			var rejection = assertThrows(
					GitHubWebhookRejectedException.class,
					() -> verifier.verify(delivery(header, PAYLOAD)),
					() -> "expected rejection for header shape " + header.length() + " characters long"
			);

			assertEquals(HttpStatus.UNAUTHORIZED, rejection.status());
			assertTrue(rejection.getMessage().contains("malformed"));
		}
	}

	@Test
	void reportsTheDeliveryIdentifierAndEventWithoutLeakingTheSecretOrThePayload() {
		var rejection = assertThrows(
				GitHubWebhookRejectedException.class,
				() -> verifier.verify(delivery(SIGNATURE, "Hello, World?"))
		);

		assertEquals(DELIVERY_ID, rejection.deliveryId());
		assertEquals("push", rejection.event());
		assertFalse(rejection.getMessage().contains(SECRET), "reason must not contain the secret");
		assertFalse(rejection.getMessage().contains("Hello"), "reason must not contain the payload");
	}

	@Test
	void refusesToStartWithoutAUsableSecret() {
		for (var secret : Arrays.asList(null, "", "   ")) {
			var properties = new GitHubWebhookProperties();
			properties.setSecret(secret);

			var failure = assertThrows(
					IllegalStateException.class, () -> new GitHubWebhookSignatureVerifier(properties));

			assertTrue(failure.getMessage().contains("zeroyaml.github.webhook.secret"));
		}
	}

	private static GitHubWebhookSignatureVerifier verifierWithSecret(String secret) {
		var properties = new GitHubWebhookProperties();
		properties.setSecret(secret);
		return new GitHubWebhookSignatureVerifier(properties);
	}

	private static GitHubWebhookDelivery delivery(String signature, String payload) {
		return new GitHubWebhookDelivery(
				DELIVERY_ID, "push", signature, null, null, null, payload.getBytes(StandardCharsets.UTF_8));
	}
}
