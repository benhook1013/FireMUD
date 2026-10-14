# Spring Cloud Gateway Service

The gateway's design documentation lives at:
[📄 Spring Cloud Gateway Design](../../design/architecture/microservices/spring-cloud-gateway/README.md)

This README links to the Gateway's canonical design. **Do not duplicate design details here.**

## Current first-party gameplay availability

Public first-party `/ws/game/**` cookie admission and gameplay-route readiness remain unavailable. The filter returns `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` for a presented first-party cookie and does not mint or replay the legacy HMAC context. The canonical Account producer, shared replay readiness, and dedicated Gateway signing-key qualification remain open. The trusted TCP Proxy certificate/listener boundary remains separate. See [Gateway Architecture](../../design/architecture/system-architecture-gateway.md#implementation-status), [Authentication and authorization](../../design/architecture/system-architecture-authentication.md#gateway-to-game-session-connect-context-normative), and the [Player Access and Session tracker](../../design/project-management/implementation-tracking/player-access-and-session.md#canonical-first-party-receiving-preparation).
