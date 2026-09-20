package runnerconfig

import (
	"os"
	"strings"
	"testing"
	"time"
)

func TestLoadUsesSafeLocalDefaults(t *testing.T) {
	unsetForTest(t, envGRPCAddress)
	unsetForTest(t, envRunnerID)
	unsetForTest(t, envVersion)
	unsetForTest(t, envShutdownTimeout)
	unsetForTest(t, envControlPlaneAddress)
	unsetForTest(t, envRegistrationTimeout)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("expected defaults to load: %v", err)
	}

	if cfg.GRPCAddress != defaultGRPCAddress {
		t.Fatalf("expected gRPC address %q, got %q", defaultGRPCAddress, cfg.GRPCAddress)
	}
	if cfg.RunnerID != defaultRunnerID {
		t.Fatalf("expected runner ID %q, got %q", defaultRunnerID, cfg.RunnerID)
	}
	if cfg.Version != defaultVersion {
		t.Fatalf("expected version %q, got %q", defaultVersion, cfg.Version)
	}
	if cfg.ShutdownTimeout != defaultShutdownTimeout {
		t.Fatalf("expected shutdown timeout %s, got %s", defaultShutdownTimeout, cfg.ShutdownTimeout)
	}
	if cfg.ControlPlaneAddress != defaultControlPlaneAddress {
		t.Fatalf("expected control plane address %q, got %q", defaultControlPlaneAddress, cfg.ControlPlaneAddress)
	}
	if cfg.RegistrationTimeout != defaultRegistrationTimeout {
		t.Fatalf("expected registration timeout %s, got %s", defaultRegistrationTimeout, cfg.RegistrationTimeout)
	}
}

func TestLoadReadsEnvironmentOverrides(t *testing.T) {
	t.Setenv(envGRPCAddress, "127.0.0.1:51001")
	t.Setenv(envRunnerID, "developer-runner")
	t.Setenv(envVersion, "1.2.3")
	t.Setenv(envShutdownTimeout, "250ms")
	t.Setenv(envControlPlaneAddress, "127.0.0.1:51002")
	t.Setenv(envRegistrationTimeout, "500ms")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("expected environment overrides to load: %v", err)
	}

	if cfg.GRPCAddress != "127.0.0.1:51001" {
		t.Fatalf("expected override gRPC address, got %q", cfg.GRPCAddress)
	}
	if cfg.RunnerID != "developer-runner" {
		t.Fatalf("expected override runner ID, got %q", cfg.RunnerID)
	}
	if cfg.Version != "1.2.3" {
		t.Fatalf("expected override version, got %q", cfg.Version)
	}
	if cfg.ShutdownTimeout != 250*time.Millisecond {
		t.Fatalf("expected override shutdown timeout, got %s", cfg.ShutdownTimeout)
	}
	if cfg.ControlPlaneAddress != "127.0.0.1:51002" {
		t.Fatalf("expected override control plane address, got %q", cfg.ControlPlaneAddress)
	}
	if cfg.RegistrationTimeout != 500*time.Millisecond {
		t.Fatalf("expected override registration timeout, got %s", cfg.RegistrationTimeout)
	}
}

func TestLoadRejectsInvalidControlPlaneAddress(t *testing.T) {
	tests := []struct {
		name      string
		address   string
		wantError string
	}{
		{name: "empty", address: " ", wantError: envControlPlaneAddress},
		{name: "missing port", address: "localhost", wantError: envControlPlaneAddress},
		{name: "non numeric port", address: "localhost:http", wantError: "port must be numeric"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv(envGRPCAddress, defaultGRPCAddress)
			t.Setenv(envRunnerID, defaultRunnerID)
			t.Setenv(envVersion, defaultVersion)
			t.Setenv(envControlPlaneAddress, tt.address)

			_, err := Load()
			if err == nil {
				t.Fatal("expected an invalid control plane address to fail")
			}
			if !strings.Contains(err.Error(), tt.wantError) {
				t.Fatalf("expected error containing %q, got %q", tt.wantError, err.Error())
			}
		})
	}
}

