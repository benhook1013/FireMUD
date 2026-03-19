## Summary
This PR finishes the current Spring Boot 4 migration slice on top of `develop` and fixes the CI regressions it exposed.

The branch updates the affected runtime and test paths so the repo is consistently on Boot 4, the local/dev compose stack still starts cleanly, and the canonical CI smoke path is green again.

## What changed
- migrates backend services onto the Spring Boot 4 / Spring Framework 7 compatible configuration and test path
- stabilizes `tcp-proxy-service`, `spring-cloud-gateway`, and `game-session-service` integration and cross-service tests under Boot 4
- adds shared Boot 4 test fixtures in `common-library` and removes the repeated `TestRestTemplate`-based simple HTTP probe pattern
- fixes the Gatling plugin/version drift that broke root Gradle configuration for dependency submission and overlay-related validation
- restores `Core Smoke Tests` by fixing runtime saga bootstrap assumptions and the new Spring gRPC server port config in the compose/dev stack
- keeps preview/overlay CI scaffolding aligned with the current hosted-preview direction and recent dependency/tooling changes

## Validation
- `./gradlew spotlessApply`
- `./gradlew check`
- `bash services/tcp-proxy-service/telnet-login-look-smoke.sh`
- GitHub Actions `CI — Build and Security` run `23276107359` succeeded, including `Core Smoke Tests`

## Notes
- This completes the current migration-and-fix pass. Larger follow-up refactors discussed during review, like shared runtime gRPC client/server abstractions or `common-library` splitting, are intentionally not part of this PR.
