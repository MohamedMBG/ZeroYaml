package grpcserver

import (
	"context"
	"fmt"
	"net/url"
	"path"
	"strings"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// acceptedMessage and the rejection messages below are operator-facing
// diagnostics. They describe Runner state and never echo request content, which
// keeps repository details and command arguments out of Control Plane logs.
const acceptedMessage = "job accepted by the runner"

// RunJob validates a dispatched Job and answers whether this process takes
// responsibility for it.
//
// Checks run in contract order. The protocol version is examined first, because
// an unsupported version makes the meaning of the remaining fields uncertain.
// Field validation follows, and Runner state is examined last, so that a caller
// defect is reported as such even while the Runner is unavailable.
//
// The current Runner has no execution service, so it reports
// accepting_work = false and rejects every dispatch with
// JOB_REJECTION_RUNNER_UNAVAILABLE. Accepting a Job becomes reachable when
// execution support is added; nothing here executes a command.
func (s *Server) RunJob(
	ctx context.Context,
	req *runnerv1.RunJobRequest,
) (*runnerv1.RunJobResponse, error) {
	if req == nil {
		return nil, status.Error(codes.InvalidArgument, "run job request must be present")
	}

	if strings.TrimSpace(req.GetProtocolVersion()) == "" {
		return nil, status.Error(codes.InvalidArgument, "protocol_version must not be empty")
	}

	if req.GetProtocolVersion() != s.identity.ProtocolVersion {
		return s.rejectJob(
			req.GetJob().GetJobId(),
			runnerv1.JobRejectionReason_JOB_REJECTION_UNSUPPORTED_PROTOCOL_VERSION,
			fmt.Sprintf("runner implements protocol version %s", s.identity.ProtocolVersion),
		), nil
	}

	if err := validateJobSpecification(req.GetJob()); err != nil {
		return nil, status.Error(codes.InvalidArgument, err.Error())
	}

	if !s.identity.AcceptingWork {
		return s.rejectJob(
			req.GetJob().GetJobId(),
			runnerv1.JobRejectionReason_JOB_REJECTION_RUNNER_UNAVAILABLE,
			fmt.Sprintf("runner status is %s and it is not accepting work", s.identity.Status),
		), nil
	}

	return &runnerv1.RunJobResponse{
		JobId:      req.GetJob().GetJobId(),
		Acceptance: runnerv1.JobAcceptance_JOB_ACCEPTED,
		Message:    acceptedMessage,
		RunnerId:   s.identity.RunnerID,
		InstanceId: s.identity.InstanceID,
	}, nil
}

// rejectJob answers a well-formed dispatch the Runner declines. The response
// carries the answering process so that the Control Plane can record which
// Runner refused the Job.
func (s *Server) rejectJob(
	jobID string,
	reason runnerv1.JobRejectionReason,
	message string,
) *runnerv1.RunJobResponse {
	return &runnerv1.RunJobResponse{
		JobId:           jobID,
		Acceptance:      runnerv1.JobAcceptance_JOB_REJECTED,
		RejectionReason: reason,
		Message:         message,
		RunnerId:        s.identity.RunnerID,
		InstanceId:      s.identity.InstanceID,
	}
}

// validateJobSpecification enforces the executable minimum of the contract at
// the remote boundary. A Runner receives dispatches over the network, so it
// never assumes that a caller validated the request first.
func validateJobSpecification(job *runnerv1.JobSpecification) error {
	if job == nil {
		return fmt.Errorf("job must be present")
	}
	if strings.TrimSpace(job.GetJobId()) == "" {
		return fmt.Errorf("job_id must not be empty")
	}
	if err := validateRepository(job.GetRepository()); err != nil {
		return err
	}

	return validateExecution(job.GetExecution())
}

func validateRepository(repository *runnerv1.JobRepository) error {
	if repository == nil {
		return fmt.Errorf("repository must be present")
	}

	location := strings.TrimSpace(repository.GetLocation())
	if location == "" {
		return fmt.Errorf("repository location must not be empty")
	}

	parsed, err := url.Parse(location)
	if err != nil || !parsed.IsAbs() {
		// The scheme is what tells the Runner how to resolve the source, so a
		// relative or unparsable location cannot be acted on.
		return fmt.Errorf("repository location must be an absolute URI")
	}

	if strings.TrimSpace(repository.GetRevision()) == "" {
		return fmt.Errorf("repository revision must not be empty")
	}

	return nil
}

func validateExecution(execution *runnerv1.JobExecution) error {
	if execution == nil {
		return fmt.Errorf("execution must be present")
	}

	command := execution.GetCommand()
	if len(command) == 0 {
		return fmt.Errorf("execution command must contain at least one argument")
	}
	for _, argument := range command {
		if strings.TrimSpace(argument) == "" {
			return fmt.Errorf("execution command arguments must not be empty")
		}
	}

	return validateWorkingDirectory(execution.GetWorkingDirectory())
}

// validateWorkingDirectory keeps a dispatch inside the workspace the Runner
// controls. An absolute path or a parent traversal would let a caller select a
// directory outside the checked-out repository, which is a privilege the
// dispatch contract does not grant.
func validateWorkingDirectory(workingDirectory string) error {
	if strings.TrimSpace(workingDirectory) == "" {
		return fmt.Errorf("execution working_directory must not be empty")
	}

	// Both separators are normalized because the Control Plane and the Runner
	// may run on different operating systems.
	normalized := strings.ReplaceAll(workingDirectory, `\`, "/")
	if path.IsAbs(normalized) || isWindowsDriveRooted(normalized) {
		return fmt.Errorf("execution working_directory must be relative to the repository")
	}

	for _, element := range strings.Split(normalized, "/") {
		if element == ".." {
			return fmt.Errorf("execution working_directory must not traverse outside the repository")
		}
	}

	return nil
}

// isWindowsDriveRooted reports a path such as "C:/work", which path.IsAbs does
// not treat as absolute because it only understands slash-rooted paths.
func isWindowsDriveRooted(normalizedPath string) bool {
	if len(normalizedPath) < 2 || normalizedPath[1] != ':' {
		return false
	}

	driveLetter := normalizedPath[0]

	return (driveLetter >= 'a' && driveLetter <= 'z') || (driveLetter >= 'A' && driveLetter <= 'Z')
}
