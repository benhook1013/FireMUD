# ❓ FireMUD Platform FAQ

This document collects common questions and answers about the FireMUD Game Platform.

---

## General

- **What is the purpose of FireMUD?**  
  FireMUD is a modular platform for hosting and creating text-based MUD games. It provides real-time multiplayer services and integrated tools for game creators.

- **Is FireMUD open source?**  
  The project is released under the Business Source License 1.1, which converts to the Apache 2.0 License in April 2027. Non-commercial use is permitted without a separate license.

---

## Architecture and Design

- **What technologies does FireMUD use?**  
  The backend is composed of Java Spring Boot microservices communicating through gRPC. The web frontend is built with React and Material‑UI. Data is stored in PostgreSQL with Redis for caching.

- **Why a microservice architecture?**  
  Separating functionality into services like account management, world management, and session management keeps the platform modular and allows each part to scale independently.

---

## Development and Contribution

- **How can I contribute to FireMUD?**  
  Fork this repository, create a feature branch, make your changes, and open a pull request against `main`. Follow the coding standards described in the README and design documents.

- **Where do I find design resources?**  
  The `FireMUD_Design/` directory contains architecture diagrams, service descriptions, and planning documents that explain how the system fits together.

---

## Deployment and Infrastructure

- **How is the platform deployed?**
  Services run in Docker containers and are orchestrated with Kubernetes. For local development you can use Docker Compose. Continuous integration and deployment are handled by GitHub Actions.

- **Can GitHub Actions work with Docker and Kubernetes?**
  Yes. GitHub Actions can build Docker images, run tests, and push images to a registry. From there you can deploy those images to any Kubernetes cluster using actions that invoke `kubectl` or Helm. Many projects use this workflow for CI/CD.

---

## Other

- **Who maintains FireMUD?**  
  The project is led by Ben Hook under the Fire‑DevOps.net umbrella. Contact details are listed in the README.
