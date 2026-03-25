# FireMUD Platform FAQ

This document collects common questions and answers about the FireMUD Game Platform.

---

## General

- **What is the purpose of FireMUD?**
  FireMUD is a modular platform for hosting and creating text-based MUD games. It provides real-time multiplayer services and integrated tools for game creators.

- **Is FireMUD open source?**
  FireMUD is source-available under the Business Source License 1.1. Each official release carries its own change date and automatically switches to the Apache 2.0 License two years after publication. Private use, self-hosting, and modification are allowed unless they fall under the restricted commercial uses listed in [LICENSE.md](LICENSE.md).

---

## Architecture and Design

- **What technologies does FireMUD use?**
  The backend is composed of Java Spring Boot microservices communicating through gRPC. The web frontend is built with React and Material‑UI. Data is stored in PostgreSQL, while Redis holds only transient session and gameplay state.

- **Why a microservice architecture?**
  Separating functionality into services like account management, world management, and session management keeps the platform modular and allows each part to scale independently.

---

## Development and Contribution

- **How can I contribute to FireMUD?**
  Fork this repository, create a feature branch, make your changes, and open a pull request against `main`. Follow the coding standards described in the README and design documents.

- **Where do I find design resources?**
  The `design/` directory contains architecture diagrams, service descriptions, and planning documents that explain how the system fits together.

- **How do I get a development environment running?**
  Follow the steps in [**Developer Setup**](DEVELOPER_SETUP.md) to install prerequisites and run `./gradlew devUp`.

- **Where are the API schemas defined?**
  gRPC protobuf files live under the [`protos/`](protos) directory. Each microservice README links to its versioned schemas.

- **Where can I find the roadmap?**
  The active task list is in [design/project-management/task-list.md](design/project-management/task-list.md).

- **How do I report bugs or request features?**
  Open an issue on GitHub with as much detail as possible. Please search existing issues first to avoid duplicates.

---

## Deployment and Infrastructure

- **How is the platform deployed?**
  Services run in Docker containers and are orchestrated with Kubernetes. For local development you can use Docker Compose. Continuous integration and deployment are handled by GitHub Actions.

- **Can GitHub Actions work with Docker and Kubernetes?**
  Yes. GitHub Actions can build Docker images, run tests, and push images to a registry. From there you can deploy those images to any Kubernetes cluster using actions that invoke `kubectl` or Helm. Many projects use this workflow for CI/CD.

- **How are new versions published?**
  Each FireMUD service is tagged and released through GitHub Actions. The resulting Docker images are pushed to a registry and deployed to Kubernetes. Every official release records its own publication date, BSL change date, and dependency notices as part of the release process before later converting to Apache 2.0.

- **How are mTLS certificates issued?**
  The Kubernetes cluster runs `cert-manager`, which automatically issues and renews TLS and mTLS certificates for each service. Certificates are stored as Kubernetes Secrets and mounted into the pods.

---

## Other

- **Who maintains FireMUD?**
  FireMUD is maintained under the FireDevOps.net umbrella. Current contact details are listed in the README.
