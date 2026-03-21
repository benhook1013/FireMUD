## Summary
This PR rolls the current `feature/design-and-vip` branch forward on top of `develop` and brings the codebase, docs, shared runtime modules, and GitHub Actions layout into the current target shape.

The branch is intentionally broad. It includes the current Spring Boot 4 / Framework 7 migration work, extraction of shared backend runtime/build modules, service package cleanup, major architecture documentation refreshes, CI restructuring, and a full readiness-model cleanup for the current player-critical path so FireMUD does not admit user-facing traffic before `connect -> LOGIN -> first LOOK` is actually safe.

## What changed
- migrates the active backend stack onto the Boot 4 / Framework 7 baseline and updates service tests, probes, and runtime wiring to match
- extracts and adopts shared backend modules and build conventions, including split platform/data/saga/web/JWT/test support pieces
- cleans up service package/layout conventions across multiple services, including `tcp-proxy-service`, `world-management-service`, and related shared runtime paths
- refreshes a large set of architecture and operational docs to describe the current target state directly
- tightens readiness semantics across the current gameplay path:
  - standardizes explicit `/actuator/health/readiness` and `/actuator/health/liveness` usage
  - makes `tcp-proxy-service` refuse new Telnet sessions while the downstream gameplay path is not ready
  - adds dependency-aware readiness for `spring-cloud-gateway`, `game-session-service`, and `game-logic-service`
  - upgrades process-up / ping-up checks to bounded, operation-shaped canaries with reserved readiness-only probe identifiers
  - adds shared readiness payload helpers plus readiness transition metrics/logging
  - updates smoke coverage so Telnet verifies pre-readiness refusal and both Telnet and direct WebSocket verify post-readiness `LOGIN -> LOOK`
- restructures CI into clearer workflow domains:
  - `CI — Validation`
  - `Smoke Tests`
  - `Security Checks`
  - `CodeQL Analysis`
  - `License Checks`
- adds gate jobs and PR summaries so merge protection can target stable high-level checks instead of matrix legs
- expands CodeQL to scan both Java and JavaScript/TypeScript, adds a `CodeQL Gate`, and finalizes the cross-workflow static-analysis summary flow
- folds `Secret Compliance Validation` into the security domain so `Security Gate` and `Security Summary` cover both filesystem scanning and secret compliance policy
- replaces repeated saga entity bootstrap `@EntityScan` wiring in saga-backed services with a shared `common-saga` annotation
- fixes the follow-on CI regressions exposed during this branch:
  - smoke stack startup and Redis/shared auto-config issues
  - stale static-analysis summary behavior
  - docs publishing Lychee ignore path
  - Docker image publishing missing prebuilt JARs

## Validation
- `./gradlew spotlessApply`
- `./gradlew check`
- `./gradlew linkCheck lintMarkdown`
- `./gradlew :account-service:check :automation-scripting-service:check :game-design-service:check :game-session-service:check :logging-admin-service:check :social-groups-service:check :world-management-service:check`
- `./gradlew check -x lintMarkdown -x linkCheck --no-daemon --no-configuration-cache`
- targeted readiness verification including:
  - `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.health.GameplayPathReadinessHealthIndicatorTest'`
  - `./gradlew :game-logic-service:test --tests 'net.firedevops.firemud.gamelogic.health.ResolveLookPathProbeTest' --tests 'net.firedevops.firemud.gamelogic.health.LookDependencyReadinessHealthIndicatorTest'`
  - `./gradlew :spring-cloud-gateway:test --tests 'net.firedevops.firemud.springcloudgateway.health.GameplayRouteReadinessHealthIndicatorTest'`
  - `./gradlew :tcp-proxy-service:test --tests 'net.firedevops.firemud.tcpproxy.health.TcpProxyTrafficReadinessHealthIndicatorTest' --tests 'net.firedevops.firemud.tcpproxy.telnet.TelnetReadinessAdmissionIntegrationTest'`
- targeted local smoke verification via `./gradlew devUp` / `./gradlew devDown`, `services/tcp-proxy-service/telnet-login-look-smoke.sh`, and `services/game-session-service/websocket-login-look-smoke.sh`
- GitHub Actions on this branch covering validation, smoke, security, CodeQL, and license checks

## Notes
- This branch is not a narrow slice; the PR body is intentionally high-level rather than file-by-file.
- The readiness contract enforced by this branch is the currently implemented player slice: `connect -> LOGIN -> first LOOK`.
- Merge protection should target the stable gate checks:
  - `Validation Gate`
  - `Security Gate`
  - `Smoke Gate`
  - `CodeQL Gate`
  - `License Gate`
