# Repository Structure

This repository uses a hierarchical Gradle layout. Microservices and shared platform modules live under a top-level `services/` folder to keep the root tidy. The React UI resides in `web-client/` and various infrastructure manifests sit under `k8s/`. Additional build tooling and documentation live at the root.
Hidden configuration files used by the CI pipeline are included for completeness.

The tree below describes the canonical committed layout. Local caches and generated output are listed separately in a dedicated local-workspace section.

The tree and summary below follow a Windows Explorer style sort: directories appear before files, and items within each group are alphabetized.

```text
root
├── .github/
├── .vscode/
├── buildSrc/
├── charts/
├── config/
├── design/
├── dev-tools/
├── docker/
├── gradle/
├── k8s/
├── protos/
├── services/
│   ├── account-service/
│   ├── automation-scripting-service/
│   ├── common-data-runtime/
│   ├── common-library/
│   ├── common-platform-core/
│   ├── common-saga/
│   ├── common-security/
│   ├── common-test-support/
│   ├── common-web-support/
│   ├── entity-management-service/
│   ├── game-design-service/
│   ├── game-logic-service/
│   ├── game-session-service/
│   ├── logging-admin-service/
│   ├── social-groups-service/
│   ├── spring-cloud-gateway/
│   ├── tcp-proxy-service/
│   └── world-management-service/
├── web-client/
├── .editorconfig
├── .env.sample
├── .gitattributes
├── .gitignore
├── .lycheeignore
├── .pre-commit-config.yaml
├── .windsurfrules
├── AGENTS.md
├── build.gradle.kts
├── CODE_OF_CONDUCT.md
├── codex-maintenance.sh
├── codex-setup.sh
├── CONTRIBUTING.md
├── copilot-instructions.md
├── DEVELOPER_SETUP.md
├── FAQ.md
├── gradle.properties
├── gradlew
├── gradlew.bat
├── LICENSE.md
├── NOTICE.md
├── NOTICE.template.md
├── README.md
└── SECURITY.md
```

## Directory summary

- `.github/` – GitHub Actions workflows and issue templates.
- `.vscode/` – Recommended workspace settings for VS Code.
- `buildSrc/` – Shared Gradle convention plugins and build logic applied across modules.
- `charts/` – Umbrella Helm chart for deploying all services together.
- `config/` – Checkstyle, ESLint, git hooks, Hadolint, lychee link checker, Markdownlint, OpenAPI generator, protobuf (Buf), Redis, release automation, security scans, SpotBugs, and TypeScript configs.
- `design/` – Architecture, operations, and user guide documentation; under `design/architecture/`, overview tables, responsibility matrices, glossary terms, and explicitly labeled canonical sections define target-state contracts for implementation unless a document says otherwise.
- `dev-tools/` – Bash and Python utilities for CI helpers, deployment preflight and rollout tooling, database backups and restores, release asset generation, development certificates, API client configs (Insomnia and Kreya), preview helpers, observability scripts, data seeding, smoke-test entrypoints, and a Gatling load-testing module.
- `docker/` – Base Dockerfiles and Docker Compose stack for local development.
- `gradle/` – Gradle version catalog, build conventions, and wrapper binaries.
- `k8s/` – Kubernetes base manifests, Helm charts, overlays, monitoring configs, network policies, preview/prod support assets, storage/database helpers, Velero backup assets, and Terraform modules for local and production-like clusters.
- `protos/` – Versioned gRPC definitions for every service, organized by service and version as described in this directory’s [gRPC API Style & Versioning Guidelines](./system-architecture-grpc.md).
- `services/` – Spring Boot microservices plus shared platform/library Gradle modules (`common-library`, `common-data-runtime`, `common-platform-core`, `common-saga`, `common-security`, `common-test-support`, and `common-web-support`), including Flyway database migration scripts under `services/<service>/src/main/resources/db/migration/` where applicable.
- `web-client/` – React web application.
- `.editorconfig` – Consistent indentation and newline settings across editors.
- `.env.sample` – Example environment variables loaded by `docker compose` and the test suites.
- `.gitattributes` – Source control line-ending defaults and attribute rules.
- `.gitignore` – Git ignore rules for build outputs, IDE files, and dependencies.
- `.lycheeignore` – Ignore rules for the lychee link checker.
- `.pre-commit-config.yaml` – Configuration for automated formatting and lint checks.
- `.windsurfrules` – Compatibility link to the local AI rules.
- `AGENTS.md` – Contribution guide pointing to project AI rules.
- `build.gradle.kts` – Root Gradle build file that aggregates all modules.
- `CODE_OF_CONDUCT.md` – Community conduct expectations.
- `codex-maintenance.sh` and `codex-setup.sh` – Setup and maintenance helpers for Codex CLI workflows and AI tooling.
- `CONTRIBUTING.md` – Developer onboarding and contribution workflow.
- `copilot-instructions.md` – Usage notes and conventions for GitHub Copilot in this project.
- `DEVELOPER_SETUP.md` – Step-by-step project setup instructions.
- `FAQ.md` – Frequently asked questions for contributors.
- `gradle.properties` – Shared Gradle settings.
- `gradlew` & `gradlew.bat` – Wrapper scripts for invoking Gradle.
- `LICENSE.md` – Licensing terms for the project.
- `NOTICE.md` – Repository notice describing the current project license and release notice artifacts.
- `NOTICE.template.md` – Template used to generate the release-specific NOTICE file distributed with official releases.
- `README.md` – High-level project overview and quick-start.
- `SECURITY.md` – Responsible disclosure and security reporting guidance.

## Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Microservices Overview](./microservices/README.md)

## Local workspace examples

These paths are commonly present in developer workspaces but are ignored by source control and are not part of the canonical repository layout:

- `.gradle/` – Local Gradle cache.
- `build/` – Generated Gradle outputs.
- `node_modules/` – Installed JavaScript dependencies.