func TestLoadRejectsInvalidRegistrationTimeout(t *testing.T) {
	tests := []struct {
		name                string
		registrationTimeout string
		wantError           string
	}{
		{name: "not a duration", registrationTimeout: "soon", wantError: "must be a Go duration"},
		{name: "zero", registrationTimeout: "0s", wantError: "must be greater than zero"},
		{name: "negative", registrationTimeout: "-1s", wantError: "must be greater than zero"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv(envGRPCAddress, defaultGRPCAddress)
			t.Setenv(envRunnerID, defaultRunnerID)
			t.Setenv(envVersion, defaultVersion)
			t.Setenv(envRegistrationTimeout, tt.registrationTimeout)

			_, err := Load()
			if err == nil {
				t.Fatal("expected an invalid registration timeout to fail")
			}
			if !strings.Contains(err.Error(), tt.wantError) {
				t.Fatalf("expected error containing %q, got %q", tt.wantError, err.Error())
			}
		})
	}
}

func TestLoadRejectsInvalidShutdownTimeout(t *testing.T) {
	tests := []struct {
		name            string
		shutdownTimeout string
		wantError       string
	}{
		{
			name:            "not a duration",
			shutdownTimeout: "soon",
			wantError:       "must be a Go duration",
		},
		{
			name:            "missing unit",
			shutdownTimeout: "5",
			wantError:       "must be a Go duration",
		},
		{
			name:            "zero",
			shutdownTimeout: "0s",
			wantError:       "must be greater than zero",
		},
		{
			name:            "negative",
			shutdownTimeout: "-1s",
			wantError:       "must be greater than zero",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv(envGRPCAddress, defaultGRPCAddress)
			t.Setenv(envRunnerID, defaultRunnerID)
			t.Setenv(envVersion, defaultVersion)
			t.Setenv(envShutdownTimeout, tt.shutdownTimeout)

			_, err := Load()
			if err == nil {
				t.Fatal("expected an invalid shutdown timeout to fail")
			}
			if !strings.Contains(err.Error(), envShutdownTimeout) {
				t.Fatalf("expected the error to name %s, got %q", envShutdownTimeout, err.Error())
			}
			if !strings.Contains(err.Error(), tt.wantError) {
				t.Fatalf("expected error containing %q, got %q", tt.wantError, err.Error())
			}
		})
	}
}

func unsetForTest(t *testing.T, name string) {
	t.Helper()

	previousValue, wasSet := os.LookupEnv(name)
	if err := os.Unsetenv(name); err != nil {
		t.Fatalf("failed to unset %s: %v", name, err)
	}

	t.Cleanup(func() {
		if wasSet {
			if err := os.Setenv(name, previousValue); err != nil {
				t.Fatalf("failed to restore %s: %v", name, err)
			}
			return
		}

		if err := os.Unsetenv(name); err != nil {
			t.Fatalf("failed to keep %s unset: %v", name, err)
		}
	})
}

func TestLoadRejectsInvalidConfiguration(t *testing.T) {
	tests := []struct {
		name      string
		address   string
		runnerID  string
		version   string
		wantError string
	}{
		{
			name:      "empty address",
			address:   " ",
			runnerID:  defaultRunnerID,
			version:   defaultVersion,
			wantError: envGRPCAddress,
		},
		{
			name:      "missing port",
			address:   "localhost",
			runnerID:  defaultRunnerID,
			version:   defaultVersion,
			wantError: envGRPCAddress,
		},
		{
			name:      "non numeric port",
			address:   "localhost:http",
			runnerID:  defaultRunnerID,
			version:   defaultVersion,
			wantError: "port must be numeric",
		},
		{
			name:      "port out of range",
			address:   ":70000",
			runnerID:  defaultRunnerID,
			version:   defaultVersion,
			wantError: "port must be between 1 and 65535",
		},
		{
			name:      "empty runner ID",
			address:   defaultGRPCAddress,
			runnerID:  " ",
			version:   defaultVersion,
			wantError: envRunnerID,
		},
		{
			name:      "empty version",
			address:   defaultGRPCAddress,
			runnerID:  defaultRunnerID,
			version:   " ",
			wantError: envVersion,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv(envGRPCAddress, tt.address)
			t.Setenv(envRunnerID, tt.runnerID)
			t.Setenv(envVersion, tt.version)

			_, err := Load()
			if err == nil {
				t.Fatal("expected invalid configuration to fail")
			}
			if !strings.Contains(err.Error(), tt.wantError) {
				t.Fatalf("expected error containing %q, got %q", tt.wantError, err.Error())
			}
		})
	}
}
