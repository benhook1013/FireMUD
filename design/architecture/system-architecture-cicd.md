# FireMUD System Architecture: CI/CD Pipeline

This document describes the continuous integration strategy for FireMUD using **GitHub Actions**. Every service is built, tested, containerized, and images are pushed to the registry. Deployment to Kubernetes runs through separate workflows so both local and cloud-hosted clusters use the same pipeline but use different manifests per environment.

---

## Goals

- **Automate builds and tests** for all microservices whenever code changes are pushed by running the [`ci.yml`](../../.github/workflows/ci.yml) workflow.
- **Build Docker images** and push them to GitHub Container Registry (GHCR).
- **Deploy to Kubernetes development/demo clusters** by triggering the [`manual-helm-deploy.yml`](../../.github/workflows/manual-helm-deploy.yml) workflow, which applies the Helm charts under [`k8s/helm`](../../k8s/helm) using `values-local.yaml` by default. Staging and production clusters use Kustomize overlays as described in the Deployment Runbook and are applied via `kubectl` from a secure admin environment rather than directly from CI.
- Treat `dev-demo-cluster` as validation-only infrastructure that is excluded from production promotion evidence.
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

- Generates protobuf outputs to keep generated stubs aligned with the checked-in schemas.
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
- [`manual-backup-restore.yml`](../../.github/workflows/manual-backup-restore.yml) verifies recent backups and can run a recovery drill on demand for explicit throwaway targets only. When a drill is used as restore proof, it must produce the canonical recovery record defined in `system-architecture-backup-recovery.md`; it must not mutate live staging or production namespaces.
- [`validate-secret-compliance.yml`](../../.github/workflows/validate-secret-compliance.yml) validates secret-compliance records and enforces hard gates for production (and staging after the cutover date).

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

Deployable artifact lineage rule:

- The digest first produced for a reviewed source commit is the canonical deployable artifact for that commit.
- Promotion between staging and production must reuse that exact digest; release tags, branch tags, or `latest` tags may be added later as aliases, but they must not point at a rebuilt image if the image is intended to remain promotable.
- Any workflow that rebuilds from a release tag or branch tip produces a new artifact lineage. Those rebuilt digests are non-promotable until they independently pass staging and produce new deployment evidence plus a new production attestation chain.

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

FireMUD's preview workflow is reserved for real reviewer-accessible PR environments, not CI-only stack boot validation. The [`.github/workflows/preview.yml`](../../.github/workflows/preview.yml) workflow targets a hosted single-node k3s cluster and follows this contract:

- Build and push PR-tagged container images to private GHCR.
- Deploy or upgrade Helm release `pr-<PR_NUMBER>` into namespace `pr-<PR_NUMBER>`.
- Expose the environment at `https://pr-<PR_NUMBER>.preview.<DOMAIN>` using cluster ingress/TLS.
- Seed preview state once on first namespace creation and preserve mutable preview state for the lifetime of the PR.
- Tear the preview down when the PR closes or merges.

Main CI remains responsible for stack startup, smoke, and cross-service verification. Preview deployment is intentionally a separate concern focused on reviewer-accessible environments.

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
- When a release is ready, `release-please` opens a release PR against `main`; after that PR is merged, the release tag (for example `v1.2.3`) is created. Release-tag workflows may publish metadata and additional tags for the already-validated digest set, but must not rebuild the promotable production artifacts.
- Production promotion is performed by updating the production Kustomize overlay (for example `k8s/overlays/prod`) to image digests that have already passed staging validation, via a Git change, merging it, and applying it from a secure operator environment using `kubectl apply -k k8s/overlays/prod` following the deployment runbook.
- Overlay PRs are validated by [`.github/workflows/validate-kustomize-overlays.yml`](../../.github/workflows/validate-kustomize-overlays.yml), which checks that referenced images exist in GHCR, enforces digest pinning for staging/production overlays, and blocks staging backup schedules unless the explicit marker `k8s/overlays/stage/STAGING_BACKUPS_ENABLED` is present.
- Overlay PRs run the canonical preflight entrypoint (`dev-tools/deploy/preflight.sh`) in `ci-static` context so policy IDs and report shape match operator pre-apply validation. CI-static mode may mark production attestation policy as `not_applicable` when no production promotion is being executed.
- Production overlay PRs must include exactly one in-repo staging promotion attestation under `design/operations/deployments/production/attestations/<deployment-ref>.json` that follows `system-architecture-promotion-attestation.md`. Production PRs are rejected if they reference digests that cannot be tied to that attestation, a successful staging deployment record with live-state verification, and a staging deployment record whose `secretComplianceStatus` is `pass`.
- Production overlay PR CI must evaluate the attestation and, when the attestation classifies the release as `roll-forward-only`, the matching backup-readiness evidence before merge. Deferring those checks to operator-only preflight is non-compliant.
- Staging apply evidence must include the in-repo deployment record `design/operations/deployments/staging/deployments/<stagingOverlayCommitSha>.json`; production promotion validation fails when this record is missing, digest-mismatched, lacks live-state verification, or lacks passing secret-compliance evidence.
- Player-facing preflight and operator validation must also verify environment bootstrap completeness and external integration isolation before apply; these checks are not limited to restore events.
- `manual-backup-restore.yml` is limited to throwaway recovery drills. It may target only explicit non-player-facing restore namespaces or isolated drill clusters using dedicated low-privilege credentials and GitHub Environment approvals. It must not restore into live staging or production namespaces and must not hold credentials capable of modifying the currently player-facing cluster boundary.

