# FireMUD Game Platform

[![Status: Under Development](https://img.shields.io/badge/Status-Under_Development-yellow)](./design/project-management/implementation-tracking/README.md)
[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm_Noncommercial_1.0.0-blue.svg)](LICENSE.md)
[![CI](https://github.com/benhook1013/FireMUD/actions/workflows/ci.yml/badge.svg)](https://github.com/benhook1013/FireMUD/actions/workflows/ci.yml)

[![Backend: Java Spring](https://img.shields.io/badge/Backend-Java_Spring_Framework-green)](https://spring.io/)
[![Frontend: React](https://img.shields.io/badge/Frontend-React-blue)](https://react.dev/)
[![Database: PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL-blue)](design/architecture/infrastructure/deployment-environments.md)
[![Database: Redis](https://img.shields.io/badge/Database-Redis-blue)](https://redis.io/)
[![Containerization: Docker](https://img.shields.io/badge/Containerization-Docker-blue)](https://www.docker.com/)
[![Orchestration: Kubernetes](https://img.shields.io/badge/Orchestration-Kubernetes-blue)](https://kubernetes.io/)

Welcome to the **FireMUD Game Platform**, a modular and scalable system developed and maintained by Benjamin James Hook under the [FireDevOps.net](https://firedevops.net) project brand for creating and running [Multi-User Dungeon (MUD) games](https://en.wikipedia.org/wiki/Multi-user_dungeon). FireDevOps is the project brand and firedevops.net is its website, not a separate legal entity. An official hosted service is a planned target, not a currently operating service.

FireMUD is a modern engine for classic text-based online RPGs: creators use it to build persistent shared worlds with rooms, items, NPCs, and quests, and players connect over the web or Telnet to explore, chat, and adventure together in real time.

*This project uses the [PolyForm Noncommercial License 1.0.0](LICENSE.md). Commercial use not otherwise permitted by LICENSE.md or applicable law requires a separate written agreement with Benjamin James Hook. See [LICENSING.md](LICENSING.md) for plain-language licensing lanes, [HOSTED_CONTENT_TERMS.md](HOSTED_CONTENT_TERMS.md) for the explicitly nonoperative hosted-content baseline, [TRADEMARKS.md](TRADEMARKS.md) for brand use, our [FAQ](FAQ.md), and the [release process](design/operations/release-process.md) for details.*

## Table of Contents

- [FireMUD Game Platform](#firemud-game-platform)
  - [Table of Contents](#table-of-contents)
  - [What This Repository Contains](#what-this-repository-contains)
  - [Platform Snapshot](#platform-snapshot)
    - [Key Features](#key-features)
    - [Tech Stack](#tech-stack)
  - [Architecture](#architecture)
  - [Docs Map](#docs-map)
  - [Getting Started](#getting-started)
  - [Contributing](#contributing)
  - [Support Us](#support-us)
  - [Contact](#contact)
  - [Acknowledgments](#acknowledgments)

---

## What This Repository Contains

This repository serves as the **central mono-repo** for the FireMUD Game Platform, containing:

- All microservices and shared utilities.
- Documentation of the requirements, features, architecture, and design.
- Collaborative design discussions, feature planning, and refinements.

---

## Platform Snapshot

### Key Features

- **Microservice Architecture**: Modular services for gameplay, account, design, and platform operations.
- **Concurrent Multi-Server Hosting**: Support for multiple hosted MUD realms under one platform.
- **Cross-Client Gameplay**: Shared backend for first-party web clients and legacy Telnet access.
- **Creator Tooling**: Services and docs for world design, publishing, and scripted behavior.
- **Accessible Text UX**: Text-first presentation intended to work well with screen readers and low-vision players.
- **Operator and Moderation Controls**: Platform support for observability, moderation, and runtime operations.

### Tech Stack

- **Backend Framework**: Java Spring Framework
- **Database**: PostgreSQL
- **Caching**: Redis for transient session and gameplay state
- **Networking**: WebSocket/TCP
- **Inter-Service Communication**: gRPC
- **API Gateway**: Spring Cloud Gateway
- **Frontend**: React with Material-UI
- **Containerization**: [Docker & Docker Compose](design/architecture/infrastructure/deployment-environments.md)
- **Orchestration**: [Kubernetes Deployments](design/architecture/infrastructure/deployment-environments.md)
- **CI/CD**: [GitHub Actions](design/architecture/system-architecture-cicd.md)
- **Monitoring and Logging**: Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager (see [Logging & Monitoring](design/architecture/system-architecture-logging-monitoring.md))

---

## Architecture

FireMUD is built as a set of Spring Boot services communicating over gRPC, with Spring Cloud Gateway as the unified HTTP/WebSocket edge and the TCP Proxy Service bridging Telnet into the same gameplay path used by web clients.

Canonical architecture references:

- [System Architecture Overview](design/architecture/system-architecture-overview.md)
- [Service Responsibility Matrix](design/architecture/service-responsibility-matrix.md)
- [Microservices Documentation](design/architecture/microservices/README.md)
- [System Context Diagram](design/architecture/system-context-diagram.md)
- [Authentication & Authorization](design/architecture/system-architecture-authentication.md)

---

## Docs Map

- [Design Documentation Index](design/README.md) – best entry point for architecture, workflows, project-management docs, and user guides.
- [Product Requirements](design/product/requirements.md) – canonical product requirements and platform scope.
- [User Journeys](design/product/user-journeys/overview.md) – player, creator, and operator flows.
- [Infrastructure Overview](design/architecture/infrastructure/README.md) – deployment environments and shared systems.
- [FAQ](FAQ.md) – quick context on licensing and common questions.
- [LICENSING.md](LICENSING.md) – plain-language licensing lane guidance.
- [HOSTED_CONTENT_TERMS.md](HOSTED_CONTENT_TERMS.md) – pre-launch policy baseline for official hosted creator-content terms.
- [TRADEMARKS.md](TRADEMARKS.md) – FireMUD and FireDevOps brand-use policy.

---

## Getting Started

- For local environment setup, Docker Compose workflows, and developer tooling, see [Developer Setup](DEVELOPER_SETUP.md).
- For architecture-first orientation, start with [design/README.md](design/README.md) and then the [System Architecture Overview](design/architecture/system-architecture-overview.md).
- For creator-facing platform usage, see the [Game Creator Guide](design/user-guides/game-creator-guide.md).

---

## Contributing

- Code contributions, workflow expectations, and PR guidance live in [CONTRIBUTING.md](CONTRIBUTING.md).
- Repository contributions from an external individual or legal entity require an accepted [Contributor Licence Agreement](CONTRIBUTOR_LICENSE_AGREEMENT.md) before merge; current-rights-holder and configured-automation work follow the exceptions in the contribution guide.
- AI-specific repository conventions live in [AGENTS.md](AGENTS.md).
- Community expectations live in [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
- Security issues should be reported privately to [security@firedevops.net](mailto:security@firedevops.net), not filed as public issues.

## Support Us

Your support can make a significant difference in the development and success of the FireMUD Game Platform. If you're interested in supporting the project, here are some ways you can help:

- **Contribute**: See the [Getting Started](#getting-started) and [Contributing](#contributing) sections for ways to contribute code, documentation, or ideas.
- **Spread the Word**: Share the project with friends, colleagues, and on social media platforms to help us reach a wider audience.
- **Financial Contributions**: Help fund ongoing development via [GitHub Sponsors](https://github.com/sponsors/benhook1013).

  *Note: These contributions are paid directly to Benjamin James Hook for FireMUD development and do not authorize a community operator to receive money or other commercial benefit connected to operating an instance.*

---

## Contact

- **General Contact**: [firemud@firedevops.net](mailto:firemud@firedevops.net)
- **Security Reports**: [security@firedevops.net](mailto:security@firedevops.net)
- **GitHub Repository**: [github.com/benhook1013/FireMUD](https://github.com/benhook1013/FireMUD)
- **FireDevOps.net**: [firedevops.net](https://firedevops.net)

---

## Acknowledgments

- **MUD Community**: A heartfelt thank you to the MUD game community and the developers of classic MUD platforms. Your creativity and dedication have inspired us to build upon the rich legacy of text-based gaming.
- **Open-Source Community**: Inspired by the collaborative spirit of open-source development.
