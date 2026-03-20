## Summary
This PR rolls the current `feature/design-and-vip` branch forward on top of `develop` and brings the codebase, docs, shared runtime modules, and GitHub Actions layout into the current target shape.

The branch is intentionally broad. It includes the current Spring Boot 4 / Framework 7 migration work, extraction of shared backend runtime/build modules, service package cleanup, major architecture documentation refreshes, and the CI restructuring needed to keep validation, smoke, security, and code scanning reliable.

## What changed
- migrates the active backend stack onto the Boot 4 / Framework 7 baseline and updates service tests, probes, and runtime wiring to match
- extracts and adopts shared backend modules and build conventions, including split platform/data/saga/web/JWT/test support pieces
- cleans up service package/layout conventions across multiple services, including `tcp-proxy-service`, `world-management-service`, and related shared runtime paths
- refreshes a large set of architecture and operational docs to describe the current target state directly
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
- targeted local smoke verification via `./gradlew devUp` / `./gradlew devDown` and the TCP proxy telnet smoke path
- GitHub Actions on this branch covering validation, smoke, security, CodeQL, and license checks

## Notes
- This branch is not a narrow slice; the PR body is intentionally high-level rather than file-by-file.
- Merge protection should target the stable gate checks:
  - `Validation Gate`
  - `Security Gate`
  - `Smoke Gate`
  - `CodeQL Gate`
  - `License Gate`
