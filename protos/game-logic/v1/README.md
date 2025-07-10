# Game-logic Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the game logic service.
They describe the gRPC API exposed by the service.

These messages reuse common types like `ErrorDetail` from
[`protos/shared/v1`](../../shared/v1/errors.proto) for consistent error handling.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/game-logic-service/README.md).
