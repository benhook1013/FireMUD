# Scripting, Automation, and Runtime Orchestration Vertical Slice

## Goal and Status

Goal: give the scripting and automation domain one coherent slice family covering runtime ingress, scheduling, execution budgets, tick handoff, and operator visibility so future work stops being scattered across gameplay hardening, publish-control-plane, and service-local docs. Status: complete as the parent slice-family framing; child slices own ongoing implementation follow-through.

## Why This Slice Exists

FireMUD already has substantial scripting architecture and real implementation substrate, but planning coverage is still fragmented. The domain is too large and too central to keep treating timers, quotas, runtime execution, and rollout visibility as indirect side effects of other slice families.

## Implementation Notes

The parent family is no longer discussion-gated. The scripting/runtime direction is now locked and the family is actively implemented through child slices:

- `10.1` event ingress and handler resolution
- `10.2` scheduler/timer ownership
- `10.3` execution budgets, quotas, and isolation
- `10.4` automation handoff and tick integration
- `10.5` operator visibility and runtime convergence

That means this parent doc is up to date as a taxonomy and direction lock, but it is not a statement that the scripting domain is fully finished or ready for design-only verification without looking at the child slices and, in several cases, the code.

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

- continue implementation and closure work in the child slices rather than reopening the parent family definition.
- keep `08.4` as the publication-boundary companion rather than duplicating it here.
- add future scripting/runtime work under the `10.x` family instead of burying it under generic hardening or gameplay slices.
- do not treat the parent `10` doc as a frozen review artifact for service behavior; use it to navigate the child slices, with `10.5` currently the closest thing to a broad read-model/operator deep-dive candidate.

## Checklist

- [x] Define target-state behavior and scope.
- [x] Establish the parent slice-family framing and child-slice ownership.
- [x] Verify and close the parent-family definition follow-up.
