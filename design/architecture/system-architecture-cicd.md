# FireMUD System Architecture: CI/CD Pipeline

This document describes the continuous integration strategy for FireMUD using **GitHub Actions**. Every service is built, tested, containerized, and images are pushed to the registry. Deployment to Kubernetes runs through separate workflows so both local and cloud-hosted clusters use the same pipeline but use different manifests per environment.

---

## Goals

- **Automate builds and tests** for all microservices whenever code changes are pushed by running the [`ci.yml`](../../.github/workflows/ci.yml) workflow.
- **Build Docker images** without registry credentials for pull-request smoke, then publish only successful PR artifacts through a trusted workflow; trusted branch workflows push directly to GitHub Container Registry (GHCR).
- **Deploy hosted Kubernetes development/demo environments** through the dedicated preview and dev-demo workflows. [`preview.yml`](../../.github/workflows/preview.yml) manages per-PR hosted preview releases, and [`dev-demo.yml`](../../.github/workflows/dev-demo.yml) manages the fixed `develop` dev-demo environment. Staging and production clusters use Kustomize overlays as described in the Deployment Runbook and are applied via `kubectl` from a secure admin environment rather than directly from CI.
- Treat `dev-demo-cluster` as validation-only infrastructure that is excluded from production promotion evidence.
- Keep the workflow configuration easy to maintain and extensible for additional security scans or nightly jobs.
- **Generate release notes automatically** whenever version tags are pushed.
- **Perform deep static code scanning** with CodeQL on pull requests targeting `develop` and `main`, require the dedicated CodeQL gate for both protected bases, and continue running the full analysis on `main` pushes, scheduled runs, and manual dispatches.
- **Run AI-assisted pull request review** with CodeRabbit on `develop` and `main` pull requests using repository-local review guidance plus inherited organization defaults.
- **Benchmark repository hardening** with OSSF Scorecard on `develop` and `main` pushes plus the weekly scorecard schedule.
- **Scan for vulnerabilities** with Trivy during CI runs and scheduled security scans.
- **Publish coverage feedback** to Codecov from the service validation matrix so pull requests receive patch-coverage status and coverage comments.
- **Run OWASP ZAP baseline scans** against the built web client preview during CI.
- **Publish documentation** to GitHub Pages after successful builds.
- **Create release PRs automatically** using the `release-please` workflow.
- **Propose dependency updates automatically** with Renovate against the `develop` branch across the supported dependency managers.
- **Generate database ERD diagrams** as build artifacts after each run. The [`dev-tools/docs/generate-erd.sh`](../../dev-tools/docs/generate-erd.sh) script writes them to `design/erd/`, and the workflow uploads this directory as artifacts.
- **Cancel previous runs for the same branch** using a concurrency group so CI resources are conserved and deployment jobs do not race each other for the same environment.

## Implementation Status

The current executable unconditionally blocks every player-facing production promotion class, including `rollback-compatible`, until all required production evidence and validations are complete; incomplete evidence can never become promotion authority. Static CI still validates the checked-in evidence shape and available bindings, but production preflight does not yet execute the staging-lineage, expanded backup-readiness, nested candidate recovery-controller, `PREFLIGHT-JWT-002`, or `PREFLIGHT-JWT-ROTATION-001` validations behind that block. No rollback classification becomes current promotion authority until those diagnostics, recovery inventory membership, immutable evidence dereferencing, participant, confidentiality, hardening, JWT/JWKS, and controlled-reopen validations are implemented.

---

## Workflow Structure

Workflows live in the `.github/workflows/` directory. A typical pipeline runs on every pull request and on pushes to the long-lived publication branches (`develop` today, and `main` for release-oriented publication paths).

### Pull Request Runtime And Preview Chain

