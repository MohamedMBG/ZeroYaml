package grpcserver

import (
	"context"
	"reflect"
	"testing"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
)

const (
	testRunnerID      = "runner-dev-01"
	testRunnerVersion = "0.1.0"
)

func TestGetInfoReturnsProcessIdentityAndCapabilities(t *testing.T) {
	identity, err := runneridentity.New(
		testRunnerID,
		testRunnerVersion,
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

// TestPingEchoesMessageAndReportsRunnerVersion pins the Ping response content.
// Ping is a liveness probe: it answers from process identity alone and echoes
// the caller message verbatim so that a caller can correlate a reply with the
// request that produced it. The message is deliberately neither validated nor
// normalized, because rejecting a probe would hide a reachable Runner.
func TestPingEchoesMessageAndReportsRunnerVersion(t *testing.T) {
	testCases := []struct {
		name        string
		message     string
		wantMessage string
	}{
		{
			name:        "caller message is echoed",
			message:     "control-plane",
			wantMessage: "pong: control-plane",
		},
		{
			name:        "absent message still produces a reply",
			message:     "",
			wantMessage: "pong: ",
		},
		{
			name:        "surrounding whitespace is preserved",
			message:     "  control-plane  ",
			wantMessage: "pong:   control-plane  ",
		},
		{
			name:        "non-ascii message is preserved",
			message:     "contrôle ✓",
			wantMessage: "pong: contrôle ✓",
		},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			identity := newTestIdentity(t)

			response, err := New(identity).Ping(
				context.Background(),
				&runnerv1.PingRequest{Message: testCase.message},
			)
			if err != nil {
				t.Fatalf("Ping() returned an error: %v", err)
			}

			if response.GetMessage() != testCase.wantMessage {
				t.Errorf("Message = %q, want %q", response.GetMessage(), testCase.wantMessage)
			}
			if response.GetRunnerVersion() != identity.RunnerVersion {
				t.Errorf("RunnerVersion = %q, want %q", response.GetRunnerVersion(), identity.RunnerVersion)
			}
		})
	}
}

// TestPingToleratesNilRequest documents handler behavior for an absent request.
// gRPC never delivers a nil request to a handler, but an in-process caller can,
// and a liveness probe must answer instead of panicking.
func TestPingToleratesNilRequest(t *testing.T) {
	identity := newTestIdentity(t)

	response, err := New(identity).Ping(context.Background(), nil)
	if err != nil {
		t.Fatalf("Ping() returned an error: %v", err)
	}

	if response.GetMessage() != "pong: " {
		t.Errorf("Message = %q, want %q", response.GetMessage(), "pong: ")
	}
	if response.GetRunnerVersion() != identity.RunnerVersion {
		t.Errorf("RunnerVersion = %q, want %q", response.GetRunnerVersion(), identity.RunnerVersion)
	}
}

// newTestIdentity builds the identity that a Runner process holds after startup.
func newTestIdentity(t *testing.T) runneridentity.Identity {
	t.Helper()

	identity, err := runneridentity.New(
		testRunnerID,
		testRunnerVersion,
		runneridentity.ProtocolVersion,
		runneridentity.DefaultCapabilities(),
	)
	if err != nil {
		t.Fatalf("runneridentity.New() returned an error: %v", err)
	}

	return identity
}
