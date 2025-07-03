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

---

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
