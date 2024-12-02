# FireMUD Game Platform - Documentation and Design

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-Under_Development-yellow)]()
[![Java Spring](https://img.shields.io/badge/Backend-Java_Spring_Framework-green)]()
[![React](https://img.shields.io/badge/Frontend-React-blue)]()
[![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL-blue)]()

Welcome to the **FireMUD Game Platform**, a modular and scalable system under the [Fire-DevOps.net](https://fire-devops.net) umbrella for creating and running Multi-User Dungeon (MUD) games.

---

## Table of Contents

- [FireMUD Game Platform - Documentation and Design](#firemud-game-platform---documentation-and-design)
  - [Table of Contents](#table-of-contents)
  - [Purpose](#purpose)
  - [Project Overview](#project-overview)
    - [Key Features](#key-features)
    - [Tech Stack](#tech-stack)
  - [Architecture](#architecture)
    - [Microservices](#microservices)
    - [Service Interactions](#service-interactions)
  - [Design Goals](#design-goals)
  - [Getting Started](#getting-started)
    - [For Designers and Contributors](#for-designers-and-contributors)
    - [For Developers](#for-developers)
  - [Contributing](#contributing)
  - [Contact](#contact)
  - [Acknowledgments](#acknowledgments)

---

## Purpose

This repository serves as the central hub for:

- Documenting the architecture, features, and design of the platform.
- Providing detailed explanations of each microservice and its role.
- Collaborating on design discussions, feature planning, and refinements.

*Note: Code for the individual services and components will be maintained in separate repositories.*

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

- **Backend**: Java Spring Framework
- **Frontend**: React
- **Database**: PostgreSQL
- **Networking**: WebSocket/TCP for real-time communication

---

## Architecture

### Microservices

1. **Game Server Service**
   - Handles game logic, command parsing, real-time interactions.
2. **Entity Management Service**
   - Manages all in-game entities, including players, NPCs, items, and inventory.
3. **Player Management Service**
   - Focuses on account creation, authentication, and authorization.
4. **Game Editor Service**
   - Backend for the integrated game editor used by creators.
5. **Event and Logging Service**
   - Tracks in-game events and logs system metrics.
6. **Chat and Communication Service**
   - Manages player communication and chat moderation.
7. **Moderation and Administration Service**
   - Tools for admins to enforce rules and monitor activity.

### Service Interactions

Detailed diagrams and descriptions are available in the [architecture overview](./architecture/overview.md).

---

## Design Goals

1. **Modularity**: Independently scalable and maintainable services.
2. **Flexibility**: Extensible features for diverse gameplay and customization.
3. **Performance**: Optimized for real-time interactions and low latency.
4. **User-Friendly Creation**: Intuitive tools for game creators.
5. **Community Engagement**: Facilitate a vibrant community of players and creators.
6. **Security**: Ensure data protection and secure interactions across services.

---

## Getting Started

### For Designers and Contributors

- **Review the [Architecture Overview](./architecture/overview.md)** to understand the platform's structure.
- **Explore the [Service Design Documents](./architecture/microservices.md)** for in-depth information on each microservice.
- **Join Discussions**: Participate in ongoing design topics in the `dev_notes/` section.

### For Developers

- **Code Repositories**: Individual services are maintained in separate repositories. Refer to their READMEs for setup instructions.
- **Development Notes**: Check the `dev_notes/` directory for guidelines and best practices.

---

## Contributing

We welcome feedback and contributions to improve the platform's design and functionality. Here's how you can contribute:

- **Report Issues**: If you find a bug or have a feature request, please open an issue in the relevant repository. Make sure to provide detailed information to help us understand and reproduce the issue.

- **Submit Pull Requests**: To contribute code:
  1. **Fork** the repository you wish to contribute to.
  2. **Create a new branch** for your feature or bug fix: `git checkout -b feature/your-feature-name`.
  3. **Commit your changes** with clear and descriptive messages.
  4. **Push to your fork**: `git push origin feature/your-feature-name`.
  5. **Open a pull request** against the `main` branch of the original repository.

- **Join Discussions**: Engage in design and development discussions:
  - Participate in ongoing topics in the `dev_notes/` directory.
  - Join our [Community Forums](https://community.fire-devops.net) to connect with other contributors.
  - Chat with the team on our [Discord Server](https://discord.gg/your-invite-link).

- **Review Code**: Help us by reviewing open pull requests. Constructive feedback is invaluable.

- **Security Vulnerabilities**: If you discover a security vulnerability, please **do not** file a public issue. Instead, report it directly via email to [security@fire-devops.net](mailto:security@fire-devops.net). We take security issues seriously and will respond promptly to address them.

- **Documentation**: Improve our documentation by:
  - Updating existing Markdown files for clarity and accuracy.
  - Adding examples, tutorials, or FAQs.
  - Translating documentation into other languages.

Please see our [Contributing Guidelines](./CONTRIBUTING.md) for more details on our code style, testing practices, and how to set up a development environment.


---

## Contact

- **Project Lead**: Ben
  - **Email**: [ben@fire-devops.net](mailto:ben@fire-devops.net)
  - **GitHub**: [github.com/ben-firedevops](https://github.com/ben-firedevops)
- **Fire-DevOps.net**: [fire-devops.net](https://fire-devops.net)

---

## Acknowledgments

- **Contributors**: Thanks to all the contributors who have helped shape this project.
- **Open-Source Community**: Inspired by the collaborative spirit of open-source development.
- **Classic MUD Platforms**: Building upon the legacy of text-based gaming.

---
