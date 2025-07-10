# Tcp-proxy Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the TCP Proxy
Service. They describe the internal gRPC events used by the service to notify
the Game Session Service about client disconnects and to push buffered input
after reconnects.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/tcp-proxy-service/README.md).
