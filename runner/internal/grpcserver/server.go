package grpcserver

import (
	"context"
	"fmt"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
)

type Server struct {
	runnerv1.UnimplementedRunnerServiceServer
	identity runneridentity.Identity
}

func New(identity runneridentity.Identity) *Server {
	return &Server{
		identity: identity,
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
	capabilities := s.identity.Capabilities

	return &runnerv1.RunnerInfo{
		RunnerId:        s.identity.RunnerID,
		InstanceId:      s.identity.InstanceID,
		RunnerVersion:   s.identity.RunnerVersion,
		ProtocolVersion: s.identity.ProtocolVersion,
		Capabilities: &runnerv1.RunnerCapabilities{
			OperatingSystem:    capabilities.OperatingSystem,
			Architecture:       capabilities.Architecture,
			DockerAvailable:    capabilities.DockerAvailable,
			SupportedExecutors: capabilities.SupportedExecutors,
			Labels:             capabilities.Labels,
		},
		Status:        statusToProto(s.identity.Status),
		AcceptingWork: s.identity.AcceptingWork,
	}, nil
}

func statusToProto(status runneridentity.Status) runnerv1.RunnerStatus {
	switch status {
	case runneridentity.StatusStarting:
		return runnerv1.RunnerStatus_RUNNER_STATUS_STARTING
	case runneridentity.StatusReady:
		return runnerv1.RunnerStatus_RUNNER_STATUS_READY
	case runneridentity.StatusDraining:
		return runnerv1.RunnerStatus_RUNNER_STATUS_DRAINING
	case runneridentity.StatusUnavailable:
		return runnerv1.RunnerStatus_RUNNER_STATUS_UNAVAILABLE
	default:
		return runnerv1.RunnerStatus_RUNNER_STATUS_UNSPECIFIED
	}
}
