package jobexecution

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"testing"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/jobstatus"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/sandbox"
)

func TestSubmitRunsTheJobAndReportsRunningThenTerminalStatus(t *testing.T) {
	exitCode := int32(0)
	executor := newScriptedExecutor(sandbox.Result{Outcome: sandbox.OutcomeSucceeded, ExitCode: &exitCode})
	reporter := &recordingReporter{}
	dispatcher := newTestDispatcher(context.Background(), executor, reporter, 1)

	if err := dispatcher.Submit(newTestJob()); err != nil {
		t.Fatalf("Submit() returned an error: %v", err)
	}
	close(executor.release)
	dispatcher.Close()

	reports := reporter.all()
	if len(reports) != 2 {
		t.Fatalf("reports = %+v, want a running and a terminal report", reports)
	}
	if reports[0].State != jobstatus.StateRunning || reports[0].JobID != "job-1" {
		t.Errorf("first report = %+v, want job-1 running", reports[0])
	}
	if reports[1].State != jobstatus.StateSucceeded || *reports[1].ExitCode != 0 {
		t.Errorf("terminal report = %+v, want succeeded with exit code 0", reports[1])
	}
	if !reports[1].StartedAt.Equal(reports[0].StartedAt) {
		t.Error("terminal report must repeat the start time of the running report")
	}

	specs := executor.specs()
	if len(specs) != 1 || specs[0].Image != "alpine:3.22" || specs[0].WorkingDirectory != "/workspace" {
		t.Errorf("executed specs = %+v, want the job mapped with the configured settings", specs)
	}
}

func TestSubmitRejectsAJobBeyondTheConcurrencyLimit(t *testing.T) {
	executor := newScriptedExecutor(sandbox.Result{Outcome: sandbox.OutcomeSucceeded})
	dispatcher := newTestDispatcher(context.Background(), executor, &recordingReporter{}, 1)

	if err := dispatcher.Submit(newTestJob()); err != nil {
		t.Fatalf("first Submit() returned an error: %v", err)
	}
	if err := dispatcher.Submit(newTestJob()); !errors.Is(err, ErrAtCapacity) {
		t.Errorf("second Submit() error = %v, want ErrAtCapacity", err)
	}

	close(executor.release)
	dispatcher.Close()
}

func TestSubmitFreesTheSlotWhenAnExecutionEnds(t *testing.T) {
	executor := newScriptedExecutor(sandbox.Result{Outcome: sandbox.OutcomeSucceeded})
	close(executor.release)
	reporter := &recordingReporter{}
	dispatcher := newTestDispatcher(context.Background(), executor, reporter, 1)

	if err := dispatcher.Submit(newTestJob()); err != nil {
		t.Fatalf("first Submit() returned an error: %v", err)
	}
	reporter.waitForReports(t, 2)

	// The slot is released after the terminal report, so the next Submit may
	// briefly race it; a deadline keeps the test bounded without sleeping.
	deadline := time.Now().Add(5 * time.Second)
	for {
		err := dispatcher.Submit(newTestJob())
		if err == nil {
			break
		}
		if !errors.Is(err, ErrAtCapacity) || time.Now().After(deadline) {
			t.Fatalf("Submit() after completion error = %v", err)
		}
	}

	dispatcher.Close()
}

func TestSubmitRejectsAnUnsupportedJobWithoutStartingIt(t *testing.T) {
	executor := newScriptedExecutor(sandbox.Result{})
	reporter := &recordingReporter{}
	dispatcher := newTestDispatcher(context.Background(), executor, reporter, 1)

	job := newTestJob()
	job.Repository.Location = "ssh://git@example.com/repo.git"

	if err := dispatcher.Submit(job); !errors.Is(err, sandbox.ErrUnsupportedJob) {
		t.Errorf("Submit() error = %v, want sandbox.ErrUnsupportedJob", err)
	}

	dispatcher.Close()
	if len(executor.specs()) != 0 || len(reporter.all()) != 0 {
		t.Error("an unsupported job must not execute or report")
	}
}

