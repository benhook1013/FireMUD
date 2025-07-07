# 🚀 MUD Game Platform Development To-Do List

This checklist is structured to **build foundational features first**, followed by **gameplay mechanics, multiplayer, administration, and optimizations**.
Service-specific tasks are tracked in separate files within this folder. Quick links:

- [Account Service](task-list-account-service.md)
- [Automation & Scripting Service](task-list-automation-scripting-service.md)
- [Entity Management Service](task-list-entity-management-service.md)
- [Game Design Service](task-list-game-design-service.md)
- [Game Logic Service](task-list-game-logic-service.md)
- [Game Session Service](task-list-game-session-service.md)
- [Logging & Admin Service](task-list-logging-admin-service.md)
- [Social & Groups Service](task-list-social-groups-service.md)
- [Spring Cloud Gateway](task-list-spring-cloud-gateway.md)
- [TCP Proxy Service](task-list-tcp-proxy-service.md)
- [World Management Service](task-list-world-management-service.md)

- [Common Microservice Tasks](task-list-common.md)

## 📋 Phase 0: Project Planning

- [x] **Define Vision & Scope of the Platform**
  - [x] Write a high-level product vision and key goals
  - [x] Create phased development plan

- [x] **Establish Naming Conventions & Folder Structure**
  - [x] Standardize service names, package structure, and code conventions
  - [x] Document folder and repo layout for multi-service organization

- [x] **Draft Technical Architecture Diagrams**
  - [x] High-level service map
  - [x] Data flow diagrams between client/editor/server
  - [x] Deployment architecture (e.g., Kubernetes clusters, CI/CD flow)

- [x] **Miscellaneous**
  - [x] Write initial design for each microservice
  - [x] Investigate transaction support for microservices
    - See [Transaction Strategies](../architecture/system-architecture-transactions.md)
    - [x] Document gRPC endpoints and compensating actions
    - [x] Describe Saga orchestration components in the shared library
    - [x] Provide example workflows (e.g., user registration)
  - [x] Update README after all services are defined
  - [x] Finalize architecture design documentation and diagrams
  - [x] Document service responsibility matrix
  - [x] Remove legacy Game Management Service and redistribute duties
  - [x] Conduct final review of all design documentation
  - [x] Address any missing diagrams or cross-references discovered during review
  - [x] Expand `CONTRIBUTING.md` with onboarding instructions
  - [x] Populate `FAQ.md` with common questions
  - [x] Add service-level design README links to central architecture docs
  - [x] Publish a `CODE_OF_CONDUCT.md` outlining community expectations
  - [x] Create issue template and maintain backlog for tasks and bugs
  - [x] Provide contributor guide with local setup commands and code review expectations
  - [x] Document environment variables and secrets management strategy

---

## 🛠️ Phase 1: Core Infrastructure & Basic Services

### Behavior and Orchestration Planning

- [x] Define core service responsibilities and runtime behaviors
  - [x] Outline tick flow, session management, reconnect logic, and command execution
  - [x] Document game instance lifecycle diagrams
- [x] Write sample gameplay use cases and trace the end-to-end flow
  - [x] Example flows: LOGIN, MOVE, CAST_SPELL
- [x] Identify the data each service needs to handle those flows
- [x] Derive minimal data models and proto schemas based on real usage
- [x] Refine shared DTOs and gRPC contracts from concrete examples
- [x] Document Redis key naming conventions and locking scheme

### Web Frontend

- [ ] Scaffold React-based MUD client with Vite and Material-UI
- [ ] Build web-based game editor for game creators
- [ ] Configure ESLint and Prettier for consistent formatting
- [ ] Add pre-commit hooks for frontend linting
- [ ] Add accessibility checks (Axe or Lighthouse) to CI
- [ ] Convert React frontend to TypeScript for type safety
- [ ] Run ESLint and Prettier checks in GitHub Actions

### ✅ Common Steps for All Microservices (Non-Infrastructure)

See [task-list-common.md](task-list-common.md) for tasks shared across all services.

---

## 🛠️ Phase 2: Testing & Pre-Launch Preparations

- [ ] **Write Developer Documentation for Game Creators**
  - [ ] Provide API references for scripting & integration
  - [ ] Guide for setting up and configuring hosted games

---

## 🛠️ Phase 3: Deployment & Post-Launch Iteration

- [ ] **Iterate on Features & Add More Game Customization**
  - [ ] Expand game customization options for hosted games
  - [ ] Improve scripting capabilities & developer tools
- [ ] **Onboard Game Creators & Improve UX**
  - [ ] Develop tutorials & guides for game creators on customizing worlds and configuring hosted games
  - [ ] Gather feedback from early users & iterate on UI/UX
  - [ ] Add MCP support for AI assisted game creation

## 🛠️ Phase 4: Community & Funding

- [ ] Set up financial contribution options
  - [ ] Add PayPal donation link
  - [ ] Configure GitHub Sponsors profile
- [ ] Create Patreon page

---

## ➕ Additional Tasks

- [ ] Provide command-line tooling for local game and session management
- [ ] Plan for **end-to-end UI testing** using Cypress or Playwright once the
  web UI is stable
- [ ] Evaluate localization and internationalization support for the React client
