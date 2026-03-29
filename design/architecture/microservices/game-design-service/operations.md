# Game Design Service Operations

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- Publishing a game version is coordinated using the Saga utilities from `firemud-common`. The `VersionServiceImpl` builds a workflow that first persists the new version metadata and then asks downstream services to finalize their versioned data for that `version_id`. If any step fails, previously executed actions are compensated so the database remains consistent. See [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) for the overall flow.

## Local Development Notes

`TestDataSeeder` populates a demo game, template, revision and version when the `dev` Spring profile is active. Run `services/game-design-service/smoke-test.sh` to verify both REST and gRPC endpoints. The lightweight maintained application smoke now lives under `src/test/java/integration`; the old disabled GHCR-based cross-service placeholder was removed because it did not prove a meaningful current contract.
