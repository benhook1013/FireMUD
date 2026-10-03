# Logging-admin Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the logging admin service.
They describe the gRPC API exposed by the service.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/logging-admin-service/README.md).

The existing Account audit create and receipt-read messages carry the explicit tenant identity
version and UUID fields at tags 11 and 12; see the [audit ingress contract](../../../design/architecture/microservices/logging-admin-service/api-contracts.md#account-audit-ingress-and-receipt)
for their versioned scope rules.
