package io.zeroyaml.controlplane.github.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class GitHubRepositoryPropertiesTest {

	@Test
	void acceptsTheDefaultsAndTreatsABlankTokenAsNoToken() {
		var properties = new GitHubRepositoryProperties();
		properties.setApiToken("  ");

		assertDoesNotThrow(properties::validate);
		assertNull(properties.getApiToken());
	}

	@Test
	void acceptsPlainHttpOnlyForALocalApi() {
		assertValid(properties -> properties.setApiBaseUrl(URI.create("http://localhost:8089")));
		assertInvalid("api-base-url", properties -> properties.setApiBaseUrl(URI.create("http://api.example.com")));
		assertInvalid("api-base-url", properties -> properties.setApiBaseUrl(URI.create("https://user:pw@api.github.com")));
		assertInvalid("api-base-url", properties -> properties.setApiBaseUrl(URI.create("/relative")));
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
				properties -> properties.setLocationTemplate("https://token@github.com/{owner}/{name}.git"));
		assertInvalid("location-template",
				properties -> properties.setLocationTemplate("ssh://github.com/{owner}/{name}.git"));
		assertInvalid("location-template", properties -> properties.setLocationTemplate("{owner}/{name}"));
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
	}
}
