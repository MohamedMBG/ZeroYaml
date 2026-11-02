package io.zeroyaml.controlplane.github.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.unit.DataSize;

class GitHubRepositoryPropertiesTest {

	// Stands in for a credential an operator pasted into a URL setting.
	private static final String SECRET = "credential-marker-value";

	@Test
	void acceptsTheDefaultsAndTreatsABlankTokenAsNoToken() {
		var properties = new GitHubRepositoryProperties();
		properties.setApiToken("  ");

		assertDoesNotThrow(properties::validate);
		assertNull(properties.getApiToken());
	}

	@Test
	void acceptsPlainHttpOnlyForALocalApi() {
		assertValid(properties -> properties.setApiBaseUrl("http://localhost:8089"));
		assertInvalid("api-base-url", properties -> properties.setApiBaseUrl("http://api.example.com"));
		assertInvalid("api-base-url",
				properties -> properties.setApiBaseUrl("https://user:" + SECRET + "@api.github.com"));
		assertInvalid("api-base-url", properties -> properties.setApiBaseUrl("/relative"));
		assertInvalid("api-base-url", properties -> properties.setApiBaseUrl(null));
		assertInvalid("api-base-url", properties -> properties.setApiBaseUrl(""));
	}

	@Test
	void boundsTheTimeoutAndTheResponseSize() {
		assertInvalid("api-timeout", properties -> properties.setApiTimeout(Duration.ZERO));
		assertInvalid("api-timeout", properties -> properties.setApiTimeout(Duration.ofMinutes(2)));
		assertInvalid("max-tree-response-size", properties -> properties.setMaxTreeResponseSize(DataSize.ofBytes(0)));
		assertInvalid("max-tree-response-size",
				properties -> properties.setMaxTreeResponseSize(DataSize.ofMegabytes(64)));
	}

	@Test
	void requiresALocationTemplateWithoutCredentials() {
		assertValid(properties -> properties.setLocationTemplate("file:///srv/sources/{owner}/{name}"));
		assertInvalid("location-template", properties -> properties.setLocationTemplate("https://github.com/{owner}.git"));
		assertInvalid("location-template",
				properties -> properties.setLocationTemplate("https://" + SECRET + "@github.com/{owner}/{name}.git"));
		assertInvalid("location-template",
				properties -> properties.setLocationTemplate("ssh://github.com/{owner}/{name}.git"));
		assertInvalid("location-template", properties -> properties.setLocationTemplate("{owner}/{name}"));
	}

	@Test
	void neverEchoesARejectedValueThatMayCarryACredential() {
		assertInvalid("location-template",
				properties -> properties.setLocationTemplate("https://" + SECRET + "@github.com/{owner}.git"));
		// A space makes the URI parser fail, and its message repeats the whole input.
		assertInvalid("location-template",
				properties -> properties.setLocationTemplate("https://" + SECRET + " @github.com/{owner}/{name}"));
		assertInvalid("api-base-url",
				properties -> properties.setApiBaseUrl("http://" + SECRET + "@api.example.com"));
		assertInvalid("api-base-url",
				properties -> properties.setApiBaseUrl("https://" + SECRET + "@[invalid"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://api.github.com", "http://127.0.0.1:8089" })
	void startupBindsValidApiUrls(String url) {
		try (var application = new SpringApplicationBuilder(PropertiesApplication.class)
				.web(WebApplicationType.NONE).run("--zeroyaml.github.repository.api-base-url=" + url)) {
			assertEquals(url, application.getBean(GitHubRepositoryProperties.class).getApiBaseUrl());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://%s@[invalid", "https://%s @api.github.com", "https://%s@api.github.com" })
	@ExtendWith(OutputCaptureExtension.class)
	void startupRejectsCredentialBearingApiUrlsWithoutLoggingTheirValues(String template, CapturedOutput output) {
		var application = new SpringApplicationBuilder(PropertiesApplication.class)
				.web(WebApplicationType.NONE).build();
		application.setRegisterShutdownHook(false);

		var failure = assertThrows(RuntimeException.class, () -> application.run(
				"--zeroyaml.github.repository.api-base-url=" + template.formatted(SECRET),
				"--spring.main.banner-mode=off"));

		assertTrue(output.getAll().contains("zeroyaml.github.repository.api-base-url"), "startup failure must be logged");
		assertFalse(output.getAll().contains(SECRET), "startup logs disclose the credential");
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			assertFalse(String.valueOf(cause.getMessage()).contains(SECRET), "startup failure discloses the credential");
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(GitHubRepositoryProperties.class)
	static class PropertiesApplication {
	}

	private static void assertValid(Consumer<GitHubRepositoryProperties> change) {
		var properties = new GitHubRepositoryProperties();
		change.accept(properties);
		assertDoesNotThrow(properties::validate);
	}

	private static void assertInvalid(String setting, Consumer<GitHubRepositoryProperties> change) {
		var properties = new GitHubRepositoryProperties();
		change.accept(properties);
		var failure = assertThrows(IllegalStateException.class, properties::validate);
		assertTrue(failure.getMessage().contains("zeroyaml.github.repository." + setting), failure.getMessage());
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			assertFalse(String.valueOf(cause.getMessage()).contains(SECRET), "rejection discloses the credential");
		}
	}
}
