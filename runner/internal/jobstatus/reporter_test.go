package jobstatus

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
	"google.golang.org/grpc/test/bufconn"
)

// The tests in this file drive the reporter through the generated gRPC client
// over an in-memory connection against a recording Control Plane. They
// therefore cover request marshaling, answer mapping, and failure handling
// without binding a port or running a Control Plane process.

// transportBufferSize is large enough that a test RPC never blocks on the
// in-memory pipe.
const transportBufferSize = 1024 * 1024

// reportTimeout bounds every report sent by the tests, so a regression fails
// the test instead of hanging the suite.
const reportTimeout = 10 * time.Second

func TestReportRunningSendsTheExecutionStartWithoutTerminalFields(t *testing.T) {
	controlPlane := newRecordingControlPlane(applied(runnerv1.JobLifecycleState_JOB_LIFECYCLE_RUNNING))
	reporter, identity := startReporter(t, controlPlane, reportTimeout)

	result, err := reporter.Report(context.Background(), Running("job-1", testStartedAt))
	if err != nil {
		t.Fatalf("Report() returned an error: %v", err)
	}

	if result.Decision != DecisionApplied {
		t.Errorf("Decision = %q, want %q", result.Decision, DecisionApplied)
	}
	if result.JobState != LifecycleRunning {
		t.Errorf("JobState = %q, want %q", result.JobState, LifecycleRunning)
	}

	request := controlPlane.onlyRequest(t)
	if request.GetProtocolVersion() != identity.ProtocolVersion {
		t.Errorf("ProtocolVersion = %q, want %q", request.GetProtocolVersion(), identity.ProtocolVersion)
	}
	if request.GetJobId() != "job-1" {
		t.Errorf("JobId = %q, want %q", request.GetJobId(), "job-1")
	}
	if request.GetRunnerId() != identity.RunnerID || request.GetInstanceId() != identity.InstanceID {
		t.Errorf(
			"reporting process = (%q, %q), want (%q, %q)",
			request.GetRunnerId(), request.GetInstanceId(), identity.RunnerID, identity.InstanceID,
		)
	}
	if request.GetState() != runnerv1.JobExecutionState_JOB_EXECUTION_RUNNING {
		t.Errorf("State = %s, want JOB_EXECUTION_RUNNING", request.GetState())
	}
	if !request.GetStartedAt().AsTime().Equal(testStartedAt) {
		t.Errorf("StartedAt = %s, want %s", request.GetStartedAt().AsTime(), testStartedAt)
	}
	if request.GetCompletedAt() != nil {
		t.Errorf("CompletedAt = %s, want no completion time", request.GetCompletedAt().AsTime())
	}
	if request.GetResult() != nil {
		t.Error("Result is set, want no terminal result on a running report")
	}
}

func TestReportSucceededCarriesTheExitCodeAndBothTimestamps(t *testing.T) {
	controlPlane := newRecordingControlPlane(applied(runnerv1.JobLifecycleState_JOB_LIFECYCLE_SUCCEEDED))
	reporter, _ := startReporter(t, controlPlane, reportTimeout)

	result, err := reporter.Report(context.Background(), Succeeded("job-1", testStartedAt, testCompletedAt, 0))
	if err != nil {
		t.Fatalf("Report() returned an error: %v", err)
	}

	if result.JobState != LifecycleSucceeded {
		t.Errorf("JobState = %q, want %q", result.JobState, LifecycleSucceeded)
	}

	request := controlPlane.onlyRequest(t)
	if request.GetState() != runnerv1.JobExecutionState_JOB_EXECUTION_SUCCEEDED {
		t.Errorf("State = %s, want JOB_EXECUTION_SUCCEEDED", request.GetState())
	}
	if !request.GetCompletedAt().AsTime().Equal(testCompletedAt) {
		t.Errorf("CompletedAt = %s, want %s", request.GetCompletedAt().AsTime(), testCompletedAt)
	}
	if request.GetResult().ExitCode == nil {
		t.Fatal("ExitCode is absent, want the reported code 0")
	}
	if request.GetResult().GetExitCode() != 0 {
		t.Errorf("ExitCode = %d, want 0", request.GetResult().GetExitCode())
	}
}

