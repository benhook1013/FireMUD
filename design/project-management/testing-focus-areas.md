# FireMUD Testing Focus Areas (Problem Domains)

This document lists recurring problem domains and testing focus areas for FireMUD. As the project grows and new bugs appear, extend these sections with concrete scenarios, links to tests, and regression checks.

## How to Use This Document

- Treat each numbered section as a **living catalog** of risks and regression scenarios.
- When fixing a bug, add a short note under the relevant section with links to the tests that cover it.
- Link cross-service regression suites (for example, LOOK and SAY flows) from here rather than duplicating full transcripts or Gradle invocation details.

## 1. Authentication, Authorization, and Session Management

- Login flow: correct handling of valid/invalid credentials, lockouts, and error messages.
- Session creation and teardown: game sessions, WebSocket sessions, and TCP connections cleaned up reliably on logout or disconnect.
- Session fixation and hijacking: new logins should not reuse attacker-controlled identifiers; verify session IDs and tokens are regenerated appropriately.
- Cross-service auth: Account Service, Game Session Service, and gateways agree on user identity and permissions (no privilege escalation between services).
- Idle timeouts: long-running connections (Telnet/WebSocket) respect inactivity timeouts and clean stale sessions.
- Reconnection behavior: reconnecting clients reattach cleanly or get a clear error; no duplicate sessions or “ghost” players.

## 2. Redis and Caching (Keys, TTL, and Consistency)

- Key naming: consistent namespaces and patterns, no accidental collisions between services or environments.
- TTL and expiry: session or transient keys have appropriate timeouts; expired keys do not leave orphaned state.
- Atomic operations: multi-step updates use appropriate Redis primitives (transactions, Lua, or optimistic locking) to avoid race conditions.
- Cleanup: logout, character delete, and server shutdown paths remove or invalidate related keys.
- Environment isolation: dev/test/prod Redis instances and key prefixes are distinct to prevent cross-environment interference.
- Failure behavior: Redis outages or timeouts degrade gracefully; clients see explicit errors instead of silent corruption.

## 3. Persistence and Data Integrity (PostgreSQL and JPA)

- Transaction boundaries: multi-step operations are transactional where needed; partial failures do not leave inconsistent rows.
- Referential integrity: foreign keys and relationships correctly prevent orphaned or dangling records.
- Idempotency: commands that may be retried (e.g., due to network issues) can be safely applied more than once when required.
- Migrations: schema changes are backward compatible during rolling deploys and maintain data integrity.
- Query performance: key queries used in hot paths have indexes and acceptable execution plans.

## 4. Cross-Service and Network Boundaries

- Protocol compatibility: Telnet, WebSocket, and gateway APIs behave as documented and stay backward compatible for existing clients.
- Integration flows: end-to-end tests for login, character selection, entering the world, and simple commands across multiple services.
- Timeouts and retries: reasonable client timeouts and retry strategies that do not cause thundering herds or duplicate side effects.
- Error propagation: errors from downstream services (Account, Game Session, DB, Redis) are converted to clear, safe messages for clients.
- LOOK and SAY cross-service regressions: see `design/project-management/slice-support/look-and-say-regressions.md` for the detailed test plan, transcripts, metrics, and Gradle tasks that exercise WebSocket and Telnet flows in lockstep.
- Version skew: older services can interact safely with newer ones during incremental rollouts.

## 5. Command Parsing, Input Validation, and Game Logic

- Input validation: commands and payloads are validated and sanitized; malicious or malformed input can’t cause crashes or injections.
- Command parsing: ambiguous or partial commands are handled consistently; whitespace and encoding edge cases are covered.
- State transitions: player and world state changes follow valid transitions (e.g., cannot act while dead, stunned, or disconnected).
- Business rules: core mechanics (combat, movement, inventory, economy) have deterministic, tested behavior.
- Rate limiting: spammy commands are throttled to protect CPU, network, and downstream services.
- `LOOK` cross-service regressions: the `crossServiceTest` target spins up Game Session, Game Logic, World Management, Entity Management, and the TCP proxy/Gateway so both WebSocket and Telnet flows re-run `LOGIN` + `LOOK`, validate the canonical transcript, and surface the `gamesession.command.look.*` metrics/logs described in `design/project-management/slice-support/look-cross-service-tests.md`.

## 6. Concurrency, Race Conditions, and State Consistency

- Concurrent actions: multiple commands or connections from the same player do not corrupt state or bypass checks.
- Locking and contention: shared resources (e.g., rooms, items, combat state) avoid deadlocks and starvation.
- Event ordering: events that must be processed in order (combat ticks, buffs, timers) are consistently ordered under load.
- Duplicate deliveries: message or event deduplication where at-least-once delivery is used.

## 7. Error Handling, Logging, Metrics, and Tracing

- Error contracts: APIs and services return structured error responses rather than leaking stack traces or internal details.
- Logging quality: important paths log with appropriate levels and context; no sensitive data appears in logs.
- Metrics and alerts: key operations (logins, session creation, critical commands) emit metrics and have alert thresholds.
- Tracing: cross-service flows are traceable end-to-end with consistent correlation IDs.
- Degradation tests: verify behavior when dependencies are slow, partially failing, or unavailable.

## 8. Security (OWASP and Game-Specific Threats)

- Authentication: password handling, credential storage, and login flows adhere to OWASP best practices.
- Authorization: role- and permission-based checks exist at appropriate boundaries; no “trust the client” assumptions.
- Injection: defense against SQL/NoSQL/command injection in all inputs, including in-game commands and chat.
- CSRF/XSS/Clickjacking: for any web dashboards or admin tools, apply standard OWASP defenses.
- Transport security: secure protocols, cipher suites, and certificate handling for external-facing endpoints.
- Abuse and cheating: rate limits, anti-bot/automation checks, and detection for suspicious behavior (e.g., impossible movement or actions).

## 9. Configuration, Secrets, and Environment Management

- Configuration safety: defaults are safe for production (e.g., debug off, strict security settings on).
- Secrets management: secrets never live in source control; they are injected through secure mechanisms (vaults, CI/CD secret stores).
- Environment parity: test/stage environments resemble production configurations closely enough to catch real issues.
- Misconfiguration resilience: missing or invalid configuration results in clear startup failures or safe fallbacks, not silent misbehavior.

## 10. Performance, Load, and Scalability

- Load characteristics: sustained and burst load tests for login, chat, movement, and combat traffic patterns.
- Resource usage: CPU, memory, threads, and connection pools stay within acceptable bounds under load.
- Latency budgets: critical paths (login, join world, simple commands) stay within target response times.
- Scaling strategies: horizontal scaling and sharding plans are validated in non-prod environments.

## 11. Deployment, Rollback, and Operations

- Zero-downtime deploys: blue/green or rolling deployment behavior is tested for stateful flows and long-lived connections.
- Health checks: readiness and liveness probes correctly reflect service health and dependencies.
- Rollback: rollback procedures are tested, especially after schema or protocol changes.
- Runbooks: for critical failure modes, document and periodically test operational playbooks.

## 12. Regression and Bug-Driven Testing

- Bug capture: every significant bug gets a short note added to the relevant section here with a link to a regression test.
- Regression tests: when fixing a bug, add or update tests so the failure mode is covered permanently.
- Periodic reviews: revisit this document regularly to add new domains or refine existing ones as FireMUD evolves.
