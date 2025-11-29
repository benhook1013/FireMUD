# Repository Structure

This repository uses a hierarchical Gradle layout. All microservices and the shared
`common-library` live under a top-level `services/` folder to keep the root tidy. The
React UI resides in `web-client/` and various infrastructure manifests sit under
`k8s/`. Additional build tooling and documentation live at the root.
Hidden configuration files used by the CI pipeline and generated build output
directories are included for completeness.

The tree and summary below follow a Windows Explorer style sort: directories appear
before files, and items within each group are alphabetized.

```text
root
├── .github/
├── .gradle/
├── .vscode/
├── build/
├── buildSrc/
├── charts/
├── config/
├── design/
├── dev-tools/
├── docker/
├── gradle/
├── k8s/
├── node_modules/
├── protos/
├── services/
│   ├── account-service
│   ├── automation-scripting-service
│   ├── common-library
│   ├── entity-management-service
│   ├── game-design-service
│   ├── game-logic-service
│   ├── game-session-service
│   ├── logging-admin-service
│   ├── social-groups-service
│   ├── spring-cloud-gateway
│   ├── tcp-proxy-service
│   └── world-management-service
├── web-client/
├── .editorconfig
├── .env.sample
├── .gitattributes
├── .gitignore
├── .lycheeignore
├── .pre-commit-config.yaml
├── .windsurfrules
├── AGENTS.md
├── CODE_OF_CONDUCT.md
├── CONTRIBUTING.md
├── DEVELOPER_SETUP.md
├── FAQ.md
├── LICENSE.md
├── NOTICE.md
├── README.md
├── SECURITY.md
├── build.gradle.kts
├── codex-maintenance.sh
├── codex-setup.sh
├── copilot-instructions.md
├── gradle.properties
├── gradlew
└── gradlew.bat
```

## Directory summary

- `.github/` – GitHub Actions workflows and issue templates.
- `.gradle/` – Local Gradle cache (ignored by source control).
- `.vscode/` – Recommended workspace settings for VS Code.
- `build/` – Generated Gradle build outputs (ignored by source control).
- `buildSrc/` – Shared Gradle convention plugins and build logic applied across modules.
- `charts/` – Umbrella Helm chart for deploying all services together.
- `config/` – Checkstyle, ESLint, git hooks, Hadolint, lychee link checker, Markdownlint, OpenAPI generator, protobuf (Buf), Redis, release automation, security scans, SpotBugs, and TypeScript configs.
- `design/` – Architecture, operations, and user guide documentation.
- `dev-tools/` – Shell utilities for database backups and restores, ERD generation, development certificates, a CLI, API client configs (Insomnia and Kreya), data seeding, and a Gatling load-testing module.
- `docker/` – Base Dockerfiles and Docker Compose stack for local development.
- `gradle/` – Gradle version catalog, build conventions, and wrapper binaries.
- `k8s/` – Kubernetes manifests, per-service Helm charts, monitoring configs, network policies, and sample Terraform modules for local and production clusters.
- `node_modules/` – Installed JavaScript dependencies for the `web-client` and tooling (ignored by source control).
- `protos/` – Versioned gRPC definitions for every service.
- `services/` – Spring Boot microservices plus the shared `common-library` Gradle module.
- `web-client/` – React web application.
- `.editorconfig` – Consistent indentation and newline settings across editors.
- `.env.sample` – Example environment variables loaded by `docker compose` and the test suites.
- `.gitattributes` – Source control line-ending defaults and attribute rules.
- `.gitignore` – Git ignore rules for build outputs, IDE files, and dependencies.
- `.lycheeignore` – Ignore rules for the lychee link checker.
- `.pre-commit-config.yaml` – Configuration for automated formatting and lint checks.
- `.windsurfrules` – Compatibility link to the local AI rules.
- `AGENTS.md` – Contribution guide pointing to project AI rules.
- `CODE_OF_CONDUCT.md` – Community conduct expectations.
- `CONTRIBUTING.md` – Developer onboarding and contribution workflow.
- `DEVELOPER_SETUP.md` – Step-by-step project setup instructions.
- `FAQ.md` – Frequently asked questions for contributors.
- `LICENSE.md` – Licensing terms for the project.
- `NOTICE.md` – Third-party notices required by the license.
- `README.md` – High-level project overview and quick-start.
- `SECURITY.md` – Responsible disclosure and security reporting guidance.
- `build.gradle.kts` – Root Gradle build file that aggregates all modules.
- `codex-maintenance.sh` and `codex-setup.sh` – Setup and maintenance helpers for Codex CLI workflows and AI tooling.
- `copilot-instructions.md` – Usage notes and conventions for GitHub Copilot in this project.
- `gradle.properties` – Shared Gradle settings.
- `gradlew` & `gradlew.bat` – Wrapper scripts for invoking Gradle.

Proto definitions live under `protos/` organized by service and version as described in the
[gRPC API Style & Versioning Guidelines](./system-architecture-grpc.md).
Database migration scripts for each service reside in
`services/<service>/src/main/resources/db/migration/`.

## ðŸ“š Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Microservices Overview](./microservices/README.md)
