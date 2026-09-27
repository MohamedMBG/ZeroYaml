package grpcserver

import (
	"context"
	"fmt"
	"log/slog"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runnerinfoproto"
)

// JobSubmitter starts the execution of an accepted Job without waiting for it.
// An error means the Job was not started; see RunJob for how each error is
// answered.
type JobSubmitter interface {
	Submit(job *runnerv1.JobSpecification) error
}

type Server struct {
	runnerv1.UnimplementedRunnerServiceServer
	identity runneridentity.Identity
	jobs     JobSubmitter
	logger   *slog.Logger
}

// New creates the Runner gRPC service for one process identity. Accepted Jobs
// are handed to jobs; a nil jobs rejects every dispatch as unavailable. The
// logger receives one structured record per RunJob dispatch.
func New(identity runneridentity.Identity, jobs JobSubmitter, logger *slog.Logger) *Server {
	return &Server{
		identity: identity,
		jobs:     jobs,
		logger:   logger,
	}
}

func (s *Server) Ping(
	ctx context.Context,
	req *runnerv1.PingRequest,
) (*runnerv1.PingResponse, error) {
	return &runnerv1.PingResponse{
		Message:       fmt.Sprintf("pong: %s", req.GetMessage()),
		RunnerVersion: s.identity.RunnerVersion,
	}, nil
}

func (s *Server) GetInfo(
	ctx context.Context,
	req *runnerv1.GetInfoRequest,
) (*runnerv1.RunnerInfo, error) {
	return runnerinfoproto.ToProto(s.identity), nil
}
