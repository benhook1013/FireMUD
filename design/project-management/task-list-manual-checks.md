# Manual Tooling Verification Checklist

This checklist covers optional manual steps to verify that development tooling and helper scripts work as expected.

## 🛠 Environment & Pre-commit Hooks

- [ ] Install required command line tools
  - [ ] `Java 21+`
  - [ ] `Node.js` (latest LTS)
  - [ ] `Docker` and `Docker Compose`
  - [ ] `buf` for proto linting
  - [ ] `pre-commit` Python package
- [ ] Set up git hooks
  - [ ] `pip install pre-commit`
  - [ ] `pre-commit install`
  - [ ] `pre-commit run --all-files`

## ✅ Build & Test Commands

- [ ] Run `./gradlew check` to execute unit tests, Spotless, Checkstyle and SpotBugs
- [ ] Build container images with `./gradlew buildDockerImages`
- [ ] Start the local stack with `./gradlew devUp`
  - [ ] Verify services respond to `curl -fsSL http://localhost:8080/ping`
- [ ] Stop services with `./gradlew devDown`

## 📦 Node & OpenAPI Tasks

- [ ] `npm ci` inside `web-client`
- [ ] `npm run lint`
- [ ] `npm run format -- -c`
- [ ] `npm run openapi:lint`

## 📜 Protobuf & Documentation Scripts

- [ ] `buf lint` for proto consistency
- [ ] `./dev-tools/generate-grpc-docs.sh` to update gRPC docs
- [ ] `./dev-tools/generate-erd.sh` to produce ERD diagrams
- [ ] `./dev-tools/link-check.sh` to validate links in docs

## 📂 Database & Backup Utilities

- [ ] `./dev-tools/backup-db.sh` to create a snapshot
- [ ] `./dev-tools/restore-db.sh backups/<file>` to restore a backup
- [ ] `./dev-tools/verify-backups.sh` to confirm scheduled dumps exist
- [ ] Rotate local dumps with `./dev-tools/pg-dump-rotate.sh`

## ⚙️ Miscellaneous Helpers

- [ ] `./dev-tools/firemud-cli.sh up|down|ping`
- [ ] Generate TLS certificates with `./dev-tools/generate-dev-certs.sh`
- [ ] Run cross-service tests via `./gradlew crossServiceTest` when needed
- [ ] Execute load tests via `./gradlew :load-testing:gatlingRun`

