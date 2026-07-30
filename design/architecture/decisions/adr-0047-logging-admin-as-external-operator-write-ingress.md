# ADR 0047: Logging and Admin as External Operator-Write Ingress

## Status

Accepted

## Implementation Status

This ADR is partially implemented. Logging & Admin has current operator-facing read, investigation, admission-pointer read/audit, and prepared-upgrade proof-read surfaces, but no forwarded owner mutation family is currently supported as an executable external route until its action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account-issued authorization-reference issuance plus owner-side redemption exist. The separate Logging & Admin-owned moderation policy-input/audit persistence path is currently gated/unavailable: `/moderation/actions` and `ApplyModerationAction` persist neither the `moderation_actions` record nor audit evidence and do not perform owner-side enforcement. When enabled, that persistence path requires Account authorization-reference issuance plus Logging & Admin receiving-service validation/redemption; it does not require domain-owner redemption. The live `EvaluateModerationPolicy` read remains available only to its internal enforcement owners. Versioned policy propagation and owner enforcement remain target-state work.

## Decision Record

- Decision date: 2026-07-19
- Decision key: `SEC-04`
- Primary capability: `PO-1.1` Administration and operator control
- Affected capabilities: `PO-1.2`, `PO-1.3`, `PO-1.4`, `PO-2.1`, `GR-1.1`, `AR-3.3`, `AR-2.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SEC-04`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `SEC-04`

## Context

Operator actions need one predictable external security and audit boundary without moving authoritative domain state into an administration service. Allowing every domain service to expose independent operator-write APIs would multiply authorization, audit, and failure contracts. Making dashboards or observability stores part of write success would make remediation unavailable during the incidents when operators need it most.

The added ingress hop is acceptable for operator work, but must not become a dependency of ordinary gameplay or a reason for Logging and Admin to own another service's state.

## Decision

### Canonical External Ingress

The target external operator mutation boundary enters through HTTPS at Spring Cloud Gateway and then Logging and Admin. No runtime feature-flag override, admission-pointer preparation/CAS/cutover operation, or scoped tick `PauseTicks`/`ResumeTicks` family is currently a canonically supported executable mutation. Implementation routes exist for parts of those families, but they remain nonconformant drift and must not be externally enabled until the family's action schema, shared cross-language `mutationDigest/v1` golden vectors, and Account authorization-reference issuance/redemption flow are published and consumed by every participant. Before then, no route in those families may issue an authorization reference, forward an owner mutation, or claim canonical executable support. `/moderation/actions` is unavailable/gated, and `ApplyModerationAction` is its corresponding unavailable/gated path in the separate Logging & Admin-owned policy-input/audit persistence family; that family requires its action schema, shared cross-language `mutationDigest/v1` golden vectors, Account authorization-reference issuance, and Logging & Admin receiving-service validation/redemption. Owner-side redemption and owner-side enforcement are not prerequisites for that persistence path and it must not be described as current support. The separate `EvaluateModerationPolicy` read remains live only at internal owner enforcement boundaries. `GET` admission-pointer reads, audit, and prepared-upgrade proof reads remain live and are not preparation mutations. Quota overrides, broader tick/coordination remediation, and moderation enforcement remain deferred target-state families and must not be represented by executable routes until their owner contracts exist.

For each future supported executable mutation request, Logging and Admin:

- authenticates the operator and checks the required tenant, global, or cross-tenant scope;
- validates the operator-facing request;
- records durable operator intent and audit identity;
- forwards a typed, scope-complete request to the owning domain service; and
- correlates the owner response with the audit record and returns an explicit outcome.

The domain owner alone validates domain facts and commits authoritative state. Logging and Admin may persist operator intent, audit, and workflow status, but it does not persist a competing copy of feature-flag, quota, admission, moderation-enforcement, tick, or coordination truth. It never mutates another service's database or Redis keys directly.

### Bounded Owner Delegation

