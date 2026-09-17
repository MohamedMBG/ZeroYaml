# ZeroYAML — Developer Guide

Welcome to **ZeroYAML**.

This document is the first thing new contributors should read before changing code. Its purpose is to explain the repository structure, what each folder/file is responsible for, and how the main parts of the system fit together.

> **Core idea:** the **Control Plane decides and coordinates**; the **Runner executes**.

---

## 1. What is ZeroYAML?

ZeroYAML is a developer platform that aims to turn a connected Git repository into an executable CI/CD pipeline without requiring developers to maintain large YAML workflow files.

At a high level:

```text
GitHub Repository
       |
       | webhook / API events
       v
+----------------------+
|    CONTROL PLANE     |
|  Java + Spring Boot  |
|                      |
| Decides what runs,   |
| when it runs, and    |
| where it runs.       |
+----------+-----------+
           |
           | gRPC
           v
+----------------------+
|        RUNNER        |
|          Go          |
|                      |
| Executes jobs in     |
| isolated containers. |
+----------+-----------+
           |
           v
         Docker
```

Supporting infrastructure will include PostgreSQL, Redis, and Kafka.

---

## 2. Repository Structure

Current structure:

```text
ZeroYAML/
|
|-- control-plane/          # Java/Spring Boot control plane
|-- runner/                 # Go execution runner
|-- infra/                  # Local infrastructure and Docker configuration
|-- docs/                   # Architecture and engineering documentation
|   `-- architecture/
|
|-- .gitignore              # Files Git must not track
`-- README.md               # Main project introduction
```

Each top-level directory represents a different responsibility. Avoid mixing responsibilities between them.

---

# 3. `control-plane/`

```text
control-plane/
```

The Control Plane is the **brain of ZeroYAML**.

It will eventually be responsible for:

- receiving API requests;
- receiving GitHub webhooks;
- understanding pipeline definitions;
- coordinating pipeline execution;
- scheduling jobs;
- keeping execution state;
- selecting runners;
- communicating with runners;
- storing persistent application data;
- publishing and consuming system events.

The Control Plane **does not execute user builds directly**. Execution belongs to the Runner.

Technology:

- Java 21
- Spring Boot
- Maven

---

## 3.1 `control-plane/pom.xml`

```text
control-plane/
`-- pom.xml
```

This is the Maven project configuration.

It defines things such as:

- project metadata;
- Java version;
- Spring Boot version;
- dependencies;
- Maven plugins;
- build configuration;
- testing configuration.

Think of it roughly as:

```text
Java       -> pom.xml
Node.js    -> package.json
Go         -> go.mod
```

When a Java dependency is added to the Control Plane, `pom.xml` is usually where it is declared.

---

## 3.2 `control-plane/src/main/java/`

```text
control-plane/
`-- src/
    `-- main/
        `-- java/
            `-- io/
                `-- zeroyaml/
                    `-- controlplane/
```

This folder contains the **actual Java source code** for the Control Plane.

Our base Java package is:

```text
io.zeroyaml.controlplane
```

As the project grows, this package will be divided into modules such as:

```text
io.zeroyaml.controlplane
|
|-- api/
|-- github/
|-- pipeline/
|-- scheduler/
|-- execution/
|-- runner/
|-- security/
|-- persistence/
`-- config/
```

Do not create random classes directly in the root package when a clear module exists.

---

## 3.3 `ControlPlaneApplication.java`

```text
src/main/java/io/zeroyaml/controlplane/
`-- ControlPlaneApplication.java
```

This is the **entry point of the Spring Boot application**.

It contains the Java `main()` method that starts the Control Plane.

Conceptually:

```text
ControlPlaneApplication.main()
          |
          v
Spring Boot starts
          |
          v
Spring discovers application components
          |
          v
HTTP server and application context start
```

This file should remain small. Business logic should not be placed here.

---

## 3.4 `control-plane/src/main/resources/`

```text
src/main/resources/
|
|-- application.properties
|-- static/
`-- templates/
```

This directory contains resources and application configuration that are not Java source files.

### `application.properties`

Spring Boot configuration lives here.

Examples of things that may eventually be configured here:

- application name;
- HTTP port;
- database connection;
- Kafka;
- Redis;
- logging;
- feature configuration.

Secrets should **not** be hard-coded here. Sensitive values should come from environment variables or a secret-management system.

### `static/`

Spring can serve static files from this directory.

ZeroYAML's Control Plane is primarily an API, so this folder may remain unused.

### `templates/`

Spring can render server-side HTML templates from this directory.

ZeroYAML currently does not depend on server-side HTML rendering, so this folder may also remain unused.

---

