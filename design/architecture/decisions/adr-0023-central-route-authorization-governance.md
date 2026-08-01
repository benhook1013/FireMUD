# ADR 0023: Central Route Authorization Governance

## Status

Accepted

## Implementation Status

The machine-readable matrix and substantial static Gateway route/blocking tests exist, but the route inventory is incomplete, several Gateway routes remain broad families, and the matrix is not consumed by generated completeness checks or shared runtime middleware.

- **Partial; focused static safeguard present, execution unrun here:** Production and test-profile Gateway configuration exposes only the curated route set and rejects coarse public catchall paths. Focused proof is recorded by [`GatewayRoutesConfigurationProdTest`](../../../services/spring-cloud-gateway/src/test/java/net/firedevops/firemud/springcloudgateway/config/GatewayRoutesConfigurationProdTest.java) (`publicRouteAllowlistExposesOnlyCuratedEdgeRoutes`, `prodProfileHasNoCoarsePublicCatchallRouteFallbacks`) and [`GatewayRoutesConfigurationTestProfileTest`](../../../services/spring-cloud-gateway/src/test/java/net/firedevops/firemud/springcloudgateway/config/GatewayRoutesConfigurationTestProfileTest.java) (`testProfileUsesCanonicalCuratedPublicRouteIds`, `testProfileHasNoCoarsePublicCatchallRouteFallbacks`).
- **Unavailable/unproven complete safeguard:** Generated inventory comparison, shared route middleware, strict profile/audience enforcement, active issued-token registry enforcement, and universal runtime denial of unclassified protected routes are not currently proven. Current JWT middleware does not yet enforce the complete issued-token registry, route-class, token-profile, authority-generation, and cross-tenant distinctions documented here. Acceptance of this target makes no completion claim.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `SF-1.3` Shared authentication, authorization, and policy primitives
- Affected capabilities: `AA-1.2`, `PO-1.1`, `PO-2.1`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `AUTH-04`
- Human review status: Completed
- Human review date: 2026-07-18
- Human review disposition: Revised
- Review source: `AUTH-04`

## Context

FireMUD exposes HTTP, gRPC, WebSocket, text-protocol, and operator surfaces across many services. Service-local role checks or broad edge routing can silently diverge on token profile, tenant scope, revocation, cross-tenant access, and redaction. A newly added endpoint must not become reachable merely because it matches a Gateway prefix or inherits an approximate role check.

The repository needs one governance contract spanning broad route families and service-local checks. The current implementation boundary and proof status are recorded in the dedicated Implementation Status section above; the decision below defines the target contract without treating it as current runtime proof.

## Decision

### One Machine-Readable Policy Source

`design/architecture/system-architecture-authz-route-matrix.yaml` is the canonical route-level authorization policy for entries it declares. The current YAML is an incomplete inventory, not yet a complete registry of every externally reachable or protected HTTP route, gRPC method, gameplay command, and operator surface. Each declared entry has at least:

- stable service and route identity;
- public/protected status and route class;
- accepted token profile and audience;
- caller/service identity requirements;
- account, tenant, membership, or cross-tenant scope;
- roles/capabilities and applicable authority generations;
- required live authority checks; and
- response redaction, mutation acknowledgement, and canonical security errors where applicable.

The Markdown matrix is its human-readable companion. Generated inventories, middleware mappings, annotations, and tests are derived from or mechanically checked against the YAML source rather than becoming independent policy authorities.

Until source-stable OpenAPI/protobuf coverage is complete and the generated comparison is validated, missing route coverage is recorded as authorization drift/gap. The incomplete YAML must not drive generated default-deny policy for unlisted routes. Independently of that generated-policy limitation, any protected or externally reachable route that is not covered by a validated allowlist must remain conservatively denied at the edge or unreachable; it must not be forwarded by a broad prefix. Once that inventory gate passes, the declared default-deny policy and full-fail checks apply to the complete validated source inventory.

### Enforcement

