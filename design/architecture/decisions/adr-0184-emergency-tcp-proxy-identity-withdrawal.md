# ADR 0184: Emergency TCP Proxy Identity Withdrawal

## Status

Accepted

## Implementation Status

The emergency-withdrawal rule is target state. ADR 0169's exclusive bridge listener and trust profiles are partially implemented, but a live withdrawal that proves active trust invalidation, termination of established bridges, and rejection of the withdrawn identity has not been demonstrated. Do not treat rendered configuration or a successful replacement rollout as that proof.

## Decision Record

- Human review status: Completed
- Human review date: 2026-09-24
- Human review disposition: Accepted
- Review source: `EDGE-WITHDRAWAL-01`
- Decision date: 2026-09-24
- Decision key: `EDGE-WITHDRAWAL-01`
- Primary capability: `SF-1.3` transport and workload trust
- Affected capabilities: `PO-2.1`, `PO-2.2`, `PO-3.2`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: explicit human approval of the emergency cutoff and its possible player-facing outage on 2026-09-24

## Context

[ADR 0169](./adr-0169-exclusive-environment-bound-tcp-proxy-trust.md) requires an exclusive environment-bound identity for TCP Proxy's authenticated Gateway bridge. A compromised or otherwise urgently withdrawn identity can remain usable on established bridges or old Gateway pods after admission configuration changes. Waiting for replacement credentials or a successful rollout preserves availability but can leave the withdrawn identity trusted.

## Decision

For emergency withdrawal of an otherwise unexpired TCP Proxy bridge identity, the trusted environment operator first invalidates that identity in Gateway's active trust profile. It then terminates old Gateway pods and their established bridges that could still honor the identity, even if replacement credentials or pods are not yet available. This may interrupt player-facing Telnet service; security cutoff takes precedence over continuity during this emergency.

Replacement admission is permitted only after the new allowed identity and trust are established. A rendered profile, planned rollout, or readiness claim alone cannot prove withdrawal. If active-trust invalidation or termination cannot be verified, the operation remains incomplete and cannot be reported as a successful revocation. Ordinary planned rotation continues under its separate convergence procedure; this decision does not turn every rotation into an emergency outage.

## Consequences

- Emergency withdrawal can cause a temporary outage rather than retaining a suspect bridge for availability.
- The operator needs observed active-trust and established-bridge evidence, not only configuration and rollout evidence.
- Recovery must restore service under a newly allowed identity without re-admitting the withdrawn one.

## Alternatives Considered

### Wait for replacements before terminating old bridges

Rejected for emergency withdrawal because the suspect identity could continue to authorize established traffic while replacement work is pending.

### Trust a rendered rollout or new-pod readiness result

Rejected because old pods and established bridges may still accept the withdrawn identity.

## Reversibility and Revisit Triggers

Revisit only if an independently proved mechanism can revoke the identity and terminate all affected established bridges without disrupting otherwise healthy Gateway pods. A future mechanism must preserve the same fail-closed cutoff and observed withdrawal evidence.

## Security, Operations, and Proof

The trusted operator must record non-secret evidence of the withdrawn identity, active-trust revision, affected pod/bridge termination, and rejection of a connection using that identity. It must report an incomplete or uncertain result when any step cannot be proved. Private keys and credentials must not enter logs or proof artifacts. Focused and hosted proof must cover withdrawal while replacements are absent, old established bridges, stale pods, and subsequent admission with only the new identity. The [Gateway architecture](../system-architecture-gateway.md) owns the listener behavior; the [TCP Proxy configuration](../microservices/tcp-proxy-service/configuration.md) and [Telnet degraded runbook](../system-architecture-telnet-degraded-runbook.md) retain their local consequences.