| Workflow | Execution context | Responsibility | What its GitHub run means |
| --- | --- | --- | --- |
| [`runtime-images.yml`](../../.github/workflows/runtime-images.yml) (`Build Runtime Images`) | Untrusted PR merge commit with read-only repository permissions | Build the PR merge commit locally, run the one full-stack smoke proof, and upload the successful image artifact under a fixed PR head-SHA tag | The tested merge of the PR head with its recorded base passed full-stack smoke without receiving registry-write credentials. |
| [`smoke.yml`](../../.github/workflows/smoke.yml) (`PR Smoke Gate`) | PR metadata and Actions API | Decide whether runtime smoke is required and expose the protected `Smoke Gate` by tracking the matching `Build Runtime Images` result | This is a controller and required gate; it does not run a second copy of full-stack smoke. |
| [`publish-pr-runtime-images.yml`](../../.github/workflows/publish-pr-runtime-images.yml) (`Publish PR Runtime Images`) | Trusted default-branch `workflow_run` definition | Download only the successful same-repository PR merge artifact and publish it with fixed PR head-SHA tags in GHCR | GitHub displays the default branch because the trusted publisher runs there; its summary identifies the source PR run and head SHA. |
| [`preview.yml`](../../.github/workflows/preview.yml) (`PR Preview Environment`) | PR-scoped hosted preview | Wait for the exact fixed-SHA tags, deploy them to the PR namespace, and report preview access | The preview uses the reviewed PR head artifact; it does not rebuild or select a moving branch tag. |

The trust split is deliberate: PR-controlled source can build and execute without package-write authority, while the trusted publisher never checks out or executes PR source. The source run's recorded base, head, and merge SHAs identify the tested input; the PR head SHA is the fixed publication and preview tag.

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
- Generates coverage with JaCoCo, uploads per-service coverage reports to Codecov using GitHub OIDC, and runs Trivy scans over the workspace.
- Uses Node 24 to lint OpenAPI specs, run React linters, and execute an accessibility audit using headless Chrome.
- Validates tracked Bash scripts across the repository with ShellCheck and validates tracked Python scripts by compiling them with `py_compile` so syntax regressions fail fast in CI.
- Invokes a dedicated `generate-erd` job that runs [`dev-tools/docs/generate-erd.sh`](../../dev-tools/docs/generate-erd.sh) to build ERD diagrams from service migrations and upload them as artifacts.
- Caches Buf modules, Node dependencies, Trivy database, and Gradle artifacts to speed up repeat workflow runs.
- Runs docs and link linting in `ci.yml` and verifies links again in the `docs.yml` workflow before publishing to GitHub Pages.
- Posts a summary comment on pull requests with test status and coverage, while Codecov publishes patch-coverage status separately.

The primary [`ci.yml`](../../.github/workflows/ci.yml) workflow builds and validates the repository:

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
          node-version: '24'
      - name: Check Formatting
        run: ./gradlew spotlessCheck
      - name: Lint Docs and Links
        run: ./gradlew lintMarkdown linkCheck
      - name: Run Checks
        run: ./gradlew check
