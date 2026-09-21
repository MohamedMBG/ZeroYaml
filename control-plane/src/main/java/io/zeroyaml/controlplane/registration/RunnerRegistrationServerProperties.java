package io.zeroyaml.controlplane.registration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized port for the Runner registration gRPC server. Bean validation
 * makes an invalid port fail during application startup.
 */
@Validated
@ConfigurationProperties(prefix = "zeroyaml.registration")
public class RunnerRegistrationServerProperties {

	// Bound from application properties or environment variables at application startup.
	@Min(1)
	@Max(65535)
	private int port = 50052;

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}
}