func TestSubmitRejectsJobsAfterClose(t *testing.T) {
	dispatcher := newTestDispatcher(context.Background(), newScriptedExecutor(sandbox.Result{}), &recordingReporter{}, 1)
	dispatcher.Close()

	if err := dispatcher.Submit(newTestJob()); !errors.Is(err, ErrClosed) {
		t.Errorf("Submit() error = %v, want ErrClosed", err)
	}
}

func TestSubmitRejectsJobsOnceTheLifetimeEnds(t *testing.T) {
	lifetime, cancel := context.WithCancel(context.Background())
	cancel()
	dispatcher := newTestDispatcher(lifetime, newScriptedExecutor(sandbox.Result{}), &recordingReporter{}, 1)

	if err := dispatcher.Submit(newTestJob()); !errors.Is(err, ErrClosed) {
		t.Errorf("Submit() error = %v, want ErrClosed", err)
	}
}

// TestCloseWaitsForACancelledExecutionToReport shows the shutdown order: the
// lifetime is cancelled, the execution stops, and Close returns only after the
// cancellation reached the Control Plane.
func TestCloseWaitsForACancelledExecutionToReport(t *testing.T) {
	lifetime, cancel := context.WithCancel(context.Background())
	executor := &lifetimeExecutor{started: make(chan struct{})}
	reporter := &recordingReporter{}
	dispatcher := newTestDispatcher(lifetime, executor, reporter, 1)

	if err := dispatcher.Submit(newTestJob()); err != nil {
		t.Fatalf("Submit() returned an error: %v", err)
	}
	<-executor.started
	cancel()
	dispatcher.Close()

	reports := reporter.all()
	if len(reports) != 2 {
		t.Fatalf("reports = %+v, want a running and a terminal report", reports)
	}
	if reports[1].FailureReason != jobstatus.FailureCancelled {
		t.Errorf("terminal failure reason = %s, want %s", reports[1].FailureReason, jobstatus.FailureCancelled)
	}
	if !reporter.contextsAlive() {
		t.Error("the terminal report must be sent with a context that survives shutdown")
	}
}

func TestTerminalReportMapsEveryOutcome(t *testing.T) {
	startedAt := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	completedAt := startedAt.Add(time.Minute)
	exitCode := int32(3)

	testCases := []struct {
		name       string
		result     sandbox.Result
		wantState  jobstatus.State
		wantReason jobstatus.FailureReason
		wantExit   *int32
	}{
		{
			name:      "succeeded",
			result:    sandbox.Result{Outcome: sandbox.OutcomeSucceeded},
			wantState: jobstatus.StateSucceeded,
			wantExit:  new(int32),
		},
		{
			name:       "non-zero exit",
			result:     sandbox.Result{Outcome: sandbox.OutcomeFailed, ExitCode: &exitCode, Message: "job command exited with code 3"},
			wantState:  jobstatus.StateFailed,
			wantReason: jobstatus.FailureNonZeroExit,
			wantExit:   &exitCode,
		},
		{
			name:       "timed out",
			result:     sandbox.Result{Outcome: sandbox.OutcomeTimedOut, Message: "execution exceeded its time budget"},
			wantState:  jobstatus.StateFailed,
			wantReason: jobstatus.FailureTimeout,
		},
		{
			name:       "cancelled",
			result:     sandbox.Result{Outcome: sandbox.OutcomeCancelled, Message: "execution cancelled"},
			wantState:  jobstatus.StateFailed,
			wantReason: jobstatus.FailureCancelled,
		},
		{
			name:       "execution error",
			result:     sandbox.Result{Outcome: sandbox.OutcomeExecutionError, Message: "source checkout exited with code 128"},
			wantState:  jobstatus.StateFailed,
			wantReason: jobstatus.FailureExecutionError,
		},
		{
			name:       "unrecognized outcome",
			result:     sandbox.Result{Outcome: "exploded"},
			wantState:  jobstatus.StateFailed,
			wantReason: jobstatus.FailureExecutionError,
		},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			report := terminalReport("job-1", startedAt, completedAt, testCase.result)

			if err := report.Validate(); err != nil {
				t.Fatalf("terminal report is invalid: %v", err)
			}
			if report.State != testCase.wantState || report.FailureReason != testCase.wantReason {
				t.Errorf("report = %s/%s, want %s/%s", report.State, report.FailureReason, testCase.wantState, testCase.wantReason)
			}
			if (report.ExitCode == nil) != (testCase.wantExit == nil) ||
				(report.ExitCode != nil && *report.ExitCode != *testCase.wantExit) {
				t.Errorf("ExitCode = %v, want %v", report.ExitCode, testCase.wantExit)
			}
		})
	}
}

