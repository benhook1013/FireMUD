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
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `JWT-01`

## Context

FireMUD combines locally verifiable JWTs with server-side issued-token state so a single token can be logged out immediately, refresh remains generation-bound, and possession of an exfiltrated signing key alone does not create an accepted token. The previous design created one account record, one record for every tenant in the token, and another record for global roles. Tenant requests could require multiple lookups, while issuance, rotation, and logout had to keep a variable set of duplicate records consistent.

Tenant and global authorization already comes from strictly validated signed claims plus Account-owned account, tenant, and membership authority-generation/version state. Duplicating those scopes into per-token Redis key names adds cardinality and partial-update cases without adding a distinct authorization boundary.

The current implementation writes account and tenant keys but does not consistently enforce the complete registry contract in downstream validators. Global entries, full authority-generation enforcement, and end-to-end revocation proof remain incomplete.

### Canonical Authority Tuple

Every JWT claim set, registry record, revocation event, Account lease, gameplay binding, refresh request, rebind proof, and installation acknowledgement uses the same logical `authorityTuple` and exact field names:

```text
authorityTuple: {
  issuerAuthGeneration,
  accountAuthorityGeneration,
  tenantAuthorityGeneration: { tenantId: generation },
  membershipAuthorityGeneration: { tenantId: generation },
  privateRealmGrantVersions: [
    { tenantId, worldSlug, realmSlug, grantVersion }
  ],
  accountSecurityCutoff: {
    accountAuthorityGeneration,
    outboxStreamKey,
    outboxSequence
  }?,
  tenantBillingCutoff: {
    tenantId: {
      tenantAuthorityGeneration,
      tenantBillingSequence,
      outboxStreamKey,
      outboxSequence
    }
  }?
}
```

`issuerAuthGeneration` and `accountAuthorityGeneration` are positive Account-owned generations. `tenantAuthorityGeneration` and `membershipAuthorityGeneration` are independent maps keyed by exact tenant IDs; each map's applicable keys are determined separately by the token profile and route classification. The closed `billing_safe_tenant` exception can therefore require a membership entry while deliberately omitting the target-tenant generation. Explicitly unscoped artifacts use empty maps. `privateRealmGrantVersions` contains exact `{tenantId, worldSlug, realmSlug, grantVersion}` entries and is empty for public production. `accountSecurityCutoff` and `tenantBillingCutoff` are optional cutoff evidence, not replacement authorities; each is omitted when it is not applicable, and a present `tenantBillingCutoff` contains only exact applicable tenant-ID entries. `membershipVersion` is a separate Account-owned membership projection/version map. Its one canonical non-applicable representation is the empty map `{}`; it is never omitted, `null`, a scalar sentinel, or a textual not-applicable value. When applicable, it contains only exact tenant-ID entries. The JWT claim, registry field, Account evidence-bundle value, and binding value must be structurally and value-equal; `membershipVersion` is compared independently from `membershipAuthorityGeneration`, and neither field substitutes for the other. A missing applicable field, extra scope, malformed value, or mismatch fails closed.

The tuple is copied without renaming or reinterpretation into every applicable registry record/claim, payload, lease, binding, refresh request, rebind proof, and installation acknowledgement. `issuanceFence` is copied alongside the tuple as the Account composite authority fence captured by the issuance transaction or CAS; it is not a substitute for any tuple member.

The Account durable authority record, its immutable versioned evidence bundle, and the source transaction/outbox evidence named by that bundle are read-only authority inputs to consumers. A consumer or authenticated event handler must not rewrite, merge, advance, or repair those canonical values. Authenticated Account authority events may update only a derived consumer-local projection, using validated per-stream checkpoints and set-if-greater semantics; such a projection is cache/reconciliation state and never becomes a replacement Account authority or source-evidence record.

### Versioned Account Evidence Bundle And Linearization

Account exposes one immutable, versioned `account-auth-evidence-bundle/v1` for each authorization operation. At one Account linearization point, the bundle contains only Account-owned authority state: the complete applicable registry tuple, both cutoff objects, `issuanceFence`, the exact token-identity fence, `membershipVersion` (the exact tenant-ID map, or `{}` when non-applicable), the exact Account scope, a positive `bundleVersion`, the durable source transaction or outbox version, projection freshness/source evidence, and `outboxCheckpoints`. `outboxCheckpoints` contains one exact `{outboxStreamKey, outboxSequence}` checkpoint for every applicable authority stream defined by the scope. Checkpoints cover the exact `account:auth-authority:v1:<scopeId>` stream keys involved in the bundle, with no aggregate maximum or cross-stream sequence substituted for an individual stream checkpoint. Redis registry and lease state plus Game Session binding and installation state are not Account-linearized bundle fields.

