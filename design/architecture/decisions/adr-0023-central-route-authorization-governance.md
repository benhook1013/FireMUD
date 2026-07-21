# ADR 0023: Central Route Authorization Governance

## Status

Accepted

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `SF-1.3` Shared authentication, authorization, and policy primitives
- Affected capabilities: `AA-1.2`, `PO-1.1`, `PO-2.1`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `AUTH-04`

## Context

FireMUD exposes HTTP, gRPC, WebSocket, text-protocol, and operator surfaces across many services. Service-local role checks or broad edge routing can silently diverge on token profile, tenant scope, revocation, cross-tenant access, and redaction. A newly added endpoint must not become reachable merely because it matches a Gateway prefix or inherits an approximate role check.

The repository contains a machine-readable authorization matrix and substantial static Gateway route/blocking tests, but the matrix is not currently consumed by CI or shared middleware. Several Gateway routes remain broad families, and current JWT middleware does not implement the documented route-class, token-profile, authority-generation, and cross-tenant distinctions. The governance contract is therefore accepted target state, not completed proof.

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

Until source-stable OpenAPI/protobuf coverage is complete and the generated comparison is validated, missing route coverage is recorded as authorization drift/gap. The incomplete YAML must not drive generated default-deny policy for unlisted routes. Once that inventory gate passes, the declared default-deny policy and full-fail checks apply to the complete validated source inventory.

### Enforcement

- CI will derive candidate route inventories from OpenAPI, protobuf, protocol-command, and explicitly registered operator surfaces and fail if a protected or externally reachable route is missing, stale, or inconsistently classified after the inventory gate passes.
- Runtime middleware will reject an unclassified protected route once complete inventory coverage is available. It must not approximate the route as `tenant_regular` or another permissive class; current unlisted-route findings remain drift/gap rather than generated policy.
- Shared middleware enforces route-level token profile, allowlist/authority-generation, scope, and role rules. The owning service additionally enforces live domain facts such as resource ownership, current membership, entitlement, visibility, and mutation preconditions.
- Cross-tenant support-safe, billing-safe, and data-bearing behavior uses separate classified APIs and response profiles rather than optional flags on one ambiguous endpoint.
- Gateway routes only reviewed external surfaces. Prefix routing may be a transport convenience only when the exact reachable endpoint inventory is generated and unclassified/internal endpoints are denied; a broad wildcard is not itself an exposure policy.
- Logging & Admin remains the external ingress for sensitive operator writes unless a separate owned surface is explicitly classified.

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

- Build source-stable candidate inventories from authoritative OpenAPI, protobuf, protocol, and operator registrations and compare them with the YAML matrix in CI before enabling generated default-deny/full-fail enforcement.
- Compile or validate shared HTTP/gRPC middleware metadata from the matrix and reject unknown route identities at runtime.
- Enforce strict token profile/audience, allowlist, authority-generation, tenant, role, and cross-tenant response-profile rules.
- Replace or constrain broad Gateway wildcards so exact externally reachable endpoints are known and internal/unclassified additions remain unreachable.
- Correct implementation trackers that currently describe route-matrix enforcement as proven before these checks exist.
- Prove representative public, account-scoped, tenant, billing-safe, support-safe, cross-tenant data-bearing, internal-service, gameplay-admission, and operator-write routes, including negative wrong-profile/wrong-scope cases.

## Required Documentation Alignment

- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-authz-route-matrix.md`
- `design/architecture/system-architecture-authz-route-matrix.yaml`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-overview.md`
- owning service API contracts and implementation trackers