```

The example above checks out the repository, sets up Java 21, and runs a Gradle build. Each microservice can be built in a matrix strategy so jobs run in parallel. All CI jobs share a concurrency group so new pushes cancel any running workflow for the same branch. The workflow also executes nightly at **3 AM UTC** via the `schedule` trigger so dependencies are scanned regularly.

Other workflows support additional automation:

- [`docs.yml`](../../.github/workflows/docs.yml) uses the **lychee** link checker (configured via `.lycheeignore`) before building and publishing the MkDocs-rendered architecture site to GitHub Pages. Publication is allowed only from `develop` and `main`; manual runs from other branches may validate the build but must not publish Pages.
- [`codeql.yml`](../../.github/workflows/codeql.yml) performs static code analysis on pull requests targeting `develop` and `main`, on pushes to `main`, and on the weekly CodeQL schedule plus manual workflow dispatches. The dedicated `CodeQL Gate` is enforced for pull requests targeting either protected base and succeeds without analysis only when change detection proves the diff is CodeQL-irrelevant.
- [`.coderabbit.yaml`](../../.coderabbit.yaml) configures CodeRabbit pull request review behavior for the repository. CodeRabbit inherits organization-level defaults, uses the repository-local path instructions and summary guidance, auto-reviews newly opened non-draft pull requests targeting `develop` and `main`, leaves later commits for an explicit full-review request at a meaningful checkpoint, and is currently configured as advisory rather than a merge-blocking "request changes" reviewer.
- [`scorecards.yml`](../../.github/workflows/scorecards.yml) runs OSSF Scorecard on `develop` and `main` pushes plus a weekly schedule, uploads the SARIF artifact, and publishes the findings into GitHub code scanning.
- [`license-scan.yml`](../../.github/workflows/license-scan.yml) checks open source dependencies for license compliance across the currently supported release dependency ecosystems (`Gradle` and `NPM`).
- [`renovate.json`](../../renovate.json) configures Renovate dependency-update pull requests for the `develop` branch. The current repository policy keeps Renovate eager rather than schedule-restricted, labels update PRs with `dependencies`, and groups non-major updates by ecosystem to reduce branch churn.
- [`zap-baseline.yml`](../../.github/workflows/zap-baseline.yml) runs an OWASP ZAP baseline scan against the built web client preview on pull requests and pushes to `develop` and `main`.
- [`release-notes.yml`](../../.github/workflows/release-notes.yml) creates GitHub releases with autogenerated notes whenever a version tag is pushed, generates the release-specific `NOTICE.md`, and assembles the release `/licenses` bundle from ORT output for those same release dependency ecosystems.
- [`release-please.yml`](../../.github/workflows/release-please.yml) creates release pull requests from the `develop` branch.
- [`manual-backup-restore.yml`](../../.github/workflows/manual-backup-restore.yml) verifies recent backups and can run a recovery drill on demand for explicit throwaway targets only. When a drill is used as restore proof, it must produce the canonical recovery record defined in `system-architecture-backup-recovery.md`; it must not mutate live staging or production namespaces.
- [`validate-secret-compliance.yml`](../../.github/workflows/validate-secret-compliance.yml) validates secret-compliance records and enforces hard gates for production (and staging after the cutover date).
- [`weekly-security-scan.yml`](../../.github/workflows/weekly-security-scan.yml) runs the weekly Trivy image scan.
- [`ort-advisory.yml`](../../.github/workflows/ort-advisory.yml) runs the **Weekly ORT Advisory Scan**.
- [`publish-base-image.yml`](../../.github/workflows/publish-base-image.yml) runs the **Weekly FireMUD Base Image Refresh** and is also the branch-guarded publication path for explicit base-image rebuilds on `develop` / `main`.
- [`runtime-images.yml`](../../.github/workflows/runtime-images.yml) builds pull-request images locally and runs full-stack smoke without a registry-write token. After that workflow succeeds, [`publish-pr-runtime-images.yml`](../../.github/workflows/publish-pr-runtime-images.yml) runs from the trusted default-branch workflow definition, downloads the fixed image artifact, and publishes only the exact PR head-SHA tags needed by hosted previews. It never checks out or executes PR source and never writes shared cache or branch tags. Push and manual runs on trusted branches continue to publish images and shared build caches directly.

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

Images are tagged with the commit SHA. Pull-request images are first built and smoke-tested locally without registry credentials; only the resulting successful artifact crosses into the trusted publisher that pushes those fixed SHA tags to **GitHub Container Registry (GHCR)**. Trusted `develop` and `main` publication runs push directly and may update shared build-cache tags.

Deployable artifact lineage rule:

- The digest first produced for a reviewed source commit is the canonical deployable artifact for that commit.
- Promotion between staging and production must reuse that exact digest; release tags, branch tags, or `latest` tags may be added later as aliases, but they must not point at a rebuilt image if the image is intended to remain promotable.
- Any workflow that rebuilds from a release tag or branch tip produces a new artifact lineage. Those rebuilt digests are non-promotable until they independently pass staging and produce new deployment evidence plus a new production attestation chain.

Production release digest manifest:

- Each production release must have one canonical release digest manifest under `design/operations/deployments/production/release-manifests/<release-tag-or-deployment-ref>.json`.
- The manifest binds the release tag, source commit, production deployment reference, production promotion attestation, staging deployment record, release-note/compliance asset refs, and the exact service image digest set.
- Release-note workflows may publish metadata and compliance assets for a tag, but they do not select promotable runtime artifacts. The production overlay PR and release digest manifest are the authority for which staged digests belong to the release.
- If a release tag is created before production promotion completes, the release digest manifest may use a deployment-ref filename first, but it must be updated to include the final tag before the release is treated as official.

### Base Docker Image

The firemud-base image provides a consistent OS and JVM setup across all service containers. It is built using the `buildBaseImage` Gradle task and referenced in each microservice Dockerfile as `ghcr.io/benhook1013/firemud-base:latest`.

The mutable `firemud-base:latest` tag is a branch-publication convenience tag for `develop` / `main`, not a promotable release artifact by itself:

- Weekly base-image refreshes and explicit base-image rebuilds may republish `ghcr.io/benhook1013/firemud-base:latest` on `develop` / `main`.
- Service-image digests first produced by reviewed CI runs remain the canonical deployable artifacts for promotion and attestation.
- Rebuilding a service later against a moved base-image tag creates a new artifact lineage that must earn its own validation evidence before promotion.

---

## Deploying to Kubernetes

Hosted Kubernetes rollouts use environment-specific GitHub Actions workflows rather than a generic manual Helm button:

- [`preview.yml`](../../.github/workflows/preview.yml) renders and deploys the full-stack chart in `k8s/helm/firemud` for per-PR preview namespaces on the hosted preview cluster.
- [`dev-demo.yml`](../../.github/workflows/dev-demo.yml) renders and deploys the same chart for the fixed `develop` dev-demo environment.
- Local Kubernetes iteration uses direct `helm template`, `helm lint`, `helm install`, and `kubectl apply -k` commands rather than a GitHub-hosted workflow wrapper.

Staging and production deployments rely on environment-specific overlays (for example `k8s/overlays/stage` and `k8s/overlays/prod`) applied according to the Deployment Runbook by operators using `kubectl` from a secured workstation or bastion host.

### Rollback Strategy

Staging and production rollouts use standard Kubernetes `RollingUpdate` behavior and are rolled back by re-applying the environment’s Kustomize overlay with a previously known-good image digest set. FireMUD does not rely on automated canary/auto-rollback infrastructure by default; operators treat rollback as an explicit, auditable action that restores the last known-good digest set and verifies post-deploy health checks.

---

## Automated Release Notes

When a version tag like `v1.2.3` is pushed, the `release-notes.yml` workflow
creates a GitHub release, uses the `generate_release_notes` option to produce
change logs automatically, uploads a release-specific `NOTICE.md`, and uploads a
`licenses.zip` bundle assembled from ORT notice output. This keeps release
documentation and dependency notices consistent without manual steps.

## Repository Scripting Policy

FireMUD supports two repository automation scripting languages:

- **Bash** for shell-oriented developer, CI, preview, backup, and deployment helpers.
- **Python** for structured repository tooling where shell becomes awkward, such as docs generation, observability validation, and release asset assembly.

The repository should not accumulate additional ad hoc scripting languages for automation without a clear reason and matching CI support.

Current CI enforcement is:

- ShellCheck for all tracked `.sh` scripts in the repository.
- Python syntax validation for all tracked `.py` scripts in the repository.

Release dependency-notice automation is a separate concern from repository scripting:

- Official release `/licenses` generation currently covers the package-managed ecosystems that make up the shipped product and release artifacts: `Gradle` and `NPM`.
- If Python or another scripting ecosystem later becomes a packaged runtime or release-tool dependency surface with its own manifest-managed dependencies, the release notice workflow must be expanded to scan and publish notices for that ecosystem as well.

---

## PR Preview Environments

FireMUD's preview workflow is reserved for real reviewer-accessible PR environments, not CI-only stack boot validation. The [`.github/workflows/preview.yml`](../../.github/workflows/preview.yml) workflow targets a hosted single-node k3s cluster and follows this contract:

- Build and smoke-test PR-tagged container images in a credential-free job, then publish the successful fixed-tag artifact to private GHCR from the trusted default-branch publisher.
- Deploy or upgrade Helm release `pr-<PR_NUMBER>` into namespace `pr-<PR_NUMBER>`.
- Expose the environment at `https://pr-<PR_NUMBER>.preview.<DOMAIN>` using cluster ingress/TLS.
- Expose a reviewer-usable TCP/Telnet entry path for the preview stack so manual gameplay proof can happen through the normal MUD client surface.
- Reset the preview namespace on each deploy, then seed the minimum bootstrap state needed for reviewer proof so the hosted environment remains reproducible across preview updates.
- Tear the preview down when the PR closes or merges.