Account issuance and refresh use one Account transaction or composite CAS/fence to create the durable Account evidence and the versioned `account-auth-evidence-bundle/v1` reference. That transaction/CAS does not create the Coordination Redis registry record or any Redis lease or Game Session binding/installation. Each registry, lease, binding, and installation is a separately versioned, fenced, idempotent postcondition outside the Account transaction and carries the same operation ID, request ID, canonical digest, and `issuanceFence`; retries and reconciliation apply only missing postconditions. The registry record stores the matching Account `bundleVersion` and source-version/freshness evidence with its tuple. Lease, binding, and installation evidence stores its owning service's positive version/fence plus the exact registry identity and Account bundle reference it satisfies.

One validation attempt performs exactly one atomic issued-token registry lookup, which returns one record snapshot and its positive registry version, then reads one complete immutable Account bundle and each applicable downstream owner evidence at the exact version/fence named by the registry or durable operation. It compares the JWT, registry tuple, every applicable cutoff as its complete generation-plus-`outboxStreamKey`-plus-`outboxSequence` tuple, every applicable `outboxCheckpoint`, separate `membershipVersion`, Account fences, and each independently owned lease, binding, or installation version as one decision. The `membershipVersion` claim, registry field, and Account evidence value must use the same exact map, including `{}` for non-applicable membership; no omitted/null/sentinel form is equal. If an owner reports that a requested bundle, registry-linked postcondition, or version/fence was superseded while it was being read, the consumer discards the entire attempt and retries from a new registry lookup; a second change or any reachable mismatch denies. Timeouts and unavailable owners return retryable `AUTH_UNAVAILABLE`. No consumer rereads unrelated fields, combines evidence from different attempts, or claims a cross-store atomic snapshot.

An unreachable or timed-out Account bundle, registry, lease, binding, or evidence source is unavailable and returns retryable `AUTH_UNAVAILABLE`. A reachable bundle or record that is missing, malformed, ambiguous, stale, regressed, expired, or mismatched is authoritative invalid/revoked evidence and denies the operation; it is not reclassified as unavailable. The same classification applies to retries and reconciliation, so a timeout cannot become success and reachable invalid evidence cannot become an availability exception.

## Decision

### One Registry Record Per Revocable JWT

- Account Service creates exactly one Coordination Redis record for each issued `control-ui`, `player-bootstrap`, or receiver-specific private player-delegation JWT: `session:auth:token:<tokenHash>`.
- `tokenHash` is a fixed-length SHA-256 digest of the complete compact JWT. Raw token contents never appear in Redis keys, values, logs, metrics, traces, or audit evidence.
- Every supported version of the bounded record contains `schemaVersion`, a non-empty string JWT `kid`, `accountId`, exact token profile/audience, `jti`, `iat`, `exp`, the profile-defined positive `tokenGeneration`, the complete applicable `authorityTuple`, the JWT's separate `membershipVersion` map (empty `{}` when non-applicable), positive `issuanceFence`, the matching Account evidence-bundle version/source/freshness evidence, and the explicit `state` defined below. The stored `kid` must be the exact verified JWT header key identifier; it is a string identifier, not a numeric generation. The record does not duplicate tenant-role maps or global-role grants from the signed token.
- A receiver-specific private player-delegation record created by rotation has a mandatory conditional field set: `rotationOperationId`, `leaseId`, positive `leaseVersion`, exact `gameplayBindingId`, positive `installationFence`, positive `issuanceFence`, and the complete applicable `authorityTuple`. These fields are required whenever the record participates in `TOKEN_ROTATION`; they are not optional metadata. Account compares them with the durable rotation operation, replacement lease, exact Game Session binding, and Account-owned installation fence before activation, installation, authorization, or retry. Reachable missing, expired, malformed, stale, or mismatched conditional evidence is invalid/revoked; dependency unavailability is classified as retryable `AUTH_UNAVAILABLE` under the dependency-outcome rules below. The record cannot fall back to JWT claims, mTLS identity, token hash, or registry presence alone.
- `authorityTuple.issuerAuthGeneration` is the canonical registry field name and must equal the JWT `authorityTuple.issuerAuthGeneration` claim and the value in Account's durable issuer-authority record at issuance or refresh. Account owns that durable record and its monotonic advances; the registry value is a snapshot whose Redis projection is accepted only with Account-provided source-version and freshness evidence. The registry does not become a second issuer authority and may not advance, repair, or override the durable record.
- Its absolute expiry is the JWT `exp` plus the bounded validation-skew/safety margin. Activity does not extend it.
- One Account transaction or composite Account CAS commits only the durable Account issuance operation, exact authority snapshot, and evidence-bundle reference; it does not claim to atomically write Coordination Redis or Game Session state. The Redis registry record and any applicable Redis lease or Game Session binding/installation are separately fenced, idempotent postconditions outside that transaction, bound to the same operation, request, digest, and `issuanceFence`. Account verifies every applicable postcondition before exposing a successful, usable result. A partial or ambiguous postcondition remains pending and non-authorizing until reconciliation by exact operation identity; no store's presence or absence substitutes for another. If registration or another required postcondition fails, issuance fails and the token is never exposed to the caller.

