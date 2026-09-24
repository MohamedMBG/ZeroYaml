package sandbox

import (
	"errors"
	"fmt"
	"net/url"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
)

// workspaceMountPath is where every Job container sees the checked-out
// repository. Working directories from the dispatch are resolved below it, so a
// Job can never select a directory outside its own workspace.
const workspaceMountPath = "/workspace"

// Default resource bounds for local development. They keep a runaway build from
// exhausting the Docker host without reserving capacity up front: memory is a
// ceiling, not a reservation, and the process limit stops fork bombs.
const (
	DefaultMemoryBytes int64 = 2 << 30
	DefaultPIDsLimit   int64 = 512
)

// maxRevisionLength bounds the revision handed to the checkout container.
const maxRevisionLength = 255

// revisionPattern accepts commit SHAs and plain reference names. The leading
// character must be alphanumeric so a revision can never be read as a git
// command-line option such as --upload-pack.
var revisionPattern = regexp.MustCompile(`^[0-9A-Za-z][0-9A-Za-z._/-]*$`)

// ErrUnsupportedJob marks a well-formed dispatch that this Runner cannot map to
// a container invocation, such as a repository scheme it does not fetch.
var ErrUnsupportedJob = errors.New("job cannot be executed by this runner")

// Limits are the resource bounds applied to every container of one execution.
type Limits struct {
	MemoryBytes int64
	PIDs        int64
}

// DefaultLimits returns the local-development resource bounds.
func DefaultLimits() Limits {
	return Limits{MemoryBytes: DefaultMemoryBytes, PIDs: DefaultPIDsLimit}
}

// Settings are the Runner-owned execution inputs that the RunJob contract does
// not carry yet. They are operator configuration, never repository-specific
// decisions.
type Settings struct {
	// Image runs the Job command.
	Image string

	// CheckoutImage fetches the repository into the workspace. It must provide
	// sh and git.
	CheckoutImage string

	// Timeout bounds the whole execution, from workspace creation to command
	// exit.
	Timeout time.Duration

	// LocalSourceRoot is the only host directory below which file:// repository
	// locations are accepted. Empty disables file:// locations, because mounting
	// an arbitrary host path would let a dispatch read the Runner's host.
	LocalSourceRoot string

	Limits Limits
}

// Source describes where the checkout container fetches the repository from.
// Exactly one of RemoteURL and HostPath is set.
type Source struct {
	// RemoteURL is fetched over the network from inside the checkout container.
	RemoteURL string

	// HostPath is a host repository directory, bind-mounted read-only into the
	// checkout container.
	HostPath string

	// Revision is the immutable revision checked out into the workspace.
	Revision string
}

// Spec is one fully resolved container execution. Every value the Docker
// invocation needs is explicit here, so the invocation is a pure function of
// the Spec.
type Spec struct {
	JobID         string
	Image         string
	CheckoutImage string

	// Command is the exact argument vector; the first element is the
	// executable. It is never interpreted by a shell.
	Command []string

	// WorkingDirectory is an absolute path inside the Job container.
	WorkingDirectory string

	Source  Source
	Timeout time.Duration
	Limits  Limits
}

// NewSpec maps a dispatched Job onto a container execution.
//
// The Job is expected to have passed RunJob validation already. NewSpec still
// rejects any input that would be unsafe to hand to Docker, because it is the
// last check before a container starts. Every error wraps ErrUnsupportedJob and
// never echoes the repository location, which may embed credentials.
func NewSpec(job *runnerv1.JobSpecification, settings Settings) (Spec, error) {
	if job == nil || job.GetExecution() == nil || job.GetRepository() == nil {
		return Spec{}, unsupported("job, repository, and execution must be present")
	}

	command := job.GetExecution().GetCommand()
	if len(command) == 0 {
		return Spec{}, unsupported("execution command must contain at least one argument")
	}

	workingDirectory, err := containerWorkingDirectory(job.GetExecution().GetWorkingDirectory())
	if err != nil {
		return Spec{}, err
	}

	source, err := resolveSource(job.GetRepository(), settings.LocalSourceRoot)
	if err != nil {
		return Spec{}, err
	}

	return Spec{
		JobID:            job.GetJobId(),
		Image:            settings.Image,
		CheckoutImage:    settings.CheckoutImage,
		Command:          append([]string(nil), command...),
		WorkingDirectory: workingDirectory,
		Source:           source,
		Timeout:          settings.Timeout,
		Limits:           settings.Limits,
	}, nil
}

