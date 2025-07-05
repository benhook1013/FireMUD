# Game-design Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the Game Design Service.
The primary file is `game_design_service.proto`, which defines the gRPC API for saving revisions and publishing versions.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/game-design-service/README.md).