// scriptedExecutor blocks every execution until release is closed and then
// answers with result.
type scriptedExecutor struct {
	result  sandbox.Result
	release chan struct{}

	mu       sync.Mutex
	executed []sandbox.Spec
}

func newScriptedExecutor(result sandbox.Result) *scriptedExecutor {
	return &scriptedExecutor{result: result, release: make(chan struct{})}
}

func (e *scriptedExecutor) Run(_ context.Context, spec sandbox.Spec) sandbox.Result {
	e.mu.Lock()
	e.executed = append(e.executed, spec)
	e.mu.Unlock()

	<-e.release

	return e.result
}

func (e *scriptedExecutor) specs() []sandbox.Spec {
	e.mu.Lock()
	defer e.mu.Unlock()

	return append([]sandbox.Spec(nil), e.executed...)
}

// lifetimeExecutor runs until its context is cancelled, like a container
// stopped by Runner shutdown.
type lifetimeExecutor struct {
	started chan struct{}
}

func (e *lifetimeExecutor) Run(ctx context.Context, _ sandbox.Spec) sandbox.Result {
	close(e.started)
	<-ctx.Done()

	return sandbox.Result{Outcome: sandbox.OutcomeCancelled, Message: "execution cancelled during job command"}
}

type recordingReporter struct {
	mu       sync.Mutex
	reports  []jobstatus.Report
	liveness []bool
	signal   chan struct{}
}

func (r *recordingReporter) Report(ctx context.Context, report jobstatus.Report) (jobstatus.Result, error) {
	r.mu.Lock()
	r.reports = append(r.reports, report)
	r.liveness = append(r.liveness, ctx.Err() == nil)
	signal := r.signal
	r.mu.Unlock()

	if signal != nil {
		select {
		case signal <- struct{}{}:
		default:
		}
	}

	return jobstatus.Result{Decision: jobstatus.DecisionApplied}, nil
}

func (r *recordingReporter) all() []jobstatus.Report {
	r.mu.Lock()
	defer r.mu.Unlock()

	return append([]jobstatus.Report(nil), r.reports...)
}

func (r *recordingReporter) contextsAlive() bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	for _, alive := range r.liveness {
		if !alive {
			return false
		}
	}

	return true
}

// waitForReports blocks until count reports arrived. It must be called before
// the first report is sent.
func (r *recordingReporter) waitForReports(t *testing.T, count int) {
	t.Helper()

	r.mu.Lock()
	if r.signal == nil {
		r.signal = make(chan struct{}, count)
	}
	signal := r.signal
	r.mu.Unlock()

	timeout := time.After(5 * time.Second)
	for received := len(r.all()); received < count; received++ {
		select {
		case <-signal:
		case <-timeout:
			t.Fatalf("received %d reports, want %d", len(r.all()), count)
		}
	}
}

func newTestDispatcher(lifetime context.Context, executor Executor, reporter StatusReporter, slots int) *Dispatcher {
	return NewDispatcher(
		lifetime,
		executor,
		reporter,
		sandbox.Settings{
			Image:         "alpine:3.22",
			CheckoutImage: "alpine/git:v2.49.1",
			Timeout:       time.Minute,
			Limits:        sandbox.DefaultLimits(),
		},
		slots,
		slog.New(slog.DiscardHandler),
	)
}

func newTestJob() *runnerv1.JobSpecification {
	return &runnerv1.JobSpecification{
		JobId: "job-1",
		Repository: &runnerv1.JobRepository{
			Location: "https://example.com/repo.git",
			Revision: "3af0394c1d2b4e5f60718293a4b5c6d7e8f90123",
		},
		Execution: &runnerv1.JobExecution{
			Command:          []string{"true"},
			WorkingDirectory: ".",
		},
	}
}
