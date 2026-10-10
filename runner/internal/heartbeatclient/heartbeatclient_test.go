package heartbeatclient

import (
	"context"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/test/bufconn"
)

const transportBufferSize = 1024 * 1024

func TestHeartbeatReturnsAcknowledgedDecision(t *testing.T) {
	dial, cleanup := startFakeControlPlane(t, &fakeRegistrationService{
		response: &runnerv1.HeartbeatResponse{
			Result:  runnerv1.HeartbeatResult_HEARTBEAT_ACKNOWLEDGED,
			Message: "acknowledged",
		},
	})
	defer cleanup()

	result, err := heartbeat(testContext(t), "bufconn", newTestIdentity(t), dial)
	if err != nil {
		t.Fatalf("heartbeat() returned an error: %v", err)
	}

	if result.Decision != DecisionAcknowledged {
		t.Errorf("Decision = %s, want %s", result.Decision, DecisionAcknowledged)
	}
}

func TestHeartbeatReturnsUnknownRunnerDecision(t *testing.T) {
	dial, cleanup := startFakeControlPlane(t, &fakeRegistrationService{
		response: &runnerv1.HeartbeatResponse{
			Result:  runnerv1.HeartbeatResult_HEARTBEAT_UNKNOWN_RUNNER,
			Message: "not registered",
		},
	})
	defer cleanup()

	result, err := heartbeat(testContext(t), "bufconn", newTestIdentity(t), dial)
	if err != nil {
		t.Fatalf("heartbeat() returned an error: %v", err)
	}

	if result.Decision != DecisionUnknownRunner {
		t.Errorf("Decision = %s, want %s", result.Decision, DecisionUnknownRunner)
	}
}

// TestHeartbeatReportsTheRunnerAvailability shows that every heartbeat carries
// the Runner's current status and accepting-work flag next to its identity,
// which is the only way the Control Plane learns them after registration.
func TestHeartbeatReportsTheRunnerAvailability(t *testing.T) {
	service := &fakeRegistrationService{
		response: &runnerv1.HeartbeatResponse{Result: runnerv1.HeartbeatResult_HEARTBEAT_ACKNOWLEDGED},
	}
	dial, cleanup := startFakeControlPlane(t, service)
	defer cleanup()

	identity := newTestIdentity(t)
	identity.Status = runneridentity.StatusReady
	identity.AcceptingWork = true

	if _, err := heartbeat(testContext(t), "bufconn", identity, dial); err != nil {
		t.Fatalf("heartbeat() returned an error: %v", err)
	}

	request := service.lastRequest()
	if request.GetRunnerId() != identity.RunnerID {
		t.Errorf("RunnerId = %q, want %q", request.GetRunnerId(), identity.RunnerID)
	}
	if request.GetInstanceId() != identity.InstanceID {
		t.Errorf("InstanceId = %q, want %q", request.GetInstanceId(), identity.InstanceID)
	}
	if request.GetStatus() != runnerv1.RunnerStatus_RUNNER_STATUS_READY {
		t.Errorf("Status = %s, want %s", request.GetStatus(), runnerv1.RunnerStatus_RUNNER_STATUS_READY)
	}
	if !request.GetAcceptingWork() {
		t.Error("AcceptingWork = false, want true")
	}
}

// TestHeartbeatReportsARunnerThatDoesNotAcceptWork covers a Runner without a
// verified Docker daemon: it stays alive but must not be offered work.
func TestHeartbeatReportsARunnerThatDoesNotAcceptWork(t *testing.T) {
	service := &fakeRegistrationService{
		response: &runnerv1.HeartbeatResponse{Result: runnerv1.HeartbeatResult_HEARTBEAT_ACKNOWLEDGED},
	}
	dial, cleanup := startFakeControlPlane(t, service)
	defer cleanup()

	identity := newTestIdentity(t)
	identity.Status = runneridentity.StatusReady

	if _, err := heartbeat(testContext(t), "bufconn", identity, dial); err != nil {
		t.Fatalf("heartbeat() returned an error: %v", err)
	}

	request := service.lastRequest()
	if request.GetStatus() != runnerv1.RunnerStatus_RUNNER_STATUS_READY {
		t.Errorf("Status = %s, want %s", request.GetStatus(), runnerv1.RunnerStatus_RUNNER_STATUS_READY)
	}
	if request.GetAcceptingWork() {
		t.Error("AcceptingWork = true, want false")
	}
}

// TestHeartbeatReturnsAnErrorWhenTheControlPlaneIsUnreachable shows that a
// transport failure surfaces as an explicit error rather than a decision, so
// a caller never treats an unreachable Control Plane as an acknowledged
// heartbeat.
func TestHeartbeatReturnsAnErrorWhenTheControlPlaneIsUnreachable(t *testing.T) {
	listener := bufconn.Listen(transportBufferSize)
	// No server is served on the listener, so any RPC fails once the deadline
	// below elapses.
	t.Cleanup(func() {
		_ = listener.Close()
	})

	dial := func(address string) (*grpc.ClientConn, error) {
		return grpc.NewClient(
			"passthrough:///control-plane",
			grpc.WithTransportCredentials(insecure.NewCredentials()),
			grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
				return listener.DialContext(ctx)
			}),
		)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()

	_, err := heartbeat(ctx, "bufconn", newTestIdentity(t), dial)
	if err == nil {
		t.Fatal("expected an unreachable control plane to return an error")
	}
}

func TestHeartbeatReturnsAnErrorWhenDialFails(t *testing.T) {
	dial := func(address string) (*grpc.ClientConn, error) {
		return nil, errors.New("boom")
	}

	_, err := heartbeat(testContext(t), "bufconn", newTestIdentity(t), dial)
	if err == nil {
		t.Fatal("expected a dial failure to return an error")
	}
}

// fakeRegistrationService returns a fixed response for every Heartbeat call
// it receives and keeps the most recent request for inspection.
type fakeRegistrationService struct {
	runnerv1.UnimplementedRunnerRegistrationServiceServer

	response *runnerv1.HeartbeatResponse

	mu       sync.Mutex
	received *runnerv1.HeartbeatRequest
}

func (s *fakeRegistrationService) Heartbeat(
	ctx context.Context,
	req *runnerv1.HeartbeatRequest,
) (*runnerv1.HeartbeatResponse, error) {
	s.mu.Lock()
	s.received = req
	s.mu.Unlock()

	return s.response, nil
}

func (s *fakeRegistrationService) lastRequest() *runnerv1.HeartbeatRequest {
	s.mu.Lock()
	defer s.mu.Unlock()

	return s.received
}

// startFakeControlPlane serves service over an in-memory listener and returns
// a Dialer bound to it.
func startFakeControlPlane(t *testing.T, service *fakeRegistrationService) (Dialer, func()) {
	t.Helper()

	listener := bufconn.Listen(transportBufferSize)
	server := grpc.NewServer()
	runnerv1.RegisterRunnerRegistrationServiceServer(server, service)

	serveResult := make(chan error, 1)
	go func() {
		serveResult <- server.Serve(listener)
	}()

	dial := func(address string) (*grpc.ClientConn, error) {
		return grpc.NewClient(
			"passthrough:///control-plane",
			grpc.WithTransportCredentials(insecure.NewCredentials()),
			grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
				return listener.DialContext(ctx)
			}),
		)
	}

	cleanup := func() {
		server.Stop()
		if err := <-serveResult; err != nil && !errors.Is(err, grpc.ErrServerStopped) {
			t.Errorf("serving the fake control plane failed: %v", err)
		}
	}

	return dial, cleanup
}

func testContext(t *testing.T) context.Context {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)

	return ctx
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
