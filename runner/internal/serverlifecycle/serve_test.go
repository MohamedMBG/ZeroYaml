package serverlifecycle

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
)

// testDrainTimeout is long enough that the drain phase of the graceful tests is
// never bounded by the timer, so those tests observe draining and not forcing.
const testDrainTimeout = 30 * time.Second

func TestServeUntilShutdownCompletesInFlightRPC(t *testing.T) {
	runner := startTestRunner(t, testDrainTimeout)

	pingResult := make(chan error, 1)
	go func() {
		_, err := runner.client.Ping(context.Background(), &runnerv1.PingRequest{Message: "in-flight"})
		pingResult <- err
	}()

	<-runner.service.entered
	runner.requestShutdown()

	<-runner.listenerClosed
	runner.service.completeRequests()

	if err := <-pingResult; err != nil {
		t.Fatalf("expected the in-flight RPC to complete during shutdown: %v", err)
	}
	if err := runner.waitForServeResult(); err != nil {
		t.Fatalf("expected a requested shutdown to report no error: %v", err)
	}
}

func TestServeUntilShutdownStopsAcceptingNewRPCs(t *testing.T) {
	runner := startTestRunner(t, testDrainTimeout)

	pingResult := make(chan error, 1)
	go func() {
		_, err := runner.client.Ping(context.Background(), &runnerv1.PingRequest{Message: "in-flight"})
		pingResult <- err
	}()

	<-runner.service.entered
	runner.requestShutdown()

	// The listener is closed before in-flight work finishes, so a client that
	// arrives during the drain phase cannot reach the Runner.
	<-runner.listenerClosed

	lateClient, closeLateClient := newTestClient(t, runner.address)
	defer closeLateClient()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	_, err := lateClient.Ping(ctx, &runnerv1.PingRequest{Message: "late"})
	if err == nil {
		t.Fatal("expected an RPC started during shutdown to be refused")
	}
	if code := status.Code(err); code != codes.Unavailable {
		t.Fatalf("expected an unavailable Runner, got code %s: %v", code, err)
	}

	runner.service.completeRequests()

	if err := <-pingResult; err != nil {
		t.Fatalf("expected the in-flight RPC to complete during shutdown: %v", err)
	}
	if err := runner.waitForServeResult(); err != nil {
		t.Fatalf("expected a requested shutdown to report no error: %v", err)
	}
}

func TestServeUntilShutdownReleasesListener(t *testing.T) {
	runner := startTestRunner(t, testDrainTimeout)

	runner.requestShutdown()

	if err := runner.waitForServeResult(); err != nil {
		t.Fatalf("expected a requested shutdown to report no error: %v", err)
	}

	if _, err := runner.listener.Accept(); err == nil {
		t.Fatal("expected the listener to be closed after shutdown")
	}
}

func TestServeUntilShutdownForcesStopWhenDrainTimeoutExpires(t *testing.T) {
	// The stub never lets GracefulStop return, which is how a handler that
	// refuses to finish behaves. Shutdown must still complete.
	server := newLifecycleServerStub()

	ctx, requestShutdown := context.WithCancel(context.Background())
	defer requestShutdown()

	serveResult := make(chan error, 1)
	go func() {
		serveResult <- ServeUntilShutdown(ctx, server, nil, time.Millisecond)
	}()

	<-server.serving
	requestShutdown()

	if err := <-serveResult; err != nil {
		t.Fatalf("expected a forced shutdown to report no error: %v", err)
	}
	if !server.gracefulStopCalled.Load() {
		t.Fatal("expected draining to be attempted before forcing a stop")
	}
	if !server.stopCalled.Load() {
		t.Fatal("expected the drain timeout to force a stop")
	}
}

func TestServeUntilShutdownDoesNotForceStopWhenDrainingSucceeds(t *testing.T) {
	server := newLifecycleServerStub()
	server.allowGracefulStopToReturn()

	ctx, requestShutdown := context.WithCancel(context.Background())
	defer requestShutdown()

	serveResult := make(chan error, 1)
	go func() {
		serveResult <- ServeUntilShutdown(ctx, server, nil, testDrainTimeout)
	}()

	<-server.serving
	requestShutdown()

	if err := <-serveResult; err != nil {
		t.Fatalf("expected a graceful shutdown to report no error: %v", err)
	}
	if !server.gracefulStopCalled.Load() {
		t.Fatal("expected the server to be drained")
	}
	if server.stopCalled.Load() {
		t.Fatal("expected no forced stop when draining finished within the timeout")
	}
}

func TestServeUntilShutdownReturnsServingFailure(t *testing.T) {
	serveFailure := errors.New("listener rejected connections")

	server := newLifecycleServerStub()
	server.serveError = serveFailure
	server.stopServing()

	err := ServeUntilShutdown(context.Background(), server, nil, testDrainTimeout)
	if !errors.Is(err, serveFailure) {
		t.Fatalf("expected the serving failure to be reported, got %v", err)
	}
}

func TestServeUntilShutdownTreatsStoppedServerAsCleanExit(t *testing.T) {
	server := newLifecycleServerStub()
	server.serveError = grpc.ErrServerStopped
	server.stopServing()

	if err := ServeUntilShutdown(context.Background(), server, nil, testDrainTimeout); err != nil {
		t.Fatalf("expected a stopped server to report no error: %v", err)
	}
}

