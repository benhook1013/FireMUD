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

FireMUD's canonical token contract assigns asymmetric `control-ui`, `player-bootstrap`, and receiver-specific private player-delegation JWT issuance to Account Service and assigns validation to downstream services through Account-published JWKS. Planned rotation should not interrupt users or service calls, while compromise and post-restore hardening must stop trusting the affected key immediately.

The previous rotation target stored one current and one previous private key, updated the signing Secret and JWKS resource in one Job description, and relied on hot reload plus an unspecified overlap window. It did not define a safe publication order across separately updated resources, the exact overlap condition, validator cache and unknown-`kid` behavior, issuer rollback, or a player-facing readiness gate.

The implementation is further behind the target. The shared JWT utility still uses a symmetric HMAC secret, multiple workloads receive signing material and can mint tokens locally, the reload path replaces the only accepted key immediately, and no validator consumes Account JWKS. The current watcher and JWKS-serving tests prove direct file behavior, not Kubernetes projected-volume updates, asymmetric key correspondence, rotation overlap, pruning, or fleet convergence. No checked-in rotation Job or complete player-facing rotation evidence exists.

## Decision

FireMUD's target-state private-key custody delegates private-key operations to a non-exportable signer in every environment while Account Service remains the sole issuer and lifecycle authority. Until that capability is implemented, the controlled interim fallback uses Account-only application consumption of a Kubernetes Secret: Account is the only application workload that mounts and uses the private bundle, while a narrowly scoped materialization controller is an additional infrastructure custodian solely for key generation and compare-and-set Secret mutation. Validators receive public JWKS only, and rotation automation cannot access signing material. JWT rotation itself is a phased protocol with different planned-rotation and compromise modes. The protocol is storage-backend independent so the delegated signer or the interim fallback can implement the same phases without changing validator semantics.

The Kubernetes fallback baseline uses fixed, pre-created `jwt-signing-keys` Secret and `jwt-jwks` ConfigMap resources. The materialization controller is the sole writer of the signing Secret and updates it through Kubernetes `resourceVersion` compare-and-set after an Account-authorized lifecycle request. Its Kubernetes authority necessarily makes it a private-material custodian in this fallback, but not an issuer: it may generate and materialize the requested bundle and reconcile its CAS result, but it may not sign tokens, select or promote the active signer, publish JWKS, expose key material, or use the key for any other purpose. Account has name-scoped read access to that Secret and name-scoped write authority only for the public JWKS ConfigMap; it has no list, create, or delete authority for either resource. Account is the only application workload that consumes the private Secret, and it consumes both resources through read-only projected mounts. The rotation Job has no write access to either resource; it requests transitions through Account's control interface and observes the resulting generation. Under delegated non-exportable custody, no signing Secret or mounted private material exists: Account instead owns a signer reference and authenticated signer-generation evidence while retaining the same fixed-name JWKS publication contract.

Readiness proof is specific to the custody backend. Under the Kubernetes fallback, Account must observe the pending generation in both read-only projected mounts, including the mounted generation markers, rather than treating Kubernetes API write success or a served JWKS response as proof that its process has consumed the update; it derives or challenge-signs from the mounted private key and proves that it matches the published JWK. Under delegated custody, Account must authenticate the signer endpoint, observe the pending signer reference and generation from that endpoint, and complete a challenge-signature proof against the published JWK for the same `kid` and generation. API write success, signer control-plane acknowledgement, or a served JWKS response alone is insufficient in either backend.

### Authority and Scope

- Account Service is the sole issuer and authority for the Account JWT key ring, including generation validation, token-validation semantics, signer promotion, JWKS publication, and public/private pruning. A non-exportable signer may perform only private-key operations explicitly delegated by Account; it is not an issuer, validator, promotion, JWKS-publication, or pruning authority.
- Under the interim Kubernetes Secret fallback, Account Service is the only application workload that may mount or use private signing material. The materialization controller is the only additional infrastructure custodian and is limited to the generation and Secret-CAS duties above.
- `control-ui`, player-bootstrap, and receiver-specific private player-delegation JWTs use this per-environment asymmetric key ring and carry an explicit stable `kid`.
- Validators receive no private JWT key. They validate through Account-published JWKS with a bounded cache.
- The key ring is environment-wide, not tenant-specific. Compromise of an active Account signing key is therefore an environment-wide issuer compromise; incident response must not describe tenant-selective containment as sufficient.
- Gateway connect-context signing remains a separate key family from Account JWT signing. [ADR 0024](./adr-0024-trusted-gameplay-workload-delegation.md) supersedes the former Game Session `SessionAttestation` key family; routine gameplay delegation has no per-action signing keys.

