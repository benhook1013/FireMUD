# Scripting, Automation, and Runtime Orchestration Vertical Slice

## Goal and Status

Goal: give the scripting and automation domain one coherent slice family covering runtime ingress, scheduling, execution budgets, tick handoff, and operator visibility so future work stops being scattered across gameplay hardening, publish-control-plane, and service-local docs. Status: planned.

## Why This Slice Exists

FireMUD already has substantial scripting architecture and real implementation substrate, but planning coverage is still fragmented. The domain is too large and too central to keep treating timers, quotas, runtime execution, and rollout visibility as indirect side effects of other slice families.

## Scope

- runtime script/event ingress and handler-resolution contracts
- scheduler/timer ownership and catch-up rules
- execution budgets, quotas, fairness, and isolation
- automation outbox/queue/tick handoff into Game Session
- operator observability and control-plane/runtime convergence visibility

## Out of Scope

- design-time publish/version/asset attestation work already covered by `08`
- script patch and plugin publication-versus-activation boundaries already covered by `08.4`
- general gameplay command/durability slices outside scripting-owned execution paths

## Locked Direction

- scripting needs its own slice family; it should not stay an indirect collection of adjacent runtime slices.
- design-time publication remains separate from runtime readiness and execution behavior.
- runtime scripting work must land on the canonical control-plane, scheduler, and handoff seams rather than inventing service-local shortcuts.
- operator visibility for scripting must reflect both design-time and runtime truth without collapsing them into one status model.

## Current Remaining Work

- promote the main runtime/control-plane/execution subdomains into bounded child slices.
- keep `08.4` as the publication-boundary companion rather than duplicating it here.
- use this family for future scripting implementation instead of burying work under generic hardening or gameplay slices.

## Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end to end.
- [ ] Verify and close follow-ups.
