# ADR 0167: Allowlisted Sensitive Trace Attributes

## Status

Accepted

## Implementation Status

This decision is not implemented. Span-family allowlists, sensitive-attribute access and retention controls, IP pseudonymization, collector defense in depth, and focused proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `TRACE-02`
- Decision date: 2026-07-20
- Decision key: `TRACE-02`
- Primary capability: `PO-4.1` logging, metrics, tracing, dashboards, and alerting
- Affected capabilities: `SF-1.3`, `PO-3.4`, `PO-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of incident correlation, player-linked identifiers, IP pseudonymization, retention, external trace stores, performance, and privacy

## Context

Exact gameplay identifiers make distributed traces useful during incidents, but indiscriminate attributes create linkable player histories, storage/query cost, and a path for user content, errors, credentials, or network identity to enter broadly accessible tooling. Sampling reduces volume; it does not anonymize the retained traces.

## Decision

Trace attributes use a producer-side allowlist by named span family, with collector/exporter defense in depth. An identifier is not attached merely because it is available in context.

`characterId`, tenant, game-instance, region, tick/effect, and similar exact operational identifiers are permitted only on span families whose documented incident query requires them. They are sensitive operational data: query access is least-privilege and environment-scoped, queries are audited, retention is finite and profile-defined, and export/privacy/erasure handling is declared.

`command` means a normalized bounded command verb/type, never raw player input. Errors use bounded codes and types, never free-form error messages, exception text, chat, commands, descriptions, payloads, or other user-provided content. Secrets and credentials are forbidden.

Raw client IP addresses are forbidden in every trace profile, not merely long-retention traces. When stable network correlation is justified, `remote_ip_hash` is a rotating environment-specific keyed HMAC with documented key custody, rotation, and finite correlation window. It is pseudonymous personal/network data, not anonymous data. Coarse address prefixes are disabled by default and require a separately enabled short-retention abuse-investigation profile.

External or shared trace backends must preserve equivalent ingestion filtering, encryption, query authorization/audit, environment isolation, retention, export, and deletion controls. The canonical trace-profile mapping for the ADR-0163 owner, class, eligibility, blockers, minimum horizon, cleanup, hold, export/erasure, and bounded-health fields is [Tracing: ADR-0163 Trace-Retention Mapping](../system-architecture-tracing.md#adr-0163-trace-retention-mapping). There is no universal 30-day retention promise.

## Consequences

- Named workflows retain direct operational correlation without requiring a separate mapping service for every investigation.
- Producer allowlists and collector filtering reduce accidental content/secret leakage but add schema governance and proof.
- Exact IDs and network pseudonyms remain sensitive and increase indexed bytes and query/storage cost with sampled span volume.
- Rotating keyed network correlation prevents cheap IPv4 hash enumeration but limits correlation across rotation windows.
- Disabling prefixes by default reduces exposure at the cost of less immediate network-abuse grouping.

## Alternatives Considered

### Opaque Short-Lived Trace Subject Only

This lowers trace-store exposure but makes investigation dependent on a separately authorized mapping/log system and weakens trace-only diagnosis during log-backend failure.

### Raw IDs and IPs Everywhere

This maximizes drilldown speed but creates unnecessary player/network linkage, breach exposure, retention burden, and uncontrolled attribute growth.

### Remove All Gameplay Identity

This is privacy-minimizing but makes many cross-service and replay investigations impractical.

## Implementation and Proof Obligations

Select and report the required checks and evidence under the shared [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md) workflow; record execution results in PR/CI evidence or the owning implementation tracker rather than in this decision record.

Current tracing is limited to generic gRPC spans and does not implement the semantic allowlist, keyed IP correlation, query authorization, retention, or deletion contract. The shared gRPC error helper currently writes full `ErrorDetail.message` into `grpc.app_error.message`, directly violating this decision.

Implementation must centralize allowed keys/value normalization, remove free-form error attributes, apply exporter filtering, provision environment-scoped query identities, audit queries, enforce retention and deletion, and test representative malicious/user content. Proof must cover every named span family, raw-IP and secret rejection, key rotation, prefix-disabled default, cross-environment access rejection, backend export, and storage/cardinality budgets.

## Reversibility and Revisit Triggers

Individual identifiers and retention periods can be removed or narrowed without changing trace identity. Revisit direct character IDs if access/query audit cannot be made reliable, privacy obligations require stronger pseudonymization, or incident evidence shows opaque correlation is sufficient.

## Required Documentation Alignment

- [design/architecture/system-architecture-tracing.md](../system-architecture-tracing.md)
- [design/architecture/system-architecture-security.md](../system-architecture-security.md)
- [design/architecture/system-architecture-logging-monitoring.md](../system-architecture-logging-monitoring.md)
- tracing collector/exporter and backend profile documentation