### Registry State Machine

- The registry `state` is an explicit enum: `pending`, `active`, `retiring`, or `revoked`; an absent or expired record is denial, not an implicit active state. `pending` is a non-authorizing replacement candidate awaiting registry activation. It may exist while its durable rotation operation is `PENDING` and may remain `pending` after that operation reaches `COMMITTED` until the exact Redis activation postcondition completes; `COMMITTED` evidence alone never authorizes the replacement. After the normal signature, profile, time, identity, and authority checks, `active` is the only registry state that may participate in authorization, serve as a refresh or rebind source, or begin per-token logout. For a rotated receiver-specific private delegation, `active` is necessary but not sufficient: authorization also requires the Account-owned replacement lease and binding-installation fence recorded for that registry entry to be durably `INSTALLED` for the exact token, lineage, and gameplay binding. A registry-active replacement with an `ISSUED`, `INSTALLING`, expired, aborted, superseded, missing, or mismatched lease/fence remains non-authorizing.
- `retiring` is the predecessor overlap state. Account enters it only after the replacement record and the durable rotation lease exist, and records `predecessorUsableUntil = min(originalExp, replacementIssuedAt + maxInFlightRpcDeadline)`. A predecessor may finish an RPC that was already admitted while it was `active` and may accept the matching idempotent `TOKEN_RETIREMENT` acknowledgement or reconciliation, but a fresh lookup in `retiring` denies new control-plane/admission requests, refresh, rebind, and binding installation. Presence of a `retiring` record alone never authorizes a new call.
- `revoked` is terminal and denies validation, refresh, rebind, installation, and any new logout attempt that lacks the exact prior idempotency identity. An exact retry of the durable logout operation may return its stored committed/no-op outcome, but the revoked token never authorizes that response. Account's durable logout fence/tombstone or rotation evidence remains the correctness authority; the Redis record may then be deleted or expire, and absence remains default denial. Transitions are monotonic: issuance creates `active`, rotation creates a non-authorizing `pending` replacement, and only committed rotation evidence activates that replacement and moves the predecessor to `retiring`; logout, abort, authority cutoff, or overlap expiry moves a record to `revoked`; `retiring` never returns to `active`. `PENDING_LOGOUT`, `PENDING` rotation, and `COMMITTED` are Account operation/fence states, not registry allow states.

### Validation And Authorization

