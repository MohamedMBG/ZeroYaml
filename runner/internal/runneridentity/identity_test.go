package runneridentity

import (
	"errors"
	"reflect"
	"regexp"
	"runtime"
	"testing"
)

type failingRandomSource struct{}

func (failingRandomSource) Read([]byte) (int, error) {
	return 0, errors.New("randomness unavailable")
}

func TestNewCreatesIdentityWithRandomInstanceID(t *testing.T) {
	capabilities := Capabilities{
		OperatingSystem:    "windows",
		Architecture:       "amd64",
		DockerAvailable:    true,
		SupportedExecutors: []string{"docker"},
		Labels:             map[string]string{"region": "local"},
	}

	identity, err := New("runner-dev-01", "0.1.0", "v1", capabilities)
	if err != nil {
		t.Fatalf("New() returned an error: %v", err)
	}

	if identity.RunnerID != "runner-dev-01" {
		t.Errorf("RunnerID = %q, want %q", identity.RunnerID, "runner-dev-01")
	}

	if identity.RunnerVersion != "0.1.0" {
		t.Errorf("RunnerVersion = %q, want %q", identity.RunnerVersion, "0.1.0")
	}

	if identity.ProtocolVersion != "v1" {
		t.Errorf("ProtocolVersion = %q, want %q", identity.ProtocolVersion, "v1")
	}

	if !reflect.DeepEqual(identity.Capabilities, capabilities) {
		t.Errorf("Capabilities = %#v, want %#v", identity.Capabilities, capabilities)
	}

	instanceIDPattern := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	if !instanceIDPattern.MatchString(identity.InstanceID) {
		t.Errorf("InstanceID = %q, want a version 4 UUID", identity.InstanceID)
	}
}

func TestNewInstanceIDReturnsRandomSourceError(t *testing.T) {
	_, err := newInstanceIDFrom(failingRandomSource{})
	if err == nil {
		t.Fatal("newInstanceIDFrom() returned no error")
	}
}

func TestDefaultCapabilitiesUseRuntimeInformationWithoutDockerSupport(t *testing.T) {
	capabilities := DefaultCapabilities()

	if capabilities.OperatingSystem != runtime.GOOS {
		t.Errorf("OperatingSystem = %q, want %q", capabilities.OperatingSystem, runtime.GOOS)
	}

	if capabilities.Architecture != runtime.GOARCH {
		t.Errorf("Architecture = %q, want %q", capabilities.Architecture, runtime.GOARCH)
	}

	if capabilities.DockerAvailable {
		t.Error("DockerAvailable = true, want false before Docker execution is verified")
	}

	if len(capabilities.SupportedExecutors) != 0 {
		t.Errorf("SupportedExecutors = %v, want an empty list", capabilities.SupportedExecutors)
	}

	if len(capabilities.Labels) != 0 {
		t.Errorf("Labels = %v, want an empty map", capabilities.Labels)
	}
}

func TestNewRejectsMissingIdentityValues(t *testing.T) {
	tests := []struct {
		name            string
		runnerID        string
		runnerVersion   string
		protocolVersion string
	}{
		{name: "runner ID", runnerVersion: "0.1.0", protocolVersion: ProtocolVersion},
		{name: "runner version", runnerID: "runner-dev-01", protocolVersion: ProtocolVersion},
		{name: "protocol version", runnerID: "runner-dev-01", runnerVersion: "0.1.0"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := New(test.runnerID, test.runnerVersion, test.protocolVersion, DefaultCapabilities()); err == nil {
				t.Fatal("New() returned no error")
			}
		})
	}
}
