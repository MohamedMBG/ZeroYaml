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

	envGRPCAddress     = "ZEROYAML_RUNNER_GRPC_ADDRESS"
	envRunnerID        = "ZEROYAML_RUNNER_ID"
	envVersion         = "ZEROYAML_RUNNER_VERSION"
	envShutdownTimeout = "ZEROYAML_RUNNER_SHUTDOWN_TIMEOUT"
)

// Config contains startup configuration for the Runner process.
type Config struct {
	GRPCAddress string
	RunnerID    string
	Version     string

	// ShutdownTimeout bounds the drain phase of a graceful shutdown. When it
	// expires, the remaining RPCs are cancelled so the process always terminates.
	ShutdownTimeout time.Duration
}

// Load reads Runner configuration from environment variables and validates it.
func Load() (Config, error) {
	shutdownTimeout, err := durationFromEnv(envShutdownTimeout, defaultShutdownTimeout)
	if err != nil {
		return Config{}, err
	}

	cfg := Config{
		GRPCAddress:     valueFromEnv(envGRPCAddress, defaultGRPCAddress),
		RunnerID:        valueFromEnv(envRunnerID, defaultRunnerID),
		Version:         valueFromEnv(envVersion, defaultVersion),
		ShutdownTimeout: shutdownTimeout,
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
