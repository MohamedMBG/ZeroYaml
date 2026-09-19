package grpcserver

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/mem"
	"google.golang.org/grpc/status"
	"google.golang.org/grpc/test/bufconn"
)

// The transport tests in this file drive the Runner through the generated gRPC
// client over an in-memory connection. They therefore cover request and
// response marshaling, method dispatch, and status mapping without binding a
// port, starting a Runner process, or requiring Docker.

// transportBufferSize is large enough that a test RPC never blocks on the
// in-memory pipe.
const transportBufferSize = 1024 * 1024

// rpcTimeout bounds every test RPC so that a dispatch regression fails the test
// instead of hanging the suite.
const rpcTimeout = 10 * time.Second

// concurrentPingCallers is the number of simultaneous probes used to show that
// the service keeps no mutable per-call state.
const concurrentPingCallers = 16

func TestPingOverGeneratedClientReturnsEchoAndRunnerVersion(t *testing.T) {
	identity := newTestIdentity(t)
	connection, service := startTestRunnerService(t, identity)

	client := runnerv1.NewRunnerServiceClient(connection)

	response, err := client.Ping(testContext(t), &runnerv1.PingRequest{Message: "control-plane"})
	if err != nil {
		t.Fatalf("Ping() returned an error: %v", err)
	}

	if response.GetMessage() != "pong: control-plane" {
		t.Errorf("Message = %q, want %q", response.GetMessage(), "pong: control-plane")
	}
	if response.GetRunnerVersion() != identity.RunnerVersion {
		t.Errorf("RunnerVersion = %q, want %q", response.GetRunnerVersion(), identity.RunnerVersion)
	}
	if calls := service.pingCalls.Load(); calls != 1 {
		t.Errorf("handler entries = %d, want 1", calls)
	}
}

// TestPingOverGeneratedClientAcceptsRequestWithoutMessage covers the request a
// caller sends when it only wants to know whether the Runner is reachable. The
// unset field arrives as an empty string, which is a valid probe and not an
// error.
func TestPingOverGeneratedClientAcceptsRequestWithoutMessage(t *testing.T) {
	identity := newTestIdentity(t)
	connection, service := startTestRunnerService(t, identity)

	client := runnerv1.NewRunnerServiceClient(connection)

	response, err := client.Ping(testContext(t), &runnerv1.PingRequest{})
	if err != nil {
		t.Fatalf("Ping() returned an error: %v", err)
	}

	if response.GetMessage() != "pong: " {
		t.Errorf("Message = %q, want %q", response.GetMessage(), "pong: ")
	}
	if response.GetRunnerVersion() != identity.RunnerVersion {
		t.Errorf("RunnerVersion = %q, want %q", response.GetRunnerVersion(), identity.RunnerVersion)
	}
	if calls := service.pingCalls.Load(); calls != 1 {
		t.Errorf("handler entries = %d, want 1", calls)
	}
}

// TestPingRejectsMalformedRequestPayload sends a payload that cannot be decoded
// as a PingRequest. The Runner is a remote execution boundary, so a caller that
// does not honor the contract must be rejected by the transport before any
// Runner code observes the request.
func TestPingRejectsMalformedRequestPayload(t *testing.T) {
	connection, service := startTestRunnerService(t, newTestIdentity(t))

	// The first byte of a protobuf field is its tag, and wire type 7 is not
	// defined, so these bytes can never decode as a PingRequest.
	malformedPayload := []byte{0xff}
	var reply []byte

	err := connection.Invoke(
		testContext(t),
		runnerv1.RunnerService_Ping_FullMethodName,
		malformedPayload,
		&reply,
		grpc.ForceCodecV2(rawPayloadCodec{}),
	)
	if err == nil {
		t.Fatal("expected a malformed request payload to be rejected")
	}
	if code := status.Code(err); code != codes.Internal {
		t.Errorf("status code = %s, want %s: %v", code, codes.Internal, err)
	}
	if calls := service.pingCalls.Load(); calls != 0 {
		t.Errorf("handler entries = %d, want 0 for a malformed request", calls)
	}
}

// TestUnknownRunnerMethodIsUnimplemented shows that the Runner exposes only the
// methods declared in the contract, so a caller asking for anything else gets an
// explicit answer instead of a hang or a generic failure.
func TestUnknownRunnerMethodIsUnimplemented(t *testing.T) {
	connection, service := startTestRunnerService(t, newTestIdentity(t))

	err := connection.Invoke(
		testContext(t),
		// The method name is intentionally absent from the contract, and it is
		// not a planned one, so this test stays valid as the service grows.
		"/zeroyaml.runner.v1.RunnerService/NotInTheContract",
		&runnerv1.PingRequest{Message: "control-plane"},
		&runnerv1.PingResponse{},
	)
	if err == nil {
		t.Fatal("expected an unknown method to be rejected")
	}
	if code := status.Code(err); code != codes.Unimplemented {
		t.Errorf("status code = %s, want %s: %v", code, codes.Unimplemented, err)
	}
	if calls := service.pingCalls.Load(); calls != 0 {
		t.Errorf("handler entries = %d, want 0 for an unknown method", calls)
	}
}

