# Social & Groups Service Operations

This document collects the Social & Groups Service operational behavior, readiness model, observability notes, saga participation, and integration-test guidance.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes; see [Deployment Environments](../../infrastructure/deployment-environments.md)
- `liveness` is process-local only
- `readiness` is truthful local readiness for the implemented social/chat slice and must fail when the service cannot safely satisfy new chat, guild, mail, or voice-token traffic with its required local persistence/cache/dependency state
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline

## Saga Participation

Guild creation and membership changes may participate in short synchronous saga workflows so other services remain consistent. See [Transaction Strategies](../../system-architecture-transactions.md).

## Chat Slice Status

- **Live:** `SendMessage` already writes chat messages to Redis history and persists them for moderation; the communication slices now exercise this endpoint via Game Logic's `SendCommunication` path so regression tests assert explicit type and recipient metadata before the payload reaches clients
- **Stubbed:** The regression fixtures wire a lightweight Social & Groups stub that records `SendMessageRequest` payloads, returns success, and lets the Game Session/TCP proxy cross-service tests verify canonical transcripts without targeting the full production moderation pipeline
- **Deferred:** Future work will layer in contextual features such as profanity enforcement heuristics, targeted NPC echoes, and channel-routing rules once the core SAY delivery path is stabilized by the automated regression suites

## Metrics and Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. OpenTelemetry spans are exported to the collector defined in the shared configuration. No additional setup is required when running `./gradlew bootRun`.

## Integration Test Notes

The maintained lightweight smoke for this service now lives under `src/test/java/integration` and exercises the secured app boot path directly. Broader gameplay-facing contract coverage should continue to come from the Game Session/TCP Proxy regression suites and from future targeted Social & Groups contract tests, not from generic "starts alongside another image" scaffolding.

Refer to [System Architecture Testing](../../system-architecture-testing.md) for guidance.