### Planned Rotation

A normal rotation uses these ordered phases:

1. Account Service initiates generation of a new asymmetric keypair and unique `kid` through its delegated non-exportable signer, or requests generation and Secret materialization from the sole-writer materialization controller in the Kubernetes fallback, without changing the active signer.
2. Prepublish the new public JWK alongside every public key whose tokens may still be valid by CAS-updating the Account-owned `jwt-jwks` ConfigMap. The published generation must identify the active and pending `kid` values.
3. Wait at least the configured validator JWKS maximum cache age and prove every required validator can verify two constrained Account-owned pending-key probes through production validation code. Account obtains those probe signatures only through an isolated readiness-signing operation addressed to the pending signer generation, or from generation-bound pre-signed readiness artifacts. The operation cannot change the active signer reference, enter the production issuance path, mint a caller-usable credential, or mutate signer lifecycle state. The first probe is a fixed short-lived canary marked with a dedicated non-authorizing type and audience and containing no roles or tenant scope; normal authorization middleware must reject it as non-authorizing. The second is a short-lived representative token for each production JWT family applicable to that validator and exercises the normal issuer, audience, algorithm, required-claim, key-use, and JWKS validation path. The readiness inventory is an explicit validator-to-token-profile applicability matrix: each validator receives only the production families and audiences it is designed to accept, and the same proof verifies that inapplicable audiences remain rejected. Each representative probe uses a reserved subject with no account entitlement, tenant membership, role, or scope and is submitted only through the readiness harness, which invokes the production authentication validator and then unconditionally denies authorization and side effects. Neither probe may authorize admission, grant scope, invoke an application operation, or mutate signer state. Merely resolving the JWK is insufficient. An unknown `kid` causes one forced JWKS refresh and one validation retry, then fails closed. Any expected acceptance or rejection mismatch, or any observed authorization, blocks promotion and leaves the old signer active.
4. Atomically promote the Account-owned active signer reference to the validated generation and `kid` only after Account has completed the custody-backend-specific generation and key-correspondence proof and proved that both the non-authorizing canary and representative production-family probes have converged at every required validator. The private key remains in non-exportable signer custody, or in the interim materialization-controller-written, Account-consumed bundle. A malformed bundle, missing or stale fallback mount, unverified delegated signer generation, generation mismatch, key mismatch, unobservable pending key, incomplete validator inventory, or failed probe aborts promotion and leaves the old signer active while readiness fails and operators are alerted. The pending key is never used for production token issuance before promotion; its isolated readiness-signing operation is not issuance authority. Existing traffic may continue under the old signer only while its existing readiness remains valid, otherwise the gate fails closed.
5. Retain the old public JWK until the last token actually signed by the old key has expired plus the allowed validation clock skew. The validator-cache convergence wait occurs before signer promotion and is not a substitute for this token-lifetime overlap. In target state, any retained rollback private-key state remains inside non-exportable signer custody; under the interim fallback it may remain in the materialization-controller-written, Account-consumed signing Secret solely as an explicit rollback slot. It is never distributed to validators or rotation automation.
6. Prune the retired public JWK and any retained rollback private-key state only after the overlap condition is satisfied. Prove that the active `kid` is accepted and retired or expired material is rejected, then retain immutable rotation evidence.

The rotation resources carry a common generation identifier and phase so separately updated Kubernetes objects cannot be mistaken for one atomic transaction. The materialization controller CAS-updates the fallback Secret and Account CAS-updates the public JWKS ConfigMap using each resource's observed Kubernetes `resourceVersion`; a conflict aborts the phase and requires reread/reconciliation. Signer promotion is the commit point. Publishing an additional verification key is safe before that point; publishing a signer whose key is not converged is not.

Normal rotation does not invalidate sessions or require reauthentication. Production rotation remains an explicit operator-triggered Job. Staging must periodically exercise the same artifact and phase protocol used by production, whether scheduled or operator-triggered, so production readiness is supported by current evidence rather than an untested runbook.

If an operator rolls Account Service back after signer promotion, JWKS must retain every key used by either version until all tokens issued under both versions have expired plus skew. Rollback must not remove the newly used public key merely because the previous application version is active again.

