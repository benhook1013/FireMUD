# Identity, Tenancy, Account Lifecycle, And Data Rights Review

Use this prompt to review the normal authority and lifecycle rules for accounts, tenants, membership, gameplay admission, entitlements, commerce, and account data. Adversarial threat analysis belongs to the security review.

Apply the [shared review contract](./00-shared-review-contract.md).
Apply the [orchestrated review workstream contract](./02-orchestrated-review-workstream-contract.md).

## Orchestrated Execution

A full invocation is an orchestrated review workstream. The invoking main thread takes primary ownership and delegates these prompt-specific bounded evidence lanes:

- registration, authentication, token lifecycle, and external identity;
- tenant, account, realm, game, and character identity, membership, admission, and session continuity;
- entitlements, commerce, provider callbacks, and reconciliation; and
- export, erasure, retention, and deletion evidence.

The primary reconciles identity, scope, freshness, and lifecycle handoffs across the lanes.

## Starting Sources

- `design/product/requirements.md`
- `design/product/user-journeys/players.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-jwt-and-token-contracts.md`
- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-authz-route-matrix.md`
- `design/architecture/system-architecture-authz-route-matrix.yaml`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/microservices/account-service/`
- `design/architecture/microservices/game-session-service/`
- the owning implementation trackers and focused proof

Follow canonical contracts for external identity, billing providers, membership, entitlements, export, erasure, retention, and provider callbacks when those sources delegate them.

## Review

Trace:

- registration, authentication factors, token issuance and validation, refresh, logout, revocation, and authority-generation changes;
- account, tenant, realm, game, character, session, role, and membership identities across every boundary;
- gameplay discovery, connect-token issuance, admission, `JOIN`, `PLAY`, continuity, takeover, suspension, and termination;
- entitlement evaluation, stale or unavailable authority, plan changes, subscription callbacks, cancellation, and provider reconciliation;
- external identity attachment and removal;
- account export, erasure, retention, artifact access, and deletion completion; and
- user-visible and operator-visible outcomes for rejected, stale, unavailable, duplicated, or partially completed operations.

Check that the canonical owner, scope, freshness, error result, retry rule, audit evidence, and focused proof are explicit. Keep target-only provider or product behavior distinct from implemented behavior and live provider evidence.

## Output

Provide:

1. an identity and lifecycle coverage table;
2. authority, scoping, freshness, and fail-closed findings;
3. account-lifecycle, commerce, and data-rights gaps;
4. implementation or proof drift; and
5. the review state required by the shared contract.
