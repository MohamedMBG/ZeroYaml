// Package jobstatus reports Runner execution facts to the Control Plane's
// JobExecutionStatusService.
//
// The package carries observations only. It never decides whether a Job is
// retried, cancelled, or rescheduled: the Control Plane owns the authoritative
// Job state and answers every report with how it reconciled it.
//
// Reports are safe to repeat. The Control Plane answers a duplicate or a late
// report explicitly instead of changing Job state, so a caller that is unsure
// whether a report reached the Control Plane may send it again.
package jobstatus

import (
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

// State is the execution fact being reported.
//
// There is no queued state, because queueing is a Control Plane decision the
// Runner never observes, and no cancelled state, because a stopped or
// timed-out execution is a failure with the matching FailureReason.
type State string

const (
	StateRunning   State = "running"
	StateSucceeded State = "succeeded"
	StateFailed    State = "failed"
)

// FailureReason categorizes a failed execution for the Control Plane.
type FailureReason string

const (
	// FailureNonZeroExit is a command that ran to completion and exited with a
	// non-zero code.
	FailureNonZeroExit FailureReason = "non_zero_exit"

	// FailureTimeout is an execution the Runner stopped because it exceeded its
	// time budget.
	FailureTimeout FailureReason = "timeout"

	// FailureCancelled is an execution stopped before it finished, for example
	// while the Runner was shutting down.
	FailureCancelled FailureReason = "cancelled"

	// FailureExecutionError is a Job the Runner could not execute at all, for
	// example because the workspace or the executor could not be prepared.
	FailureExecutionError FailureReason = "execution_error"
)

// maxFailureMessageLength bounds the operator-facing diagnostic carried to the
// Control Plane. A failure message is derived from execution output, so an
// unbounded value would let one Job inflate Control Plane records.
const maxFailureMessageLength = 1024

// truncationMarker is appended to a diagnostic that was cut, so an operator can
// tell a short message apart from a shortened one.
const truncationMarker = "...(truncated)"

// Report is one execution observation for a single Job.
//
// Build it with Running, Succeeded, or Failed rather than by hand, so that the
// combination of state, timestamps, and failure information stays valid.
type Report struct {
	// JobID is the Control Plane Job identity received in the dispatch.
	JobID string

	State State

	// StartedAt is when the Runner started executing the Job. Every report
	// repeats it, including a terminal one, so that a lost running report
	// cannot leave a finished Job without a start time.
	StartedAt time.Time

	// CompletedAt is when execution ended. It is the zero time for
	// StateRunning.
	CompletedAt time.Time

	// ExitCode is the code reported by the executed command. It is nil when no
	// command produced one, for example when the Runner could not start it or
	// stopped it on timeout, so a reader must never treat nil as a successful 0.
	ExitCode *int32

	// FailureReason is set only for StateFailed.
	FailureReason FailureReason

	// FailureMessage is an operator-facing diagnostic set only for StateFailed.
	// It must not contain credentials, environment values, or captured command
	// output; logs are a separate contract.
	FailureMessage string
}

// Running reports that execution of jobID began at startedAt.
func Running(jobID string, startedAt time.Time) Report {
	return Report{
		JobID:     jobID,
		State:     StateRunning,
		StartedAt: startedAt,
	}
}

// Succeeded reports that jobID ran to completion with a successful exit code.
func Succeeded(jobID string, startedAt time.Time, completedAt time.Time, exitCode int32) Report {
	return Report{
		JobID:       jobID,
		State:       StateSucceeded,
		StartedAt:   startedAt,
		CompletedAt: completedAt,
		ExitCode:    &exitCode,
	}
}

// Failed reports that jobID did not complete successfully.
//
// exitCode is nil when the execution never produced one. The message is bound
// to maxFailureMessageLength, because dropping a terminal report over an
// oversized diagnostic would leave the Job without an outcome.
func Failed(
	jobID string,
	startedAt time.Time,
	completedAt time.Time,
	exitCode *int32,
	reason FailureReason,
	message string,
) Report {
	return Report{
		JobID:          jobID,
		State:          StateFailed,
		StartedAt:      startedAt,
		CompletedAt:    completedAt,
		ExitCode:       exitCode,
		FailureReason:  reason,
		FailureMessage: boundFailureMessage(message),
	}
}

// IsTerminal reports whether this observation ends the execution.
func (r Report) IsTerminal() bool {
	return r.State == StateSucceeded || r.State == StateFailed
}

// Validate rejects a report the Control Plane could not reconcile, so a
// malformed observation is caught in this process instead of becoming an
// INVALID_ARGUMENT round trip.
func (r Report) Validate() error {
	if strings.TrimSpace(r.JobID) == "" {
		return fmt.Errorf("job ID must not be empty")
	}
	if r.StartedAt.IsZero() {
		return fmt.Errorf("started at must be set")
	}

	switch r.State {
	case StateRunning:
		return r.validateRunning()
	case StateSucceeded:
		return r.validateSucceeded()
	case StateFailed:
		return r.validateFailed()
	default:
		return fmt.Errorf("state %q is not a reportable execution state", r.State)
	}
}

func (r Report) validateRunning() error {
	if !r.CompletedAt.IsZero() {
		return fmt.Errorf("a running report must not carry a completion time")
	}
	if r.ExitCode != nil {
		return fmt.Errorf("a running report must not carry an exit code")
	}
	if r.FailureReason != "" || r.FailureMessage != "" {
		return fmt.Errorf("a running report must not carry failure information")
	}

	return nil
}

func (r Report) validateSucceeded() error {
	if err := r.validateCompletion(); err != nil {
		return err
	}
	if r.FailureReason != "" || r.FailureMessage != "" {
		return fmt.Errorf("a succeeded report must not carry failure information")
	}

	return nil
}

func (r Report) validateFailed() error {
	if err := r.validateCompletion(); err != nil {
		return err
	}
	if r.FailureReason == "" {
		return fmt.Errorf("a failed report must carry a failure reason")
	}
	if !isKnownFailureReason(r.FailureReason) {
		return fmt.Errorf("failure reason %q is not a known reason", r.FailureReason)
	}
	if strings.TrimSpace(r.FailureMessage) == "" {
		return fmt.Errorf("a failed report must carry a failure message")
	}

	return nil
}

func (r Report) validateCompletion() error {
	if r.CompletedAt.IsZero() {
		return fmt.Errorf("a terminal report must carry a completion time")
	}
	if r.CompletedAt.Before(r.StartedAt) {
		return fmt.Errorf("completion time must not be before the start time")
	}

	return nil
}

func isKnownFailureReason(reason FailureReason) bool {
	switch reason {
	case FailureNonZeroExit, FailureTimeout, FailureCancelled, FailureExecutionError:
		return true
	default:
		return false
	}
}

// boundFailureMessage cuts a diagnostic to maxFailureMessageLength bytes. The
// cut backs off to a UTF-8 boundary so a multi-byte character is never split
// into an invalid sequence.
func boundFailureMessage(message string) string {
	if len(message) <= maxFailureMessageLength {
		return message
	}

	cut := maxFailureMessageLength
	for cut > 0 && !utf8.RuneStart(message[cut]) {
		cut--
	}

	return message[:cut] + truncationMarker
}