Account Service is the authority for operator delegation through two canonical typed issuance RPCs and one redemption RPC. Human requests use `IssueHumanOperatorAuthorizationReference` and carry the forwarded `control-ui` evidence from Logging & Admin; Account validates its token `jti`, account generation, current tenant or global role, tenant/scope, action family, action-family schema identifier/version, canonical mutation digest, and any role-required assurance. A `platformAdmin` action and a cross-tenant `billingAdmin` action additionally require the bounded `privileged_control` window from ADR 0045; tenant-scoped `tenantAdmin` and `moderator` actions require the caller-bound `tenant_generation` and do not pretend to hold a global-role elevation. The human reference is bound to the actor account, current `control-ui` `jti`, account generation, role, applicable tenant or target-tenant generation, tenant/scope, action family, `actionFamilySchemaId`, `actionFamilySchemaVersion`, `controlPlaneRequestId`, mutation digest, issue time, and expiry. For a global `platformAdmin` tenant operation, it binds the current `target_tenant_generation` without inventing tenant membership and retains the validated `privileged_control` predicate for redemption.

Unattended operator automation uses `IssueAutomationOperatorAuthorizationReference` with `exact_mtls_workload_plus_versioned_automation_policy`. This is a separate automation-specific tenant-scoped authority path, not the `tenant_regular` tenant-role path and not a human membership path. Account accepts only action families explicitly enabled by the current versioned automation policy for the exact workload, target tenant, scope, action-family schema identifier/version, and canonical mutation digest; unsupported or unlisted families are rejected. Account authenticates the exact Logging and Admin workload mTLS identity, validates the policy/workload binding and the current exact `tenant_generation`, and binds that generation to the automation reference. Automation therefore cannot issue `platformAdmin` target-tenant or cross-tenant `billingAdmin` references. If a future policy introduces an automated global-role branch, Account must first define an automation-specific privileged predicate and bind that predicate plus the exact `target_tenant_generation` through issuance, forwarding, redemption, and owner commit; no current automation reference may imply that authority. The current automation reference is also bound to `workload_identity`, `automation_policy_id`, `automation_policy_version`, `tenant_generation`, tenant/scope, action family, `actionFamilySchemaId`, `actionFamilySchemaVersion`, `controlPlaneRequestId`, mutation digest, issue time, and expiry; it has no user account, end-user token, `control-ui` identity, or fabricated membership. The owner must require the same exact Logging and Admin mTLS identity and redeem the reference with `RedeemOperatorAuthorization`, rechecking the policy/workload identity and exact `tenant_generation` returned by Account. Logging and Admin records whether the actor is human or workload automation and retains the applicable human or policy evidence.

The field name is intentional: `tenant_generation` is the canonical automation binding used by Account's typed API and ADR 0048 for the current tenant-scoped branch. `target_tenant_generation` is reserved for conditional target-tenant/global-role branches, including the future automated branch described above, and is not an alias for the current automation field.

Both issuance paths use the same bounded typed-mutation admission contract before Account issues a reference. The published action-family schema pair defines the typed scope, target, expected-version, mutation, and audit-reason fields, explicit presence or absence, raw and normalized limits, and the `mutationDigest/v1` golden vectors. Account, Logging and Admin, and the owner reject malformed, over-limit, unsupported, or mismatched typed input before hashing, authorization issuance, persistence, or forwarding. The paths differ only in their authority attestation: the human path carries Account-validated `control-ui` token, role, assurance, generation, and applicable membership evidence; the automation path carries Account-validated exact mTLS workload and versioned policy evidence. Neither caller assertions nor an ingress-local role string is an authority source. For every supported action family, the Account-issued reference and its immutable `authorityEvidenceBundle` carry the complete authority evidence declared by that family's schema, including the complete authority tuple, independent `membershipVersion`, positive `issuanceFence`, authority scope, source transaction/outbox or event version, projection status/freshness, and issuance-operation identity through redemption and owner commit. Only fields that the action-family schema explicitly declares absent may be omitted; an ingress or owner must not omit a declared field because it is unavailable locally.

`controlPlaneRequestId` is the canonical logical request name across HTTP, Java/domain contracts, ADRs, and audit records. Protobuf contracts may use the language-standard wire spelling `control_plane_request_id`, which maps directly to that same identifier; no second request identity is introduced.

