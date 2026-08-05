# Account Service

## Overview

Manages user accounts and authentication for the platform. It stores profile data and is the sole service that creates and signs JWTs. These tokens authorize access to meta/control services, first-party player bootstrap, and gameplay connect-token issuance. First-party gameplay clients call Account Service-owned bootstrap and discovery surfaces through the Gateway before opening `/ws/game/**`; Game Session then consumes the gateway-verified connect context during in-band `LOGIN` and `PLAY`. Credential-bearing Telnet and non-bootstrap gameplay login still reaches Account Service indirectly through Game Session's internal `Authenticate` call.

## Implementation Status

- Provider-specific external identity linking is target-state only; no provider is advertised until its server-verified authorization, global subject uniqueness, recovery, unlink, and end-to-end login proof are complete.
- The current caller-asserted external-link scaffold is unsupported implementation drift, not an advertised provider integration.

### Responsibilities

- Registration and login flows, including password resets
- Issuing exact short-lived JWT profiles for permitted destinations, including:
  - `control-ui` JWTs for first-party admin/creator web UIs via `/auth/login`;
  - `player-bootstrap` JWTs for first-party gameplay bootstrap; and
  - receiver-specific private player-delegation JWTs only where an approved workload must carry Account authority, currently `game-session-account-delegation` for Account Service; workload-only gRPC uses mTLS without a bearer token
- Issuing first-party player bootstrap tokens and gameplay connect tokens for `/ws/game/**` admission.
- Tracking tenant-scoped profiles and achievements.
- Managing subscription status and ban enforcement.
- Self-service account recovery for compromised or lost credentials.
- Account-selected `PASSWORD` and verified-email `EMAIL_OTP` login modes.

## Key Features

- Account registration and login.
- Profile management and email notifications.
- Profiles store a display name, bio, game history, and achievements.
- Password reset and verification flows.
- Subscription tracking with ban management.
- Handles payment processing via **Stripe** for one-time purchases and recurring subscriptions.
- Link accounts to player characters for ownership and permissions.
- gRPC APIs cover authentication, account lifecycle, export/delete, runtime/admission, membership, realm-grant, entitlement, profile, and payment operations. The canonical API inventory is [API Contracts](./api-contracts.md).

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
- [User Journeys – Sign Up](../../../product/user-journeys/players.md#1-sign-up)
- [User Journeys – Purchases and Subscriptions](../../../product/user-journeys/players.md#6-purchases-and-subscriptions)
- [User Journeys – Account Data Export](../../../product/user-journeys/players.md#9-account-data-export)
- [User Journeys – Account Deletion](../../../product/user-journeys/players.md#10-account-deletion)
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
