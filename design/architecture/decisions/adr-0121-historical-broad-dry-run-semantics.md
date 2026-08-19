# ADR 0121: Historical Broad Dry-Run Semantics

## Status

Superseded

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-09`
- Primary capability: `AS-1.6` quotas, readiness, reload, and automation runtime operations
- Affected capabilities: `PO-1.1`, `PO-2.4`, `PO-4.1`
- Decision owner: FireMUD human product and architecture owner
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Superseded
- Review source: `SCRIPT-09`

## Context

`SCRIPT-09` recorded an earlier broad dry-run interpretation in which sandbox checks, identity, audit, quota, breaker, metric, and worker-capacity controls were separated from live work. The historical proposal did not establish the current command-plan response, exact input identity and fidelity limits, or durable live-worker protection contract.

## Decision

This historical proposal is retained for provenance only. It does not define current dry-run behavior; the current target is recorded in ADR 0114.

## Supersession

- Replacement ADR: [ADR 0114](./adr-0114-command-plan-preview-dry-run-isolation.md)
