# Game-design Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the Game Design Service.
The primary file is `game_design_service.proto`, which defines the gRPC API for saving revisions and publishing versions. Full-version publish now uses the durable Game Design Temporal `publish` workflow family when Temporal is enabled, while `GetPublishedReleaseBundle` remains the canonical read surface and now also exposes publish workflow runtime metadata for operators. Plugin publication follows an upload-first control-plane workflow: `UploadPluginBundle` records `SIGNATURE_VERIFIED` design-time metadata from a signed bundle, `PublishPluginVersion` promotes that verified bundle into `PUBLISHED` after base-version and policy validation, and publication reads include both point reads (`GetPublishedPluginVersion`) and tenant-scoped listing (`ListPluginVersionStatuses`) with immutable activation-gating metadata such as `signerKeyId`, `signerRevoked`, `componentPolicyDecision`, and `statusReason`.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/game-design-service/README.md).
