package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"syscall"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/grpcserver"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runnerconfig"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/serverlifecycle"
	"google.golang.org/grpc"
)

func main() {
	if err := run(); err != nil {
		log.Fatalf("ZeroYAML Runner stopped with an error: %v", err)
	}
}

// run wires the Runner process and serves until a termination signal arrives.
// Keeping the wiring here leaves main free of error handling and makes the
// startup and shutdown order explicit.
func run() error {
	cfg, err := runnerconfig.Load()
	if err != nil {
		return fmt.Errorf("invalid runner configuration: %w", err)
	}

	identity, err := runneridentity.New(
		cfg.RunnerID,
		cfg.Version,
		runneridentity.ProtocolVersion,
		runneridentity.DefaultCapabilities(),
	)
	if err != nil {
		return fmt.Errorf("failed to create runner identity: %w", err)
	}

	// SIGINT covers local development and SIGTERM covers container and service
	// managers. The signal handler is installed before the listener is opened so
	// that a signal arriving during startup still triggers an ordered shutdown.
	ctx, stopListeningForSignals := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stopListeningForSignals()

	listener, err := net.Listen("tcp", cfg.GRPCAddress)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", cfg.GRPCAddress, err)
	}

	server := grpc.NewServer()

	runnerv1.RegisterRunnerServiceServer(
		server,
		grpcserver.New(identity),
	)

	log.Printf(
		"ZeroYAML Runner gRPC server listening on %s - runner %s - instance %s - version %s - protocol %s",
		cfg.GRPCAddress,
		cfg.RunnerID,
		identity.InstanceID,
		cfg.Version,
		identity.ProtocolVersion,
	)

	// ServeUntilShutdown owns the listener from this point and closes it on every
	// stop path.
	if err := serverlifecycle.ServeUntilShutdown(ctx, server, listener, cfg.ShutdownTimeout); err != nil {
		return fmt.Errorf("gRPC server failed: %w", err)
	}

	log.Printf(
		"ZeroYAML Runner gRPC server shut down - runner %s - drain timeout %s",
		cfg.RunnerID,
		cfg.ShutdownTimeout,
	)

	return nil
}
