package grpcserver

import (
	"context"
	"testing"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"github.com/MohamedMBG/ZeroYaml/runner/internal/runneridentity"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// TestRunJobAcceptsAValidDispatch covers the acknowledgment a Runner returns
// once it takes responsibility for a Job. The response must name the answering
// process, because the Control Plane records that pair against the Job.
func TestRunJobAcceptsAValidDispatch(t *testing.T) {
	identity := newAcceptingTestIdentity(t)

	response, err := New(identity).RunJob(context.Background(), newValidRunJobRequest())
	if err != nil {
		t.Fatalf("RunJob() returned an error: %v", err)
	}

	if response.GetAcceptance() != runnerv1.JobAcceptance_JOB_ACCEPTED {
		t.Errorf("Acceptance = %s, want %s", response.GetAcceptance(), runnerv1.JobAcceptance_JOB_ACCEPTED)
	}
	if response.GetRejectionReason() != runnerv1.JobRejectionReason_JOB_REJECTION_REASON_UNSPECIFIED {
		t.Errorf("RejectionReason = %s, want unspecified for an accepted job", response.GetRejectionReason())
	}
	if response.GetJobId() != testJobID {
		t.Errorf("JobId = %q, want %q", response.GetJobId(), testJobID)
	}
	if response.GetRunnerId() != identity.RunnerID {
		t.Errorf("RunnerId = %q, want %q", response.GetRunnerId(), identity.RunnerID)
	}
	if response.GetInstanceId() != identity.InstanceID {
		t.Errorf("InstanceId = %q, want %q", response.GetInstanceId(), identity.InstanceID)
	}
}

// TestRunJobRejectsDispatchWhileNotAcceptingWork pins the behavior of the
// current Runner, which has no execution service and therefore declines work.
// The refusal is a Runner state decision, so it is an OK response the Control
// Plane can act on rather than a transport error.
func TestRunJobRejectsDispatchWhileNotAcceptingWork(t *testing.T) {
	identity := newTestIdentity(t)

	response, err := New(identity).RunJob(context.Background(), newValidRunJobRequest())
	if err != nil {
		t.Fatalf("RunJob() returned an error: %v", err)
	}

	if response.GetAcceptance() != runnerv1.JobAcceptance_JOB_REJECTED {
		t.Errorf("Acceptance = %s, want %s", response.GetAcceptance(), runnerv1.JobAcceptance_JOB_REJECTED)
	}
	if response.GetRejectionReason() != runnerv1.JobRejectionReason_JOB_REJECTION_RUNNER_UNAVAILABLE {
		t.Errorf(
			"RejectionReason = %s, want %s",
			response.GetRejectionReason(),
			runnerv1.JobRejectionReason_JOB_REJECTION_RUNNER_UNAVAILABLE,
		)
	}
	if response.GetJobId() != testJobID {
		t.Errorf("JobId = %q, want %q", response.GetJobId(), testJobID)
	}
	if response.GetRunnerId() != identity.RunnerID {
		t.Errorf("RunnerId = %q, want %q", response.GetRunnerId(), identity.RunnerID)
	}
	if response.GetMessage() == "" {
		t.Error("Message is empty, want an operator-facing reason")
	}
}

// TestRunJobRejectsAnUnsupportedProtocolVersion shows that a version the Runner
// does not implement is answered before the request fields are interpreted.
func TestRunJobRejectsAnUnsupportedProtocolVersion(t *testing.T) {
	identity := newAcceptingTestIdentity(t)

	request := newValidRunJobRequest()
	request.ProtocolVersion = "runner.v99"

	response, err := New(identity).RunJob(context.Background(), request)
	if err != nil {
		t.Fatalf("RunJob() returned an error: %v", err)
	}

	if response.GetAcceptance() != runnerv1.JobAcceptance_JOB_REJECTED {
		t.Errorf("Acceptance = %s, want %s", response.GetAcceptance(), runnerv1.JobAcceptance_JOB_REJECTED)
	}
	if response.GetRejectionReason() != runnerv1.JobRejectionReason_JOB_REJECTION_UNSUPPORTED_PROTOCOL_VERSION {
		t.Errorf(
			"RejectionReason = %s, want %s",
			response.GetRejectionReason(),
			runnerv1.JobRejectionReason_JOB_REJECTION_UNSUPPORTED_PROTOCOL_VERSION,
		)
	}
}

// TestRunJobRejectsRequestsThatViolateTheContract covers caller defects. Each
// case is a request no compliant Control Plane sends, so the Runner answers
// INVALID_ARGUMENT instead of producing an acknowledgment that would suggest
// the Job is known to the Runner.
func TestRunJobRejectsRequestsThatViolateTheContract(t *testing.T) {
	testCases := []struct {
		name    string
		request *runnerv1.RunJobRequest
	}{
		{
			name:    "absent request",
			request: nil,
		},
		{
			name: "absent protocol version",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.ProtocolVersion = "  "
			}),
		},
		{
			name: "absent job",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job = nil
			}),
		},
		{
			name: "absent job identity",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.JobId = ""
			}),
		},
		{
			name: "absent repository",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Repository = nil
			}),
		},
		{
			name: "relative repository location",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Repository.Location = "MohamedMBG/ZeroYaml"
			}),
		},
		{
			name: "absent repository revision",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Repository.Revision = ""
			}),
		},
		{
			name: "absent execution",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Execution = nil
			}),
		},
		{
			name: "empty command",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Execution.Command = nil
			}),
		},
		{
			name: "blank command argument",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Execution.Command = []string{"go", "   "}
			}),
		},
		{
			name: "absent working directory",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Execution.WorkingDirectory = ""
			}),
		},
		{
			name: "absolute working directory",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Execution.WorkingDirectory = "/etc"
			}),
		},
		{
			name: "drive rooted working directory",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Execution.WorkingDirectory = `C:\Windows`
			}),
		},
		{
			name: "working directory traversing outside the repository",
			request: mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Execution.WorkingDirectory = "runner/../../secrets"
			}),
		},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			// The identity accepts work so that a rejected request proves the
			// contract check rather than the Runner's availability.
			identity := newAcceptingTestIdentity(t)

			response, err := New(identity).RunJob(context.Background(), testCase.request)
			if err == nil {
				t.Fatalf("RunJob() returned %v, want an error", response)
			}
			if code := status.Code(err); code != codes.InvalidArgument {
				t.Errorf("status code = %s, want %s: %v", code, codes.InvalidArgument, err)
			}
			if response != nil {
				t.Errorf("response = %v, want no response for a rejected request", response)
			}
		})
	}
}

