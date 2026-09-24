package io.zeroyaml.controlplane.github.webhook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * Request-level coverage of the GitHub webhook ingress: supported,
 * unsupported, and malformed deliveries, and what reaches the downstream
 * handler in each case.
 */
@WebMvcTest(GitHubWebhookController.class)
@Import(GitHubWebhookControllerTest.WebhookTestConfiguration.class)
@TestPropertySource(properties = "zeroyaml.github.webhook.max-payload-size=64B")
class GitHubWebhookControllerTest {

	private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";
	private static final String SIGNATURE = "sha256=d57c68ca6f92289e6987922ff26938930f6e66a2d161ef06abdf1859230aa23c";
	private static final String PUSH_PAYLOAD = "{\"ref\":\"refs/heads/main\"}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingDeliveryHandler handler;

	@BeforeEach
	void clearHandler() {
		handler.deliveries.clear();
	}

	@Test
	void acceptsAPushDeliveryAndHandsTheRawDeliveryDownstream() throws Exception {
		mockMvc.perform(delivery("push", PUSH_PAYLOAD)
						.header(GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER, SIGNATURE)
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
		assertEquals(SIGNATURE, delivery.signature256().orElseThrow());
		assertEquals("292430182", delivery.hookId().orElseThrow());
		assertEquals("repository", delivery.installationTargetType().orElseThrow());
		assertEquals("79929171", delivery.installationTargetId().orElseThrow());
		assertArrayEquals(PUSH_PAYLOAD.getBytes(StandardCharsets.UTF_8), delivery.rawBody());
	}

	@Test
	void preservesTheRawBodyByteForByte() throws Exception {
		// Whitespace and key order matter to the signature, so nothing may be re-serialized.
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
	void acknowledgesPingWithoutHandingItDownstream() throws Exception {
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
	void rejectsADeliveryWithoutAnEventHeader() throws Exception {
		mockMvc.perform(post(GitHubWebhookController.PATH)
						.header(GitHubWebhookEnvelopeReader.DELIVERY_HEADER, DELIVERY_ID)
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

	private static MockHttpServletRequestBuilder delivery(String event, String payload) {
		return post(GitHubWebhookController.PATH)
				.header(GitHubWebhookEnvelopeReader.EVENT_HEADER, event)
				.header(GitHubWebhookEnvelopeReader.DELIVERY_HEADER, DELIVERY_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload.getBytes(StandardCharsets.UTF_8));
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
	@Import(GitHubWebhookEnvelopeReader.class)
	static class WebhookTestConfiguration {

		@Bean
		RecordingDeliveryHandler recordingDeliveryHandler() {
			return new RecordingDeliveryHandler();
		}
	}
}
