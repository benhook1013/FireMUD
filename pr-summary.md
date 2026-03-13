## Summary
This branch is a large architecture-and-runtime convergence pass against `develop`. It updates the target-state design across core services, gateway/proxy/session contracts, scripting and world APIs, deployment and recovery operations, observability, and the CI/smoke path used to validate the stack.

This is not a narrow feature PR. Most of the surface area is architecture and operational design material, with the runtime/code changes needed to make the documented contracts and CI behavior line up with the current system shape.

## Main themes
- aligns system architecture and service design docs across the repo, including authentication, gateway behavior, TCP proxy identity handling, Redis ownership, tick execution, scripting control-plane flows, backup/recovery, deployment operations, and observability
- adds/expands ADRs and reference docs around gameplay sharding scope, Redis ownership boundaries, TCP proxy identity canonicalization, session execution, operator runbooks, deployment environments, and secrets handling
- expands protobuf and service contracts for scripting, game-session, world-management, tcp-proxy, and related cross-service APIs
- hardens the edge path in code by moving to explicit header trust behavior in the gateway and by carrying canonical proxy/session identifiers through tcp-proxy and game-session boundaries
- updates CI and operator tooling around deploy preflight validation, kustomize overlay validation, secret-compliance validation, backup/restore validation, observability contract enforcement, and base-image publishing
- refreshes Grafana/Kibana assets, Prometheus alert rules, deployment/recovery docs, and environment/secrets documentation to match the current operating model
- consolidates contributor guidance into `AGENTS.md` and removes duplicated older AI/project-management rule files

## CI and smoke work added after the original PR description
- fixes the canonical `devUp`/compose smoke path so CI uses the prebuilt local-image override correctly instead of defaulting to missing `docker-*:latest` tags
- stabilizes local/plaintext gRPC behavior across services for dev and smoke verification
- fixes multiple service startup/runtime blockers uncovered by CI and compose smoke, including gateway reactive startup, entity scanning, optional local saga wiring, account/bootstrap/auth issues, world-management startup, and tcp-proxy startup dependencies
- hardens the Telnet `LOGIN` + `LOOK` smoke flow with explicit readiness waits, script execution fixes, and improved bootstrap behavior
- restores healthcheck-based waiting in `devUp` and fixes ancillary compose issues like `pg-dump-cron` image handling and MinIO bucket CORS setup
- removes `continue-on-error` from the smoke job so smoke failures now fail the workflow instead of producing a misleading green run
- includes a small Gradle/test-tooling cleanup pass for categorized test roots, IDE source-root behavior, and WSL-native parallel execution

## Review guidance
Review this PR by area/theme rather than by commit message. The commit history is not a good description of the final diff because this branch accumulated changes across multiple parallel workspace sessions over time.

The highest-signal review buckets are:
- `design/architecture`, `design/operations`, and `design/observability`
- gateway / tcp-proxy / game-session / world-management / scripting contract changes under `services/` and `protos/`
- CI and operator tooling under `.github/workflows/`, `dev-tools/`, `docker/`, and `k8s/`

## Current status
- canonical local smoke path is wired to the same compose/dev workflow CI uses
- the smoke job now correctly blocks CI on failure
- branch head includes the documentation convergence plus the later CI/smoke stabilization work

## Scope
Compared with `develop`, this branch is roughly:
- 118 commits
- 1000+ files changed
- substantial architecture, operations, CI, and supporting runtime change volume

That size is expected for this branch. The bulk of the change is cross-cutting design and operations convergence rather than a single isolated feature.
