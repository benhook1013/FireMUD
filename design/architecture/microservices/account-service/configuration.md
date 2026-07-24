# Account Service Configuration

This document summarizes the Account Service environment/configuration contract and the proto source location. The service follows the standard scheme described in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

## Core Configuration

The service requires:

- [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
- [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
- gRPC TLS certificates via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates)
- peer service discovery via variables prefixed `FIREMUD_SERVICES_`
- OpenTelemetry collector endpoint via `OTEL_ENDPOINT` when overriding the default

Target-state JWT signing uses `FIREMUD_AUTH_JWT_SECRET` only for local/dev compatibility and an asymmetric versioned bundle at `FIREMUD_AUTH_JWT_SECRET_PATH` in player-facing environments, mounted only into Account Service from the fixed `jwt-signing-keys` Secret. Account Service owns key-generation requests and remains authoritative for signing-generation validation, token-validation semantics, signer promotion, JWKS publication, and public/private rollback pruning. A non-exportable signer may perform only private-key operations explicitly delegated by Account; that delegation is mandatory for target-state asymmetric signing in every environment. Until that capability is implemented, the only controlled asymmetric fallback is the Account-only Kubernetes Secret baseline described in [ADR 0014](../../decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md): only Account Service may receive private material, validators receive public JWKS only, and rotation automation cannot read or write private key material. In that controlled fallback, Account updates the pre-created signing Secret and public `jwt-jwks` ConfigMap through name-scoped Kubernetes `resourceVersion` CAS, then consumes their read-only projected mounts. The inline HMAC setting is a separate legacy local/dev or explicitly ephemeral CI compatibility mode, never a player-facing or shared private-key fallback. Account promotes a new signer only after validating correspondence between the delegated signer (or controlled Account-only fallback), the prepublished JWKS public key, and the `kid`. In player-facing environments, the public JWKS file is supplied only from the fixed-name `jwt-jwks` ConfigMap, whose contents change through Account-owned CAS and reach Account through the read-only `FIREMUD_AUTH_JWKS_PATH` mount before Account serves them. Local/test configurations may use the explicitly documented classpath fixture instead. Service verification must follow the asymmetric JWKS model from [Authentication & Authorization](../../system-architecture-authentication.md#jwt-verification-model-normative). Issued-token registry retention is calculated per record from that token's actual `exp` plus `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`; gameplay continuity uses the separate derived `session_expiration_ms` policy.

In the target player-facing configuration, `FIREMUD_AUTH_JWKS_PATH` resolves to the mounted `/var/run/secrets/firemud/jwks/jwks.json` file. Account startup fails closed when the path or file is missing or unreadable, the JWKS is malformed, or its public JWK does not match the Account signing key and `kid`; Account does not fall back to a classpath JWKS resource. The classpath fallback is limited to local/test configurations.

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
| `FIREMUD_AUTH_JWKS_PATH` | Account-only path to the published `jwks.json` file (required for player-facing environments; mounted read-only from `jwt-jwks`, normally `/var/run/secrets/firemud/jwks/jwks.json`) | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Cleanup margin added to each token's remaining lifetime for issued-token registry TTL and to the initial gameplay continuity-retention horizon | `300000` |

Changing `FIREMUD_AUTH_JWT_EXPIRATION_MS` changes the `exp` claim only for newly issued JWTs; already issued JWTs retain their existing `exp`. The cleanup margin is applied when new registry records and gameplay bindings are admitted; it does not change the expiration of existing JWTs or the immutable anchor of an existing gameplay binding.

## Proto Files

The gRPC schemas for this service live in [`protos/account/v1`](../../../../protos/account/v1). Use `./gradlew generateProto` to regenerate Java stubs when the definitions change.