- A consumer first validates signature and `kid`, issuer, exact audience/profile, required claims, time bounds, and claim types locally.
- A JWT-presenting registry-backed control-plane, bootstrap, or admission operation then performs exactly one issued-token registry lookup and verifies that the record uses a supported `schemaVersion`, satisfies the operation-appropriate state rules above, and matches the token hash, `kid`, account, profile, `jti`, `tokenGeneration`, complete `authorityTuple`, separate `membershipVersion`, `issuanceFence`, and time claims. A rotated private-delegation record must contain the mandatory `rotationOperationId`, `leaseId`, `leaseVersion`, `gameplayBindingId`, `installationFence`, `issuanceFence`, and complete `authorityTuple`, and Account validation fails closed when reachable evidence is absent, stale, malformed, regressed, or mismatched with the durable operation, lease, exact gameplay binding, or installation fence. The matching Account lease must be `INSTALLED` before the replacement authorizes protected use. Missing, `pending`, `revoked`, unsupported-version, or mismatched state denies the token, except for the bounded no-op logout retry classifications defined by ADR 0031.
- The same validation must obtain one versioned Account evidence bundle for every applicable field in `authorityTuple`, both cutoff objects, `issuanceFence`, and `membershipVersion`. Separately, it obtains the exact versioned owner evidence for every applicable registry, lease, binding, and installation relationship. Account evidence identifies the exact Account scope, bundle/source version, freshness status, and committed Account-owned value against which the registry snapshot and JWT claim are checked; downstream evidence identifies its owning service, operation/request/digest binding, positive version/fence, and exact Account bundle plus registry identity. None of this evidence may be inferred from another store, the JWT, `iat`, JWKS, or mere record presence. A reported version change discards the whole validation attempt and permits one fresh retry; repeated change or reachable missing, malformed, ambiguous, stale, regressed, expired, or mismatched evidence denies, while dependency unavailability is retryable `AUTH_UNAVAILABLE`.
- The registry proves that Account issued this exact token and its lifecycle state permits the requested operation; it does not independently grant tenant or global authority. Consumers authorize the requested scope from the validated token profile/claims and the separate Account-owned revocation/version contract reviewed under JWT-02 and [ADR 0036](./adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md).
- Account Service is the sole writer and repair/reprojection authority for the durable issuer record and its registry projections. Consumers, Redis repair jobs, and other services may not advance, overwrite, or reinterpret `issuerAuthGeneration` or source-version evidence.
- Registry lookup is not part of ordinary gameplay-command processing. The dedicated `gameplay-connect` artifact is the explicit exception: its Gateway-owned atomic single-use/replay-fence handshake from ADR 0029 has no issued-token registry record. Non-JWT in-band gameplay operations likewise do not trigger this lookup. Gameplay-domain delegation retains the mTLS workload and typed execution-context boundary from ADR 0024.

### Registry Field And Claim Mapping

The registry and JWT use the same nested names under `authorityTuple`; no legacy aliases or renamed map fields are accepted. The registry snapshot and verified JWT must have identical applicable scope sets and values:

| Canonical field | JWT claim | Registry field | Scope rule |
| --- | --- | --- | --- |
| `issuerAuthGeneration` | `authorityTuple.issuerAuthGeneration` | `authorityTuple.issuerAuthGeneration` | One positive issuer value |
| `accountAuthorityGeneration` | `authorityTuple.accountAuthorityGeneration` | `authorityTuple.accountAuthorityGeneration` | One positive account value |
| `tenantAuthorityGeneration` | `authorityTuple.tenantAuthorityGeneration` | `authorityTuple.tenantAuthorityGeneration` | Exact tenant-ID map; empty only when explicitly unscoped |
| `membershipAuthorityGeneration` | `authorityTuple.membershipAuthorityGeneration` | `authorityTuple.membershipAuthorityGeneration` | Exact independently applicable tenant-ID map; may contain the caller-membership tenant when a closed route class omits target-tenant generation |
| `privateRealmGrantVersions` | `authorityTuple.privateRealmGrantVersions` | `authorityTuple.privateRealmGrantVersions` | Exact `{tenantId, worldSlug, realmSlug, grantVersion}` entries; empty for public production |
| `accountSecurityCutoff` | `authorityTuple.accountSecurityCutoff` | `authorityTuple.accountSecurityCutoff` | Present only when applicable, with exact cutoff checkpoint |
| `tenantBillingCutoff` | `authorityTuple.tenantBillingCutoff` | `authorityTuple.tenantBillingCutoff` | Optional exact tenant-ID map and checkpoint values when applicable; omitted otherwise |
| `membershipVersion` | `membershipVersion` | `membershipVersion` | Exact separate profile-applicable membership projection/version map; `{}` in all three locations when non-applicable |
| `issuanceFence` | `issuanceFence` | `issuanceFence` | Positive Account fence captured with the tuple; never substitutes for it |

For an explicitly unscoped profile, `tenantAuthorityGeneration` and `membershipAuthorityGeneration` are empty maps, `privateRealmGrantVersions` is an empty list, and `membershipVersion` is the empty map `{}` in both JWT and registry and in the Account evidence bundle; `tenantBillingCutoff` is omitted when no tenant billing cutoff applies. A tenant-bound profile has exactly the applicable tenant and realm keys, and a present `tenantBillingCutoff` has only the exact applicable tenant-ID entries; no account ID, wildcard, bare realm, fabricated tenant, or differently keyed entry is accepted. Any missing applicable field, extra scope, malformed, stale, regressed, or mismatched field fails closed. Rotation captures the same snapshot and `issuanceFence` in the pending registry record, replacement lease, binding-installation evidence, and response-envelope binding; a newer Account fence or any tuple mismatch invalidates the replacement and prevents activation.

