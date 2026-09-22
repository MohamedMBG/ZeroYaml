package jobstatus

import (
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

var (
	testStartedAt   = time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	testCompletedAt = time.Date(2026, 1, 1, 10, 0, 30, 0, time.UTC)
)

func TestRunningBuildsAnObservationWithoutTerminalInformation(t *testing.T) {
	report := Running("job-1", testStartedAt)

	if err := report.Validate(); err != nil {
		t.Fatalf("Validate() returned an error: %v", err)
	}
	if report.IsTerminal() {
		t.Error("IsTerminal() = true, want false")
	}
	if !report.CompletedAt.IsZero() {
		t.Errorf("CompletedAt = %s, want the zero time", report.CompletedAt)
	}
	if report.ExitCode != nil {
		t.Errorf("ExitCode = %d, want nil", *report.ExitCode)
	}
}

func TestSucceededCarriesTheExitCodeThatEndedTheCommand(t *testing.T) {
	report := Succeeded("job-1", testStartedAt, testCompletedAt, 0)

	if err := report.Validate(); err != nil {
		t.Fatalf("Validate() returned an error: %v", err)
	}
	if !report.IsTerminal() {
		t.Error("IsTerminal() = false, want true")
	}
	if report.ExitCode == nil {
		t.Fatal("ExitCode = nil, want a reported code")
	}
	if *report.ExitCode != 0 {
		t.Errorf("ExitCode = %d, want 0", *report.ExitCode)
	}
}

func TestFailedWithoutAnExitCodeKeepsItAbsent(t *testing.T) {
	report := Failed("job-1", testStartedAt, testCompletedAt, nil, FailureTimeout, "job exceeded its time budget")

	if err := report.Validate(); err != nil {
		t.Fatalf("Validate() returned an error: %v", err)
	}
	if report.ExitCode != nil {
		// A killed command produces no code, and an absent code must never be
		// reported as a successful 0.
		t.Errorf("ExitCode = %d, want nil", *report.ExitCode)
	}
}

func TestFailedBoundsAnOversizedFailureMessage(t *testing.T) {
	oversized := strings.Repeat("e", maxFailureMessageLength+64)

	report := Failed("job-1", testStartedAt, testCompletedAt, nil, FailureExecutionError, oversized)

	if len(report.FailureMessage) != maxFailureMessageLength+len(truncationMarker) {
		t.Errorf(
			"len(FailureMessage) = %d, want %d",
			len(report.FailureMessage),
			maxFailureMessageLength+len(truncationMarker),
		)
	}
	if !strings.HasSuffix(report.FailureMessage, truncationMarker) {
		t.Error("FailureMessage does not end with the truncation marker")
	}
	if err := report.Validate(); err != nil {
		// Dropping a terminal report over an oversized diagnostic would leave
		// the Job without an outcome, so bounding must keep it valid.
		t.Fatalf("Validate() returned an error: %v", err)
	}
}

func TestFailedKeepsAMultiByteMessageValidWhenItIsBound(t *testing.T) {
	// A three-byte rune guarantees that the byte at the cut length is inside a
	// character rather than at its start.
	oversized := strings.Repeat("€", maxFailureMessageLength)

	report := Failed("job-1", testStartedAt, testCompletedAt, nil, FailureExecutionError, oversized)

	trimmed := strings.TrimSuffix(report.FailureMessage, truncationMarker)
	if !utf8.ValidString(trimmed) {
		t.Errorf("bound message is not valid UTF-8: %q", trimmed)
	}
	if len(trimmed) >= maxFailureMessageLength {
		t.Errorf("len(bound message) = %d, want less than %d", len(trimmed), maxFailureMessageLength)
	}
}

func TestValidateRejectsMalformedReports(t *testing.T) {
	testCases := map[string]Report{
		"missing job id": {
			State:     StateRunning,
			StartedAt: testStartedAt,
		},
		"missing start time": {
			JobID: "job-1",
			State: StateRunning,
		},
		"unknown state": {
			JobID:     "job-1",
			State:     "queued",
			StartedAt: testStartedAt,
		},
		"running report with a completion time": {
			JobID:       "job-1",
			State:       StateRunning,
			StartedAt:   testStartedAt,
			CompletedAt: testCompletedAt,
		},
		"running report with failure information": {
			JobID:          "job-1",
			State:          StateRunning,
			StartedAt:      testStartedAt,
			FailureReason:  FailureTimeout,
			FailureMessage: "stopped",
		},
		"terminal report without a completion time": {
			JobID:     "job-1",
			State:     StateSucceeded,
			StartedAt: testStartedAt,
		},
		"completion before the start": {
			JobID:       "job-1",
			State:       StateSucceeded,
			StartedAt:   testCompletedAt,
			CompletedAt: testStartedAt,
		},
		"succeeded report with failure information": {
			JobID:          "job-1",
			State:          StateSucceeded,
			StartedAt:      testStartedAt,
			CompletedAt:    testCompletedAt,
			FailureMessage: "boom",
		},
		"failed report without a reason": {
			JobID:          "job-1",
			State:          StateFailed,
			StartedAt:      testStartedAt,
			CompletedAt:    testCompletedAt,
			FailureMessage: "boom",
		},
		"failed report with an unknown reason": {
			JobID:          "job-1",
			State:          StateFailed,
			StartedAt:      testStartedAt,
			CompletedAt:    testCompletedAt,
			FailureReason:  "disk on fire",
			FailureMessage: "boom",
		},
		"failed report without a message": {
			JobID:         "job-1",
			State:         StateFailed,
			StartedAt:     testStartedAt,
			CompletedAt:   testCompletedAt,
			FailureReason: FailureNonZeroExit,
		},
	}

	for name, report := range testCases {
		t.Run(name, func(t *testing.T) {
			if err := report.Validate(); err == nil {
				t.Error("Validate() returned no error, want a validation error")
			}
		})
	}
}
