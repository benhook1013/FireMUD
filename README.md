# FireMUD Game Platform

[![Status: Under Development](https://img.shields.io/badge/Status-Under_Development-yellow)](./design/project-management/task-list.md)
[![License: Business Source License 1.1](https://img.shields.io/badge/License-Business_Source_License_1.1-blue.svg)](LICENSE.md)
[![CI](https://github.com/benhook1013/FireMUD/actions/workflows/ci.yml/badge.svg)](https://github.com/benhook1013/FireMUD/actions/workflows/ci.yml)

[![Backend: Java Spring](https://img.shields.io/badge/Backend-Java_Spring_Framework-green)](https://spring.io/)
[![Frontend: React](https://img.shields.io/badge/Frontend-React-blue)](https://react.dev/)
[![Database: PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL-blue)](https://www.postgresql.org/)
[![Database: Redis](https://img.shields.io/badge/Database-Redis-blue)](https://redis.io/)
[![Containerization: Docker](https://img.shields.io/badge/Containerization-Docker-blue)](https://www.docker.com/)
[![Orchestration: Kubernetes](https://img.shields.io/badge/Orchestration-Kubernetes-blue)](https://kubernetes.io/)

Welcome to the **FireMUD Game Platform**, a modular and scalable system under the [FireDevOps.net](https://firedevops.net) umbrella for creating and running Multi-User Dungeon (MUD) games.

*This project uses the [Business Source License 1.1](LICENSE.md). Each release converts to the Apache 2.0 License two years after publication. See our [FAQ](FAQ.md) for details.*

## Start Here

For an overview of all architecture and design documents, see [Architecture Overview](design/architecture/README.md).
See [Repository Structure](design/architecture/repository-structure.md) for module layout.

---

## 📚 Table of Contents

- [Purpose](#purpose)
- [Project Overview](#project-overview)
  - [Key Features](#key-features)
  - [Tech Stack](#tech-stack)
  - [Design Goals](#design-goals)
- [Architecture](#architecture)
  - [Microservices](#microservices)
  - [Service Interactions](#service-interactions)
- [Developer Setup](./DEVELOPER_SETUP.md)
- [Repository Structure](design/architecture/repository-structure.md)
- [Getting Started and Contributing](#getting-started-and-contributing)
  - [Learn About the Platform](#learn-about-the-platform)
  - [Ways to Contribute](#ways-to-contribute)
  - [Additional Guidelines](#additional-guidelines)
- [Support Us](#support-us)
- [Contact](#contact)
- [Acknowledgments](#acknowledgments)

---

## Purpose

This repository serves as the **central mono-repo** for the FireMUD Game Platform, containing:

- All microservices and shared utilities.
- Documentation of the requirements, features, architecture, and design.
- Collaborative design discussions, feature planning, and refinements.

---

## Project Overview

### Key Features

- **Microservice Architecture**: Modular services for scalability and maintainability.
- **Multi-Server Hosting**: Support for hosting multiple MUD games simultaneously.
- **Real-Time Game Server**: Backend for gameplay mechanics, player actions, and world state management.
- **Extensible Command Parsing**: Flexible system to interpret player commands.
- **Dynamic Scripting Support**: For events and interactions within the game world.
- **Integrated Game Editor**: Tools for designing rooms, entities, quests, and dialogues.
- **Web Frontend**: React-based interface for players and creators.
- **Moderation Tools**: Comprehensive tools for administrators and moderators.

### Tech Stack

#### Backend

- **Framework**: Java Spring Framework
- **Database**: PostgreSQL
- **Caching**: Redis for transient session and gameplay state
- **Networking**: WebSocket/TCP
- **Inter-Service Communication**: gRPC
- **API Gateway**: Spring Cloud Gateway

#### Frontend

- **Framework**: React
- **Styling**: Material-UI

#### Deployment & Infrastructure

- **Containerization**: Docker
- **Orchestration**: Kubernetes
- **CI/CD**: [GitHub Actions](design/architecture/system-architecture-cicd.md)
- **Monitoring and Logging**: Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager (see [Logging & Monitoring](design/architecture/system-architecture-logging-monitoring.md))

#### Monetization

- **Payment Gateway**: Stripe
- **Subscription Management**: Custom integration

#### Testing

- **Unit Testing**: JUnit, Mockito
- **Integration Testing**: Spring Test
- **Load Testing**: Gatling (see `dev-tools/load-testing` module)
- **Accessibility Audit**: axe-core CLI (requires Google Chrome).
  To run the audit locally, install Chrome as described in
  [Developer Setup](DEVELOPER_SETUP.md#frontend-lint--accessibility)
  before executing `npm run accessibility`.

### Design Goals

1. **Modularity**: Independently scalable and maintainable services.
2. **Flexibility**: Extensible features for diverse gameplay and customization.
3. **Performance**: Optimized for real-time interactions and low latency.
4. **User-Friendly Creation**: Intuitive tools for game creators.
5. **Community Engagement**: Facilitate a vibrant community of players and creators.
6. **Security**: Ensure data protection and secure interactions across services.
7. **Accessibility**: Provide an inclusive gaming experience by ensuring the platform is accessible to all players, including those who are visually impaired or blind, through compatibility with screen readers and other assistive technologies.

---

## Architecture

### Microservices

The platform is composed of multiple Spring Boot services that communicate over gRPC. The complete list and responsibilities are maintained in the
[Microservices documentation](design/architecture/microservices/README.md).

### Service Interactions

For detailed architecture diagrams and explanations, refer to the [System Architecture Overview](design/architecture/system-architecture-overview.md).
For an overview of service responsibilities, see the [Service Responsibility Matrix](design/architecture/service-responsibility-matrix.md).

---

## Getting Started and Contributing

We welcome feedback, ideas, and contributions to improve the FireMUD platform. Here's how you can get involved:

For local environment setup instructions, see [Developer Setup](./DEVELOPER_SETUP.md).

---

### Learn About the Platform

Before contributing, we recommend reviewing the following key documents:

- **[Core Requirements](design/project-management/core-requirements.md)** – high-level product requirements.
- **[System Architecture Overview](design/architecture/system-architecture-overview.md)** – platform design and service interactions.
- **[Service Design Documents](design/architecture/microservices/README.md)** – details for each microservice.
- **[Infrastructure Overview](design/architecture/infrastructure/README.md)** – deployment environments and shared systems.
- **[Frontend Architecture](design/architecture/system-architecture-frontend.md)** – how the React interface integrates with backend services.
- **[Security Architecture](design/architecture/system-architecture-security.md)** – JWT secrets, TLS, and cross-service trust.
- **[Versioning & Runtime Configuration](design/architecture/system-architecture-versioning-runtime.md)** – publishing versions and controlling runtime flags.
- **[Example User Journeys](design/architecture/user-journeys.md)** – step-by-step workflows for creators and players.
- **[Task List](design/project-management/task-list.md)** – planned features and development progress.
- **[Game Creator Guide](design/user-guides/game-creator-guide.md)** – customizing worlds and using the scripting API.
- **[FAQ](FAQ.md)** – frequently asked questions for quick context.

---

### Ways to Contribute

- **Contribute Code**: See our [Contributing Guidelines](./CONTRIBUTING.md) for branching strategy, coding standards, and how to submit a pull request.
- **Review Pull Requests**: Provide thoughtful, constructive feedback on open pull requests.
- **Improve Documentation**: Help keep our docs accurate and beginner-friendly by fixing typos, clarifying explanations, adding examples, or expanding the FAQ.
- **Follow the [Code of Conduct](CODE_OF_CONDUCT.md)**: Treat everyone with respect and help maintain a welcoming community.
- **Report Bugs or Suggest Features**: Open an issue in the relevant repository with detailed information. Be clear, respectful, and constructive.
- **Report Security Issues**: If you discover a security vulnerability, please **do not** file a public issue. Instead, report it privately to [Ben.Hook@firedevops.net](mailto:Ben.Hook@firedevops.net). We take security seriously and will respond promptly.

---

### Additional Guidelines

See the [Contributing Guidelines](./CONTRIBUTING.md) for branching strategy, testing requirements, and coding standards. Our AI coding conventions are documented in [Local Rules](design/project-management/ai-rules-local.md) and [Global Rules](design/project-management/ai-rules-global.md).
The CI pipeline runs `./gradlew check`, which compiles and tests all modules while also running Spotless, Checkstyle, SpotBugs, and coverage reporting.

## Running Locally

Build all services and start the Docker stack:

```bash
./gradlew devUp
```

Stop the stack with:

```bash
./gradlew devDown
```

---

## Support Us

Your support can make a significant difference in the development and success of the FireMUD Game Platform. If you're interested in supporting the project, here are some ways you can help:

- **Contribute**: See the [Getting Started and Contributing](#getting-started-and-contributing) section for ways to contribute code, documentation, or ideas.
- **Spread the Word**: Share the project with friends, colleagues, and on social media platforms to help us reach a wider audience.
- **Financial Contributions**: Help fund ongoing development:
  - [Donate via PayPal](https://paypal.me/firedevops)
  - [Sponsor on GitHub](https://github.com/sponsors/benhook1013)
  - [Support us on Patreon](https://patreon.com/firemud)

  *Note: Financial contributions will be used to cover development costs, hosting, and other expenses related to the project.*

---

## Contact

- **Project Lead**: Ben Hook
  - **Email**: [Ben.Hook@firedevops.net](mailto:Ben.Hook@firedevops.net)
  - **GitHub**: [github.com/benhook1013](https://github.com/benhook1013)
- **FireDevOps.net**: [firedevops.net](https://firedevops.net)

---

## Acknowledgments

- **MUD Community**: A heartfelt thank you to the MUD game community and the developers of classic MUD platforms. Your creativity and dedication have inspired us to build upon the rich legacy of text-based gaming.
- **Open-Source Community**: Inspired by the collaborative spirit of open-source development.
