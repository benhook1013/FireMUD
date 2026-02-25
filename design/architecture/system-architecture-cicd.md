# FireMUD System Architecture: CI/CD Pipeline

This document describes the continuous integration strategy for FireMUD using **GitHub Actions**. Every service is built, tested, containerized, and images are pushed to the registry. Deployment to Kubernetes runs through separate workflows so both local and cloud-hosted clusters use the same pipeline but use different manifests per environment.

---

## Goals

- **Automate builds and tests** for all microservices whenever code changes are pushed by running the [`ci.yml`](../../.github/workflows/ci.yml) workflow.
- **Build Docker images** and push them to GitHub Container Registry (GHCR).
- **Deploy to Kubernetes development/demo clusters** by triggering the [`manual-helm-deploy.yml`](../../.github/workflows/manual-helm-deploy.yml) workflow, which applies the Helm charts under [`k8s/helm`](../../k8s/helm) using `values-local.yaml` by default. Staging and production clusters use Kustomize overlays as described in the Deployment Runbook and are applied via `kubectl` from a secure admin environment rather than directly from CI.
- Keep the workflow configuration easy to maintain and extensible for additional security scans or nightly jobs.
- **Generate release notes automatically** whenever version tags are pushed.
- **Perform code scanning** with CodeQL and open source **license checks** on every pull request.
- **Scan for vulnerabilities** with Trivy during CI runs and scheduled security scans.
- **Publish documentation** to GitHub Pages after successful builds.
- **Create release PRs automatically** using the `release-please` workflow.
- **Generate database ERD diagrams** as build artifacts after each run. The [`dev-tools/docs/generate-erd.sh`](../../dev-tools/docs/generate-erd.sh) script writes them to `design/erd/`, and the workflow uploads this directory as artifacts.
- **Cancel previous runs for the same branch** using a concurrency group so CI resources are conserved and deployment jobs do not race each other for the same environment.

---

## Workflow Structure

Workflows live in the `.github/workflows/` directory. A typical pipeline runs on every pull request and push to the main branch.

Branch-to-environment promotion contract:

| Source branch/event | Primary purpose | Deployment target |
| --- | --- | --- |
| Pull requests (`develop`, `main`, `release/**`) | Validation, preview checks, and review evidence | No direct staging/production apply |
| `develop` merges | Integration and staging candidate flow | Staging overlay updates (operator apply after policy gates) |
| Release PR merged to `main` + release tag (for example `v1.2.3`) | Production release candidate artifacting | Production overlay updates (operator apply after attestation + policy gates) |

The workflow YAML files remain the source of truth for concrete triggers; this table is the architecture contract for promotion intent.

The main [`ci.yml`](../../.github/workflows/ci.yml) workflow:

- Performs a **Buf breaking change check** to keep protobuf APIs compatible.
- Runs formatting and lint steps (Spotless, markdownlint, link checks).
- Executes a matrix of Gradle `check` tasks (one per microservice) with SpotBugs, Checkstyle, and tests enabled.
- Generates coverage with JaCoCo and runs Trivy scans over the workspace.
- Uses Node 20 to lint OpenAPI specs, run React linters, and execute an accessibility audit using headless Chrome.
- Invokes a dedicated `generate-erd` job that runs [`dev-tools/docs/generate-erd.sh`](../../dev-tools/docs/generate-erd.sh) to build ERD diagrams from service migrations and upload them as artifacts.
- Caches Buf modules, Node dependencies, Trivy database, and Gradle artifacts to speed up repeat workflow runs.
- Runs docs and link linting in `ci.yml` and verifies links again in the `docs.yml` workflow before publishing to GitHub Pages.
- Posts a summary comment on pull requests with test status and coverage.

A separate `docker-images.yml` workflow builds and publishes Docker images for all services using Docker Buildx and the `docker/build-push-action`:

```yaml
name: CI — Build and Security
on:
  push:
    branches: [ main ]
  pull_request:
  workflow_dispatch:
  schedule:
    - cron: '0 3 * * *'  # Daily at 3am UTC

defaults:
  run:
    shell: bash

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - name: Check Formatting
        run: ./gradlew spotlessCheck
      - name: Lint Docs and Links
        run: ./gradlew lintMarkdown linkCheck
      - name: Run Checks
        run: ./gradlew check
```

