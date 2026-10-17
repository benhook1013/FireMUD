# Game Session Service

Design details are kept in the architecture docs:
[Game Session Service Design](../../design/architecture/microservices/game-session-service/README.md)

This README links to the service's canonical design. **Do not duplicate design details here.**

## Current first-party gameplay availability

Ordinary credential-bearing LOGIN and reconnect behavior remain on their existing transport paths. Public first-party `/ws/game/**` admission is unavailable: Gateway cookie admission and route readiness fail closed, while Game Session bare first-party `LOGIN` and `PLAY` deny before legacy Account, Entity, or gameplay-binding effects. Game Session retains complete signed-context and canonical-route/private-handle revalidation sources and rejects partial selector fallback, but this local preparation does not qualify authenticated ingress or playable first-party LOGIN. See [Authentication and authorization](../../design/architecture/system-architecture-authentication.md#gateway-to-game-session-connect-context-normative), [Session Behavior](../../design/architecture/system-architecture-session-behavior.md#session-types-and-lifetimes), and the [Player Access and Session tracker](../../design/project-management/implementation-tracking/player-access-and-session.md#canonical-first-party-receiving-preparation).

## Account Service dependency

- On maintained credential-bearing paths, the Game Session Service delegates credential verification to Account Service `/auth/login` or gRPC `Authenticate` and stores the resulting session data under the canonical authentication and session contracts. This does not make the public first-party `/ws/game/**` receiving path available.
- The login smoke test and local workflows expect either the lightweight Account Service stub or a local Account Service instance to be reachable; without it the `LOGIN <username> <password>` step cannot complete and later `PLAY` / gameplay commands cannot be admitted.

## Local development

- Run the service against the real local dependency stack using the canonical runtime configuration. For end-to-end proof, use the repo smoke scripts under `dev-tools/` rather than a special single-service profile or single-service shortcut.

## Environment variables

- No additional service-local development flags are required for the canonical login/session path. The maintained runtime and test coverage now targets the real Redis/Postgres/downstream-service topology.
