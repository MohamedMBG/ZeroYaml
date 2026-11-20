package sandbox

import (
	"context"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
)

// dockerTestsVariable opts in to the tests that start real containers. They
// need a reachable Docker daemon, a git binary, and the configured images, so
// they stay out of the default go test run.
const dockerTestsVariable = "ZEROYAML_RUNNER_DOCKER_TESTS"

func TestDockerExecutionOfALocalRepository(t *testing.T) {
	root, revision := newDockerTestRepository(t)
	executor := New("docker", slog.New(slog.NewTextHandler(os.Stderr, nil)), nil, 5*time.Second)

	testCases := []struct {
		name             string
		command          []string
		workingDirectory string
		revision         string
		timeout          time.Duration
		wantOutcome      Outcome
		wantExitCode     int32
	}{
		{
			name:             "command reads the checked-out revision in its working directory",
			command:          []string{"sh", "-c", `test "$(pwd)" = /workspace/service && test "$(cat greeting.txt)" = hello`},
			workingDirectory: "service",
			timeout:          2 * time.Minute,
			wantOutcome:      OutcomeSucceeded,
			wantExitCode:     0,
		},
		{
			name:             "non-zero exit code is reported",
			command:          []string{"sh", "-c", "exit 7"},
			workingDirectory: ".",
			timeout:          2 * time.Minute,
			wantOutcome:      OutcomeFailed,
			wantExitCode:     7,
		},
		{
			name:             "command outliving its time budget is stopped",
			command:          []string{"sleep", "300"},
			workingDirectory: ".",
			timeout:          20 * time.Second,
			wantOutcome:      OutcomeTimedOut,
		},
		{
			name:             "unreachable revision is reported as an execution error",
			command:          []string{"true"},
			workingDirectory: ".",
			revision:         strings.Repeat("0", 40),
			timeout:          2 * time.Minute,
			wantOutcome:      OutcomeExecutionError,
		},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			jobRevision := revision
			if testCase.revision != "" {
				jobRevision = testCase.revision
			}
			spec := newDockerTestSpec(t, root, jobRevision, testCase.timeout, testCase.workingDirectory, testCase.command...)

			result := executor.Run(context.Background(), spec)

			if result.Outcome != testCase.wantOutcome {
				t.Fatalf("Outcome = %s, want %s: %s", result.Outcome, testCase.wantOutcome, result.Message)
			}
			switch testCase.wantOutcome {
			case OutcomeSucceeded, OutcomeFailed:
				if result.ExitCode == nil || *result.ExitCode != testCase.wantExitCode {
					t.Errorf("ExitCode = %v, want %d", result.ExitCode, testCase.wantExitCode)
				}
			default:
				if result.ExitCode != nil {
					t.Errorf("ExitCode = %d, want none for a command that did not run to completion", *result.ExitCode)
				}
			}

			assertNoDockerResourcesRemain(t, result.ExecutionID)
		})
	}
}

// TestDockerExecutionCleansUpAfterCancellation cancels an execution while its
// Job container is running, as a Runner shutdown does, and checks that every
// Docker resource is gone afterwards.
func TestDockerExecutionCleansUpAfterCancellation(t *testing.T) {
	root, revision := newDockerTestRepository(t)
	executor, executionID := newDockerTestSandbox(t)
	spec := newDockerTestSpec(t, root, revision, 2*time.Minute, ".", "sleep", "300")

	ctx, cancel := context.WithCancel(context.Background())
	var running sync.WaitGroup
	defer running.Wait()
	defer cancel()
	done := make(chan Result, 1)
	running.Go(func() { done <- executor.Run(ctx, spec) })

	jobContainer := newResourceNames(executionID).job
	waitForDocker(t, done, "the job container to run", func() bool {
		return dockerSucceeds("exec", jobContainer, "true")
	})
	cancel()
	result := <-done

	if result.Outcome != OutcomeCancelled {
		t.Fatalf("Outcome = %s, want %s: %s", result.Outcome, OutcomeCancelled, result.Message)
	}
	if result.ExitCode != nil {
		t.Errorf("ExitCode = %d, want none for a cancelled command", *result.ExitCode)
	}
	assertNoDockerResourcesRemain(t, executionID)
}

