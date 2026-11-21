package jobstatus

import (
	"strings"
	"testing"

	runnerv1 "github.com/MohamedMBG/ZeroYaml/runner/gen/runner/v1"
)

// TestLifecycleMappingCoversEveryRunnerV1State proves that the Runner reads
// every Job lifecycle state of execution model v1 that runner.v1 defines, so a
// state added to the contract without a Runner mapping fails the build. The
// Control Plane holds the matching check against the same enum.
func TestLifecycleMappingCoversEveryRunnerV1State(t *testing.T) {
	const prefix = "JOB_LIFECYCLE_"
	mapped := map[LifecycleState]bool{}
	for number, name := range runnerv1.JobLifecycleState_name {
		state := runnerv1.JobLifecycleState(number)
		got := lifecycleFromProto(state)
		if state == runnerv1.JobLifecycleState_JOB_LIFECYCLE_STATE_UNSPECIFIED {
			if got != LifecycleUnspecified {
				t.Errorf("lifecycleFromProto(%s) = %q, want %q", name, got, LifecycleUnspecified)
			}
			continue
		}
		want := LifecycleState(strings.ToLower(strings.TrimPrefix(name, prefix)))
		if got != want {
			t.Errorf("lifecycleFromProto(%s) = %q, want %q", name, got, want)
		}
		mapped[got] = true
	}
	if len(mapped) != 7 {
		t.Errorf("mapped %d distinct lifecycle states, want the 7 states of execution model v1", len(mapped))
	}
}
