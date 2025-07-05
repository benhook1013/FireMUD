# 📚 FireMUD Infrastructure Documentation

This directory contains core documentation for the shared infrastructure that powers the FireMUD platform. These documents provide architecture, deployment, and integration guidance across all services.

---

## 🔀 Core Infrastructure Docs

| Document                          | Description                                                                 |
|-----------------------------------|-----------------------------------------------------------------------------|
| [System Architecture Overview](../system-architecture-overview.md) | High-level design with observability and service interactions. |
| [System Architecture Diagram](../system-architecture-diagram.md) | Visual representation of component relationships and client flows. |
| [System Context Diagram](../system-context-diagram.md) | Shows clients, DMZ components, internal services, and datastores. |
| [Deployment Environments](./deployment-environments.md) | Describes how Docker Compose and Kubernetes are used in dev/prod setups.   |
| [Gateway Architecture](./gateway-architecture.md)       | Details on Spring Cloud Gateway routing, WebSocket support, and service access. |
| [Protocol Bridging](./protocol-bridging.md)             | Explains how FireMUD supports both WebSocket and Telnet clients through a unified backend. |
| [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md) | Conventions for service APIs. |
| [Redis Architecture](../system-architecture-redis.md)   | Describes where Redis is deployed and how session state is stored. |
| [Security Architecture](../system-architecture-security.md) | TLS termination, mTLS usage, and network policy overview. |
| [CI/CD Pipeline](../system-architecture-cicd.md)        | Overview of GitHub Actions workflows for building, testing, and deployment. |
| [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) | Snapshot schedules and restore workflow. |

---

### 🌐 Network Boundary and Certificates

The **Spring Cloud Gateway** and **TCP Proxy Service** sit in a DMZ behind the external load balancer. TLS and mTLS certificates for all services are issued by **cert-manager** and stored as Kubernetes Secrets.

### 🏢 Multi-Tenant Deployment

All games share the same Kubernetes cluster and infrastructure. Databases use per-service schemas keyed by `tenantId`; no tenant-specific clusters exist. See [Multi-Tenancy](../system-architecture-multi-tenancy.md) for more.

## 📜 Logging Stack

The log aggregation pipeline is summarized in [Logging & Monitoring](../system-architecture-logging-monitoring.md).

## 🧭 Usage

All service-level design documents should refer to this directory for shared infrastructure context, rather than duplicating gateway, deployment, or protocol behavior.

For example:

> See [**Gateway Architecture**](./gateway-architecture.md), [**Deployment Environments**](./deployment-environments.md), or [**Protocol Bridging**](./protocol-bridging.md) for relevant infrastructure details.
> Redis-backed session state is described in detail in [**Redis Architecture**](../system-architecture-redis.md).
> Observability integrations are summarized in [**Logging & Monitoring**](../system-architecture-logging-monitoring.md).
> Client reconnection flow is covered in the [**Reconnection Strategy**](../system-architecture-reconnection.md).
> TLS, certificate rotation, and network policies are detailed in the [**Security Architecture**](../system-architecture-security.md). Example manifests live in [`k8s/network-policies/`](../../../k8s/network-policies) and provide a default ingress policy for internal services.
> Backup procedures and disaster recovery steps are outlined in [**Backup & Disaster Recovery**](../system-architecture-backup-recovery.md).
> Service developers should follow the [**gRPC API Style & Versioning Guidelines**](../system-architecture-grpc.md) when defining new APIs.
> Distributed workflows are explained in [**Transaction Strategies**](../system-architecture-transactions.md).

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Deployment Environments](./deployment-environments.md)
- [Gateway Architecture](./gateway-architecture.md)
- [Protocol Bridging](./protocol-bridging.md)
- [Transaction Strategies](../system-architecture-transactions.md)