func TestReportFailedCarriesTheReasonAndTheNonZeroExitCode(t *testing.T) {
	controlPlane := newRecordingControlPlane(applied(runnerv1.JobLifecycleState_JOB_LIFECYCLE_FAILED))
	reporter, _ := startReporter(t, controlPlane, reportTimeout)

	exitCode := int32(2)
	report := Failed("job-1", testStartedAt, testCompletedAt, &exitCode, FailureNonZeroExit, "command exited with code 2")

	if _, err := reporter.Report(context.Background(), report); err != nil {
		t.Fatalf("Report() returned an error: %v", err)
	}

	request := controlPlane.onlyRequest(t)
	if request.GetState() != runnerv1.JobExecutionState_JOB_EXECUTION_FAILED {
		t.Errorf("State = %s, want JOB_EXECUTION_FAILED", request.GetState())
	}
	if request.GetResult().GetFailureReason() != runnerv1.JobExecutionFailureReason_JOB_EXECUTION_FAILURE_NON_ZERO_EXIT {
		t.Errorf("FailureReason = %s, want JOB_EXECUTION_FAILURE_NON_ZERO_EXIT", request.GetResult().GetFailureReason())
	}
	if request.GetResult().GetExitCode() != 2 {
		t.Errorf("ExitCode = %d, want 2", request.GetResult().GetExitCode())
	}
	if request.GetResult().GetFailureMessage() != "command exited with code 2" {
		t.Errorf("FailureMessage = %q, want the reported diagnostic", request.GetResult().GetFailureMessage())
	}
}

func TestReportFailedOnTimeoutLeavesTheExitCodeAbsent(t *testing.T) {
	controlPlane := newRecordingControlPlane(applied(runnerv1.JobLifecycleState_JOB_LIFECYCLE_FAILED))
	reporter, _ := startReporter(t, controlPlane, reportTimeout)

	report := Failed("job-1", testStartedAt, testCompletedAt, nil, FailureTimeout, "job exceeded its time budget")

	if _, err := reporter.Report(context.Background(), report); err != nil {
		t.Fatalf("Report() returned an error: %v", err)
	}

	request := controlPlane.onlyRequest(t)
	if request.GetResult().GetFailureReason() != runnerv1.JobExecutionFailureReason_JOB_EXECUTION_FAILURE_TIMEOUT {
		t.Errorf("FailureReason = %s, want JOB_EXECUTION_FAILURE_TIMEOUT", request.GetResult().GetFailureReason())
	}
	if request.GetResult().ExitCode != nil {
		// A stopped command produced no code; an absent code must stay absent
		// so the Control Plane never reads it as a successful 0.
		t.Errorf("ExitCode = %d, want it to be absent", request.GetResult().GetExitCode())
	}
}

func TestReportFailedOnCancellationReportsTheCancelledReason(t *testing.T) {
	controlPlane := newRecordingControlPlane(applied(runnerv1.JobLifecycleState_JOB_LIFECYCLE_FAILED))
	reporter, _ := startReporter(t, controlPlane, reportTimeout)

	report := Failed("job-1", testStartedAt, testCompletedAt, nil, FailureCancelled, "execution stopped during runner shutdown")

	if _, err := reporter.Report(context.Background(), report); err != nil {
		t.Fatalf("Report() returned an error: %v", err)
	}

	request := controlPlane.onlyRequest(t)
	if request.GetResult().GetFailureReason() != runnerv1.JobExecutionFailureReason_JOB_EXECUTION_FAILURE_CANCELLED {
		t.Errorf("FailureReason = %s, want JOB_EXECUTION_FAILURE_CANCELLED", request.GetResult().GetFailureReason())
	}
}