For each future supported executable mutation request, Logging and Admin computes `mutationDigest` as SHA-256 over a versioned canonical encoding of the exact action-family schema identifier/version and every scope, target, expected-version, mutation, and audit-reason field, excluding only transport credentials and the authorization reference. It forwards the reference unchanged with the typed request, digest, and same `controlPlaneRequestId`, including the human tenant-role branch's `tenant_generation`, the conditional human global-role branch's `target_tenant_generation`, or the current automation branch's `tenant_generation` binding issued by Account. For a global-role branch, the forwarded Account evidence also includes the validated `privileged_control` predicate; that predicate is never inferred from a role string or automation policy assertion. For current automation, forwarding, redemption, and owner commit must all use the exact workload identity, automation policy identity/version, target tenant/scope, and `tenant_generation` that Account authorized; a policy or mTLS assertion alone is not tenant authority. The owner recomputes the digest with the same schema identifier/version, redeems and validates the exact reference with Account, checks the returned generation and any privileged predicate against current authority, and only then performs its domain facts, ownership, fencing, and idempotency checks and commits. Logging and Admin's asserted actor, role, tenant, or scope is request context and audit input, not authority. Owner-side operator mutation RPCs are classified `internal_workload` and require the exact Logging and Admin mTLS identity plus Account redemption. A human request may carry only the Account-validated `control-ui` authorization evidence; an unattended request carries no end-user JWT or human identity.

Account's redemption result is an authenticated authorization projection, not the domain mutation outcome. It contains the validated authority tuple, applicable generation and fence, action-family schema pair, request identity, digest, reference fingerprint, expiry, and any required assurance or workload-policy evidence. The domain owner remains responsible for its durable mutation, idempotency record, and terminal outcome.

The canonical encoding is one versioned contract, `mutationDigest/v1`. Each supported action family must name a separately published `actionFamilySchemaId` and `actionFamilySchemaVersion`; those values are explicit inputs to both the digest and the Account authorization reference. This ADR defines that binding but does not claim that any concrete action-family schemas or vector artifacts currently exist. The SHA-256 preimage is the following exact byte grammar; there is no JSON serializer or language-specific alternate:

```text
segment(bytes) = ascii(canonical_decimal_byte_length(bytes)) + ":" + bytes
preimage = segment(utf8("mutationDigest/v1"))
           + field("actionFamilySchemaId")
           + field("actionFamilySchemaVersion")
           + field("scope")
           + field("target")
           + field("expectedVersion")
           + field("mutation")
           + field("auditReason")
field(name) = segment(utf8(name)) + value
value = segment(utf8(type)) + presence + segment(payload)
presence = ascii("0") | ascii("1")
```

`segment` has no separators beyond its ASCII decimal byte length and `:`; the length has no leading zero except for zero. The seven top-level fields occur exactly once and in the shown order. `actionFamilySchemaId` and `actionFamilySchemaVersion` must be present, non-null strings naming the exact supported schema pair; an unknown, unpublished, unsupported, or mismatched pair is rejected before hashing or authorization. `type` is one of `absent`, `null`, `string`, `number`, `boolean`, `object`, or `array`. An absent value is `type=absent`, `presence=0`, and an empty payload. An explicit null is `type=null`, `presence=1`, and an empty payload. A present non-null value has `presence=1` and a payload of the declared type; `presence=0` with any other type is invalid. All framing bytes and scalar payloads are UTF-8 bytes.

