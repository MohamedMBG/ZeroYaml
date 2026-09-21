package runnerinfoproto

import (
	"testing"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
)

func TestToProtoMapsIdentityFields(t *testing.T) {
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
	identity.Status = runneridentity.StatusReady
	identity.AcceptingWork = true

	info := ToProto(identity)

	if info.GetRunnerId() != identity.RunnerID {
		t.Errorf("RunnerId = %q, want %q", info.GetRunnerId(), identity.RunnerID)
	}
	if info.GetInstanceId() != identity.InstanceID {
		t.Errorf("InstanceId = %q, want %q", info.GetInstanceId(), identity.InstanceID)
	}
	if info.GetRunnerVersion() != identity.RunnerVersion {
		t.Errorf("RunnerVersion = %q, want %q", info.GetRunnerVersion(), identity.RunnerVersion)
	}
	if info.GetProtocolVersion() != identity.ProtocolVersion {
		t.Errorf("ProtocolVersion = %q, want %q", info.GetProtocolVersion(), identity.ProtocolVersion)
	}
	if info.GetStatus() != runnerv1.RunnerStatus_RUNNER_STATUS_READY {
		t.Errorf("Status = %s, want ready", info.GetStatus())
	}
	if !info.GetAcceptingWork() {
		t.Error("AcceptingWork = false, want true")
	}

	wantCapabilities := &runnerv1.RunnerCapabilities{
		OperatingSystem:    "windows",
		Architecture:       "amd64",
		DockerAvailable:    true,
		SupportedExecutors: []string{"docker"},
		Labels:             map[string]string{"region": "local"},
	}
	if info.GetCapabilities().GetOperatingSystem() != wantCapabilities.GetOperatingSystem() ||
		info.GetCapabilities().GetArchitecture() != wantCapabilities.GetArchitecture() ||
		info.GetCapabilities().GetDockerAvailable() != wantCapabilities.GetDockerAvailable() {
		t.Errorf("Capabilities = %#v, want %#v", info.GetCapabilities(), wantCapabilities)
	}
}

func TestToProtoMapsEveryStatus(t *testing.T) {
	testCases := []struct {
		status runneridentity.Status
		want   runnerv1.RunnerStatus
	}{
		{runneridentity.StatusStarting, runnerv1.RunnerStatus_RUNNER_STATUS_STARTING},
		{runneridentity.StatusReady, runnerv1.RunnerStatus_RUNNER_STATUS_READY},
		{runneridentity.StatusDraining, runnerv1.RunnerStatus_RUNNER_STATUS_DRAINING},
		{runneridentity.StatusUnavailable, runnerv1.RunnerStatus_RUNNER_STATUS_UNAVAILABLE},
	}

	for _, tc := range testCases {
		identity := newTestIdentity(t)
		identity.Status = tc.status

		if got := ToProto(identity).GetStatus(); got != tc.want {
			t.Errorf("status %s: got %s, want %s", tc.status, got, tc.want)
		}
	}
}

func newTestIdentity(t *testing.T) runneridentity.Identity {
	t.Helper()

	identity, err := runneridentity.New(
		"runner-dev-01",
		"0.1.0",
		runneridentity.ProtocolVersion,
		runneridentity.DefaultCapabilities(),
	)
	if err != nil {
		t.Fatalf("runneridentity.New() returned an error: %v", err)
	}

	return identity
}
