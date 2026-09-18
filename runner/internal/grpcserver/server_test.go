package grpcserver

import (
	"context"
	"reflect"
	"testing"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
)

func TestGetInfoReturnsProcessIdentityAndCapabilities(t *testing.T) {
	identity, err := runneridentity.New(
		"runner-dev-01",
		"0.1.0",
		runneridentity.ProtocolVersion,
		runneridentity.Capabilities{
			OperatingSystem:    "windows",
			Architecture:       "amd64",
			DockerAvailable:    true,
			SupportedExecutors: []string{"docker"},
			Labels:             map[string]string{"region": "local"},
		},
	)
	if err != nil {
		t.Fatalf("runneridentity.New() returned an error: %v", err)
	}

	response, err := New(identity).GetInfo(context.Background(), &runnerv1.GetInfoRequest{})
	if err != nil {
		t.Fatalf("GetInfo() returned an error: %v", err)
	}

	if response.GetRunnerId() != identity.RunnerID {
		t.Errorf("RunnerId = %q, want %q", response.GetRunnerId(), identity.RunnerID)
	}
	if response.GetInstanceId() != identity.InstanceID {
		t.Errorf("InstanceId = %q, want %q", response.GetInstanceId(), identity.InstanceID)
	}
	if response.GetRunnerVersion() != identity.RunnerVersion {
		t.Errorf("RunnerVersion = %q, want %q", response.GetRunnerVersion(), identity.RunnerVersion)
	}
	if response.GetProtocolVersion() != identity.ProtocolVersion {
		t.Errorf("ProtocolVersion = %q, want %q", response.GetProtocolVersion(), identity.ProtocolVersion)
	}
	if response.GetStatus() != runnerv1.RunnerStatus_RUNNER_STATUS_UNAVAILABLE {
		t.Errorf("Status = %s, want unavailable", response.GetStatus())
	}
	if response.GetAcceptingWork() {
		t.Error("AcceptingWork = true, want false")
	}

	wantCapabilities := &runnerv1.RunnerCapabilities{
		OperatingSystem:    "windows",
		Architecture:       "amd64",
		DockerAvailable:    true,
		SupportedExecutors: []string{"docker"},
		Labels:             map[string]string{"region": "local"},
	}
	if !reflect.DeepEqual(response.GetCapabilities(), wantCapabilities) {
		t.Errorf("Capabilities = %#v, want %#v", response.GetCapabilities(), wantCapabilities)
	}
}

func TestPingReturnsIdentityVersion(t *testing.T) {
	identity, err := runneridentity.New("runner-dev-01", "0.1.0", runneridentity.ProtocolVersion, runneridentity.DefaultCapabilities())
	if err != nil {
		t.Fatalf("runneridentity.New() returned an error: %v", err)
	}

	response, err := New(identity).Ping(context.Background(), &runnerv1.PingRequest{Message: "control-plane"})
	if err != nil {
		t.Fatalf("Ping() returned an error: %v", err)
	}

	if response.GetMessage() != "pong: control-plane" {
		t.Errorf("Message = %q, want %q", response.GetMessage(), "pong: control-plane")
	}
	if response.GetRunnerVersion() != identity.RunnerVersion {
		t.Errorf("RunnerVersion = %q, want %q", response.GetRunnerVersion(), identity.RunnerVersion)
	}
}