### Dependency Outcomes

Validation classifies dependency results by whether the dependency was reachable, not by whether a cache or empty response was available:

| Evidence source | Unavailable or timed out | Reachable but invalid, stale, missing, or malformed |
| --- | --- | --- |
| Issued-token registry | Retryable `AUTH_UNAVAILABLE`; deny the operation and do not mark the token revoked solely because Redis is unavailable | `AUTH_SESSION_REVOKED` or invalid-token outcome; absence is default denial |
| Account lease or rotation operation | Retryable `AUTH_UNAVAILABLE`; no activation, installation, or stored-success response | Invalid/revoked; pending, expired, aborted, superseded, missing, or mismatched evidence cannot authorize |
| Gameplay binding or binding CAS/fence | Retryable `AUTH_UNAVAILABLE`; no rebind or replacement admission | Invalid/revoked; wrong binding, version, `issuanceFence`, tuple, or installation state fails closed |
| Account token-identity fence and authority evidence | Retryable `AUTH_UNAVAILABLE`; no refresh, logout completion, or replacement issuance | Invalid/revoked; missing, regressed, stale, malformed, or mismatched fence/tuple evidence fails closed |

The same classification applies to every registry, lease, binding, fence, and Account evidence read used by a retry or reconciler. An ambiguous mutation response is reconciled by immutable operation ID and digest; it is never treated as success from registry presence or absence alone. A separate bounded Account-encrypted response envelope may retain the exact response credential for a matching retry after reconciliation, but it is not durable operation evidence, registry state, or authority.

### Profile Boundaries

- `control-ui`, player-bootstrap, and receiver-specific private player-delegation JWTs use the registry because they require individual logout or generation-bound refresh. The current private profile is `game-session-account-delegation` with audience `account-service`.
- The 30-second gameplay connect token uses its dedicated Gateway-owned atomic single-use/replay contract from ADR 0029 and does not also receive an issued-token registry record.
- Gateway-to-Game-Session signed connect context is a separate short-lived workload assertion, not an Account JWT session, and does not use this registry.

Every registry-backed profile declares mandatory finite issuance limits in the versioned Account token-profile catalog: `maxTenantAuthorityEntries`, `maxMembershipAuthorityEntries`, `maxMembershipVersionEntries`, `maxTenantBillingCutoffEntries`, `maxPrivateRealmGrantEntries`, `maxAuthorityTupleBytes`, `maxCompactJwtBytes`, and `maxRegistryRecordBytes`. The `control-ui` catalog entry additionally declares `maxControlUiTenantScopes` as a finite positive integer for its tenant-scope cardinality limit. Account startup rejects a profile whose limits are missing, non-positive where entries are permitted, inconsistent with the profile shape, or above the deployment's validated transport and Coordination Redis ceilings; it rejects a `control-ui` entry unless `maxControlUiTenantScopes` is present, finite, and positive. Issuance and refresh canonicalize the complete claim/record candidate once and reject any over-cardinality or over-byte input deterministically before JWT signing, durable issuance commit, or registry registration; retries preserve that rejection and cannot create a partial token record.

The profile cardinality contract is:

| Profile | Tenant authority | Membership authority/version | Tenant billing cutoff | Private-realm grants |
| --- | --- | --- | --- | --- |
| `control-ui` | At most the catalog's finite positive `maxControlUiTenantScopes`; keys exactly equal non-empty `scopedRoles` keys whose routes require target-tenant authority | Independently capped by `maxMembershipAuthorityEntries` and `maxMembershipVersionEntries`; keys exactly equal the caller memberships needed by the token's allowed route classifications, including a `billing_safe_tenant` key that deliberately has no tenant-authority entry | At most the configured `maxTenantBillingCutoffEntries` and only for applicable scoped tenants | `0` |
| `player-bootstrap` | `0` | `0` | `0` | `0` |
| Tenant-bound `game-session-account-delegation` | Exactly `1` | Exactly `1` with the same tenant key | `0` or `1` for that tenant | `0` for public production or exactly `1` for the selected private realm |
| Explicitly non-tenant private delegation | `0` | `0` | `0` | `0` |

