# Tcp-proxy Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the TCP Proxy
Service. They describe the internal gRPC events used by the service to notify
the Game Session Service about client disconnects.

## Scope and reconnect consequence

This package carries internal `NotifyDisconnect` events only; it is not the player-facing command or admission contract. The current and target direct-text sequences are owned by [TCP Proxy protocols](../../../design/architecture/microservices/tcp-proxy-service/protocols.md) and [Authentication](../../../design/architecture/system-architecture-authentication.md#login-and-session-flow), including the current `LOGIN` -> `PLAY` -> `LOOK` behavior and target discovery/admission rules.

The TCP Proxy’s own input buffers are strictly connection-local and cleared on disconnect. A fresh reconnect repeats the owner-defined admission sequence; the proxy emits no positive reconnect event and never replays client input, TCP or WebSocket frames, MCP state, unsent Telnet output, or other raw transport bytes. After current binding checks, Game Session may best-effort restore retained context when present; this current binding-checked restoration can still replay retained context after deliberate `LOGOUT` because suppression is unimplemented, and retained context is not authorization. Empty or expired context emits none. The target permits retained context only for an eligible, non-terminated binding and requires a fresh authoritative `LOOK` after restoration. Redis may only cache or accelerate that context. Reconnection behavior is defined by the [Reconnection Strategy](../../../design/architecture/system-architecture-reconnection.md) and [Input, Output, and Presentation](../../../design/architecture/system-architecture-input-output-and-presentation.md) designs.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/tcp-proxy-service/README.md).
