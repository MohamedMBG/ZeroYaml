package jobstatus

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// Decision mirrors how the Control Plane reconciled one report. Only
// DecisionApplied changed Job state; the other values mean the Control Plane
// deliberately kept the state it already held.
type Decision string

const (
	DecisionUnspecified Decision = "unspecified"
	DecisionApplied     Decision = "applied"
	DecisionDuplicate   Decision = "duplicate"
	DecisionUnknownJob  Decision = "unknown_job"
	DecisionConflict    Decision = "conflict"
)

// LifecycleState mirrors the Control Plane Job lifecycle. It is reported back
// for correlation and diagnosis; the Runner derives no policy from it.
type LifecycleState string

const (
	LifecycleUnspecified LifecycleState = "unspecified"
	LifecycleCreated     LifecycleState = "created"
	LifecycleQueued      LifecycleState = "queued"
	LifecycleRunning     LifecycleState = "running"
	LifecycleSucceeded   LifecycleState = "succeeded"
	LifecycleFailed      LifecycleState = "failed"
	LifecycleCancelled   LifecycleState = "cancelled"
)

// Result is the Control Plane's answer to one report.
type Result struct {
	Decision Decision
	JobState LifecycleState
	Message  string
}

// Dialer creates the client connection used for one report. It is a seam for
// tests; production callers use grpc.NewClient.
type Dialer func(address string) (*grpc.ClientConn, error)

// Reporter sends execution reports for one Runner process to the Control Plane.
//
// A Reporter holds no per-Job state, so execution code may share one instance
// across concurrent Jobs. Each report opens and closes its own connection,
// which keeps a stalled report from affecting the next one.
type Reporter struct {
	address  string
	identity runneridentity.Identity
	timeout  time.Duration
	logger   *slog.Logger
	dial     Dialer
}

// NewReporter creates the reporter used by Runner execution code. Every report
// is bounded by timeout so an unreachable Control Plane cannot block the
// execution that produced it.
func NewReporter(
	address string,
	identity runneridentity.Identity,
	timeout time.Duration,
	logger *slog.Logger,
) *Reporter {
	return newReporter(address, identity, timeout, logger, defaultDialer)
}

func newReporter(
	address string,
	identity runneridentity.Identity,
	timeout time.Duration,
	logger *slog.Logger,
	dial Dialer,
) *Reporter {
	return &Reporter{
		address:  address,
		identity: identity,
		timeout:  timeout,
		logger:   logger,
		dial:     dial,
	}
}

// Report sends one execution observation to the Control Plane and returns how
// it was reconciled.
//
// An error means the report did not reach the Control Plane, or was refused as
// malformed. Because the call is safe to repeat, a caller may send the same
// report again; it never decides Job state from a failed report, since the
// Control Plane owns that state.
//
// Every attempt writes one structured log record naming the Job, the reported
// state, and the answer, so an execution path cannot report silently. Records
// exclude the failure message, which is derived from execution output.
func (r *Reporter) Report(ctx context.Context, report Report) (Result, error) {
	if err := report.Validate(); err != nil {
		wrapped := fmt.Errorf("invalid job status report: %w", err)
		r.log(ctx, report, Result{}, wrapped)

		return Result{}, wrapped
	}

	callCtx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()

	result, err := r.send(callCtx, report)
	r.log(ctx, report, result, err)

	return result, err
}

func (r *Reporter) send(ctx context.Context, report Report) (Result, error) {
	connection, err := r.dial(r.address)
	if err != nil {
		return Result{}, fmt.Errorf("create control plane connection: %w", err)
	}
	defer connection.Close()

	client := runnerv1.NewJobExecutionStatusServiceClient(connection)

	response, err := client.ReportJobStatus(ctx, r.toRequest(report))
	if err != nil {
		return Result{}, fmt.Errorf("report job status to control plane at %s: %w", r.address, err)
	}

	return Result{
		Decision: decisionFromProto(response.GetResult()),
		JobState: lifecycleFromProto(response.GetJobState()),
		Message:  response.GetMessage(),
	}, nil
}

