package io.zeroyaml.controlplane.github.webhook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Request-level coverage of the GitHub webhook ingress: signed, unsigned,
 * unsupported, and malformed deliveries, and what reaches the downstream
 * handler in each case.
 */
@WebMvcTest(GitHubWebhookController.class)
@Import(GitHubWebhookControllerTest.WebhookTestConfiguration.class)
@TestPropertySource(properties = {
		"zeroyaml.github.webhook.max-payload-size=64B",
		"zeroyaml.github.webhook.secret=" + GitHubWebhookControllerTest.SECRET
})
class GitHubWebhookControllerTest {

	static final String SECRET = "zeroyaml-test-webhook-secret";

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";
	private static final String PUSH_PAYLOAD = "{\"ref\":\"refs/heads/main\"}";

	/**
	 * Signature of {@link #PUSH_PAYLOAD} under {@link #SECRET}, fixed here so that
	 * the tests cannot pass by comparing the endpoint against a test helper that
	 * repeats the same mistake.
	 */
	private static final String PUSH_PAYLOAD_SIGNATURE =
			"sha256=65de39cb377d0ee5fdf64220a75364b57c4468ffcd099f12a59296a0a6d8ea65";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingDeliveryHandler handler;

	@BeforeEach
	void clearHandler() {
		handler.deliveries.clear();
	}

	@Test
	void signsTheSamplePayloadExactlyAsTheFixedFixtureDoes() {
		assertEquals(PUSH_PAYLOAD_SIGNATURE, sign(PUSH_PAYLOAD));
	}

	@Test
	void acceptsASignedPushDeliveryAndHandsTheRawDeliveryDownstream() throws Exception {
		mockMvc.perform(delivery("push", PUSH_PAYLOAD)
						.header(GitHubWebhookEnvelopeReader.HOOK_ID_HEADER, "292430182")
						.header(GitHubWebhookEnvelopeReader.INSTALLATION_TARGET_TYPE_HEADER, "repository")
						.header(GitHubWebhookEnvelopeReader.INSTALLATION_TARGET_ID_HEADER, "79929171"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.deliveryId").value(DELIVERY_ID))
				.andExpect(jsonPath("$.event").value("push"))
				.andExpect(jsonPath("$.outcome").value("ACCEPTED"));

		assertEquals(1, handler.deliveries.size());
		var delivery = handler.deliveries.getFirst();
		assertEquals(DELIVERY_ID, delivery.deliveryId());
		assertEquals("push", delivery.event());
		assertEquals(PUSH_PAYLOAD_SIGNATURE, delivery.signature256().orElseThrow());
		assertEquals("292430182", delivery.hookId().orElseThrow());
		assertEquals("repository", delivery.installationTargetType().orElseThrow());
		assertEquals("79929171", delivery.installationTargetId().orElseThrow());
		assertArrayEquals(PUSH_PAYLOAD.getBytes(StandardCharsets.UTF_8), delivery.rawBody());
	}

	@Test
	void preservesTheRawBodyByteForByte() throws Exception {
		// Whitespace, key order, and non-ASCII bytes all change the signature, so
		// nothing may be re-serialized between the socket and verification.
		var payload = "\n  { \"b\" : 1,\t\"a\":\"é\" }\n";

		mockMvc.perform(delivery("push", payload))
				.andExpect(status().isAccepted());

		assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), handler.deliveries.getFirst().rawBody());
	}

	@Test
	void acceptsAJsonContentTypeWithACharset() throws Exception {
		mockMvc.perform(delivery("push", PUSH_PAYLOAD).contentType("application/json; charset=utf-8"))
				.andExpect(status().isAccepted());
	}

