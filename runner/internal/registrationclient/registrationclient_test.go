package registrationclient

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/test/bufconn"
)

const transportBufferSize = 1024 * 1024

func TestRegisterReturnsAcceptedDecision(t *testing.T) {
	dial, cleanup := startFakeControlPlane(t, &fakeRegistrationService{
		response: &runnerv1.RegisterRunnerResponse{
			Result:         runnerv1.RegistrationResult_REGISTRATION_ACCEPTED,
			RegistrationId: "registration-1",
			Message:        "accepted",
		},
	})
	defer cleanup()

	result, err := register(testContext(t), "bufconn", newTestIdentity(t), dial)
	if err != nil {
		t.Fatalf("register() returned an error: %v", err)
	}

	if result.Decision != DecisionAccepted {
		t.Errorf("Decision = %s, want %s", result.Decision, DecisionAccepted)
	}
	if result.RegistrationID != "registration-1" {
		t.Errorf("RegistrationID = %q, want %q", result.RegistrationID, "registration-1")
	}
}

func TestRegisterReturnsAlreadyRegisteredDecision(t *testing.T) {
	dial, cleanup := startFakeControlPlane(t, &fakeRegistrationService{
		response: &runnerv1.RegisterRunnerResponse{
			Result:         runnerv1.RegistrationResult_REGISTRATION_ALREADY_REGISTERED,
			RegistrationId: "registration-1",
			Message:        "already registered",
		},
	})
	defer cleanup()

	result, err := register(testContext(t), "bufconn", newTestIdentity(t), dial)
	if err != nil {
		t.Fatalf("register() returned an error: %v", err)
	}

	if result.Decision != DecisionAlreadyRegistered {
		t.Errorf("Decision = %s, want %s", result.Decision, DecisionAlreadyRegistered)
	}
}

func TestRegisterReturnsIdentityConflictDecision(t *testing.T) {
	dial, cleanup := startFakeControlPlane(t, &fakeRegistrationService{
		response: &runnerv1.RegisterRunnerResponse{
			Result:  runnerv1.RegistrationResult_REGISTRATION_IDENTITY_CONFLICT,
			Message: "identity conflict",
		},
	})
	defer cleanup()

	result, err := register(testContext(t), "bufconn", newTestIdentity(t), dial)
	if err != nil {
		t.Fatalf("register() returned an error: %v", err)
	}

	if result.Decision != DecisionIdentityConflict {
		t.Errorf("Decision = %s, want %s", result.Decision, DecisionIdentityConflict)
	}
	if result.RegistrationID != "" {
		t.Errorf("RegistrationID = %q, want empty", result.RegistrationID)
	}
}

// TestRegisterReturnsAnErrorWhenTheControlPlaneIsUnreachable shows that a
// transport failure surfaces as an explicit error rather than a decision, so
// a caller never treats an unreachable Control Plane as a rejected or
// accepted registration.
func TestRegisterReturnsAnErrorWhenTheControlPlaneIsUnreachable(t *testing.T) {
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

	_, err := register(ctx, "bufconn", newTestIdentity(t), dial)
	if err == nil {
		t.Fatal("expected an unreachable control plane to return an error")
	}
}

func TestRegisterReturnsAnErrorWhenDialFails(t *testing.T) {
	dial := func(address string) (*grpc.ClientConn, error) {
		return nil, errors.New("boom")
	}

	_, err := register(testContext(t), "bufconn", newTestIdentity(t), dial)
	if err == nil {
		t.Fatal("expected a dial failure to return an error")
	}
}

// fakeRegistrationService returns a fixed response, or the transport error
// when set, for every Register call it receives.
type fakeRegistrationService struct {
	runnerv1.UnimplementedRunnerRegistrationServiceServer

	response *runnerv1.RegisterRunnerResponse
}

func (s *fakeRegistrationService) Register(
	ctx context.Context,
	req *runnerv1.RegisterRunnerRequest,
) (*runnerv1.RegisterRunnerResponse, error) {
	return s.response, nil
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
