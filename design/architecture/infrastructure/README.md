# FireMUD Infrastructure Documentation

This directory contains core documentation for the shared infrastructure that powers the FireMUD platform. These documents provide architecture, deployment, and integration guidance across all services.

---

## Core Infrastructure Docs

| Document | Description |
| --- | --- |
| [System Architecture Overview](../system-architecture-overview.md) | High-level design with observability and service interactions. |
| [System Architecture Diagram](../system-architecture-diagram.md) | Visual representation of component relationships and client flows. |
| [System Context Diagram](../system-context-diagram.md) | Shows clients, DMZ components, internal services, and datastores. |
| [Deployment Environments](./deployment-environments.md) | Defines the canonical environment classes (`local-dev`, `pr-preview`, `dev-demo-cluster`, `hobby-self-hosted`, `staging`, `production`) and their deployment controls. |
| [Environment & Secrets Management](./environment-and-secrets.md) | How services receive configuration values and handle sensitive data. |
| [Gateway Architecture](../system-architecture-gateway.md) | Details on Spring Cloud Gateway routing, WebSocket support, and service access. |
| [Protocol Bridging](../system-architecture-protocol-bridging.md) | Explains how FireMUD supports both WebSocket and Telnet clients through a unified backend. |
| [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md) | Conventions for service APIs. |
| [Redis Architecture](../system-architecture-redis.md) | Describes where Redis is deployed and how session state is stored. |
| [Security Architecture](../system-architecture-security.md) | TLS termination, mTLS usage, and network policy overview. |
| [Database Migrations](../system-architecture-database-migrations.md) | Canonical SQL schema authority, Flyway workflow, and the repo-wide persistence convergence direction. |
| [Temporal Control-Plane Workflows](../system-architecture-temporal-workflows.md) | Canonical durable workflow substrate and the boundary between Temporal and short synchronous saga usage. |
| [CI/CD Pipeline](../system-architecture-cicd.md) | Overview of GitHub Actions workflows for building, testing, and promotion evidence, plus the operator-applied deployment model for staging and production. |
| [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) | Snapshot schedules and restore workflow. |
| [Infrastructure Schedule](./schedule.md) | CI and deployment cadence expectations by environment. |

---

### Network Boundary and Certificates

The **Spring Cloud Gateway** and **TCP Proxy Service** sit in a DMZ behind the external load balancer. TLS and mTLS certificates for all services are issued by **cert-manager** and stored as Kubernetes Secrets.

### Multi-Tenant Deployment

All games share the same Kubernetes cluster and infrastructure. Databases use per-service schemas keyed by `tenantId`; no tenant-specific clusters exist. See [Multi-Tenancy](../system-architecture-multi-tenancy.md) for more.

## Logging Stack

The log aggregation pipeline is summarized in [Logging & Monitoring](../system-architecture-logging-monitoring.md).

## Usage

All service-level design documents should refer to this directory for shared infrastructure context, rather than duplicating gateway, deployment, or protocol behavior. Environment variable definitions and configuration semantics are centralized in [Environment & Secrets Management](./environment-and-secrets.md); prefer linking there instead of redefining per-service env var tables.

## Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Deployment Environments](./deployment-environments.md)
- [Gateway Architecture](../system-architecture-gateway.md)
- [Protocol Bridging](../system-architecture-protocol-bridging.md)
- [Transaction Strategies](../system-architecture-transactions.md)
