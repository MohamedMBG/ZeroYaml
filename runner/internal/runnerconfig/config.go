package runnerconfig

import (
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultGRPCAddress = ":50051"
	defaultRunnerID    = "local-runner"
	defaultVersion     = "0.1.0"

	// defaultShutdownTimeout bounds how long the Runner waits for in-flight RPCs
	// to finish after a termination signal. It leaves room for short RPCs to
	// complete while staying below the ten-second stop grace period that Docker
	// applies by default.
	defaultShutdownTimeout = 5 * time.Second

	// defaultControlPlaneAddress targets the Control Plane registration server
	// started alongside the default Spring Boot dev profile.
	defaultControlPlaneAddress = "localhost:50052"

	// defaultRegistrationTimeout bounds the single startup registration attempt
	// so an unreachable Control Plane cannot delay the Runner from serving.
	defaultRegistrationTimeout = 5 * time.Second

	// defaultHeartbeatInterval controls how often a successfully registered
	// Runner reports liveness to the Control Plane.
	defaultHeartbeatInterval = 5 * time.Second

	// defaultHeartbeatTimeout bounds each individual heartbeat attempt so a
	// slow or unreachable Control Plane cannot stall the next scheduled tick.
	defaultHeartbeatTimeout = 5 * time.Second

	envGRPCAddress         = "ZEROYAML_RUNNER_GRPC_ADDRESS"
	envRunnerID            = "ZEROYAML_RUNNER_ID"
	envVersion             = "ZEROYAML_RUNNER_VERSION"
	envShutdownTimeout     = "ZEROYAML_RUNNER_SHUTDOWN_TIMEOUT"
	envControlPlaneAddress = "ZEROYAML_CONTROLPLANE_ADDRESS"
	envRegistrationTimeout = "ZEROYAML_RUNNER_REGISTRATION_TIMEOUT"
	envHeartbeatInterval   = "ZEROYAML_RUNNER_HEARTBEAT_INTERVAL"
	envHeartbeatTimeout    = "ZEROYAML_RUNNER_HEARTBEAT_TIMEOUT"
)

// Config contains startup configuration for the Runner process.
type Config struct {
	GRPCAddress string
	RunnerID    string
	Version     string

	// ShutdownTimeout bounds the drain phase of a graceful shutdown. When it
	// expires, the remaining RPCs are cancelled so the process always terminates.
	ShutdownTimeout time.Duration

	// ControlPlaneAddress is the Control Plane's Runner registration endpoint.
	ControlPlaneAddress string

	// RegistrationTimeout bounds the single startup registration attempt against
	// the Control Plane.
	RegistrationTimeout time.Duration

	// HeartbeatInterval controls how often a registered Runner reports
	// liveness to the Control Plane.
	HeartbeatInterval time.Duration

	// HeartbeatTimeout bounds each individual heartbeat attempt.
	HeartbeatTimeout time.Duration
}

// Load reads Runner configuration from environment variables and validates it.
func Load() (Config, error) {
	shutdownTimeout, err := durationFromEnv(envShutdownTimeout, defaultShutdownTimeout)
	if err != nil {
		return Config{}, err
	}

	registrationTimeout, err := durationFromEnv(envRegistrationTimeout, defaultRegistrationTimeout)
	if err != nil {
		return Config{}, err
	}

	heartbeatInterval, err := durationFromEnv(envHeartbeatInterval, defaultHeartbeatInterval)
	if err != nil {
		return Config{}, err
	}

	heartbeatTimeout, err := durationFromEnv(envHeartbeatTimeout, defaultHeartbeatTimeout)
	if err != nil {
		return Config{}, err
	}

	cfg := Config{
		GRPCAddress:         valueFromEnv(envGRPCAddress, defaultGRPCAddress),
		RunnerID:            valueFromEnv(envRunnerID, defaultRunnerID),
		Version:             valueFromEnv(envVersion, defaultVersion),
		ShutdownTimeout:     shutdownTimeout,
		ControlPlaneAddress: valueFromEnv(envControlPlaneAddress, defaultControlPlaneAddress),
		RegistrationTimeout: registrationTimeout,
		HeartbeatInterval:   heartbeatInterval,
		HeartbeatTimeout:    heartbeatTimeout,
	}

	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}

	return cfg, nil
}

// Validate returns an actionable startup error when configuration is invalid.
func (c Config) Validate() error {
	if strings.TrimSpace(c.GRPCAddress) == "" {
		return fmt.Errorf("%s must not be empty", envGRPCAddress)
	}

	if err := validateTCPAddress(c.GRPCAddress); err != nil {
		return fmt.Errorf("%s must be a TCP address in host:port form, such as :50051 or 127.0.0.1:50051: %w", envGRPCAddress, err)
	}

	if strings.TrimSpace(c.RunnerID) == "" {
		return fmt.Errorf("%s must not be empty", envRunnerID)
	}

	if strings.TrimSpace(c.Version) == "" {
		return fmt.Errorf("%s must not be empty", envVersion)
	}

	if c.ShutdownTimeout <= 0 {
		return fmt.Errorf("%s must be greater than zero, got %s", envShutdownTimeout, c.ShutdownTimeout)
	}

	if strings.TrimSpace(c.ControlPlaneAddress) == "" {
		return fmt.Errorf("%s must not be empty", envControlPlaneAddress)
	}

	if err := validateTCPAddress(c.ControlPlaneAddress); err != nil {
		return fmt.Errorf("%s must be a TCP address in host:port form, such as localhost:50052: %w", envControlPlaneAddress, err)
	}

	if c.RegistrationTimeout <= 0 {
		return fmt.Errorf("%s must be greater than zero, got %s", envRegistrationTimeout, c.RegistrationTimeout)
	}

	if c.HeartbeatInterval <= 0 {
		return fmt.Errorf("%s must be greater than zero, got %s", envHeartbeatInterval, c.HeartbeatInterval)
	}

	if c.HeartbeatTimeout <= 0 {
		return fmt.Errorf("%s must be greater than zero, got %s", envHeartbeatTimeout, c.HeartbeatTimeout)
	}

	return nil
}

func valueFromEnv(name string, fallback string) string {
	if value, ok := os.LookupEnv(name); ok {
		return strings.TrimSpace(value)
	}

	return fallback
}

func durationFromEnv(name string, fallback time.Duration) (time.Duration, error) {
	rawValue, isSet := os.LookupEnv(name)
	if !isSet {
		return fallback, nil
	}

	value, err := time.ParseDuration(strings.TrimSpace(rawValue))
	if err != nil {
		return 0, fmt.Errorf("%s must be a Go duration such as 5s or 500ms: %w", name, err)
	}

	return value, nil
}

func validateTCPAddress(address string) error {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}

	if strings.Contains(host, "/") {
		return errors.New("host must not contain a path")
	}

	portNumber, err := strconv.Atoi(port)
	if err != nil {
		return fmt.Errorf("port must be numeric: %w", err)
	}

	if portNumber < 1 || portNumber > 65535 {
		return fmt.Errorf("port must be between 1 and 65535, got %d", portNumber)
	}

	return nil
}
