package sandbox

import (
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
)

const testRevision = "3af0394c1d2b4e5f60718293a4b5c6d7e8f90123"

func TestNewSpecMapsARemoteJobOntoAnExplicitExecution(t *testing.T) {
	job := newTestJob()

	spec, err := NewSpec(job, testSettings(""))
	if err != nil {
		t.Fatalf("NewSpec() returned an error: %v", err)
	}

	if spec.JobID != "job-1" {
		t.Errorf("JobID = %q, want job-1", spec.JobID)
	}
	if spec.Image != "alpine:3.22" || spec.CheckoutImage != "alpine/git:v2.49.1" {
		t.Errorf("images = %q, %q, want the configured images", spec.Image, spec.CheckoutImage)
	}
	if !slices.Equal(spec.Command, []string{"go", "test", "./..."}) {
		t.Errorf("Command = %v, want the dispatched argument vector", spec.Command)
	}
	if spec.WorkingDirectory != "/workspace/runner" {
		t.Errorf("WorkingDirectory = %q, want /workspace/runner", spec.WorkingDirectory)
	}
	if spec.Source.RemoteURL != "https://example.com/repo.git" || spec.Source.HostPath != "" {
		t.Errorf("Source = %+v, want the remote URL only", spec.Source)
	}
	if spec.Source.Revision != testRevision {
		t.Errorf("Revision = %q, want %q", spec.Source.Revision, testRevision)
	}
	if spec.Timeout != time.Minute {
		t.Errorf("Timeout = %s, want the configured timeout", spec.Timeout)
	}
	if spec.Limits != DefaultLimits() {
		t.Errorf("Limits = %+v, want the default limits", spec.Limits)
	}

	// The spec must not alias the request, which the gRPC runtime may reuse.
	job.Execution.Command[0] = "rm"
	if spec.Command[0] != "go" {
		t.Error("Command aliases the request argument slice")
	}
}

func TestNewSpecResolvesWorkingDirectoriesInsideTheWorkspace(t *testing.T) {
	testCases := map[string]string{
		".":              "/workspace",
		"services/api":   "/workspace/services/api",
		`services\api`:   "/workspace/services/api",
		"services/./api": "/workspace/services/api",
	}

	for workingDirectory, want := range testCases {
		t.Run(workingDirectory, func(t *testing.T) {
			job := newTestJob()
			job.Execution.WorkingDirectory = workingDirectory

			spec, err := NewSpec(job, testSettings(""))
			if err != nil {
				t.Fatalf("NewSpec() returned an error: %v", err)
			}
			if spec.WorkingDirectory != want {
				t.Errorf("WorkingDirectory = %q, want %q", spec.WorkingDirectory, want)
			}
		})
	}
}

func TestNewSpecRejectsJobsThatCannotRunSafely(t *testing.T) {
	testCases := []struct {
		name   string
		mutate func(job *runnerv1.JobSpecification)
	}{
		{name: "missing execution", mutate: func(job *runnerv1.JobSpecification) { job.Execution = nil }},
		{name: "empty command", mutate: func(job *runnerv1.JobSpecification) { job.Execution.Command = nil }},
		{name: "absolute working directory", mutate: func(job *runnerv1.JobSpecification) { job.Execution.WorkingDirectory = "/etc" }},
		{name: "drive rooted working directory", mutate: func(job *runnerv1.JobSpecification) { job.Execution.WorkingDirectory = `C:\work` }},
		{name: "traversing working directory", mutate: func(job *runnerv1.JobSpecification) { job.Execution.WorkingDirectory = "a/../../b" }},
		{name: "revision read as an option", mutate: func(job *runnerv1.JobSpecification) { job.Repository.Revision = "--upload-pack=touch /tmp/x" }},
		{name: "revision with spaces", mutate: func(job *runnerv1.JobSpecification) { job.Repository.Revision = "main; rm -rf /" }},
		{name: "oversized revision", mutate: func(job *runnerv1.JobSpecification) { job.Repository.Revision = strings.Repeat("a", 256) }},
		{name: "ssh scheme", mutate: func(job *runnerv1.JobSpecification) { job.Repository.Location = "ssh://git@example.com/repo.git" }},
		{name: "ext transport", mutate: func(job *runnerv1.JobSpecification) { job.Repository.Location = "ext::sh -c touch% /tmp/pwned" }},
		{name: "relative location", mutate: func(job *runnerv1.JobSpecification) { job.Repository.Location = "repo.git" }},
		{name: "file location while disabled", mutate: func(job *runnerv1.JobSpecification) { job.Repository.Location = "file:///srv/repo" }},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			job := newTestJob()
			testCase.mutate(job)

			_, err := NewSpec(job, testSettings(""))
			if !errors.Is(err, ErrUnsupportedJob) {
				t.Fatalf("NewSpec() error = %v, want ErrUnsupportedJob", err)
			}
		})
	}
}

