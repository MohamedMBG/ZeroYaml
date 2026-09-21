package main

import (
	"context"
	"fmt"
	"log"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"syscall"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/grpcserver"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/registrationclient"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runnerconfig"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/serverlifecycle"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/startupregistration"
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

	// Registration happens once, before the identity is handed to the gRPC
	// server, so GetInfo and Ping always report a status consistent with what
	// the Control Plane was told. A Control Plane that is unreachable or that
	// rejects the request leaves the Runner unavailable rather than failing
	// startup, because Ping and GetInfo must stay reachable for diagnosis.
	identity = registerWithControlPlane(ctx, cfg, identity)

	listener, err := net.Listen("tcp", cfg.GRPCAddress)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", cfg.GRPCAddress, err)
	}

	server := grpc.NewServer()

	runnerv1.RegisterRunnerServiceServer(
		server,
		grpcserver.New(identity, slog.New(slog.NewTextHandler(os.Stderr, nil))),
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

// registerWithControlPlane attempts one startup registration and returns the
// identity the Runner should serve with. It never returns an error: a failed
// or rejected registration is logged and the Runner keeps serving so Ping and
// GetInfo stay reachable for diagnosis, but the returned identity's status
// stays unavailable so the Runner is never silently reported as ready.
func registerWithControlPlane(
	ctx context.Context,
	cfg runnerconfig.Config,
	identity runneridentity.Identity,
) runneridentity.Identity {
	outcome := startupregistration.Apply(ctx, cfg.ControlPlaneAddress, identity, cfg.RegistrationTimeout)

	if outcome.Err != nil {
		log.Printf(
			"Runner registration with the Control Plane at %s failed - runner %s stays unavailable: %v",
			cfg.ControlPlaneAddress,
			cfg.RunnerID,
			outcome.Err,
		)

		return outcome.Identity
	}

	if outcome.Result.Decision == registrationclient.DecisionIdentityConflict {
		log.Printf(
			"Control Plane at %s rejected registration for runner %s - identity conflict - %s",
			cfg.ControlPlaneAddress,
			cfg.RunnerID,
			outcome.Result.Message,
		)

		return outcome.Identity
	}

	log.Printf(
		"Runner %s registered with the Control Plane at %s - registration %s - result %s",
		cfg.RunnerID,
		cfg.ControlPlaneAddress,
		outcome.Result.RegistrationID,
		outcome.Result.Decision,
	)

	return outcome.Identity
}
