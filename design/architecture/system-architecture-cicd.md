# 🚀 FireMUD System Architecture: CI/CD Pipeline

This document describes the basic continuous integration and deployment strategy for FireMUD using **GitHub Actions**. Every service is built, tested, containerized, and deployed automatically so that changes reach production reliably.

---

## 🎯 Goals

- **Automate builds and tests** for all microservices whenever code changes are pushed.
- **Build Docker images** and push them to a container registry.
- **Deploy to Kubernetes** when changes are merged into designated branches (e.g., `main` or `release/*`).
- Keep the workflow configuration easy to maintain and extensible for future security scans or nightly jobs.
- **Generate release notes automatically** whenever version tags are pushed.

The main workflow runs formatting and lint checks followed by the Gradle `check` task, which compiles and tests all modules while running Spotless, Checkstyle, and SpotBugs. It then generates JaCoCo coverage reports and performs a Trivy security scan. Docker images are built in a separate workflow. See [System Architecture Testing](./system-architecture-testing.md) for additional details.

---

## 🛠️ Workflow Structure

Workflows live in the `.github/workflows/` directory. A typical pipeline runs on every pull request and push to the main branch. A separate `docker-images.yml` workflow builds and publishes Docker images for all services using the `buildDockerImages` Gradle task:

```yaml
name: CI
on:
  push:
    branches: [ main ]
  pull_request:

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
      - name: Format Code
        run: ./gradlew spotlessApply
      - name: Lint Markdown
        run: ./gradlew lintMarkdownFix
      - name: Run Checks
        run: ./gradlew check
```

The example above checks out the repository, sets up Java 21, and runs a Gradle build. Each microservice can be built in a matrix strategy so jobs run in parallel.

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
          push: true
          tags: ghcr.io/my-org/service:${{ github.sha }}
```

Images are tagged with the commit SHA and pushed to **GitHub Container Registry (GHCR)**.

### Base Docker Image

All service containers extend a common `firemud-base` image built from
`docker/base.Dockerfile`. The `buildBaseImage` Gradle task builds and pushes
this image so microservices share the same runtime configuration.

---

## 🚢 Deploying to Kubernetes

For production branches, an additional job deploys the newly built images using Helm:

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

Cluster credentials and registry secrets are stored as encrypted repository secrets so that the workflow can authenticate securely.

### Rollback Strategy

New service versions are deployed alongside existing ones. If issues appear after a rollout, prior releases can be reinstated and the newer copies scaled down or removed.

---

## 📝 Automated Release Notes

When a version tag like `v1.2.3` is pushed, the `release-notes.yml` workflow
creates a GitHub release and uses the `generate_release_notes` option to produce
change logs automatically. This keeps release documentation consistent without
manual steps.

---

## 🔍 PR Preview Environments

Pull requests spin up a short-lived Docker Compose stack so reviewers can test
changes interactively. The `.github/workflows/preview.yml` workflow builds the
service images with `./gradlew buildDockerImages` and launches them using
`docker compose up`. A status comment is posted with a summary once the gateway
passes its health check. The runner is discarded at the end of the job,
removing the preview automatically. The workflow first copies `.env.sample` to
`.env` so Docker Compose has default environment variables available.

---

## ➕ Optional Add-Ons

- **Nightly builds or scheduled jobs** for integration testing.
- **Security scanning** using tools like Trivy. A scheduled workflow runs
  weekly to scan dependencies and container images for vulnerabilities.
- **Notifications** via email when workflows fail.

These can be added as separate workflows or additional jobs in the main pipeline.

---

## 📚 Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Testing Strategy](./system-architecture-testing.md)
