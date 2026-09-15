# ZeroYAML

ZeroYAML is a developer platform that turns a connected Git repository
into an executable CI/CD pipeline without requiring developers to write
and maintain large YAML workflow files.


## New contributor?

If this is your first time working on ZeroYAML, read:

[Developer Guide](./DEVELOPER_GUIDE.md)

It explains the repository structure, architecture, prerequisites,
development workflow, and the purpose of the main files and folders.


## Architecture

ZeroYAML is divided into two main planes:

### Control Plane

Responsible for:

- API
- GitHub integration
- webhook processing
- pipeline orchestration
- scheduling
- execution coordination

Technology:

- Java 21
- Spring Boot

### Execution Plane

Responsible for executing pipeline jobs in isolated environments.

Technology:

- Go
- Docker
- gRPC

### Infrastructure

The local development environment contains:

- PostgreSQL
- Redis
- Kafka

## Repository structure

```text
ZeroYAML/
├── control-plane/
├── runner/
├── infra/
└── docs/
    └── architecture/