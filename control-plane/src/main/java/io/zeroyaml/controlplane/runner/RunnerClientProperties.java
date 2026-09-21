package io.zeroyaml.controlplane.runner;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized endpoint and deadline settings for the Runner connection.
 * Bean validation makes invalid connection settings fail during application startup.
 */
@Validated
@ConfigurationProperties(prefix = "zeroyaml.runner")
public class RunnerClientProperties {

	// Bound from application properties or environment variables at application startup.
	@NotBlank
	private String host = "localhost";

	@Min(1)
	@Max(65535)
	private int port = 50051;

	@NotNull
	private Duration pingDeadline = Duration.ofSeconds(2);

	// A dispatch is acknowledged, not executed, so this deadline bounds the
	// acknowledgment only. It is longer than the Ping deadline because the Runner
	// validates the request before it answers.
	@NotNull
	private Duration dispatchDeadline = Duration.ofSeconds(5);

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public Duration getPingDeadline() {
		return pingDeadline;
	}

	public void setPingDeadline(Duration pingDeadline) {
		this.pingDeadline = pingDeadline;
	}

	public Duration getDispatchDeadline() {
		return dispatchDeadline;
	}

	public void setDispatchDeadline(Duration dispatchDeadline) {
		this.dispatchDeadline = dispatchDeadline;
	}
}