Rollbacks are handled by resuming a previously known-good image digest set and re-applying the staging or production manifests with those digests. See the Deployment Runbook for the step-by-step operator flow.
Before approving a production promotion, deployment evidence must classify rollback mode as either:

- `rollback-compatible` (previous digest set remains safe to re-apply against the current database schema, secret/config contract, mounted file-path contract, and external-binding contract), or
- `roll-forward-only` (schema, secret/config, file-path, or external-binding change requires forward remediation and/or restore-point recovery rather than old-binary rollback).

Production promotions lacking this explicit rollback-mode classification are non-compliant.

For production releases classified as `roll-forward-only`, promotion evidence must also include fresh backup-readiness evidence proving:

- a recent successful logical backup,
- recent backup-verification success, and
- a current restore-plan or restore-drill reference suitable for the release.

The canonical evidence path is `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`, and production CI/preflight must reject `roll-forward-only` promotions when that evidence is missing, stale, or not bound to the attestation/digest set being promoted.
Traffic-open readiness for production first-live or reopen events uses the same `design/operations/deployments/production/backup-readiness/` artifact family with the specialized naming pattern `first-live-<deployment-ref>.json` defined in `system-architecture-backup-recovery.md`; this is distinct by purpose, not by schema lineage.
For player-facing production promotions, the referenced coordinated-backup evidence must use canonical `tenant_id + region_id` scope. A release that depends on alias-scoped coordinated backup evidence is not eligible for `roll-forward-only` production promotion.
Current implementation note: because `system-architecture-backup-recovery.md` still marks player-facing coordinated-backup readiness as incomplete until canonical `tenant_id + region_id` pause scope is enforced end-to-end, player-facing production `roll-forward-only` promotion is currently non-compliant in practice. CI/preflight must reject such promotions until that implementation note is removed.

Pre-apply policy checks for staging and production must run through the canonical preflight contract in `system-architecture-deploy-preflight-policy.md`. Static checks run in overlay PR CI, and resolved-manifest/runtime checks run in operator preflight execution. Both use the same policy IDs and evidence shape.

## Canonical Deployment Evidence Lifecycle

FireMUD uses one deployment-evidence chain per deployment event so promotion, rollback, and incident review all answer from the same record set:

1. Preflight produces `design/operations/deployments/<environment>/preflight/<deployment-ref>.json`.
2. Secret-compliance validation produces or references `design/operations/secret-compliance/<environment>.yaml` plus immutable supporting evidence.
3. Operator apply produces or updates the environment deployment record:
   - staging: `design/operations/deployments/staging/deployments/<overlayCommitSha>.json`
   - production: `design/operations/deployments/production/deployments/<overlayCommitSha>.json`
   - hobby-self-hosted: `design/operations/deployments/hobby-self-hosted/deployments/<deployment-ref>.json`
4. Production promotion references exactly one staging attestation at `design/operations/deployments/production/attestations/<deployment-ref>.json`.
5. If the release is `roll-forward-only`, production also references `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`.

Lifecycle rules:

