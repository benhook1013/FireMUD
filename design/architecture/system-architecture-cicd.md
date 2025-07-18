# 🚀 FireMUD System Architecture: CI/CD Pipeline

This document describes the basic continuous integration and deployment strategy for FireMUD using **GitHub Actions**. Every service is built, tested, and containerized. Deployment to Kubernetes is triggered manually using a dedicated workflow until cloud hosting is available.

> **Status: In Progress** – Full automation of Kubernetes deployments is still being developed. (TODO: Not yet implemented)

---

## 🎯 Goals

- **Automate builds and tests** for all microservices whenever code changes are pushed.
- **Build Docker images** and push them to GitHub Container Registry (GHCR).
- **Deploy to Kubernetes manually** using the manifests in [`k8s/`](../../k8s/), with automation planned for the future. (TODO: Not yet implemented)
- Keep the workflow configuration easy to maintain and extensible for future security scans or nightly jobs.
- **Generate release notes automatically** whenever version tags are pushed.
- **Perform code scanning** with CodeQL and open source **license checks** on every pull request.
- **Publish documentation** to GitHub Pages after successful builds.
- **Create release PRs automatically** using the `release-please` workflow.
- **Generate database ERD diagrams** as build artifacts after each run. The diagrams are stored in `design/erd/` and uploaded as workflow artifacts.

The CI job first performs a **Buf breaking change check** to ensure protobuf APIs remain compatible. It then runs formatting and lint steps followed by a matrix of Gradle `check` tasks—one per microservice—which compile and test each module while running Spotless, Checkstyle, and SpotBugs. **Hadolint** checks Dockerfiles and **ShellCheck** validates shell scripts. Coverage reports are generated with JaCoCo and a Trivy security scan runs on the workspace. Node 20 is also configured so the pipeline can lint OpenAPI definitions, run the React client’s linters, and execute an accessibility audit using headless Chrome. After the scan, the job executes `dev-tools/generate-erd.sh` to build ERD diagrams from the service migrations and uploads them as artifacts. Documentation links are verified in the `docs.yml` workflow before publishing to GitHub Pages. Docker images are built in a separate workflow. See [System Architecture Testing](./system-architecture-testing.md) for additional details.

---

## 🛠️ Workflow Structure

Workflows live in the `.github/workflows/` directory. A typical pipeline runs on every pull request and push to the main branch. A separate `docker-images.yml` workflow builds and publishes Docker images for all services using Docker Buildx and the `docker/build-push-action`:

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
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '21'
      - uses: actions/setup-node@v3
        with:
          node-version: '20'
      - name: Format Code
        run: ./gradlew spotlessApply
      - name: Lint Markdown
        run: ./gradlew lintMarkdownFix
      - name: Run Checks
        run: ./gradlew check
```

The example above checks out the repository, sets up Java 21, and runs a Gradle build. Each microservice can be built in a matrix strategy so jobs run in parallel. The workflow also executes nightly at **3 AM UTC** via the `schedule` trigger so dependencies are scanned regularly.

Other workflows support additional automation:

- `docs.yml` publishes the contents of the `design/` folder to GitHub Pages.
- `codeql.yml` performs static code analysis on each pull request and push to `main`.
- `license-scan.yml` checks open source dependencies for license compliance.
- `release-please.yml` creates release pull requests from the `develop` branch.

---

## 🐳 Building and Pushing Images

After tests pass, each service is packaged into a Docker image:

```yaml
  docker-build:
    needs: build-and-test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v2
      - name: Login to Registry
        uses: docker/login-action@v2
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Build and Push
        uses: docker/build-push-action@v4
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

## 🚢 Deploying to Kubernetes

FireMUD does not yet deploy automatically to Kubernetes. Operators trigger the
`manual-helm-deploy.yml` workflow when they want to roll out a new version. That
job runs `helm upgrade` against a local cluster using the charts in
[`k8s/helm`](../../k8s/helm). Cluster credentials and registry secrets must be
configured beforehand. When full automation is added, a workflow similar to the
example below can be introduced.

```yaml
deploy:
  needs: docker-build
  if: github.ref == 'refs/heads/main'
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v3
    - name: Set up kubectl
      uses: azure/setup-kubectl@v3
    - name: Set up Helm
      uses: azure/setup-helm@v3
    - name: Deploy
      run: |
        helm upgrade --install my-service ./charts/my-service \
          --set image.tag=${{ github.sha }}
```

### Rollback Strategy

New service versions are deployed alongside existing ones. If issues appear after
a rollout, prior releases can be reinstated and the newer copies scaled down or
removed. Automated rollback or canary deployments are planned but not yet
implemented. (TODO: Not yet implemented)

---

## 📝 Automated Release Notes

When a version tag like `v1.2.3` is pushed, the `release-notes.yml` workflow
creates a GitHub release and uses the `generate_release_notes` option to produce
change logs automatically. This keeps release documentation consistent without
manual steps.

---

## 🔍 PR Preview Environments

Pull requests spin up a short-lived Docker Compose stack so reviewers can test
changes interactively. The `.github/workflows/preview.yml` workflow starts the
stack with `./gradlew devUp`, which builds the service images and runs Docker
Compose. A status comment is posted with a summary once the gateway passes its
health check. The runner is discarded at the end of the job, removing the
preview automatically. The workflow first copies `.env.sample` to `.env` so
Docker Compose has default environment variables available.

---

## ➕ Optional Add-Ons

- **Nightly builds or scheduled jobs** for integration testing. (TODO: Not yet implemented)
- **Security scanning** using tools like Trivy. The `weekly-security-scan.yml`
  workflow runs on a schedule to scan dependencies and container images for
  vulnerabilities.
- **Notifications** via email when workflows fail. (TODO: Not yet implemented)

These can be added as separate workflows or additional jobs in the main pipeline.

---

## 📚 Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Testing Strategy](./system-architecture-testing.md)
- [User Journeys – Testing & Continuous Delivery](./user-journeys.md#17-testing--continuous-delivery)
