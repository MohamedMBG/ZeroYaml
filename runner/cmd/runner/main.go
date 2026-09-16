package main

import (
	"log"
	"net"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/grpcserver"
	"google.golang.org/grpc"
)

const (
	address = ":50051"
	version = "0.1.0"
)

func main() {
	listener, err := net.Listen("tcp", address)
	if err != nil {
		log.Fatalf("failed to listen on %s: %v", address, err)
	}

	server := grpc.NewServer()

	runnerv1.RegisterRunnerServiceServer(
		server,
		grpcserver.New(version),
	)

	log.Printf(
		"ZeroYAML Runner gRPC server listening on %s - version %s",
		address,
		version,
	)

	if err := server.Serve(listener); err != nil {
		log.Fatalf("gRPC server failed: %v", err)
	}
}