func TestNewSpecNeverEchoesTheRepositoryLocation(t *testing.T) {
	job := newTestJob()
	job.Repository.Location = "ssh://user:s3cr3t@example.com/repo.git"

	_, err := NewSpec(job, testSettings(""))
	if err == nil {
		t.Fatal("NewSpec() returned no error for an unsupported scheme")
	}
	if strings.Contains(err.Error(), "s3cr3t") {
		t.Errorf("error %q contains the repository location", err)
	}
}

func TestNewSpecAcceptsAFileLocationInsideTheLocalSourceRoot(t *testing.T) {
	root := t.TempDir()
	repository := filepath.Join(root, "project")
	if err := os.Mkdir(repository, 0o755); err != nil {
		t.Fatalf("create repository directory: %v", err)
	}

	job := newTestJob()
	job.Repository.Location = fileURI(repository)

	spec, err := NewSpec(job, testSettings(root))
	if err != nil {
		t.Fatalf("NewSpec() returned an error: %v", err)
	}

	want, _ := filepath.EvalSymlinks(repository)
	if spec.Source.HostPath != want || spec.Source.RemoteURL != "" {
		t.Errorf("Source = %+v, want host path %q only", spec.Source, want)
	}
}

func TestNewSpecRejectsAFileLocationOutsideTheLocalSourceRoot(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()

	testCases := map[string]string{
		"sibling directory": fileURI(outside),
		"parent traversal":  fileURI(filepath.Join(root, "..", filepath.Base(outside))),
		"missing directory": fileURI(filepath.Join(root, "missing")),
		"remote file host":  "file://fileserver/share/repo",
	}

	for name, location := range testCases {
		t.Run(name, func(t *testing.T) {
			job := newTestJob()
			job.Repository.Location = location

			_, err := NewSpec(job, testSettings(root))
			if !errors.Is(err, ErrUnsupportedJob) {
				t.Fatalf("NewSpec() error = %v, want ErrUnsupportedJob", err)
			}
		})
	}
}

func newTestJob() *runnerv1.JobSpecification {
	return &runnerv1.JobSpecification{
		JobId: "job-1",
		Repository: &runnerv1.JobRepository{
			Location: "https://example.com/repo.git",
			Revision: testRevision,
		},
		Execution: &runnerv1.JobExecution{
			Command:          []string{"go", "test", "./..."},
			WorkingDirectory: "runner",
		},
	}
}

func testSettings(localSourceRoot string) Settings {
	return Settings{
		Image:           "alpine:3.22",
		CheckoutImage:   "alpine/git:v2.49.1",
		Timeout:         time.Minute,
		LocalSourceRoot: localSourceRoot,
		Limits:          DefaultLimits(),
	}
}

// fileURI builds a file:// URI for a host path on any operating system.
func fileURI(hostPath string) string {
	slashed := filepath.ToSlash(hostPath)
	if !strings.HasPrefix(slashed, "/") {
		slashed = "/" + slashed
	}

	return (&url.URL{Scheme: "file", Path: slashed}).String()
}