`control-ui` remains valid for the closed `billing_safe_tenant` routes. Its tenant-authority, membership-authority/version, and billing-cutoff key sets are derived independently from the route classifications represented by the token: omission of target-tenant authority for `billing_safe_tenant` never removes that route's caller-membership generation, membership version, live-role, or billing-cutoff evidence. `accountSecurityCutoff` is absent or one object for every registry-backed profile. `maxControlUiTenantScopes` and every entry or byte ceiling are explicit bounded platform security settings, not values inferred from current account membership count, proxy defaults, or Redis acceptance. They are configured once for the environment through the canonical platform settings authority and constrained by deployment validation; a tenant/game cannot raise them. Focused implementation proof must cover every profile at each exact count boundary, one-entry overflow, each encoded-byte boundary, total compact-JWT and registry-record overflow, and rejection before signing or registration.

### Token Generation Semantics

`tokenGeneration` is a positive integer only for the registry-backed JWT profiles and is distinct from issuer, account, tenant, membership, and private-realm authority generations:

- `control-ui` and `player-bootstrap` have no refresh or restoration lineage. Each independently issued token uses `tokenGeneration=1`; a later login or bootstrap flow creates a new token and new registry record rather than incrementing an old lineage.
- `game-session-account-delegation` has one generation lineage per protected gameplay binding. The first token uses `tokenGeneration=1`, and each committed refresh or rebind replacement increments that lineage exactly once. A retry or reconciliation of the same operation preserves the already committed generation and cannot allocate another one.
- `gameplay-connect` has no `tokenGeneration` claim and no registry record. Its single-use replay contract from ADR 0029 supplies its separate admission identity and fence; this exception is not inherited by any registry-backed profile.

For every registry-backed profile, the JWT claim, registry field, and applicable Account-owned operation or binding evidence must agree exactly. `tokenGeneration` orders one applicable issuance lineage; it does not grant scope, replace authority-generation checks, or act as restart authority by itself.

### Revocation And Rotation

- Per-token logout durably advances an exact-token `PENDING_LOGOUT` fence before deleting the single token record idempotently, then commits the matching tombstone. Other devices, tokens, and gameplay bindings remain unaffected.
- Bulk issuer, account, tenant, and membership revocation uses monotonic authority generations rather than scanning token records or encoding every scope in the token key. These generations are not epoch timestamps.
- Generation-bound private player-delegation rotation first commits the immutable `TOKEN_ROTATION` operation in `PENDING` state and the Account-owned replacement lease described by ADR 0031, then idempotently creates a non-authorizing `pending` replacement record in Coordination Redis. After verifying that pending record and the lease evidence, Account commits the durable operation as `COMMITTED`. A token-fence-guarded Redis operation then atomically activates the replacement and moves the predecessor to `retiring`; Account does not expose the replacement response before that transition succeeds. Every replacement mutation and Game Session installation CAS validates the current Account-owned token-identity fence; a pending or committed exact-token logout fence rejects stale work and cannot be bypassed by a later Redis write. A crash after the durable commit but before Redis activation is reconciled toward that committed transition, while a failure before commit revokes or removes the pending candidate and leaves the predecessor active. Game Session binding installation remains separately fenced by the lease/CAS protocol, and the predecessor is deleted only after the bounded in-flight overlap. No global transaction spans Account durable state, Coordination Redis, and the gameplay binding.
- Registry absence is default denial. Coordination reset therefore forces reauthentication/reissuance rather than making unregistered but cryptographically valid tokens acceptable.

### Durable Idempotency Evidence And Retry Semantics

ADR 0031's idempotent retirement, per-token logout, and logout-all rules require durable Account-owned evidence in addition to the Redis registry record. Redis absence is not, by itself, proof that one of those operations completed.

