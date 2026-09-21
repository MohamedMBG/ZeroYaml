package io.zeroyaml.controlplane.registration;

/**
 * The Control Plane's decision for one Runner registration request.
 */
public enum RegistrationDecision {
	ACCEPTED,
	ALREADY_REGISTERED,
	IDENTITY_CONFLICT
}