func (r *Reporter) toRequest(report Report) *runnerv1.ReportJobStatusRequest {
	request := &runnerv1.ReportJobStatusRequest{
		ProtocolVersion: r.identity.ProtocolVersion,
		JobId:           report.JobID,
		RunnerId:        r.identity.RunnerID,
		InstanceId:      r.identity.InstanceID,
		State:           stateToProto(report.State),
		StartedAt:       timestamppb.New(report.StartedAt),
	}

	if !report.IsTerminal() {
		return request
	}

	request.CompletedAt = timestamppb.New(report.CompletedAt)
	request.Result = &runnerv1.JobExecutionResult{
		ExitCode:       report.ExitCode,
		FailureReason:  failureReasonToProto(report.FailureReason),
		FailureMessage: report.FailureMessage,
	}

	return request
}

func (r *Reporter) log(ctx context.Context, report Report, result Result, err error) {
	attributes := []slog.Attr{
		slog.String("job_id", report.JobID),
		slog.String("reported_state", string(report.State)),
		slog.String("runner_id", r.identity.RunnerID),
		slog.String("instance_id", r.identity.InstanceID),
	}

	if report.State == StateFailed {
		attributes = append(attributes, slog.String("failure_reason", string(report.FailureReason)))
	}

	if err != nil {
		attributes = append(attributes, slog.String("error", err.Error()))
		r.logger.LogAttrs(ctx, slog.LevelWarn, "job status report failed", attributes...)

		return
	}

	attributes = append(
		attributes,
		slog.String("decision", string(result.Decision)),
		slog.String("job_state", string(result.JobState)),
	)

	// A report the Control Plane did not apply is an operational signal: the
	// Job already moved on, or this process is not the one executing it.
	level := slog.LevelInfo
	if result.Decision != DecisionApplied {
		level = slog.LevelWarn
	}

	r.logger.LogAttrs(ctx, level, "job status report acknowledged", attributes...)
}

func defaultDialer(address string) (*grpc.ClientConn, error) {
	return grpc.NewClient(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
}

func stateToProto(state State) runnerv1.JobExecutionState {
	switch state {
	case StateRunning:
		return runnerv1.JobExecutionState_JOB_EXECUTION_RUNNING
	case StateSucceeded:
		return runnerv1.JobExecutionState_JOB_EXECUTION_SUCCEEDED
	case StateFailed:
		return runnerv1.JobExecutionState_JOB_EXECUTION_FAILED
	default:
		return runnerv1.JobExecutionState_JOB_EXECUTION_STATE_UNSPECIFIED
	}
}

func failureReasonToProto(reason FailureReason) runnerv1.JobExecutionFailureReason {
	switch reason {
	case FailureNonZeroExit:
		return runnerv1.JobExecutionFailureReason_JOB_EXECUTION_FAILURE_NON_ZERO_EXIT
	case FailureTimeout:
		return runnerv1.JobExecutionFailureReason_JOB_EXECUTION_FAILURE_TIMEOUT
	case FailureCancelled:
		return runnerv1.JobExecutionFailureReason_JOB_EXECUTION_FAILURE_CANCELLED
	case FailureExecutionError:
		return runnerv1.JobExecutionFailureReason_JOB_EXECUTION_FAILURE_EXECUTION_ERROR
	default:
		return runnerv1.JobExecutionFailureReason_JOB_EXECUTION_FAILURE_REASON_UNSPECIFIED
	}
}

func decisionFromProto(result runnerv1.JobStatusReportResult) Decision {
	switch result {
	case runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_APPLIED:
		return DecisionApplied
	case runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_DUPLICATE:
		return DecisionDuplicate
	case runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_UNKNOWN_JOB:
		return DecisionUnknownJob
	case runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_CONFLICT:
		return DecisionConflict
	default:
		return DecisionUnspecified
	}
}

func lifecycleFromProto(state runnerv1.JobLifecycleState) LifecycleState {
	switch state {
	case runnerv1.JobLifecycleState_JOB_LIFECYCLE_CREATED:
		return LifecycleCreated
	case runnerv1.JobLifecycleState_JOB_LIFECYCLE_QUEUED:
		return LifecycleQueued
	case runnerv1.JobLifecycleState_JOB_LIFECYCLE_RUNNING:
		return LifecycleRunning
	case runnerv1.JobLifecycleState_JOB_LIFECYCLE_SUCCEEDED:
		return LifecycleSucceeded
	case runnerv1.JobLifecycleState_JOB_LIFECYCLE_FAILED:
		return LifecycleFailed
	case runnerv1.JobLifecycleState_JOB_LIFECYCLE_CANCELLED:
		return LifecycleCancelled
	default:
		return LifecycleUnspecified
	}
}
