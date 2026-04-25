# Game-design Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the Game Design Service.
The primary file is `game_design_service.proto`, which defines the gRPC API for saving revisions and publishing versions. Plugin publication reads now include both point reads (`GetPublishedPluginVersion`) and tenant-scoped listing (`ListPluginVersionStatuses`) with immutable activation-gating metadata such as `signerKeyId`, `signerRevoked`, and `componentPolicyDecision` so Automation & Scripting and operator tooling can fail closed before enabling a plugin version at runtime.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/game-design-service/README.md).