- CI will derive candidate route inventories from OpenAPI, protobuf, protocol-command, and explicitly registered operator surfaces and fail if a protected or externally reachable route is missing, stale, or inconsistently classified after the inventory gate passes.
- Before complete inventory coverage is available, edge and service safeguards must deny or leave unreachable any unclassified protected or external route; this conservative guard is not generated policy from the incomplete YAML. After the inventory gate passes, runtime middleware rejects every unclassified protected route. It must not approximate the route as `tenant_regular` or another permissive class; current unlisted-route findings remain drift/gap until then.
- Shared middleware enforces route-level token profile, committed-issuance/`active` registry, allowlist/authority-generation, scope, and role rules only for registry-backed JWT-presenting routes. The owning service additionally enforces live domain facts such as resource ownership, current membership, entitlement, visibility, and mutation preconditions.
- Cross-tenant support-safe, billing-safe, and data-bearing behavior uses separate classified APIs and response profiles rather than optional flags on one ambiguous endpoint.
- Gateway routes only reviewed external surfaces. Prefix routing may be a transport convenience only when the exact reachable endpoint inventory is generated and unclassified/internal endpoints are denied; a broad wildcard is not itself an exposure policy.
- Logging & Admin remains the external ingress for sensitive operator writes unless a separate owned surface is explicitly classified.

### Credential-Path Partition

The exact route identity is resolved first and must select one known route class before claim-shape, audience, or other token-derived policy is evaluated. An unknown, unclassified, multiply classified, or otherwise invalid protected control-plane, bootstrap, or admission route is denied before token-derived policy and is never approximated as `tenant_regular` or another permissive class. Once a known registry-backed JWT-presenting route class is selected, a class that accepts a registry-backed JWT (`control-ui`, `player-bootstrap`, or a named private delegation) validates the required claims, rejects malformed claim shapes, requires the exact declared token profile and audience, verifies the time bounds, and requires one matching `active` issued-token record backed by matching durable `COMMITTED` issuance evidence plus the complete applicable Account generation/evidence bundle. The record and evidence must match the token hash, `jti`, profile, audience, token generation, authority tuple, issuance fence, applicable membership version, current issuer/account/tenant/membership generations, and source-version/checkpoint/freshness evidence. For a rotated `game-session-account-delegation` route, those Account fields are not sufficient: authorization additionally requires the exact durable ADR 0035 rotation postconditions `rotationOperationId`, `leaseId`, positive `leaseVersion`, and exact `gameplayBindingId`, plus a durable installation-fence record in `INSTALLED` state matching the replacement token identity, rotation lineage, and gameplay binding. This downstream lease/binding/installation evidence is separate from the Account evidence bundle and may not be inferred from it, registry activity, JWT claims, or mTLS identity. A missing, non-`active`, pending, non-`COMMITTED`, stale, malformed, or mismatched record or evidence, Account bundle, rotation postcondition, or installation fence denies; dependency unavailability is `AUTH_UNAVAILABLE`. This registry contract is not universal gameplay middleware. The route matrix must preserve these separate partitions:

- The one-use `gameplay-connect` token uses only Gateway's dedicated replay record/fence, quarantine cutoff, deny marker, exact-`jti` atomic consume, and signed connect-context validation contract; it does not create or consult an Account issued-token registry record.
- Non-JWT `LOGIN` uses credentials and, for first-party WebSocket use, the verified connect context. Game Session performs the current Account checks and creates the authenticated session context; no registry lookup is invented.
- Non-JWT `PLAY` and fresh gameplay admission use the exact bound Game Session context, current membership/entitlement/grant and routing authority, and the Account exact-binding admission lease/CAS; they do not use the issued-token registry.
- Reconnect, resume, or rebind without a presented JWT use the exact existing gameplay binding and resume/rebind proof plus current Account authority. Stale, missing, or conflicting binding evidence denies the operation; no registry lookup is added.
- Routine gameplay commands use the validated bound context, binding/coordination fences, typed workload context, and domain authorization. They do not repeat registry or Account generation lookups per command; bounded reconciliation consumes later authority changes.

These partitions preserve ADR 0022's gameplay ownership and replay-fence boundary and ADR 0035's registry exception. A gameplay route must not acquire a registry check merely because it is gameplay-related, and the `gameplay-connect` route must never be forced through the registry-backed JWT contract. A registry-backed JWT-presenting control-plane/bootstrap/admission route must not bypass route classification, exact profile/audience validation, matching `COMMITTED` issuance evidence, the `active` registry record, or applicable generation/evidence checks by being treated as gameplay traffic.

### Change and Proof Policy

