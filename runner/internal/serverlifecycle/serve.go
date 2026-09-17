// Package serverlifecycle owns the run loop of the Runner gRPC server: it serves
// requests until the process is asked to stop, drains in-flight RPCs within a
// bounded timeout, and then releases the listener.
//
// The package deliberately knows nothing about pipelines, jobs, or Control Plane
// policy. It only manages the lifecycle of a gRPC server that was already wired
// by the caller.
package serverlifecycle

import (
	"context"
	"errors"
	"net"
	"time"

	"google.golang.org/grpc"
)

// GracefulServer is the subset of the gRPC server lifecycle required to serve
// and to shut down. *grpc.Server satisfies it, and tests use it to exercise the
// drain-timeout path deterministically.
type GracefulServer interface {
	Serve(listener net.Listener) error
	GracefulStop()
	Stop()
}

// ServeUntilShutdown serves RPCs on listener until ctx is cancelled or until the
// server stops on its own.
//
// When ctx is cancelled the shutdown sequence is:
//
//  1. GracefulStop closes the listener so no new connection or RPC is accepted,
//     and waits for in-flight RPCs to complete.
//  2. If draining takes longer than drainTimeout, Stop cancels the remaining
//     RPCs so a slow or stuck handler cannot block process exit indefinitely.
//
// The server closes listener on either stop path, therefore ServeUntilShutdown
// takes ownership of listener and the caller must not use it afterwards. A
// non-positive drainTimeout selects the immediate Stop path.
//
// The returned error describes a real serving failure. A shutdown requested
// through ctx is normal operation and reports no error.
func ServeUntilShutdown(
	ctx context.Context,
	server GracefulServer,
	listener net.Listener,
	drainTimeout time.Duration,
) error {
	serveResult := make(chan error, 1)
	go func() {
		serveResult <- server.Serve(listener)
	}()

	select {
	case err := <-serveResult:
		return serveFailure(err)
	case <-ctx.Done():
	}

	drained := make(chan struct{})
	go func() {
		server.GracefulStop()
		close(drained)
	}()

	drainDeadline := time.NewTimer(drainTimeout)
	defer drainDeadline.Stop()

	select {
	case <-drained:
	case <-drainDeadline.C:
		// Stop cancels the remaining RPCs and releases the listener, which also
		// unblocks the GracefulStop call that is still draining.
		server.Stop()
		<-drained
	}

	return serveFailure(<-serveResult)
}

// serveFailure discards the outcomes that a requested shutdown produces, so that
// a clean stop is not reported as a serving failure.
func serveFailure(err error) error {
	if err == nil || errors.Is(err, grpc.ErrServerStopped) {
		return nil
	}

	return err
}
