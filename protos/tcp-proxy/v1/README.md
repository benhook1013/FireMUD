# Tcp-proxy Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the TCP Proxy
Service. They describe the internal gRPC events used by the service to notify
the Game Session Service about client disconnects. The TCP Proxy’s own input
buffers are strictly connection-local and cleared on disconnect; any
reconnection and command replay behaviour is governed by the Game Session
Service using Redis-backed state as described in the central design docs.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/tcp-proxy-service/README.md).
