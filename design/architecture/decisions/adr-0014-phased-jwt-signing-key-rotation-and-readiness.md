# ADR 0014: Phased JWT Signing-Key Rotation and Readiness

## Status

Accepted

## Implementation Status

The decision is accepted; implementation and proof remain partial. The current watcher and JWKS-serving tests cover direct file behavior, but issuance and validation still use the shared-HMAC topology and validators do not consume Account JWKS. Account-authorized asymmetric issuance backed by non-exportable signer custody, bounded validator convergence, rotation/compromise drills, and the player-facing readiness gate remain incomplete and unproved. Acceptance records the target decision, not completion; the obligations below define the remaining proof.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `SF-1.3` Authentication and trust foundations
- Affected capabilities: `AA-1.3`, `PO-1.3`, `PO-3.2`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SEC-02`

## Context

FireMUD's canonical token contract assigns asymmetric Browser, player-bootstrap, and Service JWT issuance to Account Service and assigns validation to downstream services through Account-published JWKS. Planned rotation should not interrupt users or service calls, while compromise and post-restore hardening must stop trusting the affected key immediately.

The previous rotation target stored one current and one previous private key, updated the signing Secret and JWKS resource in one Job description, and relied on hot reload plus an unspecified overlap window. It did not define a safe publication order across separately updated resources, the exact overlap condition, validator cache and unknown-`kid` behavior, issuer rollback, or a player-facing readiness gate.

The implementation is further behind the target. The shared JWT utility still uses a symmetric HMAC secret, multiple workloads receive signing material and can mint tokens locally, the reload path replaces the only accepted key immediately, and no validator consumes Account JWKS. The current watcher and JWKS-serving tests prove direct file behavior, not Kubernetes projected-volume updates, asymmetric key correspondence, rotation overlap, pruning, or fleet convergence. No checked-in rotation Job or complete player-facing rotation evidence exists.

## Decision

FireMUD's target-state private-key custody delegates private-key operations to a non-exportable signer in every environment while Account Service remains the sole issuer and lifecycle authority. Until that capability is implemented, the controlled interim fallback is Account-only Kubernetes Secret custody: only Account Service receives private material, validators receive public JWKS only, and rotation automation cannot access signing material. JWT rotation itself is a phased protocol with different planned-rotation and compromise modes. The protocol is storage-backend independent so the delegated signer or the interim fallback can implement the same phases without changing validator semantics.

### Authority and Scope

- Account Service is the sole issuer and authority for the Account JWT key ring, including generation validation, token-validation semantics, signer promotion, JWKS publication, and public/private pruning. A non-exportable signer may perform only private-key operations explicitly delegated by Account; it is not an issuer, validator, promotion, JWKS-publication, or pruning authority.
- Under the interim Kubernetes Secret fallback, Account Service is the only application workload that may receive private signing material.
- Browser, player-bootstrap, and Service JWTs use this per-environment asymmetric key ring and carry an explicit stable `kid`.
- Validators receive no private JWT key. They validate through Account-published JWKS with a bounded cache.
- The key ring is environment-wide, not tenant-specific. Compromise of an active Account signing key is therefore an environment-wide issuer compromise; incident response must not describe tenant-selective containment as sufficient.
- Gateway connect-context signing and Game Session `SessionAttestation` signing remain separate key families. They may reuse the sequencing invariants in this record, but this decision does not merge their issuers, storage, or lifecycles.

### Planned Rotation

A normal rotation uses these ordered phases:

1. Account Service initiates generation of a new asymmetric keypair and unique `kid` through its delegated non-exportable signer, or generates it in the Account-only fallback, without changing the active signer.
2. Prepublish the new public JWK alongside every public key whose tokens may still be valid. The published generation must identify the active and pending `kid` values.
3. Wait at least the configured validator JWKS maximum cache age and prove every required validator can verify two constrained Account-owned pending-key probes through production validation code. The first is a fixed short-lived canary marked with a dedicated non-authorizing type and audience and containing no roles or tenant scope; normal authorization middleware must reject it as non-authorizing. The second is a short-lived representative token for each production JWT family that exercises the normal issuer, audience, algorithm, required-claim, key-use, and JWKS validation path. It uses a reserved probe subject with no account entitlement, tenant membership, role, or scope and is submitted only through the readiness harness, which invokes the production authentication validator and then unconditionally denies authorization and side effects. Neither probe may authorize admission, grant scope, invoke an application operation, or mutate signer state. Merely resolving the JWK is insufficient. An unknown `kid` causes one forced JWKS refresh and one validation retry, then fails closed. Any probe validation failure or any observed authorization blocks promotion and leaves the old signer active.
4. Atomically promote the Account-owned active signer reference to the validated generation and `kid` only after Account has proved that the matching public JWK is in the published generation and both the non-authorizing canary and representative production-family probes have converged at every required validator. The private key remains in non-exportable signer custody, or in the interim Account-only bundle. A malformed bundle, key mismatch, unobservable pending key, incomplete validator inventory, or failed probe aborts promotion and leaves the old signer active while readiness fails and operators are alerted.
5. Retain the old public JWK until the last token actually signed by the old key has expired plus the allowed validation clock skew. The validator-cache convergence wait occurs before signer promotion and is not a substitute for this token-lifetime overlap. In target state, any retained rollback private-key state remains inside non-exportable signer custody; under the interim fallback it may remain in the Account-only signing Secret solely as an explicit rollback slot. It is never distributed to validators or rotation automation.
6. Prune the retired public JWK and any retained rollback private-key state only after the overlap condition is satisfied. Prove that the active `kid` is accepted and retired or expired material is rejected, then retain immutable rotation evidence.

The rotation resources carry a common generation identifier and phase so separately updated Kubernetes objects cannot be mistaken for one atomic transaction. Signer promotion is the commit point. Publishing an additional verification key is safe before that point; publishing a signer whose key is not converged is not.

Normal rotation does not invalidate sessions or require reauthentication. Production rotation remains an explicit operator-triggered Job. Staging must periodically exercise the same artifact and phase protocol used by production, whether scheduled or operator-triggered, so production readiness is supported by current evidence rather than an untested runbook.

If an operator rolls Account Service back after signer promotion, JWKS must retain every key used by either version until all tokens issued under both versions have expired plus skew. Rollback must not remove the newly used public key merely because the previous application version is active again.

### Compromise and Restore Cutover

Suspected compromise and player-facing post-restore hardening use a hard cutover rather than normal overlap:

1. Quarantine new JWT issuance and JWT-protected admission/control-plane traffic.
2. Generate and publish a replacement key generation.
3. Remove the compromised or restored public key from authoritative JWKS immediately; do not retain it for overlap or rollback.
4. Invalidate outstanding authority for the environment-wide issuer, including an issuer-wide revocation watermark and required session/allowlist cleanup. Watermarks are defense in depth: an attacker holding the old private key can choose fresh claims, so key removal and validator convergence remain mandatory.
5. Force refresh or restart every validator, prove the compromised `kid` is rejected and the replacement `kid` is accepted, and record the exact validator inventory and results.
6. Reopen protected traffic only after the hard-cutover, invalidation, convergence, and evidence gates pass.

Hard cutover intentionally causes reauthentication and may create a bounded authentication outage. That disruption is accepted for containment; it is not the normal planned-rotation behavior.

### Validator and Availability Contract

- Validators cache known JWKS keys for a configured bounded maximum age and refresh proactively before expiry.
- An unknown `kid` triggers one forced refresh and one retry, then fails closed.
- While Account JWKS is temporarily unavailable, a validator may continue validating a known key only within its unexpired bounded cache entry. It must not extend cache age or accept an unknown key to preserve availability.
- Validator inventory, maximum cache age, refresh outcome, last observed JWKS generation, and active/retired key acceptance are observable and form part of rotation evidence.
- Filesystem hot reload is an implementation option, not the contract. If used, projected-volume symlink replacement, partial writes, malformed data, key/JWKS mismatch, and atomic signer swap must be tested explicitly.

### Player-Facing Readiness Gate

No player-facing environment may be described as JWT-ready, promotable, or traffic-open on the strength of mounted file paths and a served JWKS document alone. The gate requires evidence that:

- Account remains the sole issuer and lifecycle authority; the non-exportable signer performs only Account-delegated private-key operations, and the interim fallback gives private material only to Account;
- all validators use asymmetric `kid`/JWKS verification and HMAC-only fallback is disabled;
- startup and preflight reject private signing-key distribution to validators and reject missing asymmetric verification;
- a planned-rotation drill proves prepublication, old/new continuity, signer promotion, overlap, pruning, and rollback-safe JWKS retention;
- a compromise drill proves quarantine, environment-wide invalidation, hard cutover, forced convergence, old-`kid` rejection, new-`kid` acceptance, and controlled reopen; and
- retained evidence identifies the immutable rotation artifact, key generations, validator inventory, timing, and results.

Until those conditions are implemented and proved, the current shared-HMAC topology is implementation debt and player-facing JWT readiness remains blocked. It is not an alternative supported production design.

## Consequences

- Planned rotations can be invisible to users and internal callers because validators learn the new key before it signs tokens and retain the old verification key for the full remaining token lifetime.
- The protocol prevents the common race in which newly issued tokens fail because validators have not learned the new `kid`, and prevents premature pruning from invalidating still-live tokens.
- Compromise response is deliberately environment-wide because one environment signing key can mint claims for any tenant in that issuer.
- Non-exportable custody is the target-state reduction in key-extraction risk. The Account-only Kubernetes Secret fallback materially reduces the compromise blast radius compared with distributing a symmetric signing secret to every service, but it is not the final custody target.
- Rotation now requires a validator inventory, cache bounds, probes, generation state, evidence retention, and two distinct operational paths. This is more complex than a maintenance-window hard cutover.
- Existing validation can continue during a short Account/JWKS outage from bounded cache, but issuance and unknown-key discovery still depend on Account and the rotation control path.
- Kubernetes Secrets remain extractable by principals with sufficient cluster access. Non-exportable signer delegation is therefore mandatory target state in every environment; Account-only Kubernetes Secret custody is only the controlled interim fallback until that capability is implemented.

## Alternatives Considered

### Manual Coordinated Hard Cutover for Every Rotation

Replace one key, restart the fleet, invalidate all sessions, and accept a maintenance outage on every rotation. This is the strongest simpler interim approach because it removes overlap, cache-convergence, and watcher races. It also creates scheduled authentication outages, forces user reauthentication, interrupts short-lived internal calls, and makes correct fleet sequencing a manual burden. It remains acceptable only as an explicitly quarantined interim operation or for compromise/restore, not as the player-facing planned-rotation target.

### Account-Only Kubernetes Secret Interim Fallback

Until non-exportable signer delegation is available in an environment, keep the versioned private bundle in an Account-only Kubernetes Secret, publish public JWKS through Account, and prevent validators and rotation automation from receiving private material. This is a controlled custody fallback that preserves Account authority and the phased protocol; it is not the target-state private-key custody model and must not be treated as permission to distribute signing authority.

### Keep Shared HMAC Signing

Continue mounting one symmetric key to validators and allow services to mint tokens locally. This is operationally simple but gives every validator signing authority, creates environment-wide forgery exposure from any key-bearing workload, cannot safely support public JWKS verification, and conflicts with the accepted Account issuer boundary. It is not supported for player-facing environments.

### Per-Tenant Signing Keys

Use a separate key ring for each tenant to reduce tenant blast radius. This multiplies key, cache, incident, discovery, rotation, and proof cardinality and does not remove environment-level service authority risks. It is not justified for the current shared-cluster model.

## Implementation and Proof Obligations

- Replace shared-HMAC player-facing issuance and validation with Account-authorized asymmetric signing through the delegated non-exportable signer and downstream JWKS validation. Use Account-only Secret custody only as the controlled interim fallback until signer delegation is implemented.
- Remove Account private signing material from every validating workload and prevent local service token minting outside Account.
- Define a reusable versioned signing/JWKS bundle and phased rotation state machine with a single signer-promotion commit point.
- Implement bounded validator caching, proactive refresh, unknown-`kid` one-refresh/one-retry behavior, convergence probes, and validator inventory evidence.
- Add the issuer-wide revocation surface required for environment-key compromise and prove that it cannot substitute for compromised-key rejection.
- Prove Kubernetes projected-volume update behavior if filesystem hot reload remains in use.
- Add planned-rotation, rollback, compromise, and post-restore drills using the exact production artifact and retain immutable evidence.
- Enforce the player-facing readiness gate in startup, deployment preflight, promotion, and traffic-open workflows.
- Keep implementation trackers partial until the complete issuance, validation, rotation, convergence, and incident paths have focused proof.

## Reversibility and Revisit Triggers

The phased protocol is independent of signer storage, which keeps changes to the non-exportable signer implementation reversible at the application contract. The interim Account-only Secret fallback does not alter Account authority or permit a second signer authority. Once downstream services rely on Account-only asymmetric JWKS, returning to shared HMAC would weaken the trust boundary and requires a new security decision.

Revisit the delegated signer implementation if its compliance evidence, availability, latency, quota, disaster-recovery behavior, or self-hosted operating model becomes inadequate. Revisit the Account-issued Service JWT model if per-call issuance makes centralized signing operationally unacceptable; managed workload identity or mTLS authorization is the preferred alternative to redistributing signing authority.

## Required Documentation Alignment

The following sources must remain aligned with this decision:

- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-jwt-and-token-contracts.md`
- `design/architecture/system-architecture-jwt-compromise-runbook.md`
- `design/architecture/system-architecture-shared-libraries.md`
- `design/architecture/system-architecture-deploy-preflight-policy.md`
- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-post-restore-hardening.md`
- `design/architecture/infrastructure/environment-and-secrets-overview.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`
- `design/architecture/microservices/account-service/configuration.md`
