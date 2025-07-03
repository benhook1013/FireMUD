# 📚 FireMUD Infrastructure Documentation

This directory contains core documentation for the shared infrastructure that powers the FireMUD platform. These documents provide architecture, deployment, and integration guidance across all services.

---

## 🔀 Core Infrastructure Docs

| Document                          | Description                                                                 |
|-----------------------------------|-----------------------------------------------------------------------------|
| [Gateway Architecture](./gateway-architecture.md)       | Details on Spring Cloud Gateway routing, WebSocket support, and service access. |
| [Deployment Environments](./deployment-environments.md) | Describes how Docker Compose and Kubernetes are used in dev/prod setups.   |
| [Protocol Bridging](./protocol-bridging.md)             | Explains how FireMUD supports both WebSocket and Telnet clients through a unified backend. |
| [Redis Architecture](../system-architecture-redis.md)   | Describes where Redis is deployed and how session state is stored. |
| [CI/CD Pipeline](../system-architecture-cicd.md)        | Overview of GitHub Actions workflows for building, testing, and deployment. |
| [System Architecture Overview](../system-architecture-overview.md) | High-level design with observability and service interactions. |
| [System Architecture Diagram](../system-architecture-diagram.md) | Visual representation of component relationships and client flows. |
| [Security Architecture](../system-architecture-security.md) | TLS termination, mTLS usage, and network policy overview. |
| [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) | Snapshot schedules and restore workflow. |
| [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md) | Conventions for service APIs. |

---

### 🌐 Network Boundary and Certificates

The **Spring Cloud Gateway** and **TCP Proxy Service** sit in a DMZ behind the external load balancer. TLS and mTLS certificates for all services are issued by **cert-manager** and stored as Kubernetes Secrets.

### 🏢 Multi-Tenant Deployment

All games share the same Kubernetes cluster and infrastructure. Databases use per-service schemas keyed by `tenantId`; no tenant-specific clusters exist. See [Multi-Tenancy](../system-architecture-multi-tenancy.md) for more.

## 📜 Logging Stack

Logs from each service are collected by Fluent Bit sidecars and forwarded to Elasticsearch for indexing and search. See [Logging & Admin Service](../microservices/logging-admin-service/README.md) for dashboards and moderation tools.

## 🧭 Usage

All service-level design documents should refer to this directory for shared infrastructure context, rather than duplicating gateway, deployment, or protocol behavior.

For example:

> See [**Gateway Architecture**](./gateway-architecture.md), [**Deployment Environments**](./deployment-environments.md), or [**Protocol Bridging**](./protocol-bridging.md) for relevant infrastructure details.
> Redis-backed session state is described in detail in [**Redis Architecture**](../system-architecture-redis.md).
> Observability and metrics integrations are outlined in the [**System Architecture Overview**](../system-architecture-overview.md#📊-observability-and-monitoring).
> Log aggregation using Fluent Bit, Elasticsearch, and Kibana is covered in the [**Log Aggregation**](./deployment-environments.md#📜-log-aggregation) section of **Deployment Environments**.
> Client reconnection flow is covered in the [**Reconnection Strategy**](../system-architecture-reconnection.md).
> TLS, certificate rotation, and network policies are detailed in the [**Security Architecture**](../system-architecture-security.md).
> Backup procedures and disaster recovery steps are outlined in [**Backup & Disaster Recovery**](../system-architecture-backup-recovery.md).
> Service developers should follow the [**gRPC API Style & Versioning Guidelines**](../system-architecture-grpc.md) when defining new APIs.