Scalar and composite payloads are canonicalized normatively: every input must first be decoded as valid Unicode scalar values; malformed UTF-8, unpaired UTF-16 surrogates, non-scalar code points, and replacement-character recovery are rejected before normalization. `string` is Unicode NFC-normalized before its normalized value is UTF-8 encoded. The accepted numeric source grammar is ASCII `-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?`, with no leading or trailing whitespace, no leading zero in a nonzero integer, no `+` sign, and no exponent; a source using any other exponent, plus-sign, leading-zero, whitespace, or non-decimal form is rejected before decimal conversion. A source with arbitrary precision is interpreted as a base-10 decimal rather than a binary floating-point value, but its raw coefficient/scale and normalized bytes remain bounded by the action-family limits below. Fractional trailing zeroes are removed, `-0`, `-0.00`, and every other signed-zero spelling accepted by the grammar normalize to canonical `0`, and non-finite values such as `NaN`, `Infinity`, and `-Infinity` are rejected. The resulting canonical `number` must match `0` or `-?(?:[1-9][0-9]*(\.[0-9]*[1-9])?|0\.[0-9]*[1-9])`; the canonical grammar has no negative-zero form, plus sign, leading zero, exponent, or trailing fractional zero. Required golden-vector outcomes therefore include `1 -> 1`, `-12.3400 -> -12.34`, `-0.00 -> 0`, and rejection of `+1`, `1e3`, `01`, `1.`, `.5`, an ASCII space before or after `1`, `NaN`, and `Infinity`; a mathematically valid arbitrary-precision value is rejected when its declared raw/normalized scale or byte limit is exceeded rather than rounded or converted through a finite-precision float. `boolean` is exactly `true` or `false`.

For an `object`, the action-family schema declares one fixed member list and order. Each key is valid Unicode, NFC-normalized before UTF-8 encoding, and compared only after normalization; two raw keys that normalize to the same key are a collision and are rejected, as are duplicate raw keys, unknown keys, missing required keys, or any input order other than the declared schema order. The object is `segment(canonical_decimal_member_count)` followed by every declared member, including an absent optional member, as `segment(utf8(normalized_key)) + value`. The member count is the number of declared members, not the number supplied by the caller. An absent optional member is therefore always serialized as `segment(utf8("absent")) + ascii("0") + segment(empty)`, in its declared position; it is never omitted or reordered. `array` is `segment(canonical_decimal_element_count)` followed by each element `value` in request order. Nested object and array values use the same rules. A value whose wire type does not match its action-family schema is rejected before hashing. Transport credentials and the opaque authorization reference are excluded; every scope, target, expected-version, mutation, and audit-reason field declared by the action-family schema is included, including explicit absence or null where allowed.

Each published action-family schema must define two bounded limit layers. Raw parsing limits cap transport bytes, token/framing length, composite nesting depth, object member count, and array element count before normalization or allocation can expand attacker-controlled input. Normalized limits separately cap normalized string bytes, canonical numeric bytes and scale, each canonical segment length, and the total canonical preimage bytes. Account, Logging and Admin, and the domain owner enforce the same raw and normalized limits before hashing, authorization issuance, persistence, or forwarding. Canonicalization that expands a value beyond any normalized or preimage limit is rejected rather than truncated or hashed. Missing limits, an over-limit value, or disagreement between participants makes the family unsupported or the request invalid; implementations must not parse or hash an unbounded payload and reject it only afterward.

The `mutationDigest` value is the lowercase hexadecimal SHA-256 digest of that preimage, exactly 64 ASCII hexadecimal characters. Any future encoding change requires a new version and an explicit compatibility rule; implementations must not silently reinterpret `mutationDigest/v1`. Before claiming a family supported, Account, Logging & Admin, and every owner implementation must consume the same published action-family schema pair and cross-language golden vectors. Those required vectors must include canonical preimage bytes (hex) and expected digest for null versus absent top-level and optional object members, absent-member count and declared order, composed versus decomposed Unicode strings and keys, post-normalization key collisions, malformed Unicode rejection, numeric/string distinctions, object key ordering, arrays, representative scope/target mutations, and signed-zero normalization and rejection cases; each supported language records passing evidence in its contract tests. Until a concrete action-family schema identified by its schema pair and the corresponding cross-language vectors exist for a family, that family remains unsupported pending those artifacts; this ADR does not claim that the schemas or vectors currently exist.

`authorizationReferenceFingerprint` is the versioned, keyed, non-reversible identity of the exact opaque reference. The canonical value is `arfp/v1/<keyId>/<lowercase-hex-HMAC>`, where the HMAC is HMAC-SHA-256 with `K[keyId]` over this exact byte preimage:

