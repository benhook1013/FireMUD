# FireMUD Platform FAQ

This document collects common questions and answers about the FireMUD Game Platform.

---

## General

- **What is the purpose of FireMUD?**
  FireMUD is a modular platform for hosting and creating text-based MUD games. It provides real-time multiplayer services and integrated tools for game creators.

- **Is FireMUD open source?**
  FireMUD is source-available under the PolyForm Noncommercial License 1.0.0. Personal use, private self-hosting, and modification are allowed for noncommercial purposes as described in [LICENSE.md](LICENSE.md). Commercial use requires a separate written agreement.

---

## Development and Contribution

- **How can I contribute to FireMUD?**
  See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution workflow and [DEVELOPER_SETUP.md](DEVELOPER_SETUP.md) for local setup.

- **Where do I find design resources?**
  Start with [design/README.md](design/README.md). It points to the canonical architecture docs, user journeys, and slice-tracking material.

- **How do I get a development environment running?**
  Follow the steps in [**Developer Setup**](DEVELOPER_SETUP.md) to install prerequisites and run `./gradlew devUp`.

- **Where are the API schemas defined?**
  gRPC protobuf files live under the [`protos/`](protos) directory. Each microservice README links to its versioned schemas.

- **Where can I find the roadmap?**
  The active task list is in [design/project-management/task-list.md](design/project-management/task-list.md).

---

## Security and Licensing

- **How do I report a security issue?**
  Use GitHub private vulnerability reporting or email [security@firedevops.net](mailto:security@firedevops.net). See [SECURITY.md](SECURITY.md).

- **Who maintains FireMUD?**
  FireMUD is maintained under the FireDevOps.net umbrella. Current contact details are listed in [README.md](README.md).
