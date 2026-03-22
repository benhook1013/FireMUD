# Game Logic Service Operations

This document collects Game Logic's readiness model, deployment-facing operational notes, and local verification hooks for the current service slice.

## Readiness and Liveness

- `liveness` is local-only and indicates that the process is alive and able to continue serving.
- `readiness` is command-path safety. For the currently implemented player slice, Game Logic is ready only when the downstream services required for `ResolveLook` are reachable, specifically World Management and Entity Management.
- This service is not ready for new gameplay traffic if it can answer `Ping` locally but cannot satisfy the first `LOOK` dependency chain.
- The readiness canary for this slice is a dedicated internal `ResolveLook`-shaped helper rather than a second, unrelated dependency-check path, so readiness and the command path stay aligned on request shape and dependency naming.
- The helper uses explicit short deadlines on downstream world and entity RPCs and reserved readiness-only sentinel identifiers so readiness remains bounded and cannot collide with real gameplay state.
- Readiness transition observability uses the shared contract from [Deployment Environments](../../infrastructure/deployment-environments.md): `firemud.readiness.current`, `firemud.readiness.transitions`, and structured logs keyed by the curated dependency names `worldManagementService` and `entityManagementService`.

## Deployment Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Local Development Notes

The `smoke-test.sh` script under `services/game-logic-service` verifies both REST and gRPC endpoints.

## Cross-Service Integration Test

An integration test at `services/game-session-service/src/test/java/crossservice/net/firedevops/firemud/GameSessionCrossServiceIntegrationTest.java` starts this service alongside Game Session using Testcontainers. Run it manually after building the Docker images:

```bash
./gradlew :game-session-service:test --tests "*CrossServiceIntegrationTest"
```