- The canonical public logout API uses `POST /auth/logout` for one presented `control-ui` or `player-bootstrap` token and `POST /auth/logout-all` for the authenticated account-wide cutoff. The caller-supplied high-entropy `requestId` is the operation ID for both endpoints; no second public operation-ID field is introduced. Account computes and stores a versioned `requestDigest` from the normalized operation tuple: `TOKEN_LOGOUT`, subject account, exact token profile, and token hash for `/auth/logout`; `ACCOUNT_LOGOUT_ALL`, subject account, exact token profile, and presented token hash for `/auth/logout-all`. Retirement acknowledgements use the same evidence model with operation kind `TOKEN_RETIREMENT`, predecessor token hash, and refresh lineage. Generation-bound rotation uses operation kind `TOKEN_ROTATION`, its refresh request ID, predecessor and replacement token identities, binding, lineage, and authority tuple. Raw JWTs never enter the digest or evidence.
- Every rotation, retirement acknowledgement, per-token logout, and logout-all request binds its operation ID to that immutable request digest. Reusing an operation ID with different meaning, token, profile, account, binding, lineage, or scope is rejected as an idempotency conflict.
- Account stores a bounded durable operation record outside Coordination Redis. Before the first registry mutation it commits a `PENDING` record containing the immutable operation ID, request digest, operation kind, subject, and expected token or lineage identity. For `TOKEN_LOGOUT`, that same Account transaction also advances and durably records the exact-token `PENDING_LOGOUT` fence version; the fence is authoritative before the first Redis deletion. Logout, logout-all, and retirement operations advance to `COMMITTED` only after their idempotent registry mutation and required durable acknowledgement, with the completed tombstone or authority event recorded where applicable. `TOKEN_ROTATION` uses `COMMITTED` as the durable activation decision after the non-authorizing pending replacement is verified, but that state is not a successful response by itself: the exact replacement must also be `active` and the predecessor `retiring` under the recorded fence. If a newer logout or authority fence invalidates a committed-but-not-activated rotation, a fence-guarded CAS records terminal `SUPERSEDED` evidence and stable `TOKEN_ROTATION_SUPERSEDED` failure after the pending candidate is revoked/removed or durably marked non-authorizing. A first-seen invalid operation may create terminal `FAILED` evidence, but reusing an existing `PENDING`, `COMMITTED`, or `SUPERSEDED` operation ID with a different digest is rejected without modifying the existing record, fence, tombstone, or Redis state. A completed logout-all also records the durable logout event identity and the account authority generation that superseded the presented token. The evidence is not an authorization grant and contains no raw JWT.
- A bounded Account reconciler claims stale `PENDING` operations. It revalidates the immutable digest and operation state, then compares and sets that it still owns the recorded fence version before idempotently repeating or verifying the intended mutation. A crash before the Redis mutation therefore resumes it; a crash after the mutation but before the durable commit cannot turn bare registry absence into success without the matching precommitted `PENDING` evidence. If a newer valid installation or other operation has advanced the same token lineage fence, reconciliation records a stale/no-op outcome and cannot commit over that newer state. Reconciliation serializes with concurrent requests for the same operation and token lineage, and an ambiguous state fails closed rather than returning stored success.
- Rotation uses the same durable operation evidence without pretending that Account state and Coordination Redis share a transaction. Account commits the immutable `TOKEN_ROTATION` `PENDING` record and replacement lease before the first replacement-registry mutation. The operation records the predecessor and replacement identities, refresh lineage, binding, authority tuple, request digest, and required Redis postconditions without persisting a raw JWT. A retry or reconciler inspects that evidence and the Redis postconditions, applies only missing idempotent mutations after current token-fence validation, and either verifies the non-authorizing pending replacement before committing the operation and completing the atomic activation/retirement transition, or records terminal `FAILED` evidence and revokes or removes the pending candidate. A `COMMITTED` operation whose Redis transition was interrupted is reconciled toward activation of that exact replacement and retirement of that exact predecessor only while the recorded fence remains current. If a newer logout or authority fence has won, reconciliation instead uses a fence-guarded CAS to record terminal `SUPERSEDED`/`ABORTED` evidence, revokes or removes the pending candidate or durably marks it non-authorizing, returns stable `TOKEN_ROTATION_SUPERSEDED`, and never retries activation indefinitely. Binding installation and finalization remain controlled by the lease version and Game Session compare-and-set; an incomplete, superseded, or ambiguous operation never authorizes the replacement for new calls. For response-loss recovery, Account may retain a separate bounded response envelope encrypted under Account-owned application encryption and bound to the exact request, workload, binding, lineage, replacement, and lease fence. That envelope may return the same issuance result only within its bounded lifetime after current-state revalidation; it is not operation evidence, registry state, or authorization authority. Missing, unreadable, expired, or mismatched envelope state revokes the candidate and fails closed, and any later rotation uses a new request ID only while the predecessor remains valid.
- A retry with the same `requestId` and matching `requestDigest` returns stored success only from `COMMITTED` after the full local signature, profile, time, and subject validation required by ADR 0031 and after revalidating the operation-specific postconditions. For `TOKEN_ROTATION`, the replacement must be `active`, the predecessor must be `retiring` with the recorded cutoff, and the current token fence and lease must match; a `COMMITTED` record with a still-`pending` replacement or still-`active` predecessor is reconciled and cannot return success. If the recorded fence is superseded, the retry returns stable `TOKEN_ROTATION_SUPERSEDED` from terminal `SUPERSEDED` evidence and never retries activation. Logout-family retries likewise require their committed tombstone/generation and registry postconditions. This check does not mint, reauthorize, or repeat unrelated mutations. A `PENDING` record is resumed or reconciled and a `FAILED` or `SUPERSEDED` record returns its stable failure; neither is reported as success. A different request ID for `/auth/logout` may return no-op success only when a token-specific completed tombstone proves that exact token was previously revoked; a missing registry record without that evidence remains denied.
- A `/auth/logout-all` retry may return no-op success when the presented token is already superseded only if durable Account evidence proves the prior logout-all event and generation that caused the supersession. That retry does not advance the generation or mutate state. A current token is handled as a new normally authorized operation, while an ambiguous or differently scoped request is denied rather than classified as a retry.
- Durable evidence has an explicit bounded retention horizon: no shorter than the maximum supported retry/idempotency window plus the required audit/outbox delivery window, and for token-specific tombstones no shorter than the token's `exp` plus validation skew. Logout-all supersession proof is retained through the configured authority-event/retry horizon needed to classify later retries. Background cleanup physically removes evidence only after its horizon and must not remove the only proof still required for a stored-success or supersession response. Cleanup is itself idempotent and does not change revocation authority.

