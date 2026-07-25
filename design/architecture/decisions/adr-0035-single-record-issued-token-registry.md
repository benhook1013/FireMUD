# ADR 0035: Single-Record Issued-Token Registry

## Status

Accepted

## Implementation Status

Basic JWT/JWKS and account/tenant token-state foundations exist, but the accepted single-record contract is not implemented end to end. Current issuance still writes scope-duplicated account/tenant keys, downstream validators do not consistently enforce `session:auth:token:<tokenHash>`, and complete authority-generation checks, per-token `/auth/logout`, `/auth/logout-all`, durable operation evidence, retry classification, cleanup, and revocation proof remain incomplete.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `SF-1.3` Authentication, authorization, service identity, and secret handling
- Affected capabilities: `SF-2.2`, `AA-1.3`, `AA-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `JWT-01`

## Context

FireMUD combines locally verifiable JWTs with server-side issued-token state so a single token can be logged out immediately, refresh remains generation-bound, and possession of an exfiltrated signing key alone does not create an accepted token. The previous design created one account record, one record for every tenant in the token, and another record for global roles. Tenant requests could require multiple lookups, while issuance, rotation, and logout had to keep a variable set of duplicate records consistent.

Tenant and global authorization already comes from strictly validated signed claims plus Account-owned account, tenant, and membership authority-generation/version state. Duplicating those scopes into per-token Redis key names adds cardinality and partial-update cases without adding a distinct authorization boundary.

The current implementation writes account and tenant keys but does not consistently enforce the complete registry contract in downstream validators. Global entries, full authority-generation enforcement, and end-to-end revocation proof remain incomplete.

## Decision

### One Registry Record Per Revocable JWT

- Account Service creates exactly one Coordination Redis record for each issued `control-ui`, `player-bootstrap`, or receiver-specific private player-delegation JWT: `session:auth:token:<tokenHash>`.
- `tokenHash` is a fixed-length SHA-256 digest of the complete compact JWT. Raw token contents never appear in Redis keys, values, logs, metrics, traces, or audit evidence.
- The bounded record contains `schemaVersion`, `accountId`, exact token profile/audience, `jti`, `iat`, `exp`, the JWT's positive `tokenGeneration`, and active state. It does not duplicate tenant-role maps or global-role grants from the signed token.
- Its absolute expiry is the JWT `exp` plus the bounded validation-skew/safety margin. Activity does not extend it.
- Account atomically establishes the record before returning the JWT. If registration fails, issuance fails and the token is never exposed to the caller.

### Validation And Authorization

- A consumer first validates signature and `kid`, issuer, exact audience/profile, required claims, time bounds, and claim types locally.
- A protected control-plane or admission operation then performs one issued-token registry lookup and verifies that the record uses a supported `schemaVersion`, is active, and matches the token hash, account, profile, `jti`, `tokenGeneration`, and time claims. Missing, inactive, unsupported-version, or mismatched state denies the token, except for the bounded no-op logout retry classifications defined by ADR 0031.
- The registry proves that Account issued this exact still-active token; it does not independently grant tenant or global authority. Consumers authorize the requested scope from the validated token profile/claims and the separate Account-owned revocation/version contract reviewed under JWT-02.
- Registry lookup is not part of ordinary gameplay-command processing. Gameplay-domain delegation retains the mTLS workload and typed execution-context boundary from ADR 0024.

### Profile Boundaries

- `control-ui`, player-bootstrap, and receiver-specific private player-delegation JWTs use the registry because they require individual logout or generation-bound refresh. The current private profile is `game-session-account-delegation` with audience `account-service`.
- The 30-second gameplay connect token uses its dedicated Gateway-owned atomic single-use/replay contract from ADR 0029 and does not also receive an issued-token registry record.
- Gateway-to-Game-Session signed connect context is a separate short-lived workload assertion, not an Account JWT session, and does not use this registry.

### Revocation And Rotation

- Per-token logout deletes the single token record idempotently. Other devices, tokens, and gameplay bindings remain unaffected.
- Bulk account, tenant, and membership revocation uses monotonic authority generations rather than scanning token records or encoding every scope in the token key. These generations are not epoch timestamps.
- Generation-bound private player-delegation rotation creates the replacement record before returning it, atomically swaps the gameplay binding as defined by ADR 0031, and deletes the old record after the bounded in-flight overlap.
- Registry absence is default denial. Coordination reset therefore forces reauthentication/reissuance rather than making unregistered but cryptographically valid tokens acceptable.

### Durable Idempotency Evidence And Retry Semantics

ADR 0031's idempotent retirement, per-token logout, and logout-all rules require durable Account-owned evidence in addition to the Redis registry record. Redis absence is not, by itself, proof that one of those operations completed.

- The canonical public logout API uses `POST /auth/logout` for one presented `control-ui` or `player-bootstrap` token and `POST /auth/logout-all` for the authenticated account-wide cutoff. The caller-supplied high-entropy `requestId` is the operation ID for both endpoints; no second public operation-ID field is introduced. Account computes and stores a versioned `requestDigest` from the normalized operation tuple: `TOKEN_LOGOUT`, subject account, exact token profile, and token hash for `/auth/logout`; `ACCOUNT_LOGOUT_ALL`, subject account, exact token profile, and presented token hash for `/auth/logout-all`. Retirement acknowledgements use the same evidence model with operation kind `TOKEN_RETIREMENT`, predecessor token hash, and refresh lineage. Raw JWTs never enter the digest or evidence.
- Every retirement acknowledgement, per-token logout, and logout-all request binds its operation ID to that immutable request digest. Reusing an operation ID with different meaning, token, profile, account, or scope is rejected as an idempotency conflict.
- Account stores a bounded durable operation record outside Coordination Redis. Before the first registry mutation it commits a `PENDING` record containing the immutable operation ID, request digest, operation kind, subject, and expected token or lineage identity. After the idempotent registry mutation and required durable acknowledgement, it advances that same record to `COMMITTED` with the mutation outcome and completed tombstone; validation or idempotency conflicts become terminal `FAILED` records and do not mutate Redis. A completed logout-all also records the durable logout event identity and the account authority generation that superseded the presented token. The evidence is not an authorization grant and contains no raw JWT.
- A bounded Account reconciler claims stale `PENDING` operations. It revalidates the immutable digest and operation state, idempotently repeats or verifies the intended mutation, and commits the terminal tombstone only after the required Redis outcome is established. A crash before the Redis mutation therefore resumes it; a crash after the mutation but before the durable commit cannot turn bare registry absence into success without the matching precommitted `PENDING` evidence. Reconciliation serializes with concurrent requests for the same operation and token lineage, and an ambiguous state fails closed rather than returning stored success.
- A retry with the same `requestId` and matching `requestDigest` returns the stored success only from `COMMITTED` after the full local signature, profile, time, and subject validation required by ADR 0031; it does not mint, reauthorize, or repeat unrelated mutations. A `PENDING` record is resumed or reconciled and a `FAILED` record returns its stable failure; neither is reported as success. A different request ID for `/auth/logout` may return no-op success only when a token-specific completed tombstone proves that exact token was previously revoked; a missing registry record without that evidence remains denied.
- A `/auth/logout-all` retry may return no-op success when the presented token is already superseded only if durable Account evidence proves the prior logout-all event and generation that caused the supersession. That retry does not advance the generation or mutate state. A current token is handled as a new normally authorized operation, while an ambiguous or differently scoped request is denied rather than classified as a retry.
- Durable evidence has an explicit bounded retention horizon: no shorter than the maximum supported retry/idempotency window plus the required audit/outbox delivery window, and for token-specific tombstones no shorter than the token's `exp` plus validation skew. Logout-all supersession proof is retained through the configured authority-event/retry horizon needed to classify later retries. Background cleanup physically removes evidence only after its horizon and must not remove the only proof still required for a stored-success or supersession response. Cleanup is itself idempotent and does not change revocation authority.

## Consequences

- Each revocable token creates one bounded key and requires one registry lookup rather than account plus tenant/global key combinations.
- Issuance, logout, rotation, expiry, and cleanup cannot partially update a variable set of scope records.
- Exfiltration of an Account signing key without access to Account-owned registry writes is insufficient to create an accepted token.
- Protected control-plane and admission calls retain one Coordination Redis availability dependency. Ordinary gameplay commands do not acquire that dependency.
- Per-token state is simpler but does not itself provide a device/session listing. A future device-management UI would require a bounded Account-owned index, not key scanning.
- Tenant/global authority remains dependent on correct claim-profile validation and the separately reviewed revocation/version contract rather than duplicated token-key scopes.

## Alternatives Considered

### Keep Account, Tenant, And Global Keys Per Token

This makes each scope visible in the keyspace but duplicates claims, creates one-to-many key growth, requires multiple validation reads, and introduces partial issuance/logout/rotation states. Authority generations already provide scoped bulk revocation.

### Stateless JWT Validation Only

This removes registry writes and lookups but loses immediate single-token logout and generation-bound refresh, and an exfiltrated signing key can mint accepted tokens until key cutover completes.

### Revoked-Token Denylist

A denylist writes fewer records during issuance, but absent state means allow. Lost/reset denylist state can resurrect revoked tokens, and signing-key possession is sufficient to mint tokens not present on the denylist.

### Opaque Reference Tokens

Opaque tokens can provide the same server-side revocation but require the state lookup to supply all claims and scope on every consumer. The hybrid JWT plus one issuance-record model preserves strict local cryptographic/profile validation and bounded claims while retaining immediate token lifecycle control.

## Implementation and Proof Obligations

- Replace account/tenant/global per-token keys with the single canonical `session:auth:token:<tokenHash>` record and remove obsolete key builders and compatibility reads directly in this pre-v1 system.
- Define one versioned bounded record schema and validate every field against the cryptographically verified token.
- Make issuance return contingent on record creation; prove store failure never leaks a usable unregistered token.
- Update shared validators so every applicable protected route performs local token-profile validation and exactly one registry lookup before scope authorization.
- Prove missing, expired, malformed, unsupported-version, inactive, wrong-profile, wrong-account, wrong-generation, deleted, and unavailable registry state fails closed with stable errors.
- Prove per-token logout deletes one record and leaves other tokens active; prove rotation establishes the replacement before exposure and removes the predecessor after bounded overlap.
- Prove crashes before and after each cross-store mutation converge through the durable `PENDING`/`COMMITTED`/`FAILED` operation state machine without treating unexplained Redis absence as success.
- Prove connect-token replay and Gateway connect-context paths do not create or consult the Account issued-token registry.
- Update Redis key catalogs, reset behavior, memory budgets, ACLs, operational tooling, and retained evidence for the one-key contract.

## Required Documentation Alignment

- `design/architecture/system-architecture-jwt-and-token-contracts.md`
- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-redis-cheatsheet.md`
- `design/architecture/system-architecture-redis-reset-and-recovery.md`
- `design/architecture/microservices/account-service/runtime-and-data.md`
- `design/architecture/microservices/account-service/api-contracts.md`
- `design/architecture/decisions/adr-0031-revocation-safe-session-token-rotation-and-logout.md`

## Reversibility and Revisit Triggers

The record schema is versioned and the token hash remains stable, so additional bounded fields can be introduced without changing the public token carrier. Revisit if an external identity provider becomes the token/session authority, control-plane request volume makes one registry lookup materially expensive, or a device-management product requires a durable Account-owned session index. Do not reintroduce scope-duplicated keys merely to build such an index.