## 3.5 `control-plane/src/test/`

```text
control-plane/
`-- src/
    `-- test/
        `-- java/
```

All automated Java tests belong here.

The generated project currently contains:

```text
ControlPlaneApplicationTests.java
```

Its first responsibility is simply verifying that the Spring application context can start.

As ZeroYAML grows, tests should cover components such as:

```text
PipelineServiceTest
WebhookServiceTest
SchedulerTest
RunnerRegistryTest
ExecutionServiceTest
```

Run Java tests with:

```powershell
.\control-plane\mvnw.cmd test
```

---

## 3.6 `.mvn/`

```text
control-plane/
`-- .mvn/
    `-- wrapper/
        `-- maven-wrapper.properties
```

This belongs to the **Maven Wrapper**.

The wrapper lets contributors use the Maven configuration expected by the project instead of relying entirely on a globally installed Maven version.

Windows:

```powershell
.\control-plane\mvnw.cmd test
```

Linux/macOS:

```bash
./control-plane/mvnw test
```

---

## 3.7 `mvnw` and `mvnw.cmd`

```text
mvnw
mvnw.cmd
```

Both files launch the Maven Wrapper.

- `mvnw` -> Linux/macOS
- `mvnw.cmd` -> Windows

Do not delete them.

---

## 3.8 `control-plane/.gitignore`

This `.gitignore` is specific to the Java/Spring Boot project.

It prevents generated or local files such as Maven build output or IDE metadata from being committed.

The repository also has a root `.gitignore`.

---

## 3.9 `control-plane/.gitattributes`

This file helps Git treat files consistently across operating systems.

One important use is normalizing line endings between:

- Windows;
- Linux;
- macOS.

Normally this file should not require frequent changes.

---

## 3.10 `control-plane/HELP.md`

This file is generated by Spring Initializr.

It contains links and basic information about the generated Spring Boot project.

It is not application code and may eventually be removed once the ZeroYAML documentation fully replaces it.

---

# 4. `runner/`

```text
runner/
```

The Runner is the **execution engine**.

Technology:

- Go
- Docker
- gRPC

The Runner will eventually:

1. register itself with the Control Plane;
2. receive execution requests;
3. prepare an isolated execution environment;
4. start Docker containers;
5. execute build/test/deployment commands;
6. stream logs and execution state;
7. return the final result to the Control Plane.

Example:

```text
Control Plane
     |
     | Run job #123
     v
   Runner
     |
     | create isolated container
     v
   Docker
     |
     | mvn test / npm test / go test ...
     v
Execution result
```

The Runner should focus on **safe, reliable execution**. Business decisions about which pipeline should run belong in the Control Plane.

The Go project will later contain its own `go.mod`, entry point, internal packages, executor code, and gRPC implementation.

---

# 5. `infra/`

```text
infra/
```

Infrastructure configuration belongs here.

This directory will contain the services required to run ZeroYAML locally and eventually deployment-related infrastructure.

Expected local services include:

- PostgreSQL;
- Redis;
- Kafka.

A future structure may look like:

```text
infra/
|
|-- compose.yaml
|-- postgres/
|-- kafka/
`-- monitoring/
```

The intention is that developers should not manually install every infrastructure service.

Eventually a command such as:

```powershell
docker compose -f .\infra\compose.yaml up -d
```

should start the required local environment.

---

# 6. `docs/`

```text
docs/
```

Long-form technical documentation belongs here.

Code explains **how the current implementation works**.

Documentation should also explain **why architectural decisions were made**.

---

## 6.1 `docs/architecture/`

```text
docs/
`-- architecture/
```

This directory is for system architecture documentation.

Examples:

```text
architecture/
|
|-- overview.md
|-- control-plane.md
|-- runner.md
|-- execution-flow.md
`-- event-flow.md
```

When a major architectural decision is made, document it instead of leaving the reasoning only in chat messages or developer memory.

---

# 7. Root `.gitignore`

```text
ZeroYAML/
`-- .gitignore
```

The root `.gitignore` applies to the whole repository.

Because ZeroYAML contains multiple technologies, it ignores files from several ecosystems.

Examples:

```text
**/target/        # Java/Maven build output
runner/bin/       # Go binaries
.env              # local environment variables
.idea/            # IntelliJ metadata
.vscode/          # VS Code workspace metadata
*.log             # logs
```

Never commit secrets, tokens, passwords, or private `.env` files.

---

# 8. Root `README.md`

