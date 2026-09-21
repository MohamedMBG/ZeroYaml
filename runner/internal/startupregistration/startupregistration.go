// Package startupregistration performs the Runner's one startup registration
// attempt against the Control Plane and decides the identity the Runner
// serves with afterward. A Runner is never reported ready until the Control
// Plane has explicitly accepted it, so any transport failure or rejected
// decision leaves the identity unchanged.
package startupregistration

import (
	"context"
	"time"

	"github.com/MohamedMBG/ZeroYaml/runner/internal/registrationclient"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
)

// Outcome reports what the startup registration attempt did, so the caller
// can log it without repeating the decision logic.
type Outcome struct {
	// Identity is the identity the Runner should serve with. It is identity.Status
	// promoted to ready only when the Control Plane accepted the registration.
	Identity runneridentity.Identity

	// Result is the Control Plane's decision. It is the zero value when Err is set.
	Result registrationclient.Result

	// Err is set when the Control Plane could not be reached or rejected the
	// request at the transport level.
	Err error
}

type registerFunc func(ctx context.Context, address string, identity runneridentity.Identity) (registrationclient.Result, error)

// Apply attempts one registration for identity against the Control Plane at
// address, bounded by timeout.
func Apply(ctx context.Context, address string, identity runneridentity.Identity, timeout time.Duration) Outcome {
	return apply(ctx, address, identity, timeout, registrationclient.Register)
}

func apply(
	ctx context.Context,
	address string,
	identity runneridentity.Identity,
	timeout time.Duration,
	register registerFunc,
) Outcome {
	callCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	result, err := register(callCtx, address, identity)
	if err != nil {
		return Outcome{Identity: identity, Err: err}
	}

	outcome := Outcome{Identity: identity, Result: result}
	if result.Decision == registrationclient.DecisionAccepted || result.Decision == registrationclient.DecisionAlreadyRegistered {
		outcome.Identity.Status = runneridentity.StatusReady
	}

	return outcome
}
