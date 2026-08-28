# FireMUD Platform FAQ

This document collects common questions and answers about the FireMUD Game Platform.

---

## General

- **What is the purpose of FireMUD?**
  FireMUD is a modular platform for hosting and creating text-based MUD games. It provides real-time multiplayer services and integrated tools for game creators.

- **Is FireMUD open source?**
  FireMUD is source-available under the PolyForm Noncommercial License 1.0.0. Personal use, private self-hosting, and modification are allowed for noncommercial purposes as described in [LICENSE.md](LICENSE.md). See [LICENSING.md](LICENSING.md) for plain-language guidance; commercial use not otherwise permitted by LICENSE.md or applicable law requires a separate written agreement with Benjamin James Hook.

- **May a community-hosted server receive money?**
  Not for an ordinary community operator. Public and private community instances follow the project's recognized and supported ordinary-community lane: the strict noncommercial, no-money rule in [LICENSING.md](LICENSING.md). [LICENSE.md](LICENSE.md) alone determines legal rights, including its separately stated institutional permissions; this plain-language guide does not narrow them. Benjamin James Hook may separately receive development sponsorship; that does not authorize a community operator to receive money or another commercial benefit connected to operating an instance.

- **What terms govern creator content on the official hosted service?**
  [HOSTED_CONTENT_TERMS.md](HOSTED_CONTENT_TERMS.md) is the pre-launch policy baseline, not an operative creator contract. The actual hosted operator must be identified and applicable terms accepted through the hosted creator flow. Future creator-marketplace, player-purchase, and settlement terms remain deferred; hosting-plan and platform-subscription billing is a separate planned lane.

- **May I name my community server or fork FireMUD?**
  Use your own primary name and make clear that an ordinary community server or fork is independent and unofficial. Any descriptive reference is conditional on the [TRADEMARKS.md](TRADEMARKS.md) policy; that policy does not grant official status or endorsement.

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

- **Where can I find current implementation status?**
  Domain capability status, active gaps, and remaining decisions are in [design/project-management/implementation-tracking](design/project-management/implementation-tracking/README.md).

---

## Security and Licensing

- **How do I report a security issue?**
  Use GitHub private vulnerability reporting or email [security@firedevops.net](mailto:security@firedevops.net). See [SECURITY.md](SECURITY.md).

- **Who maintains FireMUD?**
  FireMUD is developed and maintained by Benjamin James Hook under the FireDevOps project brand. An official hosted service is a planned target and is not currently operating. FireDevOps is the brand and firedevops.net is its website, not a separate legal entity. Current contact details are listed in [README.md](README.md).
