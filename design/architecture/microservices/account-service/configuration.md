# Account Service Configuration

This document summarizes the Account Service environment/configuration contract and the proto source location. The service follows the standard scheme described in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

## Core Configuration

The service requires:

- [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
- [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
- gRPC TLS certificates via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates)
- peer service discovery via variables prefixed `FIREMUD_SERVICES_`
- OpenTelemetry collector endpoint via `OTEL_ENDPOINT` when overriding the default

JWT signing key material is configured with `FIREMUD_AUTH_JWT_SECRET` or `FIREMUD_AUTH_JWT_SECRET_PATH`; player-facing environments must use an asymmetric versioned signing bundle at `FIREMUD_AUTH_JWT_SECRET_PATH`, mounted only into Account Service from the `jwt-signing-keys` Secret. Account Service or a non-exportable signer owns private-key generation, validation, promotion, and private rollback pruning. Account promotes a new signer only after the private key and `kid` match the prepublished, converged JWKS generation. The public JWKS file is supplied by the read-only `jwt-jwks` Secret through `FIREMUD_AUTH_JWKS_PATH` and served by Account Service. Service verification must follow the asymmetric JWKS model from [Authentication & Authorization](../../system-architecture-authentication.md#jwt-verification-model-normative). Server-side session TTL is derived from JWT lifetime using `FIREMUD_AUTH_JWT_EXPIRATION_MS` plus `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`.

In player-facing environments, `FIREMUD_AUTH_JWKS_PATH` must resolve to the mounted `/var/run/secrets/firemud/jwks/jwks.json` file. Account startup must fail closed when the path or file is missing or unreadable, the JWKS is malformed, or its public JWK does not match the Account signing key and `kid`; Account must not fall back to a classpath JWKS resource. The classpath fallback is limited to local/test configurations.

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
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Extra time added to the JWT lifetime when deriving server-side session TTL | `300000` |

Changing `FIREMUD_AUTH_JWT_EXPIRATION_MS` changes the `exp` claim only for newly issued JWTs; already issued JWTs retain their existing `exp`. The session safety margin affects newly admitted gameplay bindings, not the expiration of existing JWTs.

## Proto Files

The gRPC schemas for this service live in [`protos/account/v1`](../../../../protos/account/v1). Use `./gradlew generateProto` to regenerate Java stubs when the definitions change.
