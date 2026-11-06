package io.zeroyaml.controlplane.github.repository;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for reading connected GitHub repositories.
 *
 * <p>Example for a local run against a fixture API and a local source
 * directory that the Runner mounts through {@code ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT}:</p>
 *
 * <pre>
 * zeroyaml.github.repository.api-base-url=http://127.0.0.1:8089
 * zeroyaml.github.repository.location-template=file:///srv/zeroyaml/sources/{owner}/{name}
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "zeroyaml.github.repository")
public class GitHubRepositoryProperties {

	static final String OWNER_PLACEHOLDER = "{owner}";
	static final String NAME_PLACEHOLDER = "{name}";

	private static final Duration MAX_API_TIMEOUT = Duration.ofMinutes(1);
	private static final DataSize MAX_TREE_RESPONSE_SIZE = DataSize.ofMegabytes(32);
	private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
	private static final Set<String> LOCATION_SCHEMES = Set.of("https", "http", "file");

	// Bind as text so Spring's URI converter cannot echo malformed credential-bearing input.
	private String apiBaseUrl = "https://api.github.com";

	// Optional and without a default: public repositories are readable without a
	// token, and a token shipped in source would grant its access to every reader.
	private String apiToken;

	private Duration apiTimeout = Duration.ofSeconds(10);

	// A root tree is small; the bound only stops an unexpected answer from
	// exhausting memory.
	private DataSize maxTreeResponseSize = DataSize.ofMegabytes(1);

	private String locationTemplate = "https://github.com/" + OWNER_PLACEHOLDER + "/" + NAME_PLACEHOLDER + ".git";

	public String getApiBaseUrl() {
		return apiBaseUrl;
	}

	public void setApiBaseUrl(String apiBaseUrl) {
		this.apiBaseUrl = apiBaseUrl;
	}

	public String getApiToken() {
		return apiToken;
	}

	public void setApiToken(String apiToken) {
		// An unset environment placeholder resolves to an empty value, which means no token.
		this.apiToken = apiToken == null || apiToken.isBlank() ? null : apiToken;
	}

	public Duration getApiTimeout() {
		return apiTimeout;
	}

	public void setApiTimeout(Duration apiTimeout) {
		this.apiTimeout = apiTimeout;
	}

	public DataSize getMaxTreeResponseSize() {
		return maxTreeResponseSize;
	}

	public void setMaxTreeResponseSize(DataSize maxTreeResponseSize) {
		this.maxTreeResponseSize = maxTreeResponseSize;
	}

	public String getLocationTemplate() {
		return locationTemplate;
	}

	public void setLocationTemplate(String locationTemplate) {
		this.locationTemplate = locationTemplate;
	}

	/**
	 * Rejects unusable settings at startup. URL and template values are never
	 * echoed in a failure, because a rejected value may carry a credential.
	 */
	@PostConstruct
	void validate() {
		validateApiBaseUrl();
		if (apiToken != null && !apiToken.matches("[A-Za-z0-9._~+/-]+=*")) {
			throw new IllegalStateException("zeroyaml.github.repository.api-token must be a valid bearer token");
		}
		if (apiTimeout == null || apiTimeout.isNegative() || apiTimeout.isZero()
				|| apiTimeout.compareTo(MAX_API_TIMEOUT) > 0) {
			throw new IllegalStateException(
					"zeroyaml.github.repository.api-timeout must be greater than zero and at most 1m, got " + apiTimeout);
		}
		if (maxTreeResponseSize == null || maxTreeResponseSize.toBytes() <= 0
				|| maxTreeResponseSize.compareTo(MAX_TREE_RESPONSE_SIZE) > 0) {
			throw new IllegalStateException(
					"zeroyaml.github.repository.max-tree-response-size must be greater than zero and at most 32MB, got "
							+ maxTreeResponseSize);
		}
		validateLocationTemplate();
	}

	private void validateApiBaseUrl() {
		final URI url;
		try {
			url = apiBaseUrl == null ? null : URI.create(apiBaseUrl);
		}
		catch (IllegalArgumentException ex) {
			// URI parser causes repeat the input and must not reach startup logs.
			throw new IllegalStateException("zeroyaml.github.repository.api-base-url is not a valid URI");
		}
		var scheme = url == null || url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
		var host = url == null || url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
		var valid = url != null && url.isAbsolute() && !host.isEmpty()
				&& url.getRawUserInfo() == null && url.getRawQuery() == null && url.getRawFragment() == null
				// Plain HTTP would expose the token, so it is limited to a local fixture API.
				&& (scheme.equals("https") || scheme.equals("http") && LOOPBACK_HOSTS.contains(host));
		if (!valid) {
			throw new IllegalStateException(
					"zeroyaml.github.repository.api-base-url must be an https URL without credentials, query, or "
							+ "fragment (http is accepted only for localhost)");
		}
	}

	private void validateLocationTemplate() {
		if (locationTemplate == null || !locationTemplate.contains(OWNER_PLACEHOLDER)
				|| !locationTemplate.contains(NAME_PLACEHOLDER)) {
			throw new IllegalStateException(
					"zeroyaml.github.repository.location-template must contain {owner} and {name}");
		}
		final URI sample;
		try {
			sample = URI.create(locationFor("owner", "name"));
		}
		catch (IllegalArgumentException ex) {
			// The parser's message, and so the cause, repeats the input; neither is attached.
			throw new IllegalStateException("zeroyaml.github.repository.location-template is not a valid URI template");
		}
		var scheme = sample.getScheme() == null ? "" : sample.getScheme().toLowerCase(Locale.ROOT);
		var host = sample.getHost() == null ? "" : sample.getHost().toLowerCase(Locale.ROOT);
		if (!sample.isAbsolute() || !LOCATION_SCHEMES.contains(scheme) || sample.getRawUserInfo() != null
				|| sample.getRawQuery() != null || sample.getRawFragment() != null
				|| scheme.equals("http") && !LOOPBACK_HOSTS.contains(host)) {
			// Locations are stored with every Job and sent to the Runner, so they must not carry credentials.
			throw new IllegalStateException(
					"zeroyaml.github.repository.location-template must produce an https, http, or file URI without "
							+ "credentials, query, or fragment (http is accepted only for localhost)");
		}
	}

	String locationFor(String owner, String name) {
		return locationTemplate.replace(OWNER_PLACEHOLDER, owner).replace(NAME_PLACEHOLDER, name);
	}
}