### Compromise and Restore Cutover

Suspected compromise and player-facing post-restore hardening use a hard cutover rather than normal overlap:

1. Quarantine new JWT issuance and JWT-protected admission/control-plane traffic.
2. Generate and publish a replacement key generation.
3. Remove the compromised or restored public key from authoritative JWKS immediately; do not retain it for overlap or rollback.
4. Advance the environment-wide issuer authority generation so validators reject issued-token registry snapshots from the prior generation across every account, tenant, and token profile. Enqueue bounded physical cleanup of superseded `session:auth:token:<tokenHash>` records and affected control/gameplay sessions; wildcard registry scans are not revocation authority. Generation invalidation immediately rejects existing issued records, while key removal and validator convergence remain mandatory because an attacker holding the old private key can choose fresh claims.
5. Force refresh or restart every validator and install a fail-closed block for the compromised `kid` that overrides any still-unexpired cached JWK. Refresh must atomically replace or evict the compromised cached key before validation resumes; a validator that cannot prove eviction or equivalent pre-validation rejection remains quarantined. Prove the compromised `kid` is rejected and the replacement `kid` is accepted, and record the exact validator inventory and results.
6. Reopen protected traffic only after the hard-cutover, invalidation, convergence, and evidence gates pass.

Hard cutover intentionally causes reauthentication and may create a bounded authentication outage. That disruption is accepted for containment; it is not the normal planned-rotation behavior.

### Validator and Availability Contract

- Validators cache known JWKS keys for a configured bounded maximum age and refresh proactively before expiry.
- An unknown `kid` triggers one forced refresh and one retry, then fails closed.
- While Account JWKS is temporarily unavailable, a validator may continue validating a known key only within its unexpired bounded cache entry. It must not extend cache age or accept an unknown key to preserve availability.
- Compromise and restore hard cutovers override ordinary cache availability: the quarantined `kid` must be evicted or rejected before cached-key signature acceptance, and protected traffic cannot reopen until every validator proves that behavior.
- Validator inventory, maximum cache age, refresh outcome, last observed JWKS generation, and active/retired key acceptance are observable and form part of rotation evidence.
- Filesystem hot reload is an implementation option, not the contract. If used, projected-volume symlink replacement, partial writes, malformed data, key/JWKS mismatch, and atomic signer swap must be tested explicitly.

### Player-Facing Readiness Gate

No player-facing environment may be described as JWT-ready, promotable, or traffic-open on the strength of mounted file paths and a served JWKS document alone. The gate requires evidence that:

- Account remains the sole issuer and lifecycle authority; the non-exportable signer performs only Account-delegated private-key operations, and the interim fallback limits private-material custody to the materialization controller plus Account while only Account may use it for issuance;
- all validators use asymmetric `kid`/JWKS verification and HMAC-only fallback is disabled;
- startup and preflight reject private signing-key distribution to validators and reject missing asymmetric verification;
- Account completes the custody-backend-specific readiness proof before promotion: delegated signer identity, generation, and challenge-signature correspondence, or fallback Secret/ConfigMap mount generation and private-key/JWK correspondence;
- a planned-rotation drill proves prepublication, old/new continuity, signer promotion, overlap, pruning, and rollback-safe JWKS retention;
- a compromise drill proves quarantine, environment-wide invalidation, hard cutover, forced convergence, old-`kid` rejection, new-`kid` acceptance, and controlled reopen; and
- retained evidence identifies the immutable rotation artifact, key generations, validator inventory, timing, and results.

Until those conditions are implemented and proved, the current shared-HMAC topology is implementation debt and player-facing JWT readiness remains blocked. It is not an alternative supported production design.

## Consequences

- Planned rotations can be invisible to users and internal callers because validators learn the new key before it signs tokens and retain the old verification key for the full remaining token lifetime.
- The protocol prevents the common race in which newly issued tokens fail because validators have not learned the new `kid`, and prevents premature pruning from invalidating still-live tokens.
- Compromise response is deliberately environment-wide because one environment signing key can mint claims for any tenant in that issuer.
- Non-exportable custody is the target-state reduction in key-extraction risk. The materialization-controller-written, Account-consumed Kubernetes Secret fallback materially reduces the compromise blast radius compared with distributing a symmetric signing secret to every service, but it is not the final custody target.
- Rotation now requires a validator inventory, cache bounds, probes, generation state, evidence retention, and two distinct operational paths. This is more complex than a maintenance-window hard cutover.
- Existing validation can continue during a short Account/JWKS outage from bounded cache, but issuance and unknown-key discovery still depend on Account and the rotation control path.
- Kubernetes Secrets remain extractable by principals with sufficient cluster access. Non-exportable signer delegation is therefore mandatory target state in every environment; materialization-controller and Account custody through the Kubernetes Secret is only the controlled interim fallback until that capability is implemented.

