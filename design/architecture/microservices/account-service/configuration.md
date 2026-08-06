# Account Service Configuration

This document summarizes the Account Service environment/configuration contract and the proto source location. The service follows the standard scheme described in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

## Core Configuration

The service requires:

- [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
- [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
- gRPC TLS certificates via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates)
- peer service discovery via variables prefixed `FIREMUD_SERVICES_`
- OpenTelemetry collector endpoint via `OTEL_ENDPOINT` when overriding the default

JWT signing and cross-service validation follow [JWT and Token Contracts](../../system-architecture-jwt-and-token-contracts.md). Account Service is the application workload that consumes private signing material; this document records the local delivery paths and readiness consequences. The target uses delegated non-exportable private-key operations, while the interim fallback uses the read-only projected private/public mounts. The local/dev HMAC compatibility profile is never a player-facing or shared-key fallback, and local/test configurations may use the explicitly documented classpath fixture. Issued-token registry retention is calculated per record from that token's actual `exp` plus `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`; gameplay continuity uses the separate derived `session_expiration_ms` policy.

### JWT Resource Delivery and Readiness

In player-facing environments, the interim `jwt-signing-keys` private bundle is mounted read-only only into Account at `/var/run/secrets/firemud/jwt/current.key`, while the public `jwt-jwks` projection is mounted read-only for Account and every JWT validator. The target replaces the private bundle with delegated non-exportable signer operations and uses the Account-owned public resource described by the JWT contract.

`FIREMUD_AUTH_JWKS_PATH` identifies the mounted `/var/run/secrets/firemud/jwks/jwks.json` public file for Account and JWT validators. Initial publication uses Account's normal JWKS publication authority; Account readiness, token issuance, validator readiness, and protected traffic remain quarantined until the required private/public projection proof succeeds. There is no separate bootstrap writer or one-time publication authority. Ordinary player-facing readiness fails closed when the path or file is missing or unreadable, the JWKS is malformed, or its public JWK does not match the Account signing key and `kid`. Account does not fall back to a classpath JWKS resource; that fallback is limited to local/test configurations.

The configurable cleanup margin controls issued-token registry retention only. Gameplay continuity is a separate Game Session policy documented in [Reconnection](../../system-architecture-reconnection.md); Account configuration does not derive or widen that lifetime.

## Implementation Status

The current runtime still uses the shared-HMAC compatibility profile for issuance and validation and has no asymmetric non-exportable signer delegation. Any Secret-backed asymmetric materialization is the explicit interim fallback/drift, not canonical target custody; the current preflight/manifests still treat signing paths and `jwt-signing-keys` mounts as shared workload configuration rather than enforcing Account as the sole private-material consumer, and the classpath fallback remains permitted when the configured JWKS file is absent. These target-state requirements are not proof of current startup, custody, or mount enforcement; runtime, preflight, and manifest alignment is outside this documentation slice.

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
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Account-only path to the interim versioned asymmetric signing bundle (fallback while delegated non-exportable signer custody is unavailable; mounted read-only from `jwt-signing-keys`) | *(none)* |
| `FIREMUD_AUTH_JWKS_PATH` | Path to the Account-published public `jwks.json` file used by Account and JWT validators (required for player-facing environments; mounted read-only from `jwt-jwks`, normally `/var/run/secrets/firemud/jwks/jwks.json`) | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Target-state cleanup margin added to each token's remaining lifetime for issued-token registry TTL only; target startup validation requires `0..Long.MAX_VALUE - configured JWT lifetime` | `300000` |

Changing `FIREMUD_AUTH_JWT_EXPIRATION_MS` changes the `exp` claim only for newly issued JWTs; already issued JWTs retain their existing `exp`. The cleanup margin is applied when new registry records are admitted; it does not change the expiration of existing JWTs or the immutable anchor of an existing gameplay binding. A larger cleanup margin therefore cannot raise the independent gameplay-continuity cap.

## Proto Files

The gRPC schemas for this service live in [`protos/account/v1`](../../../../protos/account/v1). Use `./gradlew generateProto` to regenerate Java stubs when the definitions change.