```text
preimage = segment(utf8("FireMUD/authorizationReferenceFingerprint/v1"))
           + segment(utf8(keyId))
           + segment(utf8(referenceKind))
           + segment(referenceBytes)
referenceKind = "human_operator" | "automation_operator"
```

`segment` is the byte-length framing defined for `mutationDigest/v1`; `referenceBytes` are the exact bytes of the opaque Account-issued reference, not decoded claims or a reserialized representation. `keyId`, `referenceKind`, the domain-separation label, and the version are all fingerprint inputs; request IDs, mutation digests, transport credentials, actor assertions, and other request context are not. The HMAC output is exactly 32 bytes rendered as 64 lowercase hexadecimal ASCII characters. Account alone owns issuance, HMAC key material, key lifecycle, and authoritative fingerprint calculation at issuance and redemption. Logging and Admin stores and forwards the Account-returned value but never treats a caller-supplied value as authority. The owner redeems the exact reference with Account, receives Account's freshly recomputed fingerprint in the authenticated redemption result, and requires equality with the forwarded value before storing the idempotency tuple. Domain owners never receive the symmetric HMAC key or derive fingerprints independently; the authenticated Account redemption result is the shared canonical calculation boundary.

Account activates one key ID for new references. During rotation, Account retains prior keys for redemption for the maximum reference lifetime plus the complete idempotency-retention and reconciliation window; owners accept a fingerprint only through a successful authenticated Account redemption under an active-or-retained key ID. Existing fingerprints are never rehashed under a new key, unknown or retired key IDs and unsupported fingerprint versions fail closed, and key rotation cannot change the reference bytes or tuple semantics.

Idempotency is bound to validated authority, not to a globally unique caller identifier alone. The Account authorization reference and durable operation record bind `controlPlaneRequestId`, `actionFamilySchemaId`, `actionFamilySchemaVersion`, `mutationDigest`, the complete applicable authority tuple, independent applicable `membershipVersion`, positive `issuanceFence`, and the fingerprint; when the request is automated or crosses the internal owner boundary, they also bind the exact authenticated `workloadIdentity` and applicable automation-policy identity. A retry with a changed schema pair, digest, authorization fingerprint, authority tuple, membership version, issuance fence, workload identity, or bound scope is an idempotency conflict with no mutation, while a retry with the same validated tuple replays the stored outcome.

### Stable Authorization-Reference Issuance And Reconciliation

Account creates a durable bounded issuance record keyed by `controlPlaneRequestId` and the immutable typed request tuple and must durably retain the original opaque reference for replay. The retention may store the reference directly only inside Account's protected authority record or as a bounded encrypted response envelope, but it must cover the reference lifetime plus the complete retry and read-only reconciliation window. An exact retry returns the same opaque reference and expiry, or the same terminal outcome; a changed tuple returns canonical `IDEMPOTENCY_CONFLICT` and does not issue or forward anything. If the issuance response is lost or ambiguous, Account, Logging and Admin, and the owner reconcile by read-only lookup and redemption using that same request ID and tuple. They must never mint a replacement reference, reissue a semantically equivalent reference, or silently create a new request identity for the same mutation. Logging and Admin retains no unbounded raw reference or competing authority record. Redemption and owner reconciliation are retryable and non-destructive while the exact reference remains valid; after expiry or revocation, the unresolved operation remains non-replayable and requires a new explicit operator request.

A typed `REJECTED_BEFORE_COMMIT` outcome for `STALE_OWNER` or `STALE_FENCE` is reconciliation evidence, not direct retry permission. It must contain the same `controlPlaneRequestId`, exact mutation digest and authorization fingerprint, the rejecting owner identity and observed fence, and an owner-side operation/effect lookup proving that no mutation transaction accepted or committed that request. Logging and Admin may then durably transition the matching operation to ADR 0048's terminal `NOT_EXECUTED` state. Only that terminal state permits the same external request tuple to be rearmed for the current owner under a new `ownerMutationId` and current fence; `FENCE_REJECTED` itself is non-rearmable. A timeout, transport failure, missing or inconsistent no-commit evidence, an owner result after mutation acceptance, or any other rejection is ambiguous; callers must stop replay and use read-only reconciliation of the original request and exact tuple before taking any further mutation action.

