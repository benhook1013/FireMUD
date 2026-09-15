# FireMUD

[![Status: Under Development](https://img.shields.io/badge/Status-Under_Development-yellow)](./design/project-management/implementation-tracking/README.md)
[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm_Noncommercial_1.0.0-blue.svg)](LICENSE.md)
[![CI](https://github.com/benhook1013/FireMUD/actions/workflows/ci.yml/badge.svg)](https://github.com/benhook1013/FireMUD/actions/workflows/ci.yml)

FireMUD is a platform for creating and running persistent multiplayer text worlds—Multi-User Dungeons, or MUDs. Its design brings together gameplay, world creation, and hosting, with access through web clients and Telnet.

FireMUD is under active development. See [implementation status](design/project-management/implementation-tracking/README.md) for current progress.

## Architecture and Stack

The architecture centers on Spring Boot services communicating over gRPC. Spring Cloud Gateway provides the HTTP and WebSocket edge, while the TCP Proxy Service bridges Telnet into the gameplay path used by web clients.

The core stack includes:

- Java and Spring Boot
- React with Material-UI
- PostgreSQL and Redis
- WebSocket and TCP networking
- Docker Compose for local environments and Kubernetes for deployments
- GitHub Actions for CI/CD

## Getting Started

For local environment setup, Docker Compose workflows, and developer tooling, see [Developer Setup](DEVELOPER_SETUP.md).

## Documentation

- [Design Documentation Index](design/README.md) – entry point for product, architecture, operations, workflows, and user guides.
- [System Architecture Overview](design/architecture/system-architecture-overview.md) – platform model and technical contracts.
- [Game Creator Guide](design/user-guides/game-creator-guide.md) – creator-facing platform guidance.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution, workflow, and pull request guidance. External contributions require an accepted [Contributor Licence Agreement](CONTRIBUTOR_LICENSE_AGREEMENT.md) before merge.

## License

FireMUD uses the [PolyForm Noncommercial License 1.0.0](LICENSE.md). Commercial use not otherwise permitted by `LICENSE.md` or applicable law requires a separate written agreement; see [LICENSING.md](LICENSING.md) for plain-language guidance and links to related hosted-content and trademark terms. The [FAQ](FAQ.md) answers common licensing questions.

## Contact and Support

- General: [firemud@firedevops.net](mailto:firemud@firedevops.net)
- Private security reports: [security@firedevops.net](mailto:security@firedevops.net)
- Sponsorship: [GitHub Sponsors](https://github.com/sponsors/benhook1013)
- Project site: [FireDevOps.net](https://firedevops.net)
