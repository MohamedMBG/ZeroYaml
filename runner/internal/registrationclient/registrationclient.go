// Package registrationclient lets the Runner announce itself to the Control
// Plane's RunnerRegistrationService over gRPC. It reports a deterministic
// Result for every response the Control Plane returns, and an error only when
// the Control Plane cannot be reached or rejects the request at the transport
// level, so a caller can tell an unavailable Control Plane apart from a
// registration the Control Plane explicitly declined.
package registrationclient

import (
	"context"
	"fmt"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runnerinfoproto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// Decision mirrors the Control Plane's registration decision.
type Decision string

const (
	DecisionUnspecified       Decision = "unspecified"
	DecisionAccepted          Decision = "accepted"
	DecisionAlreadyRegistered Decision = "already_registered"
	DecisionIdentityConflict  Decision = "identity_conflict"
)

// Result is the Control Plane's answer to one registration request.
type Result struct {
	Decision       Decision
	RegistrationID string
	Message        string
}

// Dialer creates the client connection used for one registration attempt. It
// is a seam for tests; production callers use grpc.NewClient.
type Dialer func(address string) (*grpc.ClientConn, error)

// Register sends identity to the Control Plane at address and returns its
// decision. The call is bounded by ctx, which the caller derives with a
// timeout so an unreachable Control Plane cannot block Runner startup.
func Register(ctx context.Context, address string, identity runneridentity.Identity) (Result, error) {
	return register(ctx, address, identity, defaultDialer)
}

func register(ctx context.Context, address string, identity runneridentity.Identity, dial Dialer) (Result, error) {
	connection, err := dial(address)
	if err != nil {
		return Result{}, fmt.Errorf("create control plane connection: %w", err)
	}
	defer connection.Close()

	client := runnerv1.NewRunnerRegistrationServiceClient(connection)

	response, err := client.Register(ctx, &runnerv1.RegisterRunnerRequest{
		Runner: runnerinfoproto.ToProto(identity),
	})
	if err != nil {
		return Result{}, fmt.Errorf("register with control plane at %s: %w", address, err)
	}

	return Result{
		Decision:       decisionFromProto(response.GetResult()),
		RegistrationID: response.GetRegistrationId(),
		Message:        response.GetMessage(),
	}, nil
}

func defaultDialer(address string) (*grpc.ClientConn, error) {
	return grpc.NewClient(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
}

func decisionFromProto(result runnerv1.RegistrationResult) Decision {
	switch result {
	case runnerv1.RegistrationResult_REGISTRATION_ACCEPTED:
		return DecisionAccepted
	case runnerv1.RegistrationResult_REGISTRATION_ALREADY_REGISTERED:
		return DecisionAlreadyRegistered
	case runnerv1.RegistrationResult_REGISTRATION_IDENTITY_CONFLICT:
		return DecisionIdentityConflict
	default:
		return DecisionUnspecified
	}
}