// TestDockerExecutionIsolatesConcurrentWorkspaces runs two executions at the
// same time. Each writes a marker into its workspace and then waits until the
// test releases it, so both markers exist before either execution looks for
// the other one. A shared or crossed workspace would show two markers.
func TestDockerExecutionIsolatesConcurrentWorkspaces(t *testing.T) {
	root, revision := newDockerTestRepository(t)
	const script = `echo "$0" > "/workspace/marker-$0"
until [ -f /workspace/release ]; do sleep 0.1; done
test "$(ls /workspace | grep -c '^marker-')" = 1
test "$(cat "/workspace/marker-$0")" = "$0"`

	type execution struct {
		name   string
		id     string
		result chan Result
	}
	executions := []*execution{{name: "alpha"}, {name: "beta"}}

	ctx, cancel := context.WithCancel(context.Background())
	var running sync.WaitGroup
	// A failed assertion cancels both executions and waits for their cleanup.
	defer running.Wait()
	defer cancel()
	for _, current := range executions {
		var executor *Sandbox
		executor, current.id = newDockerTestSandbox(t)
		current.result = make(chan Result, 1)
		spec := newDockerTestSpec(t, root, revision, 2*time.Minute, ".", "sh", "-c", script, current.name)
		running.Go(func() { current.result <- executor.Run(ctx, spec) })
	}

	for _, current := range executions {
		names := newResourceNames(current.id)
		waitForDocker(t, current.result, "the "+current.name+" marker", func() bool {
			return dockerSucceeds("exec", names.job, "test", "-f", "/workspace/marker-"+current.name)
		})

		mounts := dockerOutput(t, "inspect", "--format",
			"{{range .Mounts}}{{.Type}} {{.Name}} {{.Destination}};{{end}}", names.job)
		if want := "volume " + names.volume + " /workspace;"; mounts != want {
			t.Errorf("%s job container mounts = %q, want only its own workspace %q", current.name, mounts, want)
		}
	}
	for _, current := range executions {
		dockerOutput(t, "exec", newResourceNames(current.id).job, "touch", "/workspace/release")
	}

	for _, current := range executions {
		result := <-current.result
		if result.Outcome != OutcomeSucceeded {
			t.Errorf("%s Outcome = %s, want %s: %s", current.name, result.Outcome, OutcomeSucceeded, result.Message)
		}
		assertNoDockerResourcesRemain(t, current.id)
	}
}

// TestDockerExecutionRefusesAWorkspaceVolumeThatAlreadyExists simulates an
// execution ID collision: a volume with the execution's workspace name already
// exists. docker volume create accepts the name, so the claim check must stop
// the execution without mounting or removing the existing volume.
func TestDockerExecutionRefusesAWorkspaceVolumeThatAlreadyExists(t *testing.T) {
	root, revision := newDockerTestRepository(t)
	executor, executionID := newDockerTestSandbox(t)
	names := newResourceNames(executionID)
	dockerOutput(t, "volume", "create", "--label", managedLabel, "--label", executionLabelName+"="+executionID, names.volume)
	t.Cleanup(func() { _ = exec.Command("docker", "volume", "rm", "--force", names.volume).Run() })

	result := executor.Run(context.Background(), newDockerTestSpec(t, root, revision, 2*time.Minute, ".", "true"))

	if result.Outcome != OutcomeExecutionError {
		t.Fatalf("Outcome = %s, want %s: %s", result.Outcome, OutcomeExecutionError, result.Message)
	}
	if !dockerSucceeds("volume", "inspect", names.volume) {
		t.Error("the existing workspace volume was removed")
	}
	filter := "label=" + executionLabelName + "=" + executionID
	if containers := dockerOutput(t, "ps", "--all", "--quiet", "--filter", filter); containers != "" {
		t.Errorf("containers were created for a refused workspace: %s", containers)
	}
}

// newDockerTestSandbox returns a Sandbox for real containers and the execution
// ID it will use, so a test can address the execution's resources while it
// runs.
func newDockerTestSandbox(t *testing.T) (*Sandbox, string) {
	t.Helper()

	executionID, err := newExecutionID()
	if err != nil {
		t.Fatal(err)
	}
	executor := New("docker", slog.New(slog.NewTextHandler(os.Stderr, nil)), nil, 5*time.Second)
	executor.newID = func() (string, error) { return executionID, nil }

	return executor, executionID
}

