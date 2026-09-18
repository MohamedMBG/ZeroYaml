# Runner Identity and Registration Contract

The Runner reports facts about its process and execution environment. The
Control Plane owns the registry and makes scheduling decisions.

## Identity fields

| Field | Meaning |
| --- | --- |
| `runner_id` | Stable logical identity configured by the operator. It must remain unchanged across process restarts. |
| `instance_id` | Cryptographically random process identity generated once at startup. A restart receives a new value. |
| `runner_version` | Version of the Runner application. |
| `protocol_version` | Version of the Control Plane-to-Runner protobuf contract. The current value is `runner.v1`. |

The pair `(runner_id, instance_id)` identifies one Runner process. Hostnames,
IP addresses, timestamps, and container IDs are not Runner identity values.

## Capabilities and state

`RunnerCapabilities` contains operating system, architecture, Docker support,
supported executors, and operator-defined labels. A Runner must only report
capabilities that have been verified locally.

`RunnerStatus` currently supports:

- `STARTING`
- `READY`
- `DRAINING`
- `UNAVAILABLE`

`accepting_work` is a Runner-reported fact. The Control Plane must still apply
its own registry, lease, capability, and scheduling rules before assigning work.

The current Runner has no execution service yet, so `GetInfo` reports
`UNAVAILABLE` and `accepting_work = false`.

## Registration semantics

`RunnerRegistrationService.Register` is the versioned registration boundary
owned by the Control Plane. Persistence, lease expiry, and heartbeat scheduling
are separate follow-up responsibilities.

The Control Plane should handle registration results as follows:

- `REGISTRATION_ACCEPTED`: a new `(runner_id, instance_id)` was registered.
- `REGISTRATION_ALREADY_REGISTERED`: the same pair registered again; the
  operation is idempotent.
- `REGISTRATION_IDENTITY_CONFLICT`: the same `runner_id` is already associated
  with a different active `instance_id`. The Control Plane must not silently
  replace the active process.

After the previous registration lease expires, a new process using the same
`runner_id` may register its new `instance_id`.

## Compatibility

The protobuf package is `zeroyaml.runner.v1`. New fields must use new field
numbers, and removed field numbers must never be reused. Older clients ignore
unknown fields, while a client that does not understand a required protocol
version must reject the registration with an explicit compatibility error.

Generated Go and Java bindings come from
`proto/runner/v1/runner.proto`; generated files must not be edited manually.