// testRunner wires a real gRPC server around ServeUntilShutdown so that shutdown
// behavior is observed through actual RPCs.
type testRunner struct {
	address         string
	listener        net.Listener
	listenerClosed  <-chan struct{}
	client          runnerv1.RunnerServiceClient
	service         *blockingPingService
	requestShutdown context.CancelFunc
	serveResult     chan error
}

func startTestRunner(t *testing.T, drainTimeout time.Duration) *testRunner {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to listen on a local port: %v", err)
	}

	observedListener := newCloseObservingListener(listener)
	service := newBlockingPingService()

	server := grpc.NewServer()
	runnerv1.RegisterRunnerServiceServer(server, service)

	ctx, requestShutdown := context.WithCancel(context.Background())

	serveResult := make(chan error, 1)
	go func() {
		serveResult <- ServeUntilShutdown(ctx, server, observedListener, drainTimeout)
	}()

	client, closeClient := newTestClient(t, listener.Addr().String())

	runner := &testRunner{
		address:         listener.Addr().String(),
		listener:        listener,
		listenerClosed:  observedListener.closed,
		client:          client,
		service:         service,
		requestShutdown: requestShutdown,
		serveResult:     serveResult,
	}

	t.Cleanup(func() {
		requestShutdown()
		service.completeRequests()
		<-serveResult
		closeClient()
	})

	return runner
}

// waitForServeResult blocks until serving ends and puts the outcome back on the
// buffered channel, so test cleanup can wait on the same signal.
func (r *testRunner) waitForServeResult() error {
	err := <-r.serveResult
	r.serveResult <- err

	return err
}

func newTestClient(t *testing.T, address string) (runnerv1.RunnerServiceClient, func()) {
	t.Helper()

	connection, err := grpc.NewClient(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatalf("failed to create a client for %s: %v", address, err)
	}

	return runnerv1.NewRunnerServiceClient(connection), func() {
		if err := connection.Close(); err != nil {
			t.Errorf("failed to close the client connection: %v", err)
		}
	}
}

// blockingPingService keeps a Ping call in flight until the test releases it,
// which is what makes the drain phase observable.
type blockingPingService struct {
	runnerv1.UnimplementedRunnerServiceServer

	entered     chan struct{}
	enteredOnce sync.Once
	complete    chan struct{}
	completeNow sync.Once
}

func newBlockingPingService() *blockingPingService {
	return &blockingPingService{
		entered:  make(chan struct{}),
		complete: make(chan struct{}),
	}
}

func (s *blockingPingService) Ping(
	ctx context.Context,
	req *runnerv1.PingRequest,
) (*runnerv1.PingResponse, error) {
	s.enteredOnce.Do(func() {
		close(s.entered)
	})

	select {
	case <-s.complete:
	case <-ctx.Done():
		return nil, ctx.Err()
	}

	return &runnerv1.PingResponse{
		Message:       "pong: " + req.GetMessage(),
		RunnerVersion: "test",
	}, nil
}

func (s *blockingPingService) completeRequests() {
	s.completeNow.Do(func() {
		close(s.complete)
	})
}

// closeObservingListener reports when the gRPC server releases the listener, so
// tests can act on shutdown progress without polling.
type closeObservingListener struct {
	net.Listener

	closed    chan struct{}
	closeOnce sync.Once
}

func newCloseObservingListener(listener net.Listener) *closeObservingListener {
	return &closeObservingListener{
		Listener: listener,
		closed:   make(chan struct{}),
	}
}

func (l *closeObservingListener) Close() error {
	err := l.Listener.Close()

	l.closeOnce.Do(func() {
		close(l.closed)
	})

	return err
}

// lifecycleServerStub implements GracefulServer without a transport so the drain
// timeout and the failure paths can be exercised deterministically. It ignores
// the listener it is given.
type lifecycleServerStub struct {
	serving      chan struct{}
	servingOnce  sync.Once
	stopped      chan struct{}
	stoppedOnce  sync.Once
	gracefulDone chan struct{}
	gracefulOnce sync.Once

	serveError         error
	gracefulStopCalled atomic.Bool
	stopCalled         atomic.Bool
}

func newLifecycleServerStub() *lifecycleServerStub {
	return &lifecycleServerStub{
		serving:      make(chan struct{}),
		stopped:      make(chan struct{}),
		gracefulDone: make(chan struct{}),
	}
}

func (s *lifecycleServerStub) Serve(net.Listener) error {
	s.servingOnce.Do(func() {
		close(s.serving)
	})

	<-s.stopped

	return s.serveError
}

func (s *lifecycleServerStub) GracefulStop() {
	s.gracefulStopCalled.Store(true)

	// Draining only ends when the test allows it or when Stop forces it.
	select {
	case <-s.gracefulDone:
	case <-s.stopped:
	}

	s.stopServing()
}

func (s *lifecycleServerStub) Stop() {
	s.stopCalled.Store(true)
	s.stopServing()
}

// allowGracefulStopToReturn makes draining finish on its own, which models a
// server with no stuck in-flight RPC.
func (s *lifecycleServerStub) allowGracefulStopToReturn() {
	s.gracefulOnce.Do(func() {
		close(s.gracefulDone)
	})
}

func (s *lifecycleServerStub) stopServing() {
	s.stoppedOnce.Do(func() {
		close(s.stopped)
	})
}
