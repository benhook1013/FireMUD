# Account Service Configuration

This document summarizes the Account Service environment/configuration contract and the proto source location. The service follows the standard scheme described in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

## Core Configuration

The service requires:

- [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
- [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
- gRPC TLS certificates via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates)
- peer service discovery via variables prefixed `FIREMUD_SERVICES_`
- OpenTelemetry collector endpoint via `OTEL_ENDPOINT` when overriding the default

Target-state JWT signing uses `FIREMUD_AUTH_JWT_SECRET` only for local/dev compatibility and an asymmetric versioned bundle at `FIREMUD_AUTH_JWT_SECRET_PATH` in player-facing environments, mounted only into Account Service from the fixed `jwt-signing-keys` Secret. Account Service implements the canonical JWT-validation behavior for its own endpoints but does not own the cross-service validation contract; it remains locally authoritative for signing-generation checks, lifecycle transitions, signer promotion, all JWKS publication, and public/private rollback pruning, but it does not generate private material itself. A non-exportable signer may perform only private-key operations explicitly delegated by Account; that delegation is mandatory whenever asymmetric signing is enabled. Until that capability is implemented, the only controlled asymmetric fallback is the materialization-controller-written, Account-consumed Kubernetes Secret baseline described in [ADR 0014](../../decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md): the controller is a narrowly scoped infrastructure custodian for private-key generation and Secret CAS, Account is the only application workload that mounts or uses private material, validators receive Account-published public JWKS only, and rotation automation cannot read or write private key material. Account consumes the read-only projected private and public mounts and CAS-publishes both the initial and subsequent contents of the public `jwt-jwks` ConfigMap; validators consume that public JWKS through read-only projections and never receive the private bundle. The inline HMAC setting remains a separate legacy local/dev or explicitly ephemeral CI compatibility mode, never a player-facing or shared private-key fallback; local/test configurations may use the explicitly documented classpath fixture instead. Account promotes a new signer only after validating correspondence between the delegated signer (or controlled Secret fallback), the published JWKS public key, and the `kid`. Service verification follows [JWT and Token Contracts](../../system-architecture-jwt-and-token-contracts.md). Issued-token registry retention is calculated per record from that token's actual `exp` plus `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`; gameplay continuity uses the separate derived `session_expiration_ms` policy.

### Initial public JWKS publication

The initial player-facing bootstrap preserves the same ownership boundary as rotation. The materialization controller generates and CAS-materializes only the private signing bundle after an authenticated Account operation. Account remains unready and keeps issuance and protected traffic quarantined while it waits for the projected private bundle, derives or receives the operation-bound public JWK, CAS-populates the fixed pre-created `jwt-jwks` ConfigMap, and observes the resulting projection. Account becomes ready only after validating the mounted private/public correspondence and matching `kid`. There is no separate bootstrap JWKS writer or one-time publication-authority exception.

In the target player-facing configuration, `FIREMUD_AUTH_JWKS_PATH` identifies the mounted `/var/run/secrets/firemud/jwks/jwks.json` public file for Account and JWT validators. During initial publication Account may run only in the quarantined bootstrap mode above; ordinary readiness fails closed when the path or file is missing or unreadable, the JWKS is malformed, or its public JWK does not match the Account signing key and `kid`. Account does not fall back to a classpath JWKS resource. Validators use the initial and subsequent Account-published public JWKS and never receive the private signing bundle. The classpath fallback is limited to local/test configurations.

The configurable cleanup margin controls issued-token registry retention only. Gameplay continuity is a separate Game Session policy documented in [Reconnection](../../system-architecture-reconnection.md); Account configuration does not derive or widen that lifetime.

## Implementation Status

The current runtime still uses shared-HMAC issuance and validation, has no non-exportable signer delegation, permits the classpath fallback when the configured JWKS file is absent, and the current preflight/manifests still treat signing paths and `jwt-signing-keys` mounts as shared workload configuration rather than enforcing Account-only access. These target-state requirements are not proof of current startup, custody, or mount enforcement; runtime, preflight, and manifest alignment is outside this documentation slice.

## Service-Specific Variables

Additional variables configure outbound email delivery and payment behavior:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SMTP_HOST` | SMTP server hostname | `localhost` |
| `SMTP_PORT` | SMTP server port | `1025` |
| `SMTP_USERNAME` | Username for SMTP auth | *(empty)* |
| `SMTP_PASSWORD` | Password for SMTP auth | *(empty)* |
| `SMTP_FROM` | From address for transactional emails | `no-reply@firemud.local` |
| `FIREMUD_MAIL_VERIFICATION_URL` | Public URL for email verification links | `http://localhost:8080/auth/verify-email?token=%s` |
| `FIREMUD_MAIL_RESET_URL` | Public URL for password reset links | `http://localhost:8080/reset-password?token=%s` |
| `FIREMUD_PAYMENT_STRIPE_API_KEY` | Stripe API key used for payments | *(none)* |
| `FIREMUD_PAYMENT_PLATFORM_FEE_PERCENT` | Platform fee percentage applied to transactions | `0` |
| `FIREMUD_AUTH_JWT_SECRET` | Inline JWT signing key material for local/dev or explicitly ephemeral stacks only (legacy compatibility; not for player-facing environments) | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Account-only path to a versioned asymmetric signing bundle (required for player-facing environments; mounted read-only from `jwt-signing-keys`) | *(none)* |
| `FIREMUD_AUTH_JWKS_PATH` | Path to the Account-published public `jwks.json` file used by Account and JWT validators (required for player-facing environments; mounted read-only from `jwt-jwks`, normally `/var/run/secrets/firemud/jwks/jwks.json`) | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Target-state cleanup margin added to each token's remaining lifetime for issued-token registry TTL only; target startup validation requires `0..Long.MAX_VALUE - configured JWT lifetime` | `300000` |

Changing `FIREMUD_AUTH_JWT_EXPIRATION_MS` changes the `exp` claim only for newly issued JWTs; already issued JWTs retain their existing `exp`. The cleanup margin is applied when new registry records are admitted; it does not change the expiration of existing JWTs or the immutable anchor of an existing gameplay binding. A larger cleanup margin therefore cannot raise the independent gameplay-continuity cap.

## Proto Files

The gRPC schemas for this service live in [`protos/account/v1`](../../../../protos/account/v1). Use `./gradlew generateProto` to regenerate Java stubs when the definitions change.