The example above checks out the repository, sets up Java 21, and runs a Gradle build. Each microservice can be built in a matrix strategy so jobs run in parallel. All CI jobs share a concurrency group so new pushes cancel any running workflow for the same branch. The workflow also executes nightly at **3 AM UTC** via the `schedule` trigger so dependencies are scanned regularly.

Other workflows support additional automation:

- [`docs.yml`](../../.github/workflows/docs.yml) uses the **lychee** link checker (configured via `.lycheeignore`) before publishing the `design/` folder to GitHub Pages.
- [`codeql.yml`](../../.github/workflows/codeql.yml) performs static code analysis on each pull request and push to `main`.
- [`license-scan.yml`](../../.github/workflows/license-scan.yml) checks open source dependencies for license compliance.
- [`release-notes.yml`](../../.github/workflows/release-notes.yml) creates GitHub releases with autogenerated notes whenever a version tag is pushed.
- [`release-please.yml`](../../.github/workflows/release-please.yml) creates release pull requests from the `develop` branch.
- [`manual-backup-restore.yml`](../../.github/workflows/manual-backup-restore.yml) verifies recent backups and can run a restore test on demand.

---

## Building and Pushing Images

After tests pass, each service is packaged into a Docker image:

```yaml
  docker-build:
    needs: build-and-test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3
      - name: Login to Registry
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Build and Push
        uses: docker/build-push-action@v6
        with:
          context: ./services/${{ matrix.service }}
          push: true
          tags: |
            ghcr.io/benhook1013/${{ matrix.service }}:${{ github.sha }}
            ghcr.io/benhook1013/${{ matrix.service }}:latest
            ghcr.io/benhook1013/${{ matrix.service }}:${{ github.ref_name }}
```

Images are tagged with the commit SHA and pushed to **GitHub Container Registry (GHCR)**.

### Base Docker Image

The firemud-base image provides a consistent OS and JVM setup across all service containers. It is built using the `buildBaseImage` Gradle task and referenced in each microservice Dockerfile as `ghcr.io/benhook1013/firemud-base:latest`.

---

## Deploying to Kubernetes

Kubernetes rollouts for local and development clusters are triggered through the [`manual-helm-deploy.yml`](../../.github/workflows/manual-helm-deploy.yml) workflow. The job runs `helm upgrade` using the charts in [`k8s/helm`](../../k8s/helm) and `values-local.yaml` by default. Cluster credentials and registry secrets must be configured beforehand. The example below mirrors the deployment steps. Staging and production deployments rely on environment-specific overlays (for example `k8s/overlays/stage` and `k8s/overlays/prod`) applied according to the Deployment Runbook by operators using `kubectl` from a secured workstation or bastion host.

```yaml
name: Manual Helm Deploy

on:
  workflow_dispatch:

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - name: Set up kubectl
        uses: azure/setup-kubectl@v4
      - name: Set up Helm
        uses: azure/setup-helm@v4
      - name: Deploy with Helm
        run: |
          helm upgrade --install firemud ./k8s/helm/firemud \
            -f k8s/helm/values-local.yaml
```

### Rollback Strategy

Staging and production rollouts use standard Kubernetes `RollingUpdate` behavior and are rolled back by re-applying the environment’s Kustomize overlay with a previously known-good image digest set. FireMUD does not rely on automated canary/auto-rollback infrastructure by default; operators treat rollback as an explicit, auditable action that restores the last known-good digest set and verifies post-deploy health checks.

---

## Automated Release Notes

When a version tag like `v1.2.3` is pushed, the `release-notes.yml` workflow
creates a GitHub release and uses the `generate_release_notes` option to produce
change logs automatically. This keeps release documentation consistent without
manual steps.

---

## PR Preview Environments

Pull requests targeting `develop`, `main`, and `release/**` spin up a short-lived Docker Compose stack so reviewers can test changes interactively. The [`.github/workflows/preview.yml`](../../.github/workflows/preview.yml) workflow uses Docker Buildx to build service images with cached layers, copies `.env.sample` to `.env`, generates development certificates, and launches the stack via Docker Compose.
A status comment is posted once the gateway passes its health check, and the
runner tears the stack down at the end of the job so the preview is removed
automatically.

