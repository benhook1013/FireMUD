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

---

## 🧭 Usage

All service-level design documents should refer to this directory for shared infrastructure context, rather than duplicating gateway, deployment, or protocol behavior.

For example:

> See [**Gateway Architecture**](./gateway-architecture.md), [**Deployment Environments**](./deployment-environments.md), or [**Protocol Bridging**](./protocol-bridging.md) for relevant infrastructure details.
> Redis-backed session state is described in detail in [**Redis Architecture**](../system-architecture-redis.md).
