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

### Kubernetes Workload Namespace

The Account Helm Deployment supplies `FIREMUD_GRPC_WORKLOAD_NAMESPACE` from the pod's `metadata.namespace` through the Kubernetes downward API. The chart reserves this variable and rejects an `extraEnv` entry with the same name; other `extraEnv` entries remain available. Account uses this configured namespace for the workload-peer checks described in the [Account API contract](./api-contracts.md).

### Local Demo Authentication Fixture

The Account receiving flag `FIREMUD_ACCOUNT_LOCAL_DEMO_AUTHENTICATION_ENABLED` defaults to `false`, and `FIREMUD_ACCOUNT_LOCAL_DEMO_AUTHENTICATION_EMAIL` defaults empty. Local/run-owned Compose explicitly enables the flag, configures its demo email and selects `local-fixture` for Account. Active `prod` excludes this fixture even when the flag is set. These settings supply only the Account-local delivery described by the [credential fixture contract](./api-contracts.md#legacy-credential-rpc-and-local-demo-fixture); the Entity seeding flag supplies no receiving authority.

## Implementation Status

The current runtime still uses the shared-HMAC compatibility profile for issuance and validation and has no asymmetric non-exportable signer delegation. Any Secret-backed asymmetric materialization is the explicit interim fallback/drift, not canonical target custody; the current preflight/manifests still treat signing paths and `jwt-signing-keys` mounts as shared workload configuration rather than enforcing Account as the sole private-material consumer, and the classpath fallback remains permitted when the configured JWKS file is absent. The runtime also retains the legacy creator-share percentage setting and provider-mutating code paths; they are unsupported implementation drift, not an accepted fee policy or product capability. These target-state requirements are not proof of current startup, custody, mount enforcement, or creator-commerce disablement; runtime, preflight, and manifest alignment is outside this documentation slice.

The StartSession operator-authorization receiver and owner service use the explicit `firemud.account.start-session-operator-authorization` composition, disabled by default (`enabled=false`). Enabling it requires the existing Account actor/issuance/hosted-terms and transaction owners, reservation-evidence client, an explicit `OwnerKeySource`, bounded reference/recovery/retention durations, the workload namespace, and a separate absolute operator response-envelope keyring mount. The operator keyring is not the generic Account response-envelope mount. Missing inputs prevent composition; this does not create an external operator ingress or enable gameplay launch/activation. The production hosted-terms environment producer and trusted-provider/key-custody inputs remain absent.

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
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Target-state per-token cleanup margin added to each token's remaining lifetime for issued-token registry TTL only; target signed-64-bit arithmetic requires `0..Long.MAX_VALUE - configured JWT lifetime`, without replacing any stricter profile or storage bound | `300000` |

Changing any of the three JWT lifetime variables—`FIREMUD_AUTH_JWT_EXPIRATION_MS`, `FIREMUD_ACCOUNT_TOKENS_PLAYER_BOOTSTRAP_EXPIRATION_MS`, or `FIREMUD_ACCOUNT_TOKENS_CONNECT_TOKEN_EXPIRATION_MS`—changes the `exp` claim only for newly issued JWTs; already issued JWTs retain their existing `exp`. `FIREMUD_ACCOUNT_TOKENS_SESSION_EXPIRATION_MS` is only the current default TTL for legacy session records whose writer omits an explicit lifetime; it does not change JWT `exp`, issued-token-registry retention, or gameplay continuity. The current control-ui, player-bootstrap, and connect-token writers pass their corresponding JWT lifetimes directly, which is implementation drift if target convergence requires one per-token retention rule. `FIREMUD_ACCOUNT_TOKENS_CONNECT_SCOPE_EXPIRATION_MS` separately controls the discovery selector's `connectScopeExpiresAt`; it is not a JWT lifetime, session-record TTL, registry-retention setting, or gameplay-continuity cap. The cleanup margin is applied when new registry records are admitted; it does not change the expiration of existing JWTs or the immutable anchor of an existing gameplay binding. A larger cleanup margin therefore cannot raise the independent gameplay-continuity cap. The separate Account overrides are current runtime controls with target ceilings and startup/preflight proof obligations recorded above; they do not establish target convergence by themselves.

The source-only Control-UI issuance preparation reads the configured margin through `firemud.auth.session-safety-margin-ms`; its nonnegative value is captured for new issuance rows only, and zero is valid. This path denies a negative margin, checked-add overflow, or a resulting absolute cleanup deadline above Redis/Lua's exact-integer ceiling of `9007199254740991` milliseconds; that actual-deadline bound is stricter than the global signed-64-bit margin arithmetic above and does not define a new universal margin policy. Retained rows without a persisted cleanup deadline remain untouched and are not backfilled or retried under a new deadline.

## Proto Files

The gRPC schemas for this service live in [`protos/account/v1`](../../../../protos/account/v1). Use `./gradlew generateProto` to regenerate Java stubs when the definitions change.

## Held first-invoice checkout configuration

The prepared [first-invoice owner](./stripe-integration.md#first-invoice-confirmation-and-completion) has no enabling flag or automatically registered bean/controller. Explicit composition requires the Account Stripe client, a protected webhook signing secret (`firemud.payment.hosting-webhook-secret`), a public Stripe publishable key for the provider-authentication client, finite provider transport limits and genuine current Account/control-ui owners. Secrets must not be logged or exposed through property diagnostics. Separate versioned billing-purpose reauthentication policies and exact commercial billing-policy rows are required; absent rows refuse and no migration seeds a default. Configuration alone does not clear registration or custody gates.

## Canonical Gameplay LOGIN Receiving

Canonical credential envelopes and exact-original retirement share the existing AccountService registration through `AccountGameplayAuthenticateReceiver`. The receiver, complete initial-LOGIN source graph and protected source selection all require `firemud.account.gameplay-login.enabled=true`; they are absent by default. `AccountGameplayLoginSourceConfiguration` composes the existing issuance/evidence/encrypted-response repositories, protected signer and trusted JWKS reader, issuer/account projection publisher, token registry, commit, activation and exact response-recovery owners. Registration supplies no synthetic owner evidence and performs no enrollment, key generation, publication or Redis mutation.

The enabled composition requires these explicitly delivered inputs:

- `firemud.grpc.workload-namespace` and `firemud.account.public-jwks.api-binding-path=/etc/firemud/account-jwt-api/binding.json`. The existing protected API binding and enabled `firemud.account.jwt-signer.materialization` trust binding must be readable and agree on environment, cluster, namespace and cluster/namespace incarnations. Their namespace must equal the configured workload namespace. The API identity is pinned at composition and rechecked by the existing owners on use; this does not create the enrolled signer, committed promotion or Account-owned public publication evidence.
- `firemud.account.gameplay-login.coordination.uri`, delivered through protected application configuration, selects only the dedicated standalone Coordination endpoint. Its shape is `rediss://account_coord_app:<encoded-password>@<host>:<explicit-port>/0`; missing credentials, another principal/database, plaintext, URI query options and fragments are rejected. TLS peer verification remains enabled against the platform-provisioned JVM trust anchors. Do not put this credential-bearing value in diagnostics or substitute generic `firemud.redis.*` configuration. The dedicated Lettuce provider disables automatic reconnect and closes with the application; the existing Redis owner independently checks the live ACL identity.
- `firemud.account.gameplay-login.coordination.command-timeout-millis`, `required-local-aof-count`, `required-replica-aof-count` and `acknowledgement-timeout-millis` under that same `coordination` prefix. Both timeouts must be between 1 and 60,000 milliseconds; the local AOF count must be one, and the replica count must be between one and sixteen. Game Session's separate qualified single-node exception does not apply to Account. No timeout or ACK policy is inherited from the ambient Redis client.
- `firemud.account.gameplay-login.registry-cleanup-margin-millis`, explicitly between zero and 300,000 milliseconds. The registered record byte ceiling remains the fixed approved delegation profile limit; this margin does not authorize cleanup of held disposal markers or retained SQL evidence.
- The absolute normalized `firemud.account.gameplay-login.request-digest-keyring-path` and `firemud.account.response-envelope.keyring-path` mounts. Existing mounted-key readers retain their active/retained-key, freshness and withdrawal checks; configuration does not create keys or replace absent mounted material.

Missing or mismatched protected trust, absent selectors and invalid explicit policy prevent enabled composition. Missing current key, enrollment, publication, authority or Redis evidence still denies the existing owner operation. Source-bearing requests never fall back to legacy Authenticate. This configuration does not provision credentials, qualify external Redis reset/restore/promotion continuity, register Game Session's separate initial-context authority or enable gameplay admission. Source and proof status belong to [Player Access and Session](../../../project-management/implementation-tracking/player-access-and-session.md#initial-private-gameplay-login-issuance-source-intake).

## Private Current Public-JWKS Read

`firemud.account.public-jwks-read.enabled=true` separately composes the private [current-publication read](./api-contracts.md#private-current-public-jwks-read); it is disabled by default. `AccountPublicJwksSourceConfiguration` selects this read or canonical gameplay LOGIN and explicitly imports the pure `AccountProtectedJwksSourceConfiguration` owner. The latter supplies one protected API/client/trusted-source graph without a component-scanned stereotype or consumer-selection condition; another independently selected Account consumer imports that same owner without selecting LOGIN or the private public-read receiver. The read alone requires the workload namespace, fixed `firemud.account.public-jwks.api-binding-path=/etc/firemud/account-jwt-api/binding.json`, enabled existing materializer trust binding, Account signer/publication repositories and transaction manager. It does not require LOGIN Coordination Redis or request/response keyrings. Enabling both consumers does not create duplicate trust sources.

The existing ordinary gRPC listener registers the receiver under that opt-in. The exact `account.v1.AccountPublicJwksReadService/GetCurrentPublicJwks` entry in both application profiles exempts only bearer authentication; the global TLS peer identity interceptor and method-local same-namespace Game Session workload guard remain mandatory. A current protected publication must already exist. Configuration performs no enrollment, publication, credential provisioning, runtime activation or traffic opening. Proof status and remaining custody requirements belong to [Player Access and Session](../../../project-management/implementation-tracking/player-access-and-session.md#initial-private-gameplay-login-issuance-source-intake).
