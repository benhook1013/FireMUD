# 🚀 CI/CD Pipeline Overview

This document describes the basic continuous integration and deployment strategy for FireMUD using **GitHub Actions**. Every service is built, tested, containerized, and deployed automatically so that changes reach production reliably.

---

## 🎯 Goals

- **Automate builds and tests** for all microservices whenever code changes are pushed.
- **Build Docker images** and push them to a container registry.
- **Deploy to Kubernetes** when changes are merged into designated branches (e.g., `main` or `release/*`).
- Keep the workflow configuration easy to maintain and extensible for future security scans or nightly jobs.

---

## 🛠️ Workflow Structure

Workflows live in the `.github/workflows/` directory. A typical pipeline runs on every pull request and push to the main branch:

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
  build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '17'
      - name: Build and Test
        run: ./gradlew build
```

The example above checks out the repository, sets up Java 17, and runs a Gradle build. Each microservice can be built in a matrix strategy so jobs run in parallel.

---

## 🐳 Building and Pushing Images

After tests pass, each service is packaged into a Docker image:

```yaml
  docker-build:
    needs: build-test
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

## ➕ Optional Add-Ons

- **Nightly builds or scheduled jobs** for integration testing.
- **Security scanning** using tools like Trivy.
- **Notifications** to Slack or email when workflows fail.

These can be added as separate workflows or additional jobs in the main pipeline.

---

## 📚 Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)