func TestReportRepeatedTerminalObservationReportsTheDuplicateDecision(t *testing.T) {
	answers := []*runnerv1.ReportJobStatusResponse{
		applied(runnerv1.JobLifecycleState_JOB_LIFECYCLE_SUCCEEDED),
		{
			JobId:    "job-1",
			Result:   runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_DUPLICATE,
			JobState: runnerv1.JobLifecycleState_JOB_LIFECYCLE_SUCCEEDED,
			Message:  "Job already succeeded",
		},
	}
	controlPlane := newRecordingControlPlane(answers...)
	reporter, _ := startReporter(t, controlPlane, reportTimeout)

	report := Succeeded("job-1", testStartedAt, testCompletedAt, 0)

	first, err := reporter.Report(context.Background(), report)
	if err != nil {
		t.Fatalf("first Report() returned an error: %v", err)
	}
	second, err := reporter.Report(context.Background(), report)
	if err != nil {
		// A repeated report is part of the contract, so it must not be an error.
		t.Fatalf("repeated Report() returned an error: %v", err)
	}

	if first.Decision != DecisionApplied {
		t.Errorf("first Decision = %q, want %q", first.Decision, DecisionApplied)
	}
	if second.Decision != DecisionDuplicate {
		t.Errorf("repeated Decision = %q, want %q", second.Decision, DecisionDuplicate)
	}
	if second.JobState != LifecycleSucceeded {
		t.Errorf("repeated JobState = %q, want %q", second.JobState, LifecycleSucceeded)
	}
	if count := controlPlane.requestCount(); count != 2 {
		t.Errorf("requests received = %d, want 2", count)
	}
}

func TestReportMapsTheRemainingControlPlaneDecisions(t *testing.T) {
	testCases := map[string]struct {
		answer runnerv1.JobStatusReportResult
		want   Decision
	}{
		"unknown job":     {runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_UNKNOWN_JOB, DecisionUnknownJob},
		"conflict":        {runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_CONFLICT, DecisionConflict},
		"unspecified":     {runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_RESULT_UNSPECIFIED, DecisionUnspecified},
		"unknown to this": {runnerv1.JobStatusReportResult(99), DecisionUnspecified},
	}

	for name, testCase := range testCases {
		t.Run(name, func(t *testing.T) {
			controlPlane := newRecordingControlPlane(&runnerv1.ReportJobStatusResponse{
				JobId:  "job-1",
				Result: testCase.answer,
			})
			reporter, _ := startReporter(t, controlPlane, reportTimeout)

			result, err := reporter.Report(context.Background(), Running("job-1", testStartedAt))
			if err != nil {
				t.Fatalf("Report() returned an error: %v", err)
			}
			if result.Decision != testCase.want {
				t.Errorf("Decision = %q, want %q", result.Decision, testCase.want)
			}
		})
	}
}

func TestReportRejectsAMalformedObservationWithoutCallingTheControlPlane(t *testing.T) {
	controlPlane := newRecordingControlPlane(applied(runnerv1.JobLifecycleState_JOB_LIFECYCLE_RUNNING))
	reporter, _ := startReporter(t, controlPlane, reportTimeout)

	malformed := Report{JobID: "job-1", State: StateSucceeded, StartedAt: testStartedAt}

	if _, err := reporter.Report(context.Background(), malformed); err == nil {
		t.Fatal("Report() returned no error, want a validation error")
	}
	if count := controlPlane.requestCount(); count != 0 {
		t.Errorf("requests received = %d, want 0", count)
	}
}

func TestReportReturnsAnErrorWhenTheControlPlaneRefusesTheCall(t *testing.T) {
	controlPlane := newRecordingControlPlane()
	controlPlane.failWith = status.Error(codes.InvalidArgument, "protocol_version must not be empty")
	reporter, _ := startReporter(t, controlPlane, reportTimeout)

	result, err := reporter.Report(context.Background(), Running("job-1", testStartedAt))
	if err == nil {
		t.Fatal("Report() returned no error, want the transport failure")
	}
	if result.Decision != "" {
		t.Errorf("Decision = %q, want no decision for a failed report", result.Decision)
	}
}