- The deployment record is the canonical answer to “what is currently deployed and promotable for this environment.”
- Preflight artifacts, secret-compliance snapshots, smoke evidence, and live-state verification are supporting evidence linked from the deployment record rather than parallel sources of truth.
- Re-applying the same overlay commit does not create a second competing promotion record; operators update the same deployment record with a new apply event timestamp, new live-state evidence, and the outcome of the latest smoke checks.
- A promotion attestation is valid only if its referenced staging deployment record remains the latest successful apply record for that staging overlay commit.
- Rollback uses the deployment record and original attestation lineage for the digest set being restored.

Terminology note:

- `deployment evidence` answers what was checked, applied, and verified for a concrete deployment event in one environment.
- `promotion evidence` is the subset of evidence used to prove a staging deployment is eligible to be promoted into production, primarily the attestation plus its referenced deployment and compliance records.
- `traffic-open evidence` is the evidence family used to prove an environment may be opened or reopened to player traffic, for example the production traffic-open backup-readiness artifact or hobby traffic-open records.
- `promotion candidate` means a staging deployment record that is eligible to produce production promotion evidence; quarantined or detached staging drills can remain valid deployment evidence without becoming promotion candidates.
- `deployment-ref` is the canonical environment-agnostic identity for one deployment event and is used by preflight, attestation, and backup-readiness artifacts. For Git-managed staging/production overlays, the canonical `deployment-ref` is the same Git SHA recorded as `overlayCommitSha`; the docs use `overlayCommitSha` only where the Git-derived source of the deployment-ref matters.

Illustrative deployment record shape:

```json
{
  "environment": "staging",
  "overlayCommitSha": "<git-sha>",
  "appliedAt": "2026-03-13T10:15:00Z",
  "appliedBy": "operator@example",
  "deployStatus": "pass",
  "smokeStatus": "pass",
  "serviceDigests": {
    "spring-cloud-gateway": "ghcr.io/example/spring-cloud-gateway@sha256:...",
    "game-session-service": "ghcr.io/example/game-session-service@sha256:..."
  },
  "preflightReportPath": "design/operations/deployments/staging/preflight/<git-sha>.json",
  "liveStateEvidence": [
    "namespace annotation firemud.io/overlay-sha=<git-sha>",
    "rollout digests matched reviewed overlay"
  ],
  "secretComplianceStatus": "pass",
  "secretComplianceEvidenceRef": "design/operations/secret-compliance/staging.yaml",
  "smokeEvidence": [
    "design/operations/deployments/staging/smoke/<git-sha>.json"
  ]
}
```

Exact field requirements for promotion remain the canonical contract defined in this section and in `system-architecture-promotion-attestation.md`; the example exists only to make producer implementations consistent.

## Secret Compliance Gate

Promotion and DR-readiness reporting depend on environment secret-compliance records described in `infrastructure/environment-and-secrets-overview.md`.

- `production`: missing or stale secret-compliance records are a hard CI gate for promotion.
- `staging`: missing or stale records emit warnings until **June 30, 2026**, and become a hard CI gate for staging promotions on **July 1, 2026**.
  This gate applies to staging deployment/promotion evidence, especially any deployment intended to produce production-promotion attestation. Detached or quarantined staging drills remain operational exercises, not promotion candidates.
- `hobby-self-hosted`: operator tooling should validate records before opening traffic, but GitHub CI gating may be unavailable.

Promotion-evidence rule:

- Any staging deployment record used by production attestation must carry `secretComplianceStatus=pass` and a `secretComplianceEvidenceRef` even during the staging warning-only period.

Enforcement workflow contract:

- CI check name: `validate-secret-compliance`.
- Expected implementation location: `.github/workflows/validate-secret-compliance.yml`.
- Required validator behavior:
  - Load `design/operations/secret-compliance/<environment>.yaml`.
  - Fail when required records/classes are missing.
  - Fail when a record lacks both `lastRotationAt` and `lastProvisionedAt`, or sets both.
  - Fail when credential age exceeds configured maximum age for hard-gated environments, regardless of whether age is measured from bootstrap provisioning or later rotation.
  - Fail when `evidenceRef`/`evidenceKey` is missing, the referenced evidence artifact is missing, or the evidence record lacks an immutable artifact identifier (`immutableArtifactId` with digest-qualified identity).
- Required record classes:
  - `jwt-signing-keys-jwks`
  - `postgres-application-credentials`
  - `backup-object-store-credentials`
  - `operator-credentials`

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
