# Repository Structure

This repository uses a hierarchical Gradle layout. All microservices and the shared
`common-library` live under a top-level `services/` folder to keep the root tidy. The
React UI resides in `web-client/` and various infrastructure manifests sit under
`k8s/`. Additional build tooling and documentation live at the root.
Hidden configuration files used by the CI pipeline are omitted from the tree for
brevity.

```text
root
├── services/
│   ├── common-library
│   ├── account-service
│   ├── ...
│   ├── spring-cloud-gateway
│   └── tcp-proxy-service
├── protos/
├── web-client/
├── design/
├── config/
├── dev-tools/
├── docker/
├── k8s/
├── charts/
├── AGENTS.md
├── .env.sample
├── gradle/
├── .github/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── package.json
├── package-lock.json
├── gradlew
└── gradlew.bat
```

## Directory summary

- `services/` – All Spring Boot microservices and the shared `common-library` Gradle module.
- `protos/` – Versioned gRPC definitions for every service.
- `web-client/` – React web application.
- `design/` – Architecture, operations, and user guide documentation.
- `config/` – Checkstyle, ESLint, git hooks, Hadolint, lychee link checker, Markdownlint, protobuf (Buf), Redis, release automation, security scans, SpotBugs, and TypeScript configs.
- `dev-tools/` – Shell utilities for database backups, ERD generation, API client configs, and a Gatling load-testing module.
- `docker/` – Base Dockerfiles and Docker Compose stack for local development.
- `k8s/` – Kubernetes manifests, per-service Helm charts, monitoring configs,
  network policies, and sample Terraform modules for local and production clusters.
- `charts/` – Umbrella Helm chart for deploying all services together.
- `.github/` – GitHub Actions workflows and issue templates.
- `gradle/` – Gradle wrapper binaries.
- `AGENTS.md` – Contribution guide pointing to project AI rules.
- `.env.sample` – Example environment variables loaded by `docker compose` and the test suites.
- `.editorconfig` – Consistent indentation and newline settings across editors.
- `.gitignore` & `.gitattributes` – Source control rules and line-ending defaults.
- `build.gradle.kts` – Root Gradle build file that aggregates all modules.
- `settings.gradle.kts` – Declares Gradle subprojects.
- `gradle.properties` – Shared Gradle settings.
- `package.json` & `package-lock.json` – Node scripts used for markdown linting and docs.
- `README.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `DEVELOPER_SETUP.md`, `LICENSE.md`, `NOTICE.md`, `FAQ.md`, `SECURITY.md` and other root Markdown files – Project documentation and guidelines.
- `gradlew` & `gradlew.bat` – Wrapper scripts for invoking Gradle.
- `dev-tools/init-gradle-wrappers.ps1` – Utility script to generate Gradle wrappers for each service on Windows.
- `.windsurfrules` – Compatibility link to the local AI rules.
- `.vscode/` – Recommended workspace settings for VS Code.

Proto definitions live under `protos/` organized by service and version as described in the
[gRPC API Style & Versioning Guidelines](./system-architecture-grpc.md).
Database migration scripts for each service reside in
`services/<service>/src/main/resources/db/migration/`.

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Microservices Overview](./microservices/README.md)