// TestRunJobAcceptsAWorkingDirectoryInsideTheRepository documents the accepted
// shape of a nested working directory, including a Windows-style separator.
func TestRunJobAcceptsAWorkingDirectoryInsideTheRepository(t *testing.T) {
	testCases := []string{".", "services/api", `services\api`, "services/./api"}

	for _, workingDirectory := range testCases {
		t.Run(workingDirectory, func(t *testing.T) {
			request := mutateRunJobRequest(func(request *runnerv1.RunJobRequest) {
				request.Job.Execution.WorkingDirectory = workingDirectory
			})

			response, err := New(newAcceptingTestIdentity(t)).RunJob(context.Background(), request)
			if err != nil {
				t.Fatalf("RunJob() returned an error: %v", err)
			}
			if response.GetAcceptance() != runnerv1.JobAcceptance_JOB_ACCEPTED {
				t.Errorf("Acceptance = %s, want %s", response.GetAcceptance(), runnerv1.JobAcceptance_JOB_ACCEPTED)
			}
		})
	}
}

const testJobID = "0f4b1a1e-1f2c-4c53-9b3a-3f5b5f4b9a11"

// newValidRunJobRequest builds the dispatch a compliant Control Plane sends.
func newValidRunJobRequest() *runnerv1.RunJobRequest {
	return &runnerv1.RunJobRequest{
		ProtocolVersion: runneridentity.ProtocolVersion,
		Job: &runnerv1.JobSpecification{
			JobId: testJobID,
			Repository: &runnerv1.JobRepository{
				Location: "https://github.com/MohamedMBG/ZeroYaml.git",
				Revision: "3af0394c1d2b4e5f60718293a4b5c6d7e8f90123",
			},
			Execution: &runnerv1.JobExecution{
				Command:          []string{"go", "test", "./..."},
				WorkingDirectory: "runner",
			},
		},
	}
}

// mutateRunJobRequest derives a request from the valid dispatch so that each
// test case states only the field under test.
func mutateRunJobRequest(mutate func(request *runnerv1.RunJobRequest)) *runnerv1.RunJobRequest {
	request := newValidRunJobRequest()
	mutate(request)

	return request
}

// newAcceptingTestIdentity builds the identity of a Runner that reports itself
// ready for work, which the current process does not do on its own because it
// has no execution service yet.
func newAcceptingTestIdentity(t *testing.T) runneridentity.Identity {
	t.Helper()

	identity := newTestIdentity(t)
	identity.Status = runneridentity.StatusReady
	identity.AcceptingWork = true

	return identity
}