func TestReportStopsWaitingWhenTheCallTimeoutExpires(t *testing.T) {
	controlPlane := newRecordingControlPlane()
	// The handler answers only when the call is over, so the reporter's own
	// timeout is what ends the attempt.
	controlPlane.blockUntilCallEnds = true
	reporter, _ := startReporter(t, controlPlane, 50*time.Millisecond)

	_, err := reporter.Report(context.Background(), Running("job-1", testStartedAt))
	if err == nil {
		t.Fatal("Report() returned no error, want a deadline failure")
	}
	if code := status.Code(err); code != codes.DeadlineExceeded {
		t.Errorf("status code = %s, want %s", code, codes.DeadlineExceeded)
	}
}

func TestReportLogsTheAnswerWithoutTheFailureMessage(t *testing.T) {
	controlPlane := newRecordingControlPlane(applied(runnerv1.JobLifecycleState_JOB_LIFECYCLE_FAILED))
	var records bytes.Buffer
	reporter, identity := startReporterWithLogger(
		t,
		controlPlane,
		reportTimeout,
		slog.New(slog.NewTextHandler(&records, nil)),
	)

	report := Failed("job-1", testStartedAt, testCompletedAt, nil, FailureTimeout, "workspace path /tmp/secret-workspace")

	if _, err := reporter.Report(context.Background(), report); err != nil {
		t.Fatalf("Report() returned an error: %v", err)
	}

	logged := records.String()
	for _, want := range []string{
		"job status report acknowledged",
		"job_id=job-1",
		"reported_state=failed",
		"failure_reason=timeout",
		"decision=applied",
		"job_state=failed",
		"runner_id=" + identity.RunnerID,
	} {
		if !strings.Contains(logged, want) {
			t.Errorf("log record does not contain %q: %s", want, logged)
		}
	}
	if strings.Contains(logged, "secret-workspace") {
		// The failure message is derived from execution output and stays out of
		// Runner log records.
		t.Errorf("log record contains the failure message: %s", logged)
	}
}

func TestReportLogsANonAppliedAnswerAsAWarning(t *testing.T) {
	controlPlane := newRecordingControlPlane(&runnerv1.ReportJobStatusResponse{
		JobId:    "job-1",
		Result:   runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_CONFLICT,
		JobState: runnerv1.JobLifecycleState_JOB_LIFECYCLE_CANCELLED,
		Message:  "Job is assigned to another runner instance",
	})
	var records bytes.Buffer
	reporter, _ := startReporterWithLogger(
		t,
		controlPlane,
		reportTimeout,
		slog.New(slog.NewTextHandler(&records, nil)),
	)

	if _, err := reporter.Report(context.Background(), Running("job-1", testStartedAt)); err != nil {
		t.Fatalf("Report() returned an error: %v", err)
	}

	logged := records.String()
	if !strings.Contains(logged, "level=WARN") {
		t.Errorf("log record is not a warning: %s", logged)
	}
	if !strings.Contains(logged, "decision=conflict") {
		t.Errorf("log record does not name the decision: %s", logged)
	}
}

// recordingControlPlane is a Control Plane stand-in that records every report
// it receives and answers with prepared responses in order. The last prepared
// answer is repeated once the list is exhausted.
type recordingControlPlane struct {
	runnerv1.UnimplementedJobExecutionStatusServiceServer

	mutex    sync.Mutex
	requests []*runnerv1.ReportJobStatusRequest
	answers  []*runnerv1.ReportJobStatusResponse

	// failWith, when set, is returned instead of an answer.
	failWith error

	// blockUntilCallEnds holds the handler until the caller's context is done,
	// which lets a test exercise the reporter's own call timeout.
	blockUntilCallEnds bool
}

func newRecordingControlPlane(answers ...*runnerv1.ReportJobStatusResponse) *recordingControlPlane {
	return &recordingControlPlane{answers: answers}
}