func newDockerTestSpec(
	t *testing.T,
	root string,
	revision string,
	timeout time.Duration,
	workingDirectory string,
	command ...string,
) Spec {
	t.Helper()

	settings := Settings{
		Image:           "alpine:3.22",
		CheckoutImage:   "alpine/git:v2.49.1",
		Timeout:         timeout,
		LocalSourceRoot: root,
		Limits:          DefaultLimits(),
	}
	job := &runnerv1.JobSpecification{
		JobId:      "integration-job",
		Repository: &runnerv1.JobRepository{Location: fileURI(root), Revision: revision},
		Execution: &runnerv1.JobExecution{
			Command:          command,
			WorkingDirectory: workingDirectory,
		},
	}

	spec, err := NewSpec(job, settings)
	if err != nil {
		t.Fatalf("NewSpec() returned an error: %v", err)
	}

	return spec
}

// waitForDocker polls condition until it holds. It fails early when the
// execution already finished, because the condition can then never hold, and
// fails after one minute, which covers pulling the pinned images.
func waitForDocker(t *testing.T, finished <-chan Result, description string, condition func() bool) {
	t.Helper()

	deadline := time.Now().Add(time.Minute)
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for !condition() {
		select {
		case result := <-finished:
			t.Fatalf("execution finished before %s: %s: %s", description, result.Outcome, result.Message)
		case <-ticker.C:
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", description)
		}
	}
}

func dockerSucceeds(args ...string) bool {
	return exec.Command("docker", args...).Run() == nil
}

func dockerOutput(t *testing.T, args ...string) string {
	t.Helper()

	output, err := exec.Command("docker", args...).Output()
	if err != nil {
		t.Fatalf("docker %s failed: %v", strings.Join(args, " "), err)
	}

	return strings.TrimSpace(string(output))
}

// newDockerTestRepository creates a git repository with one commit and
// returns its directory and commit SHA. A later commit moves the branch so the
// test proves the dispatched revision, not the branch head, is checked out.
func newDockerTestRepository(t *testing.T) (string, string) {
	t.Helper()

	if os.Getenv(dockerTestsVariable) != "1" {
		t.Skipf("set %s=1 to run tests that start Docker containers", dockerTestsVariable)
	}
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is required to build the test repository")
	}
	if _, err := Probe(context.Background(), "docker", 10*time.Second); err != nil {
		t.Fatalf("%s=1 but Docker is unavailable: %v", dockerTestsVariable, err)
	}

	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "service"), 0o755); err != nil {
		t.Fatalf("create service directory: %v", err)
	}
	if err := os.WriteFile(filepath.Join(root, "service", "greeting.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatalf("write greeting: %v", err)
	}

	runGit(t, root, "init", "--quiet")
	runGit(t, root, "add", ".")
	runGit(t, root, "commit", "--quiet", "-m", "first")
	revision := runGit(t, root, "rev-parse", "HEAD")

	if err := os.WriteFile(filepath.Join(root, "service", "greeting.txt"), []byte("changed"), 0o644); err != nil {
		t.Fatalf("rewrite greeting: %v", err)
	}
	runGit(t, root, "commit", "--quiet", "-am", "second")

	return root, revision
}

func runGit(t *testing.T, directory string, args ...string) string {
	t.Helper()

	command := exec.Command("git", append([]string{"-c", "user.name=ZeroYAML Test", "-c", "user.email=test@zeroyaml.invalid"}, args...)...)
	command.Dir = directory

	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("git %s failed: %v: %s", strings.Join(args, " "), err, output)
	}

	return strings.TrimSpace(string(output))
}

func assertNoDockerResourcesRemain(t *testing.T, executionID string) {
	t.Helper()

	if executionID == "" {
		t.Fatal("result carries no execution ID")
	}

	filter := "label=" + executionLabelName + "=" + executionID
	for _, args := range [][]string{
		{"ps", "--all", "--quiet", "--filter", filter},
		{"volume", "ls", "--quiet", "--filter", filter},
	} {
		output, err := exec.Command("docker", args...).Output()
		if err != nil {
			t.Fatalf("docker %s failed: %v", strings.Join(args, " "), err)
		}
		if remaining := strings.TrimSpace(string(output)); remaining != "" {
			t.Errorf("docker %s found leftover resources: %s", args[0], remaining)
		}
	}
}