	@Test
	void acknowledgesASignedPingWithoutHandingItDownstream() throws Exception {
		mockMvc.perform(delivery("ping", "{\"zen\":\"Keep it logically awesome.\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("IGNORED"))
				.andExpect(jsonPath("$.event").value("ping"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void ignoresAnUnsupportedEventWithoutHandingItDownstream() throws Exception {
		mockMvc.perform(delivery("pull_request", "{\"action\":\"opened\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deliveryId").value(DELIVERY_ID))
				.andExpect(jsonPath("$.event").value("pull_request"))
				.andExpect(jsonPath("$.outcome").value("IGNORED"))
				.andExpect(jsonPath("$.message").value("event type is not supported; supported events: push"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsAPushDeliveryWithoutASignature() throws Exception {
		mockMvc.perform(unsignedDelivery("push", PUSH_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.deliveryId").value(DELIVERY_ID))
				.andExpect(jsonPath("$.event").value("push"))
				.andExpect(jsonPath("$.outcome").value("REJECTED"))
				.andExpect(jsonPath("$.message").value(
						"X-Hub-Signature-256 header is missing; configure a secret on the GitHub webhook"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsAPushDeliverySignedWithADifferentSecret() throws Exception {
		var forged = "sha256=" + HexFormat.of().formatHex(
				hmac("an-attacker-controlled-secret", PUSH_PAYLOAD.getBytes(StandardCharsets.UTF_8)));

		mockMvc.perform(unsignedDelivery("push", PUSH_PAYLOAD)
						.header(GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER, forged))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.outcome").value("REJECTED"))
				.andExpect(jsonPath("$.message").value("signature does not match the configured webhook secret"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsAPayloadThatChangedAfterItWasSigned() throws Exception {
		mockMvc.perform(unsignedDelivery("push", "{\"ref\":\"refs/heads/attacker\"}")
						.header(GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER, PUSH_PAYLOAD_SIGNATURE))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.outcome").value("REJECTED"))
				.andExpect(jsonPath("$.message").value("signature does not match the configured webhook secret"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsAMalformedSignatureWithoutEchoingIt() throws Exception {
		var digest = PUSH_PAYLOAD_SIGNATURE.substring("sha256=".length());
		var malformed = List.of(digest, "sha1=" + digest, "sha256=" + digest + "0", "sha256=not-hexadecimal", "garbage");

		for (var header : malformed) {
			mockMvc.perform(unsignedDelivery("push", PUSH_PAYLOAD)
							.header(GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER, header))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.outcome").value("REJECTED"))
					.andExpect(jsonPath("$.message").value(
							"X-Hub-Signature-256 header is malformed; expected sha256=<64 hexadecimal characters>"));
		}

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsAnUnsignedDeliveryBeforeTheEventAllowListDecides() throws Exception {
		// ping and unsupported events are answered 200 OK once verified. That answer
		// stays behind the signature gate, so an unauthenticated caller cannot probe
		// which events the Control Plane acts on.
		mockMvc.perform(unsignedDelivery("ping", "{\"zen\":\"Keep it logically awesome.\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.event").value("ping"))
				.andExpect(jsonPath("$.outcome").value("REJECTED"));

		mockMvc.perform(unsignedDelivery("pull_request", "{\"action\":\"opened\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.event").value("pull_request"))
				.andExpect(jsonPath("$.outcome").value("REJECTED"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsADeliveryWithoutAnEventHeader() throws Exception {
		mockMvc.perform(post(GitHubWebhookController.PATH)
						.header(GitHubWebhookEnvelopeReader.DELIVERY_HEADER, DELIVERY_ID)
						.header(GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER, PUSH_PAYLOAD_SIGNATURE)
						.contentType(MediaType.APPLICATION_JSON)
						.content(PUSH_PAYLOAD))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.outcome").value("REJECTED"))
				.andExpect(jsonPath("$.message").value("X-GitHub-Event header is missing or malformed"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsAMalformedEventHeaderWithoutEchoingIt() throws Exception {
		mockMvc.perform(delivery("push\r\nX-Injected: 1", PUSH_PAYLOAD))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.event").doesNotExist())
				.andExpect(jsonPath("$.message").value("X-GitHub-Event header is missing or malformed"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsADeliveryWithoutADeliveryIdentifier() throws Exception {
		mockMvc.perform(post(GitHubWebhookController.PATH)
						.header(GitHubWebhookEnvelopeReader.EVENT_HEADER, "push")
						.header(GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER, PUSH_PAYLOAD_SIGNATURE)
						.contentType(MediaType.APPLICATION_JSON)
						.content(PUSH_PAYLOAD))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.event").value("push"))
				.andExpect(jsonPath("$.message").value("X-GitHub-Delivery header is missing or malformed"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsAFormEncodedDelivery() throws Exception {
		mockMvc.perform(delivery("push", "payload=%7B%7D").contentType(MediaType.APPLICATION_FORM_URLENCODED))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.outcome").value("REJECTED"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsADeliveryWithoutAContentType() throws Exception {
		mockMvc.perform(post(GitHubWebhookController.PATH)
						.header(GitHubWebhookEnvelopeReader.EVENT_HEADER, "push")
						.header(GitHubWebhookEnvelopeReader.DELIVERY_HEADER, DELIVERY_ID)
						.header(GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER, PUSH_PAYLOAD_SIGNATURE)
						.content(PUSH_PAYLOAD))
				.andExpect(status().isUnsupportedMediaType());

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsAnEmptyBody() throws Exception {
		mockMvc.perform(delivery("push", ""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("payload must be a JSON object"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsABodyThatIsNotAJsonObject() throws Exception {
		for (var payload : List.of("[]", "not json", "   ", "\"push\"")) {
			mockMvc.perform(delivery("push", payload))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.outcome").value("REJECTED"));
		}

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void rejectsAPayloadAboveTheConfiguredLimit() throws Exception {
		var payload = "{\"padding\":\"" + "x".repeat(64) + "\"}";

		mockMvc.perform(delivery("push", payload))
				.andExpect(status().isContentTooLarge())
				.andExpect(jsonPath("$.message").value("payload exceeds the 64 byte limit"));

		assertTrue(handler.deliveries.isEmpty());
	}

	@Test
	void acceptsAPayloadExactlyAtTheConfiguredLimit() throws Exception {
		var payload = "{\"padding\":\"" + "x".repeat(64 - 14) + "\"}";
		assertEquals(64, payload.length());

		mockMvc.perform(delivery("push", payload))
				.andExpect(status().isAccepted());
	}

	@Test
	void doesNotServeReadRequests() throws Exception {
		mockMvc.perform(get(GitHubWebhookController.PATH))
				.andExpect(status().isMethodNotAllowed());
	}

	/** A delivery signed with the configured secret, the way GitHub sends one. */
	private static MockHttpServletRequestBuilder delivery(String event, String payload) {
		return unsignedDelivery(event, payload)
				.header(GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER, sign(payload));
	}

	private static MockHttpServletRequestBuilder unsignedDelivery(String event, String payload) {
		return post(GitHubWebhookController.PATH)
				.header(GitHubWebhookEnvelopeReader.EVENT_HEADER, event)
				.header(GitHubWebhookEnvelopeReader.DELIVERY_HEADER, DELIVERY_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload.getBytes(StandardCharsets.UTF_8));
	}

	private static String sign(String payload) {
		return "sha256=" + HexFormat.of().formatHex(hmac(SECRET, payload.getBytes(StandardCharsets.UTF_8)));
	}

	private static byte[] hmac(String secret, byte[] payload) {
		try {
			var mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return mac.doFinal(payload);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("the test fixture cannot be signed", ex);
		}
	}

	static final class RecordingDeliveryHandler implements GitHubWebhookDeliveryHandler {

		final List<GitHubWebhookDelivery> deliveries = new CopyOnWriteArrayList<>();

		@Override
		public void handle(GitHubWebhookDelivery delivery) {
			deliveries.add(delivery);
		}
	}

	@TestConfiguration
	@EnableConfigurationProperties(GitHubWebhookProperties.class)
	@Import({GitHubWebhookEnvelopeReader.class, GitHubWebhookSignatureVerifier.class})
	static class WebhookTestConfiguration {

		@Bean
		RecordingDeliveryHandler recordingDeliveryHandler() {
			return new RecordingDeliveryHandler();
		}
	}
}