func (c *recordingControlPlane) ReportJobStatus(
	ctx context.Context,
	request *runnerv1.ReportJobStatusRequest,
) (*runnerv1.ReportJobStatusResponse, error) {
	c.mutex.Lock()
	c.requests = append(c.requests, request)
	index := len(c.requests) - 1
	answers := c.answers
	failWith := c.failWith
	block := c.blockUntilCallEnds
	c.mutex.Unlock()

	if block {
		<-ctx.Done()
		return nil, status.FromContextError(ctx.Err()).Err()
	}
	if failWith != nil {
		return nil, failWith
	}
	if len(answers) == 0 {
		return &runnerv1.ReportJobStatusResponse{JobId: request.GetJobId()}, nil
	}
	if index >= len(answers) {
		index = len(answers) - 1
	}

	return answers[index], nil
}

func (c *recordingControlPlane) requestCount() int {
	c.mutex.Lock()
	defer c.mutex.Unlock()

	return len(c.requests)
}

func (c *recordingControlPlane) onlyRequest(t *testing.T) *runnerv1.ReportJobStatusRequest {
	t.Helper()

	c.mutex.Lock()
	defer c.mutex.Unlock()

	if len(c.requests) != 1 {
		t.Fatalf("requests received = %d, want 1", len(c.requests))
	}

	return c.requests[0]
}

func applied(state runnerv1.JobLifecycleState) *runnerv1.ReportJobStatusResponse {
	return &runnerv1.ReportJobStatusResponse{
		JobId:    "job-1",
		Result:   runnerv1.JobStatusReportResult_JOB_STATUS_REPORT_APPLIED,
		JobState: state,
		Message:  "report applied",
	}
}

func startReporter(
	t *testing.T,
	controlPlane *recordingControlPlane,
	timeout time.Duration,
) (*Reporter, runneridentity.Identity) {
	t.Helper()

	return startReporterWithLogger(t, controlPlane, timeout, slog.New(slog.DiscardHandler))
}

func startReporterWithLogger(
	t *testing.T,
	controlPlane *recordingControlPlane,
	timeout time.Duration,
	logger *slog.Logger,
) (*Reporter, runneridentity.Identity) {
	t.Helper()

	identity, err := runneridentity.New(
		"test-runner",
		"0.0.1-test",
		runneridentity.ProtocolVersion,
		runneridentity.DefaultCapabilities(),
	)
	if err != nil {
		t.Fatalf("runneridentity.New() returned an error: %v", err)
	}

	return newReporter(
		"in-memory-control-plane",
		identity,
		timeout,
		logger,
		startInMemoryControlPlane(t, controlPlane),
	), identity
}

// startInMemoryControlPlane serves controlPlane over an in-memory listener and
// returns a Dialer that reaches it, so the reporter uses the generated client
// and a real gRPC connection without binding a port.
func startInMemoryControlPlane(t *testing.T, controlPlane *recordingControlPlane) Dialer {
	t.Helper()

	listener := bufconn.Listen(transportBufferSize)
	server := grpc.NewServer()
	runnerv1.RegisterJobExecutionStatusServiceServer(server, controlPlane)

	served := make(chan struct{})
	go func() {
		defer close(served)
		// A test that sends no report can stop the server before this goroutine
		// enters Serve, which is an ordered shutdown rather than a failure.
		if err := server.Serve(listener); err != nil && !errors.Is(err, grpc.ErrServerStopped) {
			t.Errorf("in-memory control plane stopped with an error: %v", err)
		}
	}()

	t.Cleanup(func() {
		server.Stop()
		<-served
	})

	return func(string) (*grpc.ClientConn, error) {
		return grpc.NewClient(
			"passthrough:///in-memory-control-plane",
			grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
				return listener.DialContext(ctx)
			}),
			grpc.WithTransportCredentials(insecure.NewCredentials()),
		)
	}
}
