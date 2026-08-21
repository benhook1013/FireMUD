# Tcp-proxy Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the TCP Proxy
Service. They describe the internal gRPC events used by the service to notify
the Game Session Service about client disconnects.

## Implementation Status

The current returning-member contract is `LOGIN` -> `PLAY` -> `LOOK`. Discovery/`WORLDS` is optional and is not enforced as a prerequisite, and explicit `JOIN` is unavailable. The target skip-`JOIN` path requires current `ACTIVE` membership, an exact fresh reread of `membershipAuthorityGeneration`/`membershipVersion`, the applicable realm grant, and a fresh positive entitlement result; entitlement failure fails closed and must not mutate membership. The target fresh direct-text sequence below requires `WORLDS`; that requirement is not current returning-member runtime behavior, where discovery remains optional.

## Target Direct-Text Gameplay Sequence

The canonical fresh direct-text/Telnet sequence is ordered as follows:

1. `WORLDS` — perform fresh public world discovery.
2. `LOGIN <email> <secret>` (or the applicable credential-bearing `LOGIN` form).
3. `REALMS <world>` — obtain the authenticated realm-scoped target.
4. For a public-production target, conditionally send `JOIN` only when the current policy permits public joining and membership is missing or `INACTIVE`, after a fresh positive entitlement result and the applicable membership/policy evidence have been obtained. If the current policy does not permit joining, or entitlement fails, the operation fails closed and does not mutate membership. Private/playtest targets do not use `JOIN`; they require existing `ACTIVE` membership, the current realm grant, and a fresh positive entitlement result before target admission.
5. `CHARS` in the selected realm, or allowed realm-scoped character creation, only when no valid current character is already selected.
6. `PLAY`.
7. `LOOK` for a fresh authoritative view.

The TCP Proxy’s own input buffers are strictly connection-local and cleared on disconnect. A fresh reconnect repeats the direct-text admission sequence; it never replays client input, TCP or WebSocket frames, MCP state, unsent Telnet output, or other raw transport bytes. Game Session restores authorized eligible retained context when present; empty or expired context emits none. Redis may only cache or accelerate that context. Reconnection behavior is defined by the [Reconnection Strategy](../../../design/architecture/system-architecture-reconnection.md) and [Input, Output, and Presentation](../../../design/architecture/system-architecture-input-output-and-presentation.md) designs.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/tcp-proxy-service/README.md).
