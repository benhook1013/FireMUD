# ADR 0105: No Degraded Script Admission Without Authoritative Pin

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-08`
- Primary capability: `AR-3.3` live rollout, rollback, and runtime replacement
- Affected capabilities: `AS-1.6`, `SF-1.3`, `PO-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of stale-pin admission, operator overrides, automation availability, rollback safety, and capability-based exceptions

## Context

Automation admission must know the exact Game Session-owned script pin for an instance. Allowing an operator to bypass unavailable authority using a stale observation can admit work for a patch or pin epoch that has already been displaced by rollback or repin. A bounded TTL and durable audit can limit and explain that unsafe window, but neither proves that the admitted version was authoritative.

FireMUD can degrade more safely by stopping new scripted work while allowing ordinary gameplay that does not depend on Automation & Scripting to continue.

## Decision

New Automation & Scripting admission requires a bounded-fresh authoritative projection of Game Session's exact `(tenantId, gameInstanceId, scriptPatchVersion, scriptPinEpoch)`. If the projection is absent or stale, Automation may attempt a bounded refresh from Game Session. If it cannot obtain authoritative state, admission fails closed with `pin_state_unavailable`. A mismatch fails with the version/pin-fence outcome; Automation never substitutes a last-known patch or epoch.

There is no operator stale-pin override. Actor identity, reason, audit, scope, and TTL remain necessary controls for authorized operations, but they do not make stale state correct. Operators recover by restoring authoritative Game Session reads or projection delivery, repairing the affected control-plane path, or explicitly repinning after authority is available. They do not edit the projection or authorize speculative admission.

This failure closes new script and plugin-trigger admission for the affected scope, not ordinary gameplay by default. Game Session may continue player commands and other non-script runtime work when its normal gameplay dependencies and exact execution fences remain healthy. Already admitted Automation work retains its captured version and epoch and must still pass the normal persistence, handoff, and Game Session execution fences.

A future exception requires a new architecture decision and measured evidence that fail-closed automation causes unacceptable player harm. The only acceptable direction is a short-lived capability issued by Game Session while it has authoritative state and bound to the exact `(tenantId, gameInstanceId, scriptPatchVersion, scriptPinEpoch)`. That decision must define issuance, audience, expiry, replay resistance, revocation and rollback behavior, and downstream verification. A generic operator flag, stale-cache grace period, or audit-only bypass is not such a capability.

## Consequences

- A pin-observation outage cannot cause Automation to mint work for a displaced patch or epoch.
- Scripted NPC behavior, timers, and automation may pause or reject new triggers while ordinary gameplay continues.
- Recovery focuses on restoring authority instead of reasoning about work admitted during an unsafe override window.
- Operators cannot trade correctness for automation availability with an emergency toggle.
- Any future availability exception carries explicit capability issuance and verification cost rather than relying on TTL and audit alone.

## Alternatives Considered

### Audited, Scoped, TTL-Bounded Operator Override

Rejected because it still cannot identify the authoritative patch and epoch during the outage. In an emergency rollback, a stale node could admit the exact version being removed; later Game Session fencing would reject that work at best and an incomplete fence could apply it at worst.

### Extend the Freshness Window or Use Last-Known Pin State

Rejected because elapsed time is not evidence that no rollback or repin occurred. A short stale window can still cross the consequential transition.

### Stop All Gameplay When Automation Pin State Is Unavailable

Rejected as the default because script admission authority can fail independently of healthy Game Session player-command execution. Games may expose a feature-specific degraded state, but the scripting control plane does not impose a platform-wide gameplay stop.

### Issue an Exact Short-Lived Game Session Capability Now

Deferred. It could preserve bounded automation through a projection outage without guessing the pin, but adds issuance, keying, expiry, replay, revocation, and verifier behavior before measured need justifies it.

## Implementation and Proof Obligations

Projection freshness must be explicit and bounded. Admission, scheduler, plugin, retry, and replay entry points must apply the same exact version-and-epoch rule and deterministic failure vocabulary. Metrics and audit distinguish missing, stale, refresh-failed, version-mismatched, and epoch-mismatched state without recording high-cardinality identifiers as ordinary metric labels.

Proof must cover missing and stale projections; bounded refresh success and failure; rollback or repin during a projection outage; repin to the same patch with a new epoch; reordered projection delivery; script and plugin triggers; scheduler firings; already-admitted work reaching persistence, handoff, and execution; recovery after authority returns; absence of an override path; and ordinary non-script gameplay continuing during scoped Automation failure.

The current Automation ingress already fails closed with `pin_state_unavailable` when its pin projection is missing or stale, and no degraded operator override is implemented. That is the correct baseline. Exact `scriptPinEpoch` propagation and proof across every admission, scheduling, replay, handoff, and execution boundary remain incomplete under ADR 0100, so this decision does not claim the full contract is implemented or proved.

## Reversibility and Revisit Triggers

Freshness bounds, refresh mechanics, and degraded player messaging may evolve without permitting stale admission. Revisit only when measured incidents show material player harm from fail-closed automation and Game Session can issue and downstream services can verify a short-lived exact-pin capability. Any such exception requires a new decision; TTL and audit on an operator override remain insufficient.
