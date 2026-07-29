# ADR 0047: Logging and Admin as External Operator-Write Ingress

## Status

Accepted

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

## Implementation Status

This ADR is partially implemented. Logging & Admin has the current operator-facing moderation, report, and coordination surfaces, but the complete owner-forwarding, durable idempotency, and proof boundary is not implemented for every action family. In particular, the current moderation action path persists policy input and audit only; it does not invoke an owner-side enforcement RPC. The versioned policy-propagation and owner-enforcement path below remains target-state work.

## Context

Operator actions need one predictable external security and audit boundary without moving authoritative domain state into an administration service. Allowing every domain service to expose independent operator-write APIs would multiply authorization, audit, and failure contracts. Making dashboards or observability stores part of write success would make remediation unavailable during the incidents when operators need it most.

The added ingress hop is acceptable for operator work, but must not become a dependency of ordinary gameplay or a reason for Logging and Admin to own another service's state.

## Decision

### Canonical External Ingress

The target external operator mutation boundary enters through HTTPS at Spring Cloud Gateway and then Logging and Admin. No runtime feature-flag override, admission-pointer/version-upgrade operation, or scoped tick `PauseTicks`/`ResumeTicks` family is currently a canonically supported executable mutation. Implementation routes exist for parts of those families, but they remain nonconformant drift and must not be externally enabled until the family's action schema and cross-language `mutationDigest/v1` golden-vector artifact are published and consumed by every participant. Before then, no route in those families may issue an authorization reference, forward an owner mutation, or claim canonical executable support. The current moderation route persists policy input and audit only and is neither forwarded nor enforced. Quota overrides, broader tick/coordination remediation, and moderation enforcement remain deferred target-state families and must not be represented by executable routes until their owner contracts exist.

For each future supported executable mutation request, Logging and Admin:

- authenticates the operator and checks the required tenant, global, or cross-tenant scope;
- validates the operator-facing request;
- records durable operator intent and audit identity;
- forwards a typed, scope-complete request to the owning domain service; and
- correlates the owner response with the audit record and returns an explicit outcome.

The domain owner alone validates domain facts and commits authoritative state. Logging and Admin may persist operator intent, audit, and workflow status, but it does not persist a competing copy of feature-flag, quota, admission, moderation-enforcement, tick, or coordination truth. It never mutates another service's database or Redis keys directly.

### Bounded Owner Delegation

Account Service is the authority for operator delegation and supports two explicit issuance paths. Human requests use the typed Account `IssueHumanOperatorAuthorizationReference` path and require the current `control-ui` identity; issuance validates its token `jti`, account generation, current tenant or global role, tenant/scope, action family, action-family schema identifier/version, and canonical mutation digest. A `platformAdmin` action and a cross-tenant `billingAdmin` action additionally require the bounded `privileged_control` window from ADR 0045; tenant-scoped `tenantAdmin` and `moderator` actions do not pretend to hold a global-role elevation. Account then issues an opaque, bounded authorization reference bound to the actor account, current `control-ui` `jti`, account generation, role, tenant/scope, action family, `actionFamilySchemaId`, `actionFamilySchemaVersion`, `controlPlaneRequestId`, mutation digest, issue time, and expiry. For a global `platformAdmin` tenant operation, the reference also binds the current target-tenant generation without inventing tenant membership.

Unattended operator automation uses the typed Account `IssueAutomationOperatorAuthorizationReference` path with `exact_mtls_workload_plus_versioned_automation_policy`. Account authenticates the exact Logging and Admin workload mTLS identity and validates the current versioned automation policy for the requested tenant/scope, action family, action-family schema identifier/version, and canonical mutation digest. The reference is bound to `workload_identity`, `automation_policy_id`, `automation_policy_version`, tenant/scope, action family, `actionFamilySchemaId`, `actionFamilySchemaVersion`, `controlPlaneRequestId`, mutation digest, issue time, and expiry; it has no user account, end-user token, `control-ui` identity, or privileged human window. The owner must require the same exact Logging and Admin mTLS identity and redeem the reference with Account. Logging and Admin records whether the actor is human or workload automation and retains the applicable human or policy evidence.

