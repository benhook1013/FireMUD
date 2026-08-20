# Tcp-proxy Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the TCP Proxy
Service. They describe the internal gRPC events used by the service to notify
the Game Session Service about client disconnects. The TCP Proxy’s own input
buffers are strictly connection-local and cleared on disconnect. A fresh reconnect repeats `LOGIN` and `PLAY`; it never replays client input, TCP or WebSocket frames, MCP state, unsent Telnet output, or other raw transport bytes. Game Session may restore an authorized, bounded semantic recent-context window from its durable owner storage; Redis may only cache or accelerate that context. Reconnection behavior is defined by the [Reconnection Strategy](../../../design/architecture/system-architecture-reconnection.md) and [Input, Output, and Presentation](../../../design/architecture/system-architecture-input-output-and-presentation.md) designs.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/tcp-proxy-service/README.md).
