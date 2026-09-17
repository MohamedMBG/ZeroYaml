package main

import (
	"log"
	"net"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/grpcserver"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runnerconfig"
	"google.golang.org/grpc"
)

func main() {
	cfg, err := runnerconfig.Load()
	if err != nil {
		log.Fatalf("invalid runner configuration: %v", err)
	}

	listener, err := net.Listen("tcp", cfg.GRPCAddress)
	if err != nil {
		log.Fatalf("failed to listen on %s: %v", cfg.GRPCAddress, err)
	}

	server := grpc.NewServer()

	runnerv1.RegisterRunnerServiceServer(
		server,
		grpcserver.New(cfg.Version),
	)

	log.Printf(
		"ZeroYAML Runner gRPC server listening on %s - runner %s - version %s",
		cfg.GRPCAddress,
		cfg.RunnerID,
		cfg.Version,
	)

	if err := server.Serve(listener); err != nil {
		log.Fatalf("gRPC server failed: %v", err)
	}
}