Main CI remains responsible for stack startup, smoke, and cross-service verification. Preview deployment is intentionally a separate concern focused on reviewer-accessible environments.

Initial hosted preview proof target:

- The first reviewer-usable proof milestone is not a rich browser UI.
- The first milestone is manual `LOGIN -> PLAY -> LOOK` over the hosted TCP/Telnet path using a terminal client or Mudlet-style client.
- Browser-first preview UX is a later step and should not block making the hosted preview environment real.

---

## Related Documentation

- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Developer Tools & Scripting](./system-architecture-scripting.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Testing Strategy](./system-architecture-testing.md)
- [User Journeys – Testing & Continuous Delivery](../product/user-journeys/operators.md#3-testing--continuous-delivery)

---

## Promotion & Rollback Model

FireMUD uses a simple promotion flow from pull requests through staging to production:

- Feature branches are merged into `develop` after passing CI.
- Staging promotion is performed by updating the staging Kustomize overlay (for example `k8s/overlays/stage`) to the desired image digests (`image@sha256:...`) via a Git change, merging it, and applying it from a secure operator environment using `kubectl apply -k k8s/overlays/stage`. This keeps “what was deployed” traceable in Git history and removes mutable-tag drift.
- When a release is ready, `release-please` opens a release PR against `main`; after that PR is merged, the release tag (for example `v1.2.3`) is created. Release-tag workflows may publish metadata and additional tags for the already-validated digest set, but must not rebuild or choose the promotable production artifacts.
- Production promotion is performed by updating the production Kustomize overlay (for example `k8s/overlays/prod`) to image digests that have already passed staging validation, via a Git change, merging it, and applying it from a secure operator environment using `kubectl apply -k k8s/overlays/prod` following the deployment runbook.
- Production release PRs must include a release digest manifest that binds the release tag or deployment reference to the production attestation and exact staged digest set.
- Overlay PRs are validated by [`.github/workflows/validate-kustomize-overlays.yml`](../../.github/workflows/validate-kustomize-overlays.yml), which checks that referenced images exist in GHCR, enforces digest pinning for staging/production overlays, and blocks staging backup schedules unless the explicit marker `k8s/overlays/stage/STAGING_BACKUPS_ENABLED` is present.
- Overlay PRs run the canonical preflight entrypoint (`dev-tools/deploy/preflight.py`) in `ci-static` context so policy IDs and report shape match operator pre-apply validation. CI-static mode may mark production attestation policy as `not_applicable` when no production promotion is being executed.
- Production overlay PRs must include exactly one in-repo staging promotion attestation under `design/operations/deployments/production/attestations/<deployment-ref>.json` that follows `system-architecture-promotion-attestation.md`. Production PRs are rejected if they reference digests that cannot be tied to that attestation, a successful staging deployment record with live-state verification, and a staging deployment record whose `secretComplianceStatus` is `pass`.
- Production overlay PR CI must evaluate the attestation and compact recovery-compatibility result for every promotion. `compatibilityStatus=incompatible` is unconditionally non-promotable. `compatibilityStatus=drill_required` remains non-promotable until the fresh drill is complete and the compatibility result is regenerated as `compatible`; attached backup-readiness evidence cannot make the stale result promotable. A `roll-forward-only` release requires matching full backup-readiness evidence in addition to a regenerated compatible result. Deferring those checks to operator-only preflight is non-compliant.
- Staging apply evidence must include the immutable in-repo deployment record `design/operations/deployments/staging/deployments/<stagingOverlayCommitSha>/<stagingDeploymentEventId>.json`; production promotion validation fails when the attestation does not select the exact event record or that record is missing, digest-mismatched, lacks live-state verification, or lacks passing secret-compliance evidence.
- Player-facing preflight and operator validation must also verify environment bootstrap completeness and external integration isolation before apply; these checks are not limited to restore events.
- `manual-backup-restore.yml` is limited to throwaway recovery drills. It may target only explicit non-player-facing restore namespaces or isolated drill clusters using dedicated low-privilege credentials and GitHub Environment approvals. It must not restore into live staging or production namespaces and must not hold credentials capable of modifying the currently player-facing cluster boundary.

Rollbacks are handled by resuming a previously known-good image digest set and re-applying the staging or production manifests with those digests. See the Deployment Runbook for the step-by-step operator flow.
Before approving a production promotion, deployment evidence must classify rollback mode as either:

- `rollback-compatible` (previous digest set remains safe to re-apply against the current database schema, secret/config contract, mounted file-path contract, and external-binding contract), or
- `roll-forward-only` (schema, secret/config, file-path, or external-binding change requires forward remediation and/or restore-point recovery rather than old-binary rollback).

Production promotions lacking this explicit rollback-mode classification are non-compliant.

Every production promotion records a compact recovery-compatibility result against the current production-equivalent cold-start drill. A `drill_required` result blocks promotion until the required fresh drill is complete and a new `compatible` result replaces it. For releases classified as `roll-forward-only`, promotion evidence must also include fresh full backup-readiness evidence proving:

- a recent successful logical backup,
- recent backup-verification success, and
- a current environment-wide `cold_start_restore` record suitable for the release, including empty Redis, safe recovery-participant dispositions, hardening, and controlled reopen.

The canonical full-evidence path is `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`. The compact result uses `compatibilityStatus` (`compatible`, `drill_required`, or `incompatible`) as the outcome and `newDrillRequired` as the machine-readable drill gate. `incompatible` is a terminal failed result. `drill_required` requires `newDrillRequired=true` and is also non-promotable: after the drill and full evidence are complete, the compatibility classifier must produce a new `compatible` result bound to that evidence. Every `roll-forward-only` release also sets `newDrillRequired=true`, carries matching full evidence, and must have a compatible regenerated result. Production CI/preflight rejects stale `drill_required` results and full evidence that is missing, stale, or not bound to the source production database lineage, candidate recovery tooling, exact candidate digests, migration path, config, bindings, and promotion attestation. Compatible rollback releases keep only the compact result or immutable reference in promotion/deployment evidence rather than copying the full recovery record.

Traffic-open readiness for production first-live or reopen events uses `design/operations/deployments/production/traffic-open/<first-live|reopen>-<deployment-ref>/<deploymentEventId>.json`, whose stored event identity matches the referenced preflight report, and references the canonical backup-readiness, recovery-controller, and confidentiality evidence. Routine online backups cover the environment-wide PostgreSQL database and do not use Game Session pause/resume as readiness proof.

Pre-apply policy checks for staging and production must run through the canonical preflight contract in `system-architecture-deploy-preflight-policy.md`. Static checks run in overlay PR CI, and resolved-manifest/runtime checks run in operator preflight execution. Both use the same policy IDs and evidence shape.

## Canonical Deployment Evidence Lifecycle

FireMUD uses one deployment-evidence chain per deployment event so promotion, rollback, and incident review all answer from the same record set:

1. Preflight produces `design/operations/deployments/<environment>/preflight/<deployment-ref>/<deploymentEventId>.json`.
2. Secret-compliance validation produces or references `design/operations/secret-compliance/<environment>.yaml` plus immutable supporting evidence.
3. Operator apply produces one immutable environment deployment record:
   - staging: `design/operations/deployments/staging/deployments/<overlayCommitSha>/<deploymentEventId>.json`
   - production: `design/operations/deployments/production/deployments/<overlayCommitSha>/<deploymentEventId>.json`
   - hobby-self-hosted: `design/operations/deployments/hobby-self-hosted/deployments/<deployment-ref>/<deploymentEventId>.json`
4. After apply and live-state verification succeed, the operator updates the environment's one current-state index at `design/operations/deployments/<environment>/deployments/current.json`. The index identifies the exact immutable deployment record through `deploymentRef`, `deploymentEventId`, and `deploymentRecordRef`; it is the sole repository answer to what is currently deployed in that environment.
5. Production promotion references exactly one staging attestation at `design/operations/deployments/production/attestations/<deployment-ref>.json`. The attestation binds one exact staging deployment event and remains immutable even after the staging current-state index advances.
6. Production release publication references one release digest manifest at `design/operations/deployments/production/release-manifests/<release-tag-or-deployment-ref>.json`.
7. Every production release records its compact recovery-compatibility result; if that result requires a drill or the release is `roll-forward-only`, production also references `design/operations/deployments/production/backup-readiness/<deployment-ref>.json`.

Lifecycle rules:

- The environment's `deployments/current.json` index is the canonical answer to “what is currently deployed.” Promotion eligibility is a separate immutable claim: an attestation selects one exact successful deployment event and never performs a later “latest event” lookup.
- Preflight artifacts, secret-compliance snapshots, smoke evidence, and live-state verification are supporting evidence linked from the deployment record rather than parallel sources of truth.
- Current promotion trust is repository-reviewed evidence with immutable artifact references. CI treats the production attestation as the deterministic selector for its exact in-repo deployment record, then verifies digest equality, live-state evidence shape, and immutable secret-compliance references. Detached signatures are not required in the current single-admin/operator model.
- Re-applying the same overlay commit creates a new immutable event record and, after successful live-state verification, advances the current-state index. It never overwrites prior preflight, apply, or attestation evidence.
- A promotion attestation is valid only if `stagingOverlayCommitSha` plus `stagingDeploymentEventId` selects the exact successful promotable apply event it attests. A later apply does not invalidate or retarget that historical attestation.
- Rollback uses the deployment record and original attestation lineage for the digest set being restored.

Terminology note:

- `deployment evidence` answers what was checked, applied, and verified for a concrete deployment event in one environment.
- `promotion evidence` is the subset of evidence used to prove a staging deployment is eligible to be promoted into production, primarily the attestation plus its referenced deployment and compliance records.
- `traffic-open evidence` is the evidence family used to prove an environment may be opened or reopened to player traffic, for example the production traffic-open backup-readiness artifact or hobby traffic-open records.
- `promotion candidate` means a staging deployment record that is eligible to produce production promotion evidence; quarantined or detached staging drills can remain valid deployment evidence without becoming promotion candidates.
- `deployment-ref` is the canonical environment-agnostic identity for the reviewed deployment input lineage and is used by preflight, attestation, and backup-readiness artifacts. For Git-managed staging/production overlays, it is the same Git SHA recorded as `overlayCommitSha`. `deploymentEventId` is the distinct UUID for one concrete preflight/apply event, including a retry or later re-apply of the same deployment ref.

Illustrative deployment record shape:

```json
{
  "environment": "staging",
  "overlayCommitSha": "<git-sha>",
  "deploymentEventId": "<uuid>",
  "appliedAt": "2026-03-13T10:15:00Z",
  "appliedBy": "operator@example",
  "deployStatus": "pass",
  "smokeStatus": "pass",
  "serviceDigests": {
    "spring-cloud-gateway": "ghcr.io/example/spring-cloud-gateway@sha256:...",
    "game-session-service": "ghcr.io/example/game-session-service@sha256:..."
  },
  "preflightReportPath": "design/operations/deployments/staging/preflight/<git-sha>/<deploymentEventId>.json",
  "liveStateEvidence": {
    "status": "pass",
    "observedOverlaySha": "<git-sha>",
    "observedDigests": {
      "spring-cloud-gateway": "ghcr.io/example/spring-cloud-gateway@sha256:...",
      "game-session-service": "ghcr.io/example/game-session-service@sha256:..."
    },
    "evidenceRefs": [
      "namespace annotation firemud.io/overlay-sha=<git-sha>",
      "rollout digests matched reviewed overlay"
    ]
  },
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

The `jwt-signing-keys-jwks` age record is necessary but not sufficient for JWT readiness. Any staging deployment used for production attestation, production promotion, or player-facing first-live/reopen evidence must also carry `PREFLIGHT-JWT-002=pass` and current `PREFLIGHT-JWT-ROTATION-001=pass` evidence proving Account-only asymmetric signing, validator convergence, planned rotation through pruning, and compromise hard cutover. The current executable preflight does not yet implement those checks, so mounted JWT/JWKS resources and a fresh credential-age record cannot be used to claim the player-facing JWT gate is satisfied.

---

## Deployment Credentials & Environments

CI workflows and operators use distinct credentials for each Kubernetes environment:

- **Development clusters**
  - May use a kubeconfig or token that is available to a wider set of workflows (for example the hosted preview and dev-demo environments).
  - Intended for non-player-facing stacks where rapid iteration is more important than strict change control.
- **Staging cluster**
  - Uses credentials limited to operator `kubectl` access and, if introduced later, dedicated staging deployment workflows.
  - Credentials are not exposed to pull request workflows; only merges to `develop` and explicit operator actions (or approved staging workflows) can update the staging cluster.
- **Production cluster**
  - Uses credentials restricted to production deployment paths and operator `kubectl` access from approved workstations or bastion hosts.
  - No GitHub Actions workflow currently applies production manifests directly; any future workflow that does so must use GitHub Environments and require manual approvals.

Registry credentials (for GHCR) are shared across environments but access to pull images into each cluster is controlled by Kubernetes secrets and RBAC within that environment.
