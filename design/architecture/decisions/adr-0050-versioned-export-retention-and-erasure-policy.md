# ADR 0050: Versioned Export, Retention, and Erasure Policy

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `DATA-01`
- Primary capability: `AA-1.3` Authentication, recovery, security policy, and account data rights
- Affected capabilities: `PO-1.3`, `AA-2.3`, `SF-2.1`, `PO-3.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `DATA-01`

## Context

[ADR 0043](./adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md) settles the global account-deletion state machine but delegates export completeness and retained-data policy here. The current implementation exports only the Account row and Account-owned profiles, exposes excessive global Account fields through its tenant export, and hard-deletes payment and subscription rows after checking only one blocking subscription. It neither fulfills the documented cross-service export nor safely retains and minimizes necessary evidence.

A single universal retention duration would be false across record purposes and deployment jurisdictions. Conversely, an unspecified “audit/compliance” exception can preserve personal data indefinitely. FireMUD needs one enforceable registry shape with exact finite schedules per player-facing environment.

## Decision

### Full Subject Export

Account orchestrates an asynchronous, versioned JSON export manifest across every owning service for the authenticated global subject. The canonical account route is `GET /accounts/{accountId}/export`; it is caller-bound to that global subject and does not accept a tenant selector. A successful complete export contains all required owner contributions and their schema versions; partial failure remains visibly incomplete and retryable.

The export includes portable subject-supplied and subject-observed data the caller is entitled to receive, including applicable account and profile data, tenant relationships, characters and player state, social relationships, purchase and entitlement records, and other owner-held subject data. It excludes password hashes, active or historical token material, provider secrets, internal fraud or security detection methods, tenant-owned content administered but not owned by the subject, and other subjects' private data. Redacted, omitted, unavailable, and separately retained categories are named in the manifest rather than silently disappearing.

### Tenant-Administrator Export

Tenant-admin export is a separate tenant-controlled recovery and billing contract, not a proxy full-subject request. The canonical route is `GET /tenant-admin/tenants/{tenantId}/export`; its only data selector is the tenant path and its authority is the caller's live `tenantAdmin` membership for that tenant. It remains available through the billing-safe surface when that tenant is billing-blocked and returns a tenant-wide export of tenant-owned records, with only the minimum stable subject references needed to interpret them. It never exposes global email, credentials, external identities, security state, unrelated account attributes, or the existence of other tenant relationships.

The tenant-admin route is not account-targeted: it must not accept an `accountId` path, query parameter, or request-body selector, and it must not return a subject-pair export. The current Account Service implementation remains drift because its tenant export still requires an account target and returns Account/profile-local data rather than a tenant-wide owner contribution set.

### Canonical Retention Registry

Every persistent record category that may contain subject-related data has one canonical registry entry declaring:

- owning service and scope;
- whether and how it appears in subject and tenant exports;
- terminal action: erase, anonymize, tombstone, or retain a minimized record;
- retention trigger and exact maximum duration;
- approved purpose or legal/policy basis;
- roles allowed to read retained data; and
- treatment in backups, replicas, derived stores, logs, and external providers.

No entry may use indefinite retention or a generic “audit” or “compliance” purpose. A player-facing environment requires a complete, versioned operator-level schedule approved by its privacy and finance owners before opening. Statutory or provider-specific numeric durations belong in that jurisdiction-aware schedule; tenants cannot casually extend or shorten them.

### Deletion and Shared Records

The immediate access and orchestration behavior remains governed by ADR 0043. After its published cancellation cutoff:

- credentials, direct profile identity, external-login links, recovery material, active grants, and owner-specific private state are erased or irreversibly anonymized;
- shared or tenant-owned records remain only when other-party or tenant integrity requires them, with the deleted subject replaced by a non-login-capable tombstone unless an approved retained correlation is necessary; and
- billing, tax, refund, fraud, moderation, security, and audit evidence remains only in the minimized form, restricted access, and finite duration declared for its category.

Terminal deletion must not claim that immutable backup copies were edited in place. Backups expire under their existing schedule and are not ordinary searchable data. Before an account reaches terminal `deleted`, Account must append a minimized erasure-overlay record to an immutable, versioned journal retained independently of the PostgreSQL backup lineage. The record carries a monotonic journal sequence, the bounded pseudonymous subject locator needed to find snapshot-era rows, the terminal erasure workflow identity, policy version, completion time, and integrity digest or signature; it contains no reusable credential or ordinary profile data.

The erasure journal is retained for at least the maximum age of every backup that could still resurrect the subject plus the configured recovery safety window. Before the backup workflow opens its PostgreSQL snapshot transaction, it reads and durably binds the current committed journal sequence as `artifactErasureHighWater`. Restore reads and verifies every sequence strictly greater than that bound through the current high-water, inclusive, and idempotently replays each owner-specific erasure or tombstone before traffic reopens. This ordering is deliberately conservative: a deletion committed after the bound but visible in the snapshot is replayed harmlessly, while a deletion committed after the snapshot begins can never be skipped by a later-sampled high-water. Terminal deletion cannot complete until the journal append is durably acknowledged outside the database snapshot boundary. A recovery with missing or duplicate sequence coverage, unverifiable records, unavailable owners, or incomplete convergence remains quarantined. After no eligible backup can contain the erased subject, the locator and journal record expire under the same versioned retention registry rather than becoming an indefinite identity store.

## Consequences

- “Full export” becomes a truthful cross-service result rather than a label for Account-local rows.
- Export is asynchronous and requires versioned contributions, redaction, retry, and completeness reporting from multiple services.
- Tenant recovery cannot leak a player's global account or other-game relationships.
- Deletion may retain explicitly minimized evidence for finite approved periods, so physical disappearance of every byte is not instantaneous.
- Operators carry ongoing retention-schedule and expiry-proof responsibilities, but cannot use a vague indefinite exception.
- Recovery must reconcile erasures before reopen.

## Alternatives Considered

### Account-Local Export and Immediate Hard Delete

Rejected because it produces an incomplete export, leaves other services untreated, destroys settlement evidence, mishandles shared records, and cannot converge partial deletion safely.

### Indefinite Soft Deletion

Rejected because it never establishes terminal minimization and turns every operational concern into permanent retention.

### One Hard-Coded Retention Duration

Rejected because transaction, security, moderation, log, and ordinary profile data have different purposes and applicable obligations. The canonical registry shape is fixed; exact finite numeric schedules are approved per player-facing operating environment.

## Implementation and Proof Obligations

Before this capability is complete, implementation and focused proof must cover registry completeness; subject-bound export authorization and recent authentication; all required service contributions; explicit partial results; other-subject and tenant redaction; tenant export without global `AccountDto` leakage; every safely disclosable billing blocker; retryable owner-specific erasure; minimized retained records and expiry; external-provider cleanup; durable erasure-journal append before terminal deletion; sequence, integrity, high-water, and expiry enforcement; and restore of a backup containing later-erased data followed by complete idempotent owner replay before reopen.

The current Account/profile-only exports and synchronous hard-delete path remain implementation drift and cannot satisfy the player-facing capability gate.

## Reversibility and Revisit Triggers

Registry categories and finite schedules can evolve through versioned policy without redefining account identity or the erasure workflow. Revisit the architecture if a deployment's binding law or provider obligations require a different controller boundary, if tenant-owned exports need a separate dedicated service, or if measured export volume justifies a durable export pipeline rather than Account-led orchestration.