// containerWorkingDirectory resolves a repository-relative directory to its
// absolute path inside the Job container.
func containerWorkingDirectory(workingDirectory string) (string, error) {
	normalized := strings.ReplaceAll(strings.TrimSpace(workingDirectory), `\`, "/")
	if normalized == "" {
		return "", unsupported("execution working_directory must not be empty")
	}
	if path.IsAbs(normalized) || strings.Contains(normalized, ":") {
		return "", unsupported("execution working_directory must be relative to the repository")
	}

	for _, element := range strings.Split(normalized, "/") {
		if element == ".." {
			return "", unsupported("execution working_directory must not traverse outside the repository")
		}
	}

	return path.Join(workspaceMountPath, normalized), nil
}

func resolveSource(repository *runnerv1.JobRepository, localSourceRoot string) (Source, error) {
	revision := strings.TrimSpace(repository.GetRevision())
	if len(revision) > maxRevisionLength || !revisionPattern.MatchString(revision) {
		return Source{}, unsupported("repository revision must be a commit SHA or reference name starting with a letter or digit")
	}

	location, err := url.Parse(strings.TrimSpace(repository.GetLocation()))
	if err != nil || !location.IsAbs() {
		return Source{}, unsupported("repository location must be an absolute URI")
	}

	switch strings.ToLower(location.Scheme) {
	case "https", "http":
		return Source{RemoteURL: location.String(), Revision: revision}, nil
	case "file":
		hostPath, err := resolveLocalSource(location, localSourceRoot)
		if err != nil {
			return Source{}, err
		}

		return Source{HostPath: hostPath, Revision: revision}, nil
	default:
		// Only schemes the checkout container can fetch without extra credentials
		// or transports are accepted; git's ext:: and similar transports can run
		// arbitrary commands.
		return Source{}, unsupported(fmt.Sprintf("repository scheme %q is not supported; use https, http, or file", location.Scheme))
	}
}

// resolveLocalSource turns a file:// location into a host directory inside the
// configured local source root. Symbolic links are resolved before the
// containment check so a link inside the root cannot point outside it.
func resolveLocalSource(location *url.URL, localSourceRoot string) (string, error) {
	if localSourceRoot == "" {
		return "", unsupported("file repository locations are disabled on this runner")
	}
	if location.Host != "" && location.Host != "localhost" {
		return "", unsupported("file repository locations must refer to the runner host")
	}

	locationPath := location.Path
	// A Windows location such as file:///C:/src parses to the path /C:/src.
	if len(locationPath) >= 3 && locationPath[0] == '/' && locationPath[2] == ':' {
		locationPath = locationPath[1:]
	}

	hostPath := filepath.Clean(filepath.FromSlash(locationPath))
	if !filepath.IsAbs(hostPath) {
		return "", unsupported("file repository location must be an absolute path")
	}

	resolvedPath, err := filepath.EvalSymlinks(hostPath)
	if err != nil {
		return "", unsupported("file repository location does not exist on the runner host")
	}

	resolvedRoot, err := filepath.EvalSymlinks(localSourceRoot)
	if err != nil {
		return "", unsupported("configured local source root does not exist on the runner host")
	}

	relative, err := filepath.Rel(resolvedRoot, resolvedPath)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", unsupported("file repository location is outside the configured local source root")
	}

	// A comma would split the --mount option that carries the path to Docker.
	if strings.Contains(resolvedPath, ",") {
		return "", unsupported("file repository location must not contain a comma")
	}

	return resolvedPath, nil
}

func unsupported(reason string) error {
	return fmt.Errorf("%w: %s", ErrUnsupportedJob, reason)
}
