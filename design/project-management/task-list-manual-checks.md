# Manual Tooling Verification Checklist

This checklist covers optional manual steps to verify that development tooling and helper scripts work as expected.

## 🛠 Environment & Pre-commit Hooks

- [ ] Install required command line tools
  - [ ] `Java 21+`
  - [ ] `Node.js` (latest LTS)
  - [ ] `Docker` and `Docker Compose`
  - [ ] Verify Docker Compose with `docker compose version`
  - [ ] `buf` for proto linting
  - [ ] `pre-commit` Python package
  - [ ] `hadolint` for Dockerfile linting
  - [ ] `shellcheck` for shell script validation
  - [ ] Verify Gradle wrapper with `./gradlew --version`
- [ ] Set up git hooks
  - [ ] `pip install pre-commit`
  - [ ] `pre-commit install`
  - [ ] `git config core.hooksPath config/git-hooks`
  - [ ] `pre-commit run --all-files`
  - [ ] Copy `.env.sample` to `.env` and adjust values as needed

## ✅ Build & Test Commands

- [ ] Run `./gradlew check` to execute unit tests, Spotless, Checkstyle and SpotBugs
- [ ] Run `./gradlew build` to compile all services
- [ ] Build container images with `./gradlew buildDockerImages`
- [ ] Build the base container image with `./gradlew buildBaseImage`
- [ ] Start the local stack with `./gradlew devUp`
  - [ ] Verify services respond to `curl -fsSL http://localhost:8080/ping`
- [ ] Stop services with `./gradlew devDown`

## 📦 Node & OpenAPI Tasks

- [ ] `npm ci` inside `web-client`
- [ ] `npm run lint`
- [ ] `npm run format -- -c`
- [ ] `npm run format:fix`
- [ ] `npm run openapi:lint`
- [ ] `npm run accessibility` (requires Google Chrome)
- [ ] `npm run build` to generate the production bundle
- [ ] `npm run preview` to verify the production bundle
- [ ] `npm run dev` to start the Vite development server
- [ ] `npm run test` to execute frontend unit tests

## 📜 Protobuf & Documentation Scripts

- [ ] `buf lint` for proto consistency
- [ ] `buf breaking --against origin/main` to check for API changes
- [ ] `./gradlew generateProto` to regenerate Java stubs
- [ ] `./dev-tools/generate-grpc-docs.sh` to update gRPC docs
- [ ] `./dev-tools/generate-erd.sh` to produce ERD diagrams
- [ ] `./dev-tools/link-check.sh` to validate links in docs
- [ ] `./gradlew lintMarkdown` to check Markdown formatting
- [ ] `./gradlew lintMarkdownFix` to auto-fix Markdown
- [ ] `hadolint` on all Dockerfiles
- [ ] `shellcheck` on scripts under `dev-tools/`

## 📂 Database & Backup Utilities

- [ ] `./dev-tools/backup-db.sh` to create a snapshot
- [ ] `./dev-tools/restore-db.sh backups/<file>` to restore a backup
- [ ] `./dev-tools/verify-backups.sh` to confirm scheduled dumps exist
- [ ] Rotate local dumps with `./dev-tools/pg-dump-rotate.sh`
- [ ] `./dev-tools/setup-local-backup.sh` to configure local backup tooling
- [ ] `./dev-tools/restore-latest-db.sh` to restore the newest dump
- [ ] `./dev-tools/restore-redis-aof.sh <file>` to restore Redis state
- [ ] `./dev-tools/restore-cluster.sh <backup-name>` for full cluster recovery

## ⚙️ Miscellaneous Helpers

- [ ] `./dev-tools/firemud-cli.sh up|down|ping`
- [ ] Generate TLS certificates with `./dev-tools/generate-dev-certs.sh`
- [ ] Generate TLS certificates via Gradle with `./gradlew generateDevCerts`
- [ ] Run cross-service tests via `./gradlew crossServiceTest` when needed
- [ ] Execute load tests via `./gradlew :load-testing:gatlingRun`

## 🌱 Data Seeding & API Clients

- [ ] `./dev-tools/seed-test-data.sh` to populate sample game data
- [ ] `./dev-tools/seed-automation-scripting-data.sh` to add scripting examples
- [ ] Import `dev-tools/insomnia/firemud-insomnia.json` in Insomnia for REST API calls
- [ ] Open `dev-tools/kreya/.kreya-project.yaml` in Kreya for gRPC testing

## 🔒 Security & Scanning

- [ ] `trivy fs --config .trivy.yaml .` to scan dependencies and Dockerfiles

## ☸ Kubernetes & Helm

- [ ] `helm dependency update k8s/helm/firemud` to pull chart dependencies
- [ ] `helm lint k8s/helm/firemud` to validate charts
- [ ] `helm upgrade --install firemud k8s/helm/firemud -f k8s/helm/values-dev.yaml`
- [ ] `helm install game-session k8s/helm/game-session-service -f k8s/helm/values-local.yaml`
- [ ] `kubectl get pods -n firemud` to verify running services

## 🌍 Terraform Modules

- [ ] `terraform init` in `k8s/terraform-production`
- [ ] `terraform plan` to preview infrastructure changes
- [ ] `terraform fmt -check` to ensure formatting
- [ ] `terraform validate` to check module syntax

## 🔥 Service Smoke Tests

- [ ] Run `services/account-service/smoke-test.sh`
- [ ] Run `services/automation-scripting-service/smoke-test.sh`
- [ ] Run `services/game-design-service/smoke-test.sh`
- [ ] Run `services/game-logic-service/smoke-test.sh`
- [ ] Run `services/social-groups-service/smoke-test.sh`
- [ ] Run `services/tcp-proxy-service/smoke-test.sh`
- [ ] Run `services/world-management-service/smoke-test.sh`
