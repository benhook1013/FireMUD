# Account Service Configuration

This document summarizes the Account Service environment/configuration contract and the proto source location. The service follows the standard scheme described in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

## Core Configuration

The service requires:

- [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
- [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
- gRPC TLS certificates via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates)
- peer service discovery via variables prefixed `FIREMUD_SERVICES_`
- OpenTelemetry collector endpoint via `OTEL_ENDPOINT` when overriding the default

JWT signing and cross-service validation follow [JWT and Token Contracts](../../system-architecture-jwt-and-token-contracts.md). This document records Account-local delivery paths and readiness consequences. Target non-exportable-signer mode uses delegated private-key operations, requires signer health and convergence of the Account-owned public JWKS, and permits no private-key mount or distribution. The interim fallback gives Account the private projection and Account and validators the public projection read-only. The local/dev HMAC compatibility profile is never a player-facing or shared-key fallback, and local/test configurations may use the explicitly documented classpath fixture. Issued-token registry retention is calculated per record from that token's actual `exp` plus `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`; gameplay continuity uses the separate derived `session_expiration_ms` policy.

### JWT Resource Delivery and Readiness

In player-facing environments, the interim `jwt-signing-keys` private bundle is mounted read-only only into Account at `/var/run/secrets/firemud/jwt/current.key`, while the public `jwt-jwks` projection is mounted read-only for Account and every JWT validator. Target non-exportable-signer mode has no private-key mount or distribution; it requires signer health and convergence of the Account-owned public JWKS. The interim fallback instead requires the private/public projection proof before readiness.

`FIREMUD_AUTH_JWKS_PATH` identifies the mounted `/var/run/secrets/firemud/jwks/jwks.json` public file for Account and JWT validators. JWT lifecycle and publication remain in [JWT and Token Contracts](../../system-architecture-jwt-and-token-contracts.md). Target delegated-signer readiness requires signer health and Account-owned public-JWKS convergence. For the interim mounted fallback, Account readiness, token issuance, validator readiness, and protected traffic remain quarantined until private/public projection proof succeeds; that proof includes the configured path/file, JWKS shape, and public-key-to-`kid` match. In target/player-facing configurations, Account must not fall back to a classpath JWKS resource; that fallback is limited to local/test configurations.

The configurable cleanup margin controls issued-token registry retention only. Gameplay continuity is a separate Game Session policy documented in [Reconnection](../../system-architecture-reconnection.md); Account configuration does not derive or widen that lifetime.

## Implementation Status

The current runtime still uses the shared-HMAC compatibility profile for issuance and validation and has no asymmetric non-exportable signer delegation. Any Secret-backed asymmetric materialization is the explicit interim fallback/drift, not canonical target custody; the current preflight/manifests still treat signing paths and `jwt-signing-keys` mounts as shared workload configuration rather than enforcing Account as the sole private-material consumer, and the classpath fallback remains permitted when the configured JWKS file is absent. The runtime also retains the legacy creator-share percentage setting and provider-mutating code paths; they are unsupported implementation drift, not an accepted fee policy or product capability. These target-state requirements are not proof of current startup, custody, mount enforcement, or creator-commerce disablement; runtime, preflight, and manifest alignment is outside this documentation slice.

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
| `FIREMUD_PAYMENT_PLATFORM_FEE_PERCENT` | Unsupported creator-share substrate; implementations must ignore this variable and it cannot enable provider mutation. No legacy value is an approved fee policy. Any future FireMUD-managed creator-player fee remains target-only under [ADR 0179](../../decisions/adr-0179-firemud-managed-creator-commerce-boundary.md). | *(unsupported; unset)* |
| `FIREMUD_AUTH_JWT_SECRET` | Inline JWT signing key material for local/dev or explicitly ephemeral stacks only (legacy compatibility; not for player-facing environments) | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Account-only path to the interim versioned asymmetric signing bundle (fallback while delegated non-exportable signer custody is unavailable; mounted read-only from `jwt-signing-keys`) | *(none)* |
| `FIREMUD_AUTH_JWKS_PATH` | Path to the Account-published public `jwks.json` file used by Account and JWT validators (required for player-facing environments; mounted read-only from `jwt-jwks`, normally `/var/run/secrets/firemud/jwks/jwks.json`) | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of newly issued JWTs for profiles using the global setting (target `control-ui` and receiver-specific private player-delegation profiles); Account-specific player-bootstrap and gameplay-connect lifetimes are configured separately below | `3600000` |
| `FIREMUD_ACCOUNT_TOKENS_SESSION_EXPIRATION_MS` | Current default legacy Account authentication-session record TTL when `SessionService.storeSession(tenantId, accountId, token)` is called without an explicit lifetime (currently gameplay text/delegation paths); control-ui and player-bootstrap account rows currently pass their corresponding JWT lifetimes directly, while connect-token rows pass the connect-token lifetime directly. It is not JWT `exp`, gameplay-continuity, or issued-token-registry retention | `3600000` |
| `FIREMUD_ACCOUNT_TOKENS_PLAYER_BOOTSTRAP_EXPIRATION_MS` | `player-bootstrap` JWT lifetime; target ceiling is `300000` ms (five minutes). Current runtime consumes this override, but target startup/preflight bound validation remains incomplete | `300000` |
| `FIREMUD_ACCOUNT_TOKENS_CONNECT_SCOPE_EXPIRATION_MS` | Current Account discovery `connectScopeId` selector lifetime, surfaced as `connectScopeExpiresAt`; target scope proof remains short-lived and expiry-bound | `120000` |
| `FIREMUD_ACCOUNT_TOKENS_CONNECT_TOKEN_EXPIRATION_MS` | `gameplay-connect` JWT lifetime; target ceiling is `30000` ms. Current runtime consumes this override, but target startup/preflight bound validation remains incomplete | `30000` |
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Target-state cleanup margin added to each token's remaining lifetime for issued-token registry TTL only; target startup validation requires `0..Long.MAX_VALUE - configured JWT lifetime` | `300000` |

Changing any of the three JWT lifetime variables—`FIREMUD_AUTH_JWT_EXPIRATION_MS`, `FIREMUD_ACCOUNT_TOKENS_PLAYER_BOOTSTRAP_EXPIRATION_MS`, or `FIREMUD_ACCOUNT_TOKENS_CONNECT_TOKEN_EXPIRATION_MS`—changes the `exp` claim only for newly issued JWTs; already issued JWTs retain their existing `exp`. `FIREMUD_ACCOUNT_TOKENS_SESSION_EXPIRATION_MS` is only the current default TTL for legacy session records whose writer omits an explicit lifetime; it does not change JWT `exp`, issued-token-registry retention, or gameplay continuity. The current control-ui, player-bootstrap, and connect-token writers pass their corresponding JWT lifetimes directly, which is implementation drift if target convergence requires one per-token retention rule. `FIREMUD_ACCOUNT_TOKENS_CONNECT_SCOPE_EXPIRATION_MS` separately controls the discovery selector's `connectScopeExpiresAt`; it is not a JWT lifetime, session-record TTL, registry-retention setting, or gameplay-continuity cap. The cleanup margin is applied when new registry records are admitted; it does not change the expiration of existing JWTs or the immutable anchor of an existing gameplay binding. A larger cleanup margin therefore cannot raise the independent gameplay-continuity cap. The separate Account overrides are current runtime controls with target ceilings and startup/preflight proof obligations recorded above; they do not establish target convergence by themselves.

## Proto Files

The gRPC schemas for this service live in [`protos/account/v1`](../../../../protos/account/v1). Use `./gradlew generateProto` to regenerate Java stubs when the definitions change.
