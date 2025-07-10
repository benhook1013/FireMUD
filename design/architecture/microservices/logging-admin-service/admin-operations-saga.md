# 🚦 Admin Operations Saga

Certain moderation actions require coordination across multiple services. The Logging & Admin Service uses the shared `SagaBuilder` from `firemud-common` to orchestrate these workflows.

## Use Cases

- **Account bans** – disable login via the Account Service, terminate active sessions through the Game Session Service, and remove social posts via the Social & Groups Service.
- **Content takedowns** – revoke problematic items or rooms by calling the Entity Management and World Management services.

## Flow Overview

1. Moderators trigger an admin command via the REST API or admin UI.
2. The service builds a saga sequence with compensating actions for each step.
3. Saga state is persisted in the `saga_instance` and `saga_step` tables so progress can be tracked.
4. Metrics for active sagas and failures are exported through `SagaMetrics`.
5. Operators inspect progress in the built‑in Saga Dashboard.

This approach keeps complex admin actions consistent across services while providing transparency and retry logic. See [Transaction Strategies](../system-architecture-transactions.md) for details on the saga engine.
