# Repository Structure

This repository uses a hierarchical Gradle layout. All microservices and the shared
`common-library` live under a top-level `services/` folder to keep the root tidy. The
React UI resides in `web-client/` and various infrastructure manifests sit under
`k8s/`.  Additional build tooling and documentation live at the root.

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
├── gradle/
├── .github/
├── buf.gen.yaml
├── buf.work.yaml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── package.json
├── package-lock.json
├── docker-compose.yml
└── docker-compose.override.yml
```

## Directory summary

- `services/` – All Spring Boot microservices and the shared `common-library` Gradle module.
- `protos/` – Versioned gRPC definitions for every service.
- `web-client/` – React web application.
- `design/` – Architecture, operations, and user guide documentation.
- `config/` – Checkstyle, git hooks, and SpotBugs configs.
- `dev-tools/` – Utility scripts and API client configurations.
- `docker/` – Base Dockerfiles used by the build process.
- `k8s/` – Helm charts, network policies, and Terraform modules.
- `.github/` – GitHub Actions workflows and issue templates.
- `gradle/` – Gradle wrapper binaries.
- `buf.gen.yaml` and `buf.work.yaml` – Buf configuration for protobuf linting and code generation.
- `build.gradle.kts` – Root Gradle build file that aggregates all modules.
- `settings.gradle.kts` – Declares Gradle subprojects.
- `gradle.properties` – Shared Gradle settings.
- `package.json` & `package-lock.json` – Node scripts used for markdown linting and docs.
- `docker-compose.yml` – Local development environment.
- `docker-compose.override.yml` – Extra services for local testing.

Proto definitions live under `protos/` organized by service and version as described in the gRPC design document. Database migration scripts for each service reside in `src/main/resources/db/migration/`.