`controlPlaneRequestId` is the canonical logical request name across HTTP, Java/domain contracts, ADRs, and audit records. Protobuf contracts may use the language-standard wire spelling `control_plane_request_id`, which maps directly to that same identifier; no second request identity is introduced.

For each future supported executable mutation request, Logging and Admin computes `mutationDigest` as SHA-256 over a versioned canonical encoding of the exact action-family schema identifier/version and every scope, target, expected-version, mutation, and audit-reason field, excluding only transport credentials and the authorization reference. It forwards the reference unchanged with the typed request, digest, and same `controlPlaneRequestId`. The owner recomputes the digest with the same schema identifier/version, redeems and validates the exact reference with Account, then independently checks current domain facts, ownership, fencing, and idempotency. Logging and Admin's asserted actor, role, tenant, or scope is request context and audit input, not authority. Owner-side operator mutation RPCs are classified `internal_workload` and require the exact Logging and Admin mTLS identity plus Account redemption. A human request may carry only the Account-validated `control-ui` authorization evidence; an unattended request carries no end-user JWT or human identity.

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

Scalar and composite payloads are canonicalized normatively: `string` is Unicode NFC followed by UTF-8 encoding; numeric source forms are first interpreted as finite base-10 decimals, and forms representing zero such as `-0` or `-0.00` normalize to canonical `0`. The resulting canonical `number` must match `0` or `-?(?:[1-9][0-9]*(\.[0-9]*[1-9])?|0\.[0-9]*[1-9])`; fractional trailing zeroes are removed, the canonical grammar has no negative-zero form, and a signed-zero form that reaches canonical validation without normalization is rejected. Numbers never use a plus sign, leading zero, or exponent; `boolean` is exactly `true` or `false`; `object` is `segment(canonical_decimal_member_count)` followed by each `segment(utf8(key)) + value` in the exact key order declared by the action-family schema; and `array` is `segment(canonical_decimal_element_count)` followed by each element `value` in request order. Nested object and array values use the same `value` grammar. Unknown or duplicate object keys, undeclared key order, non-finite numbers, invalid Unicode, and a value whose wire type does not match its action-family schema are rejected before hashing. Transport credentials and the opaque authorization reference are excluded; every scope, target, expected-version, mutation, and audit-reason field declared by the action-family schema is included, including explicit absence or null where allowed.

Each published action-family schema must define two bounded limit layers. Raw parsing limits cap transport bytes, token/framing length, composite nesting depth, object member count, and array element count before normalization or allocation can expand attacker-controlled input. Normalized limits separately cap normalized string bytes, canonical numeric bytes and scale, each canonical segment length, and the total canonical preimage bytes. Account, Logging and Admin, and the domain owner enforce the same raw and normalized limits before hashing, authorization issuance, persistence, or forwarding. Canonicalization that expands a value beyond any normalized or preimage limit is rejected rather than truncated or hashed. Missing limits, an over-limit value, or disagreement between participants makes the family unsupported or the request invalid; implementations must not parse or hash an unbounded payload and reject it only afterward.

The `mutationDigest` value is the lowercase hexadecimal SHA-256 digest of that preimage, exactly 64 ASCII hexadecimal characters. Any future encoding change requires a new version and an explicit compatibility rule; implementations must not silently reinterpret `mutationDigest/v1`. Before claiming a family supported, Account, Logging & Admin, and every owner implementation must consume the same published action-family schema pair and cross-language golden vectors. When such vectors are published, they must include canonical preimage bytes (hex) and expected digest for null versus absent values, numeric/string distinctions, Unicode normalization, object key ordering, arrays, representative scope/target mutations, and signed-zero normalization and rejection cases; each supported language records passing evidence in its contract tests. Until a concrete action-family schema identified by its schema pair and the corresponding cross-language vectors exist for a family, that family remains unsupported pending those artifacts; this ADR does not claim that the schemas or vectors currently exist.

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