```text
ZeroYAML/
`-- README.md
```

This is the public front page of the repository.

It should answer questions such as:

- What is ZeroYAML?
- What problem does it solve?
- How is it architected?
- How do I run it?
- What stage is the project currently in?
- Where should contributors start?

Keep the root README concise enough that a new developer can understand the project quickly.

This Developer Guide can contain the deeper repository explanation.

---

# 9. Architecture Rule to Remember

The most important separation in ZeroYAML is:

```text
CONTROL PLANE
    |
    | decides + coordinates
    |
    v
RUNNER
    |
    | executes
    |
    v
DOCKER
```

### Control Plane

Answers:

> What should run?

> When should it run?

> Which runner should execute it?

> What is the current state?

### Runner

Answers:

> How do I execute this job safely?

> How do I isolate it?

> What logs/results should I return?

Do not move execution responsibilities into the Control Plane merely because it is easier in the short term.

---

# 10. Prerequisites

Before working on ZeroYAML, install:

### Required for all contributors

- Git
- Docker Desktop
- Docker Compose

### Control Plane development

- Java 21

### Control Plane Runner connection

The Control Plane generates its Java gRPC client from `proto/runner/v1/runner.proto` during the Maven build. The local Runner connection uses these defaults:

| Property | Default | Purpose |
| --- | --- | --- |
| `zeroyaml.runner.host` | `localhost` | Runner host name |
| `zeroyaml.runner.port` | `50051` | Runner gRPC port |
| `zeroyaml.runner.ping-deadline` | `2s` | Ping RPC deadline |

Override them with Spring Boot environment variables such as `ZEROYAML_RUNNER_HOST`, `ZEROYAML_RUNNER_PORT`, and `ZEROYAML_RUNNER_PING_DEADLINE`; do not hardcode deployment endpoints in Java code.

### Runner development

- Go
- Docker

### Frontend development

When the frontend is introduced:

- Node.js
- pnpm

Developers working only on one subsystem do not necessarily need every language toolchain immediately, but integration work may require multiple toolchains.

---

# 11. First Commands for a New Developer

Clone the repository:

```powershell
git clone https://github.com/MohamedMBG/ZeroYAML.git
cd ZeroYAML
```

Check Java:

```powershell
java -version
```

Check Go:

```powershell
go version
```

Check Docker:

```powershell
docker --version
docker compose version
```

Run Control Plane tests:

```powershell
.\control-plane\mvnw.cmd test
```

When the Runner is initialized:

```powershell
Push-Location runner
go test ./...
Pop-Location
```

To generate the Control Plane gRPC client and verify its Ping integration path, run:

```powershell
.\control-plane\mvnw.cmd test
```

The focused Ping test uses an in-process Runner and does not require a separately running Runner process.

---

# 12. Before Creating New Code

Before adding a class, package, service, or infrastructure component, ask:

1. Does this belong to the Control Plane or the Runner?
2. Does a module for this responsibility already exist?
3. Am I introducing business logic into an entry-point/configuration class?
4. Am I committing generated files or secrets?
5. Does this change affect an architectural contract that should be documented?

Prefer clear boundaries over shortcuts.

---

# 13. Git Workflow

Do not work directly on the main branch.

Use feature branches, for example:

```text
feat/phase-1-foundation
feat/grpc-runner-contract
feat/github-webhooks
fix/runner-heartbeat
docs/architecture-overview
```

Before committing:

```powershell
git status
```

Run the relevant tests.

Then use clear commit messages:

```text
feat: add runner registration contract
fix: handle invalid webhook signature
test: add scheduler unit tests
docs: document execution lifecycle
chore: configure local kafka service
```

---

# 14. Current Project Phase

ZeroYAML is currently in **Phase 1 — Foundation**.

The immediate goal is to establish:

- repository structure;
- Java Control Plane;
- Go Runner;
- local infrastructure;
- architecture documentation;
- communication contracts;
- reliable development workflow.

Do not prematurely build advanced pipeline features before the foundation and service boundaries are stable.

---

# 15. If You Are New to the Project

Read these in order:

```text
1. Root README.md
2. This Developer Guide
3. docs/architecture/
4. The module you are going to work on
5. Related tests
```

If you still do not understand where a change belongs, discuss the architecture before implementing it.

---

## Final Mental Model

```text
                    ZeroYAML

                  GitHub / API
                       |
                       v
              +----------------+
              | CONTROL PLANE  |
              | Java / Spring  |
              +-------+--------+
                      |
                     gRPC
                      |
                      v
              +----------------+
              |     RUNNER     |
              |       Go       |
              +-------+--------+
                      |
                    Docker
                      |
                      v
                User workload


        PostgreSQL / Redis / Kafka
                  support
             the platform
```

**Control Plane = decide and coordinate.**

**Runner = execute.**

Keep that distinction clear and the rest of the architecture becomes much easier to reason about.