// TestPingIsSafeForConcurrentCallers exercises the probe from several callers at
// once, because a Runner answers liveness checks while it serves other work. The
// service holds no mutable state, so every caller must observe the same reply.
func TestPingIsSafeForConcurrentCallers(t *testing.T) {
	identity := newTestIdentity(t)
	connection, service := startTestRunnerService(t, identity)

	client := runnerv1.NewRunnerServiceClient(connection)
	ctx := testContext(t)

	var callers sync.WaitGroup
	failures := make(chan error, concurrentPingCallers)

	for caller := 0; caller < concurrentPingCallers; caller++ {
		callers.Add(1)

		go func() {
			defer callers.Done()

			response, err := client.Ping(ctx, &runnerv1.PingRequest{Message: "control-plane"})
			if err != nil {
				failures <- err

				return
			}

			if response.GetMessage() != "pong: control-plane" {
				failures <- errors.New("unexpected message " + response.GetMessage())

				return
			}
			if response.GetRunnerVersion() != identity.RunnerVersion {
				failures <- errors.New("unexpected runner version " + response.GetRunnerVersion())
			}
		}()
	}

	callers.Wait()
	close(failures)

	for err := range failures {
		t.Errorf("concurrent Ping() failed: %v", err)
	}

	if calls := service.pingCalls.Load(); calls != concurrentPingCallers {
		t.Errorf("handler entries = %d, want %d", calls, concurrentPingCallers)
	}
}

// rawPayloadCodec sends and receives a message body byte-for-byte. It lets a
// test present the Runner with bytes that no generated client would produce,
// which is how an unknown or faulty caller reaches a remote boundary.
type rawPayloadCodec struct{}

func (rawPayloadCodec) Marshal(v any) (mem.BufferSlice, error) {
	payload, ok := v.([]byte)
	if !ok {
		return nil, fmt.Errorf("raw payload codec marshals []byte, got %T", v)
	}

	return mem.BufferSlice{mem.SliceBuffer(payload)}, nil
}

func (rawPayloadCodec) Unmarshal(data mem.BufferSlice, v any) error {
	target, ok := v.(*[]byte)
	if !ok {
		return fmt.Errorf("raw payload codec unmarshals into *[]byte, got %T", v)
	}

	*target = data.Materialize()

	return nil
}

func (rawPayloadCodec) Name() string {
	return "raw-payload-test-codec"
}

// recordingRunnerService counts handler entries so that a transport test can
// prove whether a request reached the Runner implementation. It delegates every
// decision to the real service.
type recordingRunnerService struct {
	*Server

	pingCalls atomic.Int64
}

func (s *recordingRunnerService) Ping(
	ctx context.Context,
	req *runnerv1.PingRequest,
) (*runnerv1.PingResponse, error) {
	s.pingCalls.Add(1)

	return s.Server.Ping(ctx, req)
}

// startTestRunnerService serves the Runner over an in-memory listener and
// returns a client connection to it. The server and the connection are released
// when the test ends.
func startTestRunnerService(
	t *testing.T,
	identity runneridentity.Identity,
) (*grpc.ClientConn, *recordingRunnerService) {
	t.Helper()

	listener := bufconn.Listen(transportBufferSize)
	service := &recordingRunnerService{Server: New(identity)}

	server := grpc.NewServer()
	runnerv1.RegisterRunnerServiceServer(server, service)

	serveResult := make(chan error, 1)
	go func() {
		serveResult <- server.Serve(listener)
	}()

	// The target is a placeholder: the dialer below ignores it and returns the
	// in-memory connection instead of resolving a name or opening a socket.
	connection, err := grpc.NewClient(
		"passthrough:///runner",
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
			return listener.DialContext(ctx)
		}),
	)
	if err != nil {
		server.Stop()
		<-serveResult
		t.Fatalf("failed to create a client for the test Runner: %v", err)
	}

	t.Cleanup(func() {
		if err := connection.Close(); err != nil {
			t.Errorf("failed to close the client connection: %v", err)
		}

		server.Stop()

		if err := <-serveResult; err != nil && !errors.Is(err, grpc.ErrServerStopped) {
			t.Errorf("serving the test Runner failed: %v", err)
		}
	})

	return connection, service
}

// testContext bounds an RPC and releases its resources when the test ends.
func testContext(t *testing.T) context.Context {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), rpcTimeout)
	t.Cleanup(cancel)

	return ctx
}