Idempotency is bound to validated authority, not to a globally unique caller identifier alone. The Account authorization reference and durable operation record bind `controlPlaneRequestId`, `actionFamilySchemaId`, `actionFamilySchemaVersion`, `mutationDigest`, and the fingerprint; when the request is automated or crosses the internal owner boundary, they also bind the exact authenticated `workloadIdentity` and applicable automation-policy identity. A retry with a changed schema pair, digest, authorization fingerprint, workload identity, or bound scope is an idempotency conflict with no mutation, while a retry with the same validated tuple replays the stored outcome.

This protocol defines the target ingress for the following action families. It does not provide executable forwarding for the current moderation policy-input/audit route; no executable moderation route may be added until an owning enforcement contract exists:

| Action family | Current boundary | Target boundary |
| --- | --- | --- |
| Runtime feature-flag override | Implementation route exists but is unsupported/nonconformant pending action-family schema and cross-language `mutationDigest/v1` golden vectors; external enablement is denied | Owner-validated, durable Game Session mutation |
| Admission-pointer/version-upgrade control | Partial implementation routes exist but are unsupported/nonconformant pending action-family schema and cross-language `mutationDigest/v1` golden vectors; external enablement is denied | Owner-validated, fenced Game Session mutation |
| Tick pause/resume | Implementation forwarding exists but is unsupported/nonconformant pending action-family schema and cross-language `mutationDigest/v1` golden vectors; external enablement is denied | Owner-validated, fenced Game Session mutation |
| Moderation enforcement | No executable route; current route persists policy input and audit only | Versioned policy propagation to Game Session or Social & Groups enforcement owner |
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
- prove that no feature-flag, admission-pointer/version-upgrade, or tick mutation route is classified executable before its action-family schema and cross-language `mutationDigest/v1` golden vectors are published and consumed by every participating implementation; the current moderation route persists policy input and audit only, and executable moderation routes are rejected until an owning enforcement contract exists;
- prove external clients cannot reach the corresponding owner-side mutation APIs directly;
- require exact `control-ui`, current role, and role-appropriate assurance for human ingress, while proving the separate unattended path requires exact mTLS workload identity plus current versioned automation policy and cannot impersonate a user;
- prove Account issues and owns the bounded operator authorization reference and that the owner redeems it rather than trusting Logging and Admin assertions;
- prove Logging and Admin records actor, scope, action, reason, request identity, and final outcome without trusting caller-supplied actor identity;
- prove the owner performs the authoritative validation and durable mutation without Logging and Admin writing owner state;
- prove retries, duplicate delivery, owner timeout, audit failure, and uncertain completion converge through correlated idempotent outcomes bound to the validated authorization-reference fingerprint and applicable workload identity rather than duplicate mutation;
- prove core operator writes continue when each observability dependency is unavailable;
- maintain an explicit inventory and focused audit proof for every bypass-safe external write; and
- prove ordinary gameplay and owner-local enforcement do not call Logging and Admin merely to process a command or commit domain state.

Feature-flag, admission-pointer/version-upgrade, and tick families are unsupported pending their action-family schemas and cross-language `mutationDigest/v1` golden vectors; they are not executable families in the current boundary. Quota and broader remediation families remain deferred until their owner contracts exist. Any family is incomplete until its external route, audit, typed forwarding, Account reference redemption, owner mutation, negative authorization, retry, and outage behavior are all demonstrated. Moderation remains a persistence-only policy-input/audit route until its owning enforcement contract exists. A family with no current route or owner contract is recorded as coverage drift and is not represented by a placeholder endpoint.

## Reversibility and Revisit Triggers

The routing rule is reversible one workflow class at a time, but changing it requires replacing the centralized audit and scope guarantees at every newly exposed domain boundary. Revisit this decision if operator-write volume makes the additional hop material, a domain demonstrates a broad class of genuinely domain-local bypass-safe writes, Logging and Admin availability cannot meet incident-response needs, a durable operator command plane becomes justified, or a new workflow requires coordinated mutation across multiple domain owners with stronger completion semantics than correlated audit and owner idempotency provide.