This protocol defines the target ingress for the following action families. It does not provide executable owner forwarding for `/moderation/actions`, which is a Logging & Admin-owned policy-input/audit persistence path and remains unavailable/gated pending its action schema, shared `mutationDigest/v1` vectors, Account authorization-reference issuance, and Logging & Admin receiving-service validation/redemption. It is not an owner-side enforcement mutation:

| Action family | Current boundary | Target boundary |
| --- | --- | --- |
| Runtime feature-flag override | `ToggleFeatureFlag` and its HTTP path are unavailable/gated pending the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account authorization-reference issuance/redemption; external enablement is denied | Owner-validated, durable Game Session mutation |
| Admission-pointer/version-upgrade control | Live reads, audit, and prepared-upgrade proof reads remain available; preparation, CAS, and cutover mutations are unavailable/target-only pending the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account authorization-reference issuance/redemption | Owner-validated, fenced Game Session mutation |
| Tick pause/resume | Per-instance `PauseTicks`/`ResumeTicks` implementation forwarding exists but is unavailable/gated pending the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account authorization-reference issuance/redemption; external enablement is denied | Owner-validated, fenced Game Session mutation |
| Moderation policy input/audit and enforcement | `/moderation/actions` and `ApplyModerationAction` are unavailable/gated pending the action-family schema, shared cross-language `mutationDigest/v1` vectors, Account authorization-reference issuance, and Logging & Admin receiving-service validation/redemption; no owner-side redemption or enforcement mutation is part of this path. The separate `EvaluateModerationPolicy` read remains live for internal owner enforcement | Versioned policy propagation to Game Session or Social & Groups enforcement owner |
| Game Session session lifecycle (`/sessions*`) | Current `POST /sessions*` hooks remain owner-local Game Session surfaces; they are not the external Logging & Admin ingress and direct owner edge exposure is denied | Logging & Admin authenticates and records operator intent, then forwards typed owner lifecycle RPCs with the Account authorization reference; target-only until the target contract and proof artifacts exist |
| Quota override | No current route or Account owner mutation contract | Hypothetical target entitlement overlay owned by Account through this ingress |
| Broader tick/coordination remediation | No current route or Game Session owner RPC | Target owner control API through this ingress; no direct Redis write |

### Direct Domain Surfaces

Domain write APIs used by this workflow are internal service-to-service surfaces by default. An external domain write is allowed only when the owning contract explicitly designates it as bypass-safe and documents its exact route, domain-local authority, validation, audit behavior, and reason that no Logging and Admin policy or cross-domain orchestration is required.

Safe reads may route directly to the owning domain through Gateway when their authorization, tenant isolation, and redaction contract is explicit. Edge routability alone does not make a write bypass-safe.

### Availability and Runtime Boundary

Core operator writes must remain independent of Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, Alertmanager, and other observability systems. Those systems may supply investigation context, but their outage cannot determine whether an operator mutation succeeds. Write success depends only on the durable audit/intent path and the owning domain contract.

The additional Logging and Admin hop is accepted for human and automated operator workflows. It is not introduced into ordinary gameplay command processing, domain-to-domain gameplay calls, or owner-local enforcement. Logging and Admin remains an ingress, audit, and coordination boundary rather than a gameplay dependency or general domain owner.

## Consequences

- External operator writes have one reviewed authentication, scope, validation, and audit entry point.
- Domain ownership remains explicit: authoritative mutations and domain preconditions stay in the owning service.
- Operators cannot bypass audit by calling an owner-side write route directly unless that exact workflow is documented as bypass-safe.
- Safe read paths need not pay the extra coordination hop.
- Operator writes remain usable during observability outages.
- The extra network hop adds latency and another failure point, which is accepted because these are control-plane rather than gameplay-hot-path operations.
- Logging and Admin and each owner need correlated request identity, retry-safe outcomes, and clear failure reporting so an uncertain response does not invite an unsafe duplicate mutation.

## Alternatives Considered

### Expose Independent Domain Admin Writes

