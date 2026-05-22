# Game Design Service Operations

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- Publishing a game version is coordinated through the Temporal-backed `publish` workflow family. The synchronous `PublishVersion` API now requires a stable `publish_request_id`, while the durable workflow performs version metadata creation, participant digest gating, release attestation, and publish reconciliation under one caller-visible workflow identity. See [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) for the overall flow.

## Local Development Notes

`TestDataSeeder` populates a demo game, template, revision and version only when explicit smoke/runtime seeding is enabled through deployment config (`FIREMUD_SMOKE_SEED_DEMO_RUNTIME_ENABLED=true` in the local Compose smoke stack). Run `services/game-design-service/smoke-test.sh` to verify both REST and gRPC endpoints. The lightweight maintained application smoke now lives under `src/test/java/integration`; the old disabled GHCR-based cross-service placeholder was removed because it did not prove a meaningful current contract.
