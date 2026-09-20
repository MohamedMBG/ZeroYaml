package startupregistration

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/MohamedMBG/ZeroYaml/runner/internal/registrationclient"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
)

func TestApplyPromotesIdentityToReadyOnAccepted(t *testing.T) {
	identity := newTestIdentity(t)

	outcome := apply(context.Background(), "control-plane:50052", identity, time.Second, func(
		ctx context.Context, address string, id runneridentity.Identity,
	) (registrationclient.Result, error) {
		return registrationclient.Result{Decision: registrationclient.DecisionAccepted, RegistrationID: "registration-1"}, nil
	})

	if outcome.Err != nil {
		t.Fatalf("expected no error, got %v", outcome.Err)
	}
	if outcome.Identity.Status != runneridentity.StatusReady {
		t.Errorf("Status = %s, want %s", outcome.Identity.Status, runneridentity.StatusReady)
	}
}

func TestApplyPromotesIdentityToReadyOnAlreadyRegistered(t *testing.T) {
	identity := newTestIdentity(t)

	outcome := apply(context.Background(), "control-plane:50052", identity, time.Second, func(
		ctx context.Context, address string, id runneridentity.Identity,
	) (registrationclient.Result, error) {
		return registrationclient.Result{Decision: registrationclient.DecisionAlreadyRegistered}, nil
	})

	if outcome.Identity.Status != runneridentity.StatusReady {
		t.Errorf("Status = %s, want %s", outcome.Identity.Status, runneridentity.StatusReady)
	}
}

// TestApplyKeepsIdentityUnchangedOnIdentityConflict documents that a Control
// Plane rejection never makes the Runner report itself ready, since a Runner
// stuck in a conflicted registration must not accept dispatched work.
func TestApplyKeepsIdentityUnchangedOnIdentityConflict(t *testing.T) {
	identity := newTestIdentity(t)
	wantStatus := identity.Status

	outcome := apply(context.Background(), "control-plane:50052", identity, time.Second, func(
		ctx context.Context, address string, id runneridentity.Identity,
	) (registrationclient.Result, error) {
		return registrationclient.Result{Decision: registrationclient.DecisionIdentityConflict, Message: "conflict"}, nil
	})

	if outcome.Err != nil {
		t.Fatalf("expected no error, got %v", outcome.Err)
	}
	if outcome.Identity.Status != wantStatus {
		t.Errorf("Status = %s, want unchanged %s", outcome.Identity.Status, wantStatus)
	}
}

// TestApplyKeepsIdentityUnchangedOnTransportFailure covers an unreachable
// Control Plane. The Runner must not silently report itself available when
// registration could not even be attempted successfully.
func TestApplyKeepsIdentityUnchangedOnTransportFailure(t *testing.T) {
	identity := newTestIdentity(t)
	wantStatus := identity.Status
	transportErr := errors.New("control plane unavailable")

	outcome := apply(context.Background(), "control-plane:50052", identity, time.Second, func(
		ctx context.Context, address string, id runneridentity.Identity,
	) (registrationclient.Result, error) {
		return registrationclient.Result{}, transportErr
	})

	if !errors.Is(outcome.Err, transportErr) {
		t.Errorf("Err = %v, want %v", outcome.Err, transportErr)
	}
	if outcome.Identity.Status != wantStatus {
		t.Errorf("Status = %s, want unchanged %s", outcome.Identity.Status, wantStatus)
	}
}

func TestApplyBoundsTheRegisterCallWithATimeout(t *testing.T) {
	identity := newTestIdentity(t)

	var gotDeadlineSet bool
	apply(context.Background(), "control-plane:50052", identity, 50*time.Millisecond, func(
		ctx context.Context, address string, id runneridentity.Identity,
	) (registrationclient.Result, error) {
		_, gotDeadlineSet = ctx.Deadline()

		return registrationclient.Result{Decision: registrationclient.DecisionAccepted}, nil
	})

	if !gotDeadlineSet {
		t.Error("expected the register call context to carry a deadline")
	}
}

func newTestIdentity(t *testing.T) runneridentity.Identity {
	t.Helper()

	identity, err := runneridentity.New(
		"runner-dev-01",
		"0.1.0",
		runneridentity.ProtocolVersion,
		runneridentity.DefaultCapabilities(),
	)
	if err != nil {
		t.Fatalf("runneridentity.New() returned an error: %v", err)
	}

	return identity
}
