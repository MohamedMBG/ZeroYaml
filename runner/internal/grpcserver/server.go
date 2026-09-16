package grpcserver

import (
	"context"
	"fmt"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
)

type Server struct {
	runnerv1.UnimplementedRunnerServiceServer
	version string
}

func New(version string) *Server {
	return &Server{
		version: version,
	}
}

func (s *Server) Ping(
	ctx context.Context,
	req *runnerv1.PingRequest,
) (*runnerv1.PingResponse, error) {
	return &runnerv1.PingResponse{
		Message:       fmt.Sprintf("pong: %s", req.GetMessage()),
		RunnerVersion: s.version,
	}, nil
}
