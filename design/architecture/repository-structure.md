# Repository Structure

This repository uses a hierarchical Gradle layout. All microservices and the shared `common-library` live under a top-level `services/` folder to keep the root tidy.  The React UI resides in `web-client/` and various infrastructure manifests sit under `k8s/`.

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
├── .github/
├── build.gradle.kts
└── docker-compose.yml
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
- `build.gradle.kts` – Root Gradle build file that aggregates all modules.
- `docker-compose.yml` – Local development environment.

Proto definitions live under `protos/` organized by service and version as described in the gRPC design document. Database migration scripts for each service reside in `src/main/resources/db/migration/`.

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Microservices Overview](./microservices/README.md)
