# ADR 0048: Durable Idempotent Operator-Write Execution

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `ADMIN-01`
- Primary capability: `PO-1.1` Administration and operator control
- Affected capabilities: `PO-1.3`, `PO-1.4`, `SF-2.3`, `GR-1.1`, `PO-4.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `ADMIN-01`

## Context

[ADR 0047](./adr-0047-logging-admin-as-external-operator-write-ingress.md) makes Logging and Admin the external ingress for operator writes while preserving domain mutation authority. That routing choice does not by itself settle which durable record proves execution, how timeouts and retries avoid duplicate effects, or how a stable Game Session front end reaches region-owned state.

Operator writes are low-volume control-plane operations, so one durable intent record and owner-side idempotency are justified. They must not become a distributed transaction, depend on an observability backend, or allow an ingress process to bypass current domain authority and gameplay fencing.

## Decision

### One Correlated Execution

Logging and Admin assigns one `controlPlaneRequestId`, computes ADR 0047's versioned canonical `mutationDigest`, and durably records the requested actor, reason, scope, mutation, digest, and initial status before forwarding. If that initial durable record cannot be written, the mutation is not attempted.

Account first issues the applicable opaque bounded operator authorization reference defined by ADR 0047. A human reference is bound to the current `control-ui` token `jti`, account generation, role-assurance result, tenant/scope, action family, actor, `controlPlaneRequestId`, `mutationDigest`, issue time, and expiry. An unattended-automation reference is instead bound to the exact workload identity, current automation-policy identity and version, tenant/scope, action family, `controlPlaneRequestId`, `mutationDigest`, issue time, and expiry; it carries no invented user or human elevation. Logging and Admin forwards that reference unchanged with the typed request, digest, and same request identifier to the authoritative domain owner. The owner:

- authenticates the immediate Logging and Admin workload with exact mTLS identity and redeems the reference with Account;
- authorizes the current actor and scope rather than trusting ingress-derived domain conclusions or Logging and Admin assertions;
- validates current domain preconditions and, where applicable, the current authority generation or gameplay fence;
- recomputes the canonical mutation digest and requires it to match the Account reference;
- treats `controlPlaneRequestId` as its idempotency identity while storing the matching digest on the durable result row;
- durably commits the mutation and durable result together, or commits neither; and
- returns the previously committed result only when a duplicate request identifier carries the same digest; same-identifier/different-digest requests fail with canonical `IDEMPOTENCY_CONFLICT` and never apply either payload again.

Logging and Admin then records the correlated outcome. It reports success only after the owner confirms its durable commit.

### Failure and Reconciliation Semantics

A failed final audit-outcome update does not roll back an already committed domain mutation. Each owner exposes an internal, read-only request-result lookup for the exact `controlPlaneRequestId`, callable only by the allowlisted Logging and Admin workload. The lookup reads the durable idempotency/result record and cannot execute or reauthorize the mutation, so it remains safe after the original human or automation authorization reference expires or is revoked.

Logging and Admin retains the original durable intent and reconciles through that result lookup until the owner result can be recorded. It may redeliver the mutation with the same identifier only while the original authorization reference remains valid. Once that reference is invalid, reconciliation is read-only: an existing owner result proves the committed or rejected outcome, while durable absence proves the mutation did not commit because owner mutation and result persistence are atomic. Authority revocation never creates a fresh reference merely to replay an old write.

Operator-visible states must distinguish at least:

- **not executed** — forwarding never occurred or the owner durably rejected the request;
- **committed, response lost or outcome pending** — execution may have committed and the same identifier must be queried through the owner result-read contract rather than replaced; and
- **failed** — a terminal, durable owner result proves no successful mutation for that request.

Clients and operators must not create a fresh request identifier merely because a response timed out. Retention of idempotency results must cover the documented retry and reconciliation window for that action family.

### Region-Scoped Game Session Writes

A Game Session request that mutates region-scoped tick or coordination state executes at the current lease owner under the current gameplay fence. A stable front end locates and forwards to that owner; it does not mutate region-owned state itself. A stale owner or stale fence rejects the request, after which the same `controlPlaneRequestId` may be safely retried against the current owner.

Owner-side admission, feature-flag, and tick control RPCs are `internal_workload` calls under this contract. Scopes or action families that cannot yet satisfy this durable, idempotent, fenced contract are rejected as unsupported rather than implemented through a weaker direct-write path. Current moderation enforcement and quota override owner contracts remain coverage drift until their routes and owner APIs exist.

### Availability Boundary

Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, Alertmanager, and similar observability systems may assist investigation, but are not part of write success, owner durability, idempotency, or reconciliation. The required durable records remain in Logging and Admin PostgreSQL and the owning domain's authoritative store.

## Consequences

- An accepted operator request is auditable before execution and can be reconciled after crashes or lost responses.
- Each owner needs a durable request-result record or equivalent atomic idempotency mechanism for supported operator actions.
- Logging and Admin remains a coordinator and audit owner, not a second owner of domain state.
- The protocol adds durable writes and coordination latency to low-volume operator actions, not to ordinary gameplay processing.
- Cross-service atomic rollback is deliberately absent: a committed owner mutation remains valid while a missing final audit outcome is repaired.
- Region-scoped remediation cannot bypass lease ownership even when invoked through an externally stable endpoint.

## Alternatives Considered

### Best-Effort Forwarding with Audit Afterward

Rejected because an audit outage could allow unaudited mutation and a lost response could encourage a duplicate effect.

### Distributed Transaction Across Logging and Admin and the Owner

Rejected because it would couple availability and storage protocols across services without eliminating the need for idempotent recovery.

### Logging and Admin Owns the Authoritative Mutation

Rejected because it duplicates domain state and bypasses owner-side invariants, current authority, and gameplay fencing.

### Let the Stable Game Session Front End Mutate Region State

Rejected because the front end is not the lease owner and cannot safely serialize a write with region tick execution.

## Implementation and Proof Obligations

The current implementation is partial. Each supported operator-action family must demonstrate:

- durable intent before forwarding, including trusted actor, reason, exact scope, mutation, and `controlPlaneRequestId`;
- authorization and domain validation at the owner;
- atomic owner mutation and result persistence with duplicate delivery returning the original result;
- an allowlisted read-only owner result lookup that recovers the durable outcome after authorization expiry or revocation without executing the mutation;
- recovery from crashes before forwarding, during owner execution, after owner commit, and before final outcome recording;
- truthful operator outcomes for rejection, timeout, lost response, duplicate delivery, authorization expiry, and reconciliation;
- no successful mutation when the initial intent store is unavailable;
- successful execution and reconciliation while observability dependencies are unavailable; and
- for region-scoped Game Session actions, current-owner forwarding, stale-fence rejection, and safe same-identifier retry after ownership changes.

## Reversibility and Revisit Triggers

Individual action families can migrate to another durable command mechanism while preserving their request identifiers and result history. Revisit this decision if write volume makes synchronous intent-and-forward coordination material, a durable command bus supplies equivalent authorization and outcome semantics, or an action genuinely requires atomic mutation across multiple domain owners rather than one authoritative owner plus correlated audit.
