package runnerconfig

import (
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
)

const (
	defaultGRPCAddress = ":50051"
	defaultRunnerID    = "local-runner"
	defaultVersion     = "0.1.0"

	envGRPCAddress = "ZEROYAML_RUNNER_GRPC_ADDRESS"
	envRunnerID    = "ZEROYAML_RUNNER_ID"
	envVersion     = "ZEROYAML_RUNNER_VERSION"
)

// Config contains startup configuration for the Runner process.
type Config struct {
	GRPCAddress string
	RunnerID    string
	Version     string
}

// Load reads Runner configuration from environment variables and validates it.
func Load() (Config, error) {
	cfg := Config{
		GRPCAddress: valueFromEnv(envGRPCAddress, defaultGRPCAddress),
		RunnerID:    valueFromEnv(envRunnerID, defaultRunnerID),
		Version:     valueFromEnv(envVersion, defaultVersion),
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

	return nil
}

func valueFromEnv(name string, fallback string) string {
	if value, ok := os.LookupEnv(name); ok {
		return strings.TrimSpace(value)
	}

	return fallback
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
