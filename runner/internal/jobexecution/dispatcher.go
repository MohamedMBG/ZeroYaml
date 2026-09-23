// Package jobexecution runs accepted Jobs in the background and reports what
// each execution did to the Control Plane.
//
// The Dispatcher is the bridge between a RunJob acknowledgment, which must
// answer immediately, and a container execution, which may take minutes. It
// bounds how many executions run at once, ties every execution to the Runner
// process lifetime instead of to the RPC that dispatched it, and reports a
// running and a terminal status for each accepted Job.
package jobexecution

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/jobstatus"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/sandbox"
)

var (
	// ErrAtCapacity means every execution slot is in use. The Job was not
	// accepted and may be dispatched to another Runner.
	ErrAtCapacity = errors.New("runner is already running its maximum number of concurrent jobs")

	// ErrClosed means the Runner is shutting down and accepts no new Jobs.
	ErrClosed = errors.New("runner is shutting down and accepts no new jobs")
)

// Executor runs one resolved Job to completion.
type Executor interface {
	Run(ctx context.Context, spec sandbox.Spec) sandbox.Result
}

// StatusReporter delivers one execution observation to the Control Plane.
type StatusReporter interface {
	Report(ctx context.Context, report jobstatus.Report) (jobstatus.Result, error)
}

// Dispatcher accepts Jobs and runs each one on its own goroutine, bounded by a
// fixed number of execution slots.
//
// Every execution runs under the lifetime context passed to NewDispatcher, so
// cancelling it stops running containers. Close then waits for every execution
// to finish its cleanup and final report.
type Dispatcher struct {
	lifetime context.Context
	executor Executor
	reporter StatusReporter
	settings sandbox.Settings
	logger   *slog.Logger
	now      func() time.Time

	slots chan struct{}

	// mu guards closed and orders every running.Add before Close calls
	// running.Wait, as sync.WaitGroup requires.
	mu      sync.Mutex
	closed  bool
	running sync.WaitGroup
}

// NewDispatcher creates a Dispatcher that runs at most maxConcurrentJobs
// executions at once. Executions stop when lifetime is cancelled.
func NewDispatcher(
	lifetime context.Context,
	executor Executor,
	reporter StatusReporter,
	settings sandbox.Settings,
	maxConcurrentJobs int,
	logger *slog.Logger,
) *Dispatcher {
	return &Dispatcher{
		lifetime: lifetime,
		executor: executor,
		reporter: reporter,
		settings: settings,
		logger:   logger,
		now:      time.Now,
		slots:    make(chan struct{}, maxConcurrentJobs),
	}
}

// Submit maps job onto a container execution and starts it in the background.
//
// It returns without blocking. An error means the Job was not started: an
// error wrapping sandbox.ErrUnsupportedJob names a Job this Runner cannot
// execute, while ErrAtCapacity and ErrClosed describe Runner state.
func (d *Dispatcher) Submit(job *runnerv1.JobSpecification) error {
	spec, err := sandbox.NewSpec(job, d.settings)
	if err != nil {
		return err
	}

	d.mu.Lock()
	defer d.mu.Unlock()

	if d.closed || d.lifetime.Err() != nil {
		return ErrClosed
	}

	select {
	case d.slots <- struct{}{}:
	default:
		return ErrAtCapacity
	}

	d.running.Add(1)
	go d.execute(spec)

	return nil
}

// Close stops accepting Jobs and waits for every running execution to return.
// Callers cancel the lifetime context first so that running containers stop
// instead of running to completion.
func (d *Dispatcher) Close() {
	d.mu.Lock()
	d.closed = true
	d.mu.Unlock()

	d.running.Wait()
}

func (d *Dispatcher) execute(spec sandbox.Spec) {
	defer d.running.Done()
	defer func() { <-d.slots }()

	startedAt := d.now()
	d.report(jobstatus.Running(spec.JobID, startedAt))

	result := d.executor.Run(d.lifetime, spec)
	completedAt := d.now()

	d.logResult(spec, result, completedAt.Sub(startedAt))
	d.report(terminalReport(spec.JobID, startedAt, completedAt, result))
}

// report sends one observation. It is detached from the lifetime context so
// that the terminal report of an execution cancelled by shutdown still reaches
// the Control Plane; the Reporter bounds every call with its own timeout. A
// failed report is already logged by the Reporter and never retried here.
func (d *Dispatcher) report(report jobstatus.Report) {
	_, _ = d.reporter.Report(context.WithoutCancel(d.lifetime), report)
}

func (d *Dispatcher) logResult(spec sandbox.Spec, result sandbox.Result, duration time.Duration) {
	attributes := []slog.Attr{
		slog.String("job_id", spec.JobID),
		slog.String("execution_id", result.ExecutionID),
		slog.String("outcome", string(result.Outcome)),
		slog.Duration("duration", duration),
	}
	if result.ExitCode != nil {
		attributes = append(attributes, slog.Int("exit_code", int(*result.ExitCode)))
	}

	level := slog.LevelInfo
	if result.Outcome != sandbox.OutcomeSucceeded {
		level = slog.LevelWarn
		attributes = append(attributes, slog.String("message", result.Message))
	}

	d.logger.LogAttrs(context.Background(), level, "job execution finished", attributes...)
}

// terminalReport maps a sandbox result onto the execution status contract.
func terminalReport(jobID string, startedAt time.Time, completedAt time.Time, result sandbox.Result) jobstatus.Report {
	switch result.Outcome {
	case sandbox.OutcomeSucceeded:
		return jobstatus.Succeeded(jobID, startedAt, completedAt, 0)
	case sandbox.OutcomeFailed:
		return jobstatus.Failed(jobID, startedAt, completedAt, result.ExitCode, jobstatus.FailureNonZeroExit, result.Message)
	case sandbox.OutcomeTimedOut:
		return jobstatus.Failed(jobID, startedAt, completedAt, nil, jobstatus.FailureTimeout, result.Message)
	case sandbox.OutcomeCancelled:
		return jobstatus.Failed(jobID, startedAt, completedAt, nil, jobstatus.FailureCancelled, result.Message)
	default:
		message := result.Message
		if message == "" {
			message = fmt.Sprintf("execution ended with unrecognized outcome %q", result.Outcome)
		}

		return jobstatus.Failed(jobID, startedAt, completedAt, nil, jobstatus.FailureExecutionError, message)
	}
}
