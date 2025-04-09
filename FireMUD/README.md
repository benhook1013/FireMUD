# FireMUD Game Platform - Documentation and Design

[![Status: Under Development](https://img.shields.io/badge/Status-Under_Development-yellow)](./FireMUD_Design/project_management/task_list.md)
[![License: Business Source License 1.1](https://img.shields.io/badge/License-Business_Source_License_1.1-blue.svg)](LICENSE)

[![Backend: Java Spring](https://img.shields.io/badge/Backend-Java_Spring_Framework-green)](https://spring.io/)
[![Frontend: React](https://img.shields.io/badge/Frontend-React-blue)](https://react.dev/)
[![Database: PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL-blue)](https://www.postgresql.org/)
[![Database: Redis](https://img.shields.io/badge/Database-Redis-blue)](https://redis.io/)

[![Containerization: Docker](https://img.shields.io/badge/Containerization-Docker-blue)](https://www.docker.com/)
[![Orchestration: Kubernetes](https://img.shields.io/badge/Orchestration-Kubernetes-blue)](https://kubernetes.io/)


Welcome to the **FireMUD Game Platform**, a modular and scalable system under the [Fire-DevOps.net](https://fire-devops.net) umbrella for creating and running Multi-User Dungeon (MUD) games.

*This project is licensed under the [Business Source License 1.1](LICENSE). For common questions, please refer to our [FAQ](FAQ.md).*

---

## Table of Contents

- [FireMUD Game Platform - Documentation and Design](#firemud-game-platform---documentation-and-design)
  - [Table of Contents](#table-of-contents)
  - [Purpose](#purpose)
  - [Project Overview](#project-overview)
    - [Key Features](#key-features)
    - [Tech Stack](#tech-stack)
    - [Design Goals](#design-goals)
  - [Architecture](#architecture)
    - [Microservices](#microservices)
    - [Service Interactions](#service-interactions)
  - [Getting Started and Contributing](#getting-started-and-contributing)
    - [For Designers and Contributors](#for-designers-and-contributors)
    - [For Developers](#for-developers)
  - [Support Us](#support-us)
  - [Contact](#contact)
  - [Acknowledgments](#acknowledgments)
    - [Notes and TODOs](#notes-and-todos)

---

## Purpose

This repository serves as the **central mono-repo** for the FireMUD Game Platform, containing:

- All microservices and shared utilities.
- Documentation of the architecture, features, and design.
- Collaborative design discussions, feature planning, and refinements.

---

## Project Overview

### Key Features

- **Real-Time Game Server**: Backend for gameplay mechanics, player actions, and world state management.
- **Integrated Game Editor**: Tools for creators to design rooms, entities, quests, and dialogues.
- **Microservice Architecture**: Modular services for scalability and maintainability.
- **Web Frontend**: React-based interface for players and creators.
- **Extensible Command Parsing**: Flexible system to interpret player commands.
- **Dynamic Scripting Support**: For events and interactions within the game world.
- **Multi-Server Hosting**: Support for hosting multiple MUD games simultaneously.
- **Moderation Tools**: Comprehensive tools for administrators and moderators.

### Tech Stack

#### Backend

- **Framework**: Java Spring Framework
- **Database**: PostgreSQL
- **Caching**: Redis
- **Networking**: WebSocket/TCP
- **API Gateway**: Spring Cloud Gateway

#### Frontend

- **Framework**: React
- **Styling**: Tailwind CSS or Material-UI

#### Deployment & Infrastructure

- **Containerization**: Docker
- **Orchestration**: Kubernetes
- **CI/CD**: GitHub Actions or Jenkins
- **Monitoring**: Prometheus, Grafana, Loki
- **Logging**: ELK Stack (Elasticsearch, Logstash, Kibana)

#### Monetization

- **Payment Gateway**: Stripe
- **Subscription Management**: Custom integration

#### Testing

- **Unit Testing**: JUnit, Mockito
- **Integration Testing**: Spring Test, Postman
- **Load Testing**: JMeter or Gatling

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

1. **Game Management Service**
   - Handles game creation, rules, templates, versioning, and moderation policies.
1. **World Management Service**
   - Manages world maps, regions, and procedural generation.
1. **Account Service**
   - Handles user accounts, authentication, and profiles.
1. **Entity Management Service**
   - Manages in-game entities, including NPCs, items, and inventory.
1. **Game Logic Service**
   - Implements core game mechanics and rules.
1. **Automation & Scripting Service**
   - Handles AI behaviors, scripted events, and dynamic interactions.
1. **Social and Groups Service**
   - Manages player groups, chat, and social features.
1. **Logging & Admin Service**
   - Provides centralized logging and administration tools.
1. **Networking & Gateway Service**
   - Handles real-time networking and API gateway functionality.
1. **Game Design Service**
   - Provides tools for game editing and customization.

### Service Interactions

For detailed architecture diagrams and explanations, refer to the [Architecture Overview](FireMUD_Design/architecture/architecture_overview.md).

---

## Getting Started and Contributing

We welcome feedback and contributions to improve the platform's design and functionality. Here's how you can get started and contribute:

### For Designers and Contributors

- **Review the [System Architecture Overview](FireMUD_Design/architecture/system-architecture-overview.md)** to understand the platform's service layout, communication flows, and overall client-to-backend structure.
- **Review the [Infrastructure Overview](FireMUD_Design/infrastructure/_index.md)** to understand the platform's shared systems, deployment environments, and networking architecture.
- **Explore the [Service Design Documents](FireMUD_Design/architecture/microservices/index.md)** for in-depth information on each microservice.
- **Documentation**: Help improve our documentation by:
  - Updating existing Markdown files for clarity and accuracy.
  - Adding examples, tutorials, or FAQs.

### For Developers

- **Code Repositories**: Individual services are maintained in separate repositories. Refer to their READMEs for setup instructions.
- **Submit Pull Requests**: To contribute code:
  1. **Fork** the repository you wish to contribute to.
  2. **Create a new branch** for your feature or bug fix: `git checkout -b feature/your-feature-name`.
  3. **Commit your changes** with clear and descriptive messages.
  4. **Push to your fork**: `git push origin feature/your-feature-name`.
  5. **Open a pull request** against the `main` branch of the original repository.
- **Report Issues**: If you find a bug or have a feature request, please open an issue in the relevant repository with detailed information.
- **Review Code**: Help us by reviewing open pull requests. Constructive feedback is invaluable.
- **Security Vulnerabilities**: If you discover a security vulnerability, please **do not** file a public issue. Instead, report it directly via email to [Ben.Hook@fire-devops.net](mailto:Ben.Hook@fire-devops.net). We take security issues seriously and will respond promptly to address them.

Please see our [Contributing Guidelines](./CONTRIBUTING.md) for more details on code style, testing practices, and setting up a development environment.

---

## Support Us

Your support can make a significant difference in the development and success of the FireMUD Game Platform. If you're interested in supporting the project, here are some ways you can help:

- **Spread the Word**: Share the project with friends, colleagues, and on social media platforms to help us reach a wider audience.
- **Contribute**: See the [Getting Started and Contributing](#getting-started-and-contributing) section for ways to contribute code, documentation, or ideas.
- **Financial Contributions**: *[TODO: Set up financial contribution options]*

  We plan to set up options for financial support in the near future, including:

  - **Donate via PayPal**: *[Coming Soon]* <!-- TODO: Add PayPal donation link -->
  - **Sponsor on GitHub**: *[Coming Soon]* <!-- TODO: Set up GitHub Sponsors profile -->
  - **Patreon**: *[Coming Soon]* <!-- TODO: Create Patreon page -->

  *Note: Financial contributions will be used to cover development costs, hosting, and other expenses related to the project.*

---

## Contact

- **Project Lead**: Ben Hook
  - **Email**: [Ben.Hook@fire-devops.net](mailto:Ben.Hook@fire-devops.net)
  - **GitHub**: [github.com/benhook1013](https://github.com/benhook1013)
- **Fire-DevOps.net**: [fire-devops.net](https://fire-devops.net)

---

## Acknowledgments

- **MUD Community**: A heartfelt thank you to the MUD game community and the developers of classic MUD platforms. Your creativity and dedication have inspired us to build upon the rich legacy of text-based gaming.
- **Open-Source Community**: Inspired by the collaborative spirit of open-source development.

---

### Notes and TODOs

- **Financial Contribution Links**: The links for PayPal, GitHub Sponsors, and Patreon are placeholders and need to be set up. Once available, replace the *[Coming Soon]* text and placeholder comments with the actual links.
- **CONTRIBUTING.md**: Ensure the `CONTRIBUTING.md` file is created and contains relevant guidelines.
- **FAQ.md**: Populate the `FAQ.md` document with common questions and answers.
- **Architecture Diagrams**: Complete the architecture diagrams and place them in the `architecture/` directory.

---
