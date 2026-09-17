package runnerconfig

import (
	"os"
	"strings"
	"testing"
)

func TestLoadUsesSafeLocalDefaults(t *testing.T) {
	unsetForTest(t, envGRPCAddress)
	unsetForTest(t, envRunnerID)
	unsetForTest(t, envVersion)

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
}

func TestLoadReadsEnvironmentOverrides(t *testing.T) {
	t.Setenv(envGRPCAddress, "127.0.0.1:51001")
	t.Setenv(envRunnerID, "developer-runner")
	t.Setenv(envVersion, "1.2.3")

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
