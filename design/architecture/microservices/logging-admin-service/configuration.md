# Logging & Admin Service Configuration

This document summarizes the Logging & Admin Service configuration contract, Redis role, and proto source location.

## Core Configuration

The service uses the configuration approach from [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md). It requires:

- [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
- gRPC TLS certificates via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates)
- peer service discovery via variables prefixed `FIREMUD_SERVICES_`
- optional OpenTelemetry collector override via `OTEL_ENDPOINT`

## Redis Role and Prefixes

The Logging & Admin Service does **not** connect to Redis at runtime. It consumes Redis-derived metrics and coordination health information via Game Session APIs and exporters, but it never issues commands against Coordination Redis or Cache/Rate-Limit Redis directly. All remediation actions are driven through the documented runbooks in [Redis Operations & Migrations](../../system-architecture-redis-operations.md) and Game Session control APIs.

## Service-Specific Variables

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_AUTH_JWKS_URI` | JWKS endpoint used for JWT validation (canonical) | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET` | Legacy HMAC JWT validation secret (transitional only; not for player-facing environments) | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Legacy file path for HMAC JWT validation secret (transitional only; not for player-facing environments) | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_GRPC_WORKLOAD_NAMESPACE` | Namespace required for the exact Account workload identity on the StartSession reservation evidence RPC; blank configuration denies that method | *(none)* |
| `FIREMUD_GRPC_ACCOUNT_RESERVATION_EVIDENCE_APPROVED_LEAF_FILE` | Absolute path to the protected, method-scoped Account leaf-approval file for `ReadCurrentClaimEvidence`; blank, unreadable, or invalid policy denies the method | *(none)* |
| `FIREMUD_SERVICES_ACCOUNT_SERVICE` | gRPC endpoint (host:port) for the Account Service | *(none)* |
| `FIREMUD_SERVICES_GAME_SESSION_SERVICE` | gRPC endpoint (host:port) for the Game Session Service | *(none)* |
| `FIREMUD_GRPC_WORKLOAD_NAMESPACE` | Canonical workload namespace for internal gRPC identity checks | *(none)* |
| `FIREMUD_LOGGING_ADMIN_START_SESSION_OPERATOR_AUTHORIZATION_ENABLED` | Explicitly enables the local StartSession authorization coordinator composition | `false` |

## Conditional StartSession Coordinator Composition

The `FIREMUD_LOGGING_ADMIN_START_SESSION_OPERATOR_AUTHORIZATION_ENABLED` opt-in defaults to `false`. When explicitly enabled, Spring composes the existing durable reservation service with `StartSessionAuthorizationCoordinator` and an Account `StartSessionOperatorAuthorizationClient`; the client is initialized and closed through the managed Spring bean lifecycle.

Enabled composition requires a canonical `firemud.grpc.workload-namespace` and readable file-backed gRPC client certificate-chain, private-key, and CA inputs. These use the existing common gRPC TLS resolver and the `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH` inputs above. Missing, plaintext, incomplete, or non-file-backed TLS inputs fail composition rather than selecting defaults. The canonical operator contracts remain in [ADR 0047](../../decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md), [ADR 0048](../../decisions/adr-0048-durable-idempotent-operator-write-execution.md), and the [StartSession reservation evidence contract](./api-contracts.md#startsession-reservation-claim-evidence).

This configuration creates no external operator route, trusted caller context, reservation provider, or gameplay activation. The focused configuration test uses a controlled channel factory and test-only placeholder files; it is wiring proof only, not physical mTLS, production startup, external-ingress, Game Session launch-selection, or World activation proof.

`logging_admin.v1.StartSessionReservationEvidenceService/ReadCurrentClaimEvidence` is the only method exempted from the shared Bearer-JWT interceptor. The server's required client-certificate handshake and verified TLS peer interceptor remain enabled. The receiver requires both the exact Account SPIFFE identity in `FIREMUD_GRPC_WORKLOAD_NAMESPACE` and approval of the SHA-256 fingerprint derived from the presented TLS leaf. It reads the configured file for each request; a missing path, read failure, malformed file, or unapproved leaf denies the request before reservation access. The JWT header is not an identity source for this method.

The file is a protected server-owned input mounted read-only for Logging & Admin. It contains exactly one required `active=<64 lowercase hexadecimal SHA-256>` line and may contain one `overlap=<64 lowercase hexadecimal SHA-256>;expires-at=<epoch-milliseconds>` line after it. The entries must name distinct leaves. Blank lines, comments, extra entries, duplicate leaves, uppercase or malformed fingerprints, non-absolute paths, and malformed expiry values are rejected. The overlap leaf is accepted only before its absolute expiry time as evaluated by the Logging & Admin host clock; the two-entry limit bounds overlap to one active and one retiring leaf. Replace the file atomically to rotate or revoke approval: publish a new active leaf with the prior leaf as overlap and an explicit deadline, then replace it with the new active leaf alone; immediate revocation replaces the file without the affected fingerprint. Provisioning and custody of this file, including rotation updates, are environment responsibilities and are not supplied or proven by this code change. No production leaf approval or live rotation is claimed.

## Proto Files

API schemas are kept in [`protos/logging-admin/v1`](../../../../protos/logging-admin/v1). When these change, run `./gradlew generateProto` to refresh generated sources.