Adding or changing an endpoint includes its policy entry and focused enforcement proof in the same change. The maintenance and release-testing cost is an accepted security tradeoff. AI-assisted implementation may reduce mechanical upkeep but does not weaken review or proof requirements.

Security-critical classes, including authentication/session admission, billing/support, subscription/entitlement, cross-tenant, and operator-write surfaces, require explicit security review. Other classified changes follow normal owning-team review plus automated completeness checks.

## Consequences

- New and changed endpoints cannot silently inherit public exposure or an approximate role policy.
- Cross-service authorization vocabulary, token profiles, and tenant boundaries remain consistent.
- Endpoint work requires matrix updates, generated-inventory checks, middleware proof, and sometimes redaction tests.
- The YAML policy and its generators become critical build inputs and require clear ownership and review.
- Route-level governance does not replace domain authorization inside services.

## Alternatives Considered

### Service-Local Authorization

Letting each service maintain independent annotations and role checks is cheaper initially but permits drift, incomplete revocation, inconsistent cross-tenant behavior, and accidental exposure through broad routes.

### External General-Purpose Policy Engine

A dedicated policy engine could centralize evaluation and dynamic policy updates. It adds a platform-wide dependency, policy language/tooling, domain-context integration, deployment coupling, and a large common failure radius. FireMUD does not need that complexity for a mostly static route-class contract.

### Gateway-Only Authorization

Enforcing everything at Gateway misses internal service calls and cannot safely evaluate all resource ownership, membership, or domain mutation facts. Gateway remains one enforcement point, not the sole authority.

## Implementation and Proof Obligations

- Build source-stable candidate inventories from authoritative OpenAPI, protobuf, protocol, and operator registrations and compare them with the YAML matrix in CI before enabling generated default-deny/full-fail enforcement; until then, prove unclassified protected/external routes are denied or unreachable without deriving that safeguard from incomplete YAML.
- Compile or validate shared HTTP/gRPC middleware metadata from the matrix and reject unknown route identities at runtime.
- Enforce strict route-class-first evaluation followed by token profile/audience, matching `COMMITTED` issuance evidence plus `active` issued-token registry state, allowlist, authority-generation, tenant, role, and cross-tenant response-profile rules only for registry-backed JWT-presenting routes.
- Add negative proof that a registry-backed JWT-presenting control-plane/bootstrap/admission route with an unknown or wrong route class, wrong token profile or audience, missing/non-`active`/mismatched issued-token record, missing/non-`COMMITTED` issuance evidence, or missing/stale/mismatched generation/evidence bundle is denied before domain authorization; for a rotated `game-session-account-delegation` route, also prove denial when any of `rotationOperationId`, `leaseId`, `leaseVersion`, `gameplayBindingId`, or the durable `INSTALLED` installation fence is absent, stale, or mismatched, including the case where the Account evidence bundle is otherwise valid. Separately prove that `gameplay-connect` uses only its dedicated replay record/fence, quarantine cutoff, deny marker, exact-`jti` atomic consume, and signed connect-context validation and never consults that registry; non-JWT `LOGIN`, `PLAY`, reconnect, and resume/rebind remain their separate ADR 0022/0035 partitions.
- Replace or constrain broad Gateway wildcards so exact externally reachable endpoints are known and internal/unclassified additions remain unreachable.
- Correct implementation trackers that currently describe route-matrix enforcement as proven before these checks exist.
- Prove representative public, account-scoped, tenant, billing-safe, support-safe, cross-tenant data-bearing, internal-service, gameplay-admission, and operator-write routes, including negative wrong-profile/wrong-scope cases.

## Required Documentation Alignment

- [Authentication architecture](../system-architecture-authentication.md)
- [Authorization route matrix](../system-architecture-authz-route-matrix.md)
- [Authorization route matrix YAML](../system-architecture-authz-route-matrix.yaml)
- [Gateway architecture](../system-architecture-gateway.md)
- [Overview architecture](../system-architecture-overview.md)
- [Account Service API contract](../microservices/account-service/api-contracts.md)
- [Game Session Service API contract](../microservices/game-session-service/api-contracts.md)
- [Spring Cloud Gateway API contract](../microservices/spring-cloud-gateway/api-contracts.md)
- [TCP Proxy Service API contract](../microservices/tcp-proxy-service/api-contracts.md)
- [Player access and session implementation tracker](../../project-management/implementation-tracking/player-access-and-session.md)