## Consequences

- Each revocable token creates one bounded key and requires one registry lookup rather than account plus tenant/global key combinations.
- Registry issuance, logout, rotation, expiry, and cleanup do not partially update a variable set of scope records; cross-store incomplete operations remain explicit in durable Account evidence and are reconciled fail-closed.
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
- Define one versioned bounded record schema with a non-empty string JWT `kid` on every supported version, profile-specific `tokenGeneration` semantics, and mandatory conditional rotation fields, and validate every field against the cryptographically verified token and matching Account-owned evidence.
- Make issuance return contingent on successful reconciliation of every required separately fenced registry, lease, binding, and installation postcondition; prove Account evidence alone never leaks a usable unregistered token and partial postcondition failure remains recoverable.
- Update shared validators so every applicable JWT-presenting registry-backed control-plane, bootstrap, and admission operation performs local token-profile validation and exactly one registry lookup before scope authorization.
- Prove missing, expired, malformed, unsupported-version, missing/wrong `kid`, state-specific `pending`/`active`/`retiring`/`revoked`, wrong-profile, wrong-account, wrong-generation, missing/mismatched conditional rotation evidence, deleted, and unavailable registry state fails closed with stable errors.
- Prove per-token logout durably advances the exact-token `PENDING_LOGOUT` fence before deleting one record and leaves other tokens active; prove stale refresh, rebind, installation, and reconciliation CAS paths cannot recreate or mutate that logged-out identity, while rotation establishes a valid replacement before exposure and removes the predecessor after bounded overlap.
- Prove crashes before and after each cross-store mutation, including rotation before and after replacement-registry mutation, converge through the durable `PENDING`/`COMMITTED`/`FAILED` operation state machine without treating unexplained Redis absence as success; prove a committed rotation with a pending replacement or active predecessor is reconciled rather than replayed as success, the durable rotation operation and lease exist before the first replacement-registry mutation, and no global transaction is assumed.
- Prove connect-token replay and Gateway connect-context paths do not create or consult the Account issued-token registry.
- Update Redis key catalogs, reset behavior, memory budgets, ACLs, operational tooling, and retained evidence for the one-key contract.

## Required Documentation Alignment

- [JWT and token contracts](../system-architecture-jwt-and-token-contracts.md)
- [Session behavior](../system-architecture-session-behavior.md)
- [Redis](../system-architecture-redis.md)
- [Redis cheatsheet](../system-architecture-redis-cheatsheet.md)
- [Redis reset and recovery](../system-architecture-redis-reset-and-recovery.md)
- [Account runtime and data](../microservices/account-service/runtime-and-data.md)
- [Account API contracts](../microservices/account-service/api-contracts.md)
- [ADR 0031: Revocation-safe session-token rotation and logout](./adr-0031-revocation-safe-session-token-rotation-and-logout.md)

## Reversibility and Revisit Triggers

The record schema is versioned and the token hash remains stable, so additional bounded fields can be introduced without changing the public token carrier. Revisit if an external identity provider becomes the token/session authority, control-plane request volume makes one registry lookup materially expensive, or a device-management product requires a durable Account-owned session index. Do not reintroduce scope-duplicated keys merely to build such an index.