Rejected as the general rule because authorization, audit, operator UX, and outage behavior would diverge across services. Narrow domain-local writes remain possible only through the explicit bypass-safe exception.

### Create a Separate Operator Write Plane

Rejected for the current scope because it adds another deployable, security boundary, and workflow authority without removing the need for owner-side validation and mutation.

### Let Logging and Admin Own Operator-Mutable Domain State

Rejected because it creates competing authorities and couples gameplay and runtime correctness to an administration service.

### Use Dashboards or Observability Stores as the Write Backend

Rejected because observability systems are not authoritative domain stores and may be degraded during incident response.

## Implementation and Proof Obligations

The current implementation is partial. Existing ingress and forwarding paths do not by themselves prove this boundary for every action family.

Implementation and focused proof must:

- classify the exact Gateway and Logging and Admin routes and reject unauthorized, wrong-tenant, wrong-scope, and unclassified requests;
- prove that no feature-flag, admission-pointer/version-upgrade, or tick owner-mutation route is classified executable before its action-family schema, cross-language `mutationDigest/v1` golden vectors, and Account authorization-reference issuance/redemption flow are published and consumed by every participating implementation; prove that `/moderation/actions` remains unavailable/gated until its policy-input/audit schema, cross-language vectors, Account authorization-reference issuance, and Logging & Admin receiving-service validation/redemption exist, without treating owner-side enforcement as part of that persistence gate;
- prove external clients cannot reach the corresponding owner-side mutation APIs directly;
- require exact `control-ui`, current role, and role-appropriate assurance for human ingress, with `tenant_generation` on the tenant-role branch and `target_tenant_generation` plus the `privileged_control` predicate only on the conditional global-role branch, while proving the separate unattended path uses only supported automation-specific tenant-scoped policy branches, carries the exact canonical `tenant_generation` and policy/workload identity through Account issuance, Logging and Admin forwarding, redemption, and owner commit, and cannot impersonate a user or invent membership;
- prove Account issues and owns the bounded operator authorization reference and that the owner redeems it rather than trusting Logging and Admin assertions;
- prove Logging and Admin records actor, scope, action, reason, request identity, and final outcome without trusting caller-supplied actor identity;
- prove the owner performs the authoritative validation and durable mutation without Logging and Admin writing owner state;
- prove retries, duplicate delivery, owner timeout, audit failure, and uncertain completion converge through correlated idempotent outcomes bound to the validated authorization-reference fingerprint and applicable workload identity rather than duplicate mutation;
- prove core operator writes continue when each observability dependency is unavailable;
- maintain an explicit inventory and focused audit proof for every bypass-safe external write; and
- prove ordinary gameplay and owner-local enforcement do not call Logging and Admin merely to process a command or commit domain state.

Feature-flag, admission-pointer/version-upgrade, and tick owner-mutation families are unsupported pending their action-family schemas, cross-language `mutationDigest/v1` golden vectors, and Account authorization-reference issuance/redemption flow; they are not executable families in the current boundary. The separate moderation policy-input/audit persistence family is also unavailable/gated until its action schema, cross-language vectors, Account authorization-reference issuance, and Logging & Admin receiving-service validation/redemption exist; it does not require domain-owner redemption or an owning enforcement contract. Quota and broader remediation families remain deferred until their owner contracts exist. Any forwarded owner-mutation family is incomplete until its external route, audit, typed forwarding, Account reference redemption, owner mutation, negative authorization, retry, and outage behavior are all demonstrated. `/moderation/actions` must not be described as current persistence-only support while its own gate remains incomplete. A family with no current route or owner contract is recorded as coverage drift and is not represented by a placeholder endpoint.

## Reversibility and Revisit Triggers

The routing rule is reversible one workflow class at a time, but changing it requires replacing the centralized audit and scope guarantees at every newly exposed domain boundary. Revisit this decision if operator-write volume makes the additional hop material, a domain demonstrates a broad class of genuinely domain-local bypass-safe writes, Logging and Admin availability cannot meet incident-response needs, a durable operator command plane becomes justified, or a new workflow requires coordinated mutation across multiple domain owners with stronger completion semantics than correlated audit and owner idempotency provide.