## Alternatives Considered

### Manual Coordinated Hard Cutover for Every Rotation

Replace one key, restart the fleet, invalidate all sessions, and accept a maintenance outage on every rotation. This is the strongest simpler interim approach because it removes overlap, cache-convergence, and watcher races. It also creates scheduled authentication outages, forces user reauthentication, interrupts short-lived internal calls, and makes correct fleet sequencing a manual burden. It remains acceptable only as an explicitly quarantined interim operation or for compromise/restore, not as the player-facing planned-rotation target.

### Account-Consumed Kubernetes Secret Interim Fallback

Until non-exportable signer delegation is available in an environment, keep the versioned private bundle in a materialization-controller-written Secret mounted only by Account, publish public JWKS through Account, and prevent validators and rotation automation from receiving private material. The controller's custody is restricted to generation and Secret CAS and grants no signing or lifecycle authority. This is a controlled custody fallback that preserves Account issuer authority and the phased protocol; it is not the target-state private-key custody model and must not be treated as permission to distribute signing authority.

### Keep Shared HMAC Signing

Continue mounting one symmetric key to validators and allow services to mint tokens locally. This is operationally simple but gives every validator signing authority, creates environment-wide forgery exposure from any key-bearing workload, cannot safely support public JWKS verification, and conflicts with the accepted Account issuer boundary. It is not supported for player-facing environments.

### Per-Tenant Signing Keys

Use a separate key ring for each tenant to reduce tenant blast radius. This multiplies key, cache, incident, discovery, rotation, and proof cardinality and does not remove environment-level service authority risks. It is not justified for the current shared-cluster model.

## Implementation and Proof Obligations

- Replace shared-HMAC player-facing issuance and validation with Account-authorized asymmetric signing through the delegated non-exportable signer and downstream JWKS validation. Use the materialization-controller-written, Account-consumed Secret only as the controlled interim fallback until signer delegation is implemented.
- Remove Account private signing material from every validating workload and prevent local private player-delegation-token minting outside Account.
- Define a reusable versioned signing/JWKS bundle and phased rotation state machine with a single signer-promotion commit point.
- Provision fixed JWT/JWKS resources and narrow actor-specific RBAC; prove materialization-controller Secret custody and `resourceVersion` CAS, Account-only application consumption, mounted Secret/ConfigMap generation observation, cryptographic private-key/JWK correspondence, and absence of rotation-Job write authority.
- Implement bounded validator caching, proactive refresh, unknown-`kid` one-refresh/one-retry behavior, convergence probes, and validator inventory evidence.
- Add the issuer-wide revocation surface required for environment-key compromise and prove that it cannot substitute for compromised-key rejection.
- Prove Kubernetes projected-volume update behavior if filesystem hot reload remains in use.
- Add planned-rotation, rollback, compromise, and post-restore drills using the exact production artifact and retain immutable evidence.
- Enforce the player-facing readiness gate in startup, deployment preflight, promotion, and traffic-open workflows.
- Keep implementation trackers partial until the complete issuance, validation, rotation, convergence, and incident paths have focused proof.

## Reversibility and Revisit Triggers

The phased protocol is independent of signer storage, which keeps changes to the non-exportable signer implementation reversible at the application contract. The interim Secret fallback gives the materialization controller limited custody but no issuer or signer-lifecycle authority; Account remains the sole application issuer and JWKS authority. Once downstream services rely on Account-published asymmetric JWKS, returning to shared HMAC would weaken the trust boundary and requires a new security decision.

Revisit the delegated signer implementation if its compliance evidence, availability, latency, quota, disaster-recovery behavior, or self-hosted operating model becomes inadequate. Revisit the Account-issued private player-delegation model if per-call issuance makes centralized signing operationally unacceptable; managed workload identity or mTLS authorization is the preferred alternative to redistributing signing authority.

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
