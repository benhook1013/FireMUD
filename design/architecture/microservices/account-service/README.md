# Account Service

## Overview

Manages user accounts and authentication for the platform. It stores profile data and is the sole service that creates and signs JWTs. These tokens authorize access to meta/control services. The Game Session Service relies on Redis session context for gameplay and may request an updated token when a player's roles change. Public login APIs exist for administrators and account portals, but gameplay clients reach them indirectly through the Game Session Service rather than calling the Gateway directly.

### Responsibilities

- Registration and login flows, including password resets
- Issuing short-lived JWT tokens for internal meta/control APIs, including:
  - Browser JWTs for first-party admin/creator web UIs via `/auth/login`, and
  - Service JWTs for backend gRPC callers via internal authentication flows
- Tracking profiles, OAuth2 social logins, external account links, and achievements.
- Managing subscription status and ban enforcement.
- Self-service account recovery for compromised or lost credentials.
- Optional two-factor authentication for admin and moderator roles.

## Key Features

- Account registration and login.
- Profile management and email notifications.
- Profiles store a display name, bio, game history, and achievements.
- Password reset and verification flows.
- Subscription tracking with ban management.
- External account linking (Google, Discord, Steam) allows unified logins.
- Handles payment processing via **Stripe** for one-time purchases and recurring subscriptions.
- Link accounts to player characters for ownership and permissions.
- gRPC APIs expose account management, external account linking, and payment operations.

## Document Map

- [API Contracts](./api-contracts.md)
  - public and internal auth/account/payment surfaces, canonical errors, and proto/OpenAPI ownership.
- [Runtime and Data](./runtime-and-data.md)
  - JWT issuance, session-related data ownership, PostgreSQL/Redis boundaries, and account-state invariants.
- [Operations](./operations.md)
  - readiness/liveness, operational notes, and local verification or testing guidance.
- [Configuration](./configuration.md)
  - environment variables, service discovery, TLS, and service-local configuration source locations.
- [Subscription Management Design](./subscription-management.md)
  - subscription lifecycle and entitlement-specific design detail.
- [Stripe Integration Design](./stripe-integration.md)
  - Stripe-specific payment and webhook integration contracts.

## Dependencies

- **Internal:**
  - Logging & Admin Service for audit logging.
  - Game Session Service consumes tokens to create gameplay sessions.
- **External:** PostgreSQL for account data, Redis for transient session data.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Related Documentation

- [Subscription Management Design](./subscription-management.md)
- [Stripe Integration Design](./stripe-integration.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Security Architecture](../../system-architecture-security.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [System Architecture Overview](../../system-architecture-overview.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Sign Up](../../user-journeys-players.md#1-sign-up)
- [User Journeys – Purchases and Subscriptions](../../user-journeys-players.md#5-purchases-and-subscriptions)
- [User Journeys – Account Data Export & Deletion](../../user-journeys-players.md#8-account-data-export--deletion)
- [Redis Architecture](../../system-architecture-redis.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Database Migrations](../../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
