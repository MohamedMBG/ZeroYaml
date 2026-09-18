package runneridentity

import (
	"crypto/rand"
	"fmt"
	"io"
	"runtime"
	"strings"
)

const ProtocolVersion = "runner.v1"

type Status string

const (
	StatusStarting    Status = "starting"
	StatusReady       Status = "ready"
	StatusDraining    Status = "draining"
	StatusUnavailable Status = "unavailable"
)

type Capabilities struct {
	OperatingSystem    string
	Architecture       string
	DockerAvailable    bool
	SupportedExecutors []string
	Labels             map[string]string
}

type Identity struct {
	RunnerID        string
	InstanceID      string
	RunnerVersion   string
	ProtocolVersion string
	Capabilities    Capabilities
	Status          Status
	AcceptingWork   bool
}

// DefaultCapabilities reports the capabilities known without probing external
// dependencies. Docker remains unavailable until execution support is verified.
func DefaultCapabilities() Capabilities {
	return Capabilities{
		OperatingSystem:    runtime.GOOS,
		Architecture:       runtime.GOARCH,
		DockerAvailable:    false,
		SupportedExecutors: []string{},
		Labels:             map[string]string{},
	}
}

// New creates the identity held by one Runner process. Call it once during
// startup and pass the returned value to components that report Runner state.
func New(runnerID, runnerVersion, protocolVersion string, capabilities Capabilities) (Identity, error) {
	if strings.TrimSpace(runnerID) == "" {
		return Identity{}, fmt.Errorf("runner ID must not be empty")
	}
	if strings.TrimSpace(runnerVersion) == "" {
		return Identity{}, fmt.Errorf("runner version must not be empty")
	}
	if strings.TrimSpace(protocolVersion) == "" {
		return Identity{}, fmt.Errorf("protocol version must not be empty")
	}

	instanceID, err := newInstanceID()
	if err != nil {
		return Identity{}, fmt.Errorf("generate Runner instance ID: %w", err)
	}

	return Identity{
		RunnerID:        runnerID,
		InstanceID:      instanceID,
		RunnerVersion:   runnerVersion,
		ProtocolVersion: protocolVersion,
		Capabilities:    capabilities,
		Status:          StatusUnavailable,
		AcceptingWork:   false,
	}, nil
}

// newInstanceID generates an RFC 4122 version 4 UUID using cryptographically
// secure random bytes. It intentionally does not derive identity from host
// metadata, network addresses, or timestamps.
func newInstanceID() (string, error) {
	return newInstanceIDFrom(rand.Reader)
}

func newInstanceIDFrom(randomSource io.Reader) (string, error) {
	var value [16]byte
	if _, err := io.ReadFull(randomSource, value[:]); err != nil {
		return "", err
	}

	value[6] = value[6]&0x0f | 0x40
	value[8] = value[8]&0x3f | 0x80

	return fmt.Sprintf(
		"%08x-%04x-%04x-%04x-%012x",
		value[0:4],
		value[4:6],
		value[6:8],
		value[8:10],
		value[10:16],
	), nil
}