---

## Related Documentation

- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Developer Tools & Scripting](./system-architecture-scripting.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Testing Strategy](./system-architecture-testing.md)
- [User Journeys – Testing & Continuous Delivery](./user-journeys-operators.md#3-testing--continuous-delivery)

---

## Promotion & Rollback Model

FireMUD uses a simple promotion flow from pull requests through staging to production:

- Feature branches are merged into `develop` after passing CI.
- Staging promotion is performed by updating the staging Kustomize overlay (for example `k8s/overlays/stage`) to the desired image digests (`image@sha256:...`) via a Git change, merging it, and applying it from a secure operator environment using `kubectl apply -k k8s/overlays/stage`. This keeps “what was deployed” traceable in Git history and removes mutable-tag drift.
- When a release is ready, `release-please` opens a release PR against `main`; after that PR is merged, the release tag (for example `v1.2.3`) is created and images for that tag are built/pushed by the Docker image workflow.
- Production promotion is performed by updating the production Kustomize overlay (for example `k8s/overlays/prod`) to image digests that have already passed staging validation, via a Git change, merging it, and applying it from a secure operator environment using `kubectl apply -k k8s/overlays/prod` following the deployment runbook.
- Overlay PRs are validated by [`.github/workflows/validate-kustomize-overlays.yml`](../../.github/workflows/validate-kustomize-overlays.yml), which checks that referenced images exist in GHCR, enforces digest pinning for staging/production overlays, and blocks staging backup schedules unless the explicit marker `k8s/overlays/stage/STAGING_BACKUPS_ENABLED` is present.
- Production overlay PRs must include a staging promotion attestation that follows `system-architecture-promotion-attestation.md`. Production PRs are rejected if they reference digests that cannot be tied to a valid attestation artifact.
- Production overlay PRs must include an in-repo attestation artifact so CI can validate promotion evidence deterministically.

Rollbacks are handled by resuming a previously known-good image digest set and re-applying the staging or production manifests with those digests. See the Deployment Runbook for the step-by-step operator flow.

Pre-apply policy checks for staging and production must run through the canonical preflight contract in `system-architecture-deploy-preflight-policy.md`. Static checks run in overlay PR CI, and resolved-manifest/runtime checks run in operator preflight execution. Both use the same policy IDs and evidence shape.

## Secret Compliance Gate

Promotion and DR-readiness reporting depend on environment secret-compliance records described in `infrastructure/environment-and-secrets-overview.md`.

- `production`: missing or stale secret-compliance records are a hard CI gate for promotion.
- `staging`: missing or stale records emit warnings until **June 30, 2026**, and become a hard CI gate for staging promotions on **July 1, 2026**.
- `hobby-self-hosted`: operator tooling should validate records before opening traffic, but GitHub CI gating may be unavailable.

Enforcement workflow contract:

- CI check name: `validate-secret-compliance`.
- Expected implementation location: `.github/workflows/validate-secret-compliance.yml`.
- Required validator behavior:
  - Load `design/operations/secret-compliance/<environment>.yaml`.
  - Fail when required records/classes are missing.
  - Fail when credential age exceeds configured maximum age for hard-gated environments.

---

## Deployment Credentials & Environments

CI workflows and operators use distinct credentials for each Kubernetes environment:

- **Development clusters**
  - May use a kubeconfig or token that is available to a wider set of workflows (for example `manual-helm-deploy.yml` and preview environments).
  - Intended for non-player-facing stacks where rapid iteration is more important than strict change control.
- **Staging cluster**
  - Uses credentials limited to operator `kubectl` access and, if introduced later, dedicated staging deployment workflows.
  - Credentials are not exposed to pull request workflows; only merges to `develop` and explicit operator actions (or approved staging workflows) can update the staging cluster.
- **Production cluster**
  - Uses credentials restricted to production deployment paths and operator `kubectl` access from approved workstations or bastion hosts.
  - No GitHub Actions workflow currently applies production manifests directly; any future workflow that does so must use GitHub Environments and require manual approvals.

Registry credentials (for GHCR) are shared across environments but access to pull images into each cluster is controlled by Kubernetes secrets and RBAC within that environment.
