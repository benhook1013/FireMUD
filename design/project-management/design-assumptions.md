# FireMUD Design Assumptions

This document is a short orientation note for the default architectural assumptions behind FireMUD. It is not the source of truth for detailed design decisions; use the architecture docs under [`design/architecture/`](../architecture/) for canonical contracts and current target-state behavior.

## Product and Platform Shape

- FireMUD is a multi-tenant MUD hosting platform.
- The platform supports creator-managed games, shared platform accounts, and per-game characters.
- The runtime is text-first and supports both WebSocket and Telnet gameplay clients.
- The system is designed as a microservice platform with strong service ownership boundaries.

## Technical Defaults

- Backend: Java 21+, Spring Boot, gRPC, PostgreSQL, Redis.
- Frontend: React and TypeScript.
- Deployment: Docker and Kubernetes.
- Internal service-to-service communication: gRPC secured with mTLS.
- External/browser gameplay ingress: Spring Cloud Gateway.
- Telnet ingress: TCP Proxy Service bridging into the shared gameplay path.

## Data and Runtime Defaults

- PostgreSQL is the authoritative system of record.
- Redis is used only for transient coordination, session state, and cache/rate-limit behavior defined in the architecture docs.
- Game Session owns gameplay-session ingress, session binding, and tick coordination responsibilities.
- Design-time data and runtime data are intentionally separated.

## Operational Defaults

- Observability uses Prometheus, Grafana, OpenTelemetry, and centralized log aggregation.
- CI/CD runs through GitHub Actions.
- TLS and mTLS certificate management is based on cert-manager in Kubernetes-oriented environments.
- Backups, recovery, deployment, and rollback behavior are defined in the operations architecture docs rather than here.

## How To Use This File

- Use this file when you need a quick “what kind of system is this?” summary.
- Do not use this file as a planning document, implementation checklist, or architecture authority.
- When this file and the architecture docs disagree, the architecture docs win.
