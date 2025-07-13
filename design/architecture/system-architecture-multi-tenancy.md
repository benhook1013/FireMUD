# 🏢 FireMUD System Architecture: Multi-Tenancy

This document explains how FireMUD hosts many independent games on shared infrastructure. It complements the [System Architecture Overview](./system-architecture-overview.md) and the multi-tenant requirements in the [Core Requirements](../project-management/core-requirements.md).

---

## 🔗 Account-to-Game Relationships

- Players have a **single platform account** managed by the **Account Service**.
- The same account can join multiple games. Each game is identified by a `tenantId`.
- Character data and progress are scoped per `tenantId`; a player may have different characters in different games.
- Authentication is global, but services always check the requested `tenantId` when retrieving or updating game data.

## 🗂️ Data Separation per Service

- Every microservice stores data in its own PostgreSQL database schema.
- Databases are **shared across tenants**, with a `tenantId` column on each table to isolate data.
- Services enforce the `tenantId` filter on all queries to prevent cross-game access.
- Redis keys also include the `tenantId` so cached session state and runtime data remain isolated.

## ⚙️ Tenant Configuration & Scaling

- Game-specific settings—such as world size, tick intervals, and feature flags—are stored in configuration tables keyed by `tenantId`.
  Runtime flag behavior is described in [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).
- All microservices run as shared deployments; there is **no tenant-specific infrastructure** or dedicated clusters.
- Game Session Service instances scale horizontally based on overall load. Future improvements may add per-game quotas or scaling limits.

---

> 🔗 For service roles and interactions, see the [System Architecture Overview](./system-architecture-overview.md) and [Service Responsibility Matrix](./service-responsibility-matrix.md).
