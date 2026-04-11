# Design Area Slice Coverage

This document is the high-level view of how far FireMUD's major design areas have been translated into explicit slice work.

It is intentionally broader than [00-slice-progress.md](./00-slice-progress.md):

- `00-slice-progress.md` is the short ordered queue for active and near-active slice work.
- this file is the design-to-slice coverage map used to spot higher-level areas that are still mostly living in architecture docs rather than delivery-tracked slices.

Use this document when deciding:

- whether a major area is already represented well enough in slices;
- whether a new slice family should be created before more implementation work;
- which broad design domains should get a translation pass next.

## Recommended Review Order

If a fresh thread is reviewing slice coverage rather than implementing code immediately, use this order:

1. confirm the broadest under-sliced areas first, not the most convenient low-level slice;
2. start with deep runtime durability and replay (`02.18.7` through `02.18.11`) because that area was the clearest architecture-to-slice gap and is also the most SaaS-critical;
3. review scripting and automation architecture next because it appears design-heavy but still under-translated into slices;
4. review Redis/ops/recovery translation after that to separate runtime implementation work from pure operator documentation;
5. review frontend/product-surface slicing after the backend/runtime architecture-heavy gaps are under control.

Use [00-slice-progress.md](./00-slice-progress.md) for immediate implementation work, and use this file to decide whether a higher-level design area first needs more slice decomposition.

## Coverage Scale

- `Strong`: the area is well represented by coherent slices and usually already has some implementation follow-through.
- `Moderate`: the area has meaningful slice coverage, but important parts still live more in architecture docs than in tracked slice work.
- `Weak`: the area is still primarily architecture-doc-first and needs explicit slice translation before implementation can be directed safely.

## High-Level Coverage Summary

### 1. Login, Session, Reconnect, and First-Party Transport

- Coverage: `Strong`
- Slice families: `02`, `02.1.x`, `02.2.x`, `02.3` through `02.8`
- Current state:
  - core login/session lifecycle is heavily sliced;
  - reconnect, first-party parity, backend rebind, and restart invisibility all have explicit tracked slices;
  - much of this area is already partially or substantially implemented.
- Remaining gap:
  - mostly follow-through, operator-proof tails, and later presence/social expansion rather than missing structural slices.

### 2. Input, Output, Command, and Presentation Model

- Coverage: `Strong`
- Slice families: `02.13.x`
- Current state:
  - normalized command envelopes, output envelopes, prompt pipeline, presentation policy, localization foundation, command interpretation, classification, registry rollout, authored-action direction, transcript direction, and shared time semantics all have explicit slices;
  - this is one of the clearest architecture-to-slice translations in the repo.
- Remaining gap:
  - authored action execution is still future-facing;
  - some later transcript/time areas remain design-heavy rather than implementation-heavy.

### 3. Settings Model

- Coverage: `Strong`
- Slice families: `02.9` through `02.12`
- Current state:
  - platform settings architecture is well translated into slices;
  - both shared settings substrate and concrete settings surfaces are represented.
- Remaining gap:
  - mostly future expansion and presets/baselines rather than missing translation.

### 4. Communication, Chat, and Social

- Coverage: `Moderate to Strong`
- Slice families: `04.x`, plus `02.1.4` and related presence/social slices
- Current state:
  - say/whisper/tell/help/dialogue have coherent slice coverage;
  - account-scoped presence and friend activity are now explicitly tracked.
- Remaining gap:
  - broader social systems, richer policy sources, and some user-facing refinement still need more follow-through;
  - this area is reasonably sliced but not yet as mature as the core session/runtime platform.

### 5. Movement, Look, and World Interaction

- Coverage: `Moderate`
- Slice families: `03`, `05`, and related `02.13` / `02.1.3` follow-through
- Current state:
  - movement and look behavior are represented;
  - user-facing interaction surfaces are no longer unsliced.
- Remaining gap:
  - durable execution migration for movement and other direct gameplay mutation paths is only now explicitly represented through the new durability hardening train.

### 6. Inventory, Containers, Equipment, and Item Identity

- Coverage: `Strong`
- Slice families: `06`, `06.2`, `06.3`, `06.3.1`, `06.3.2`, `06.4`, `06.4.1`
- Current state:
  - item identity, container instances, visible refs, stackability/fungibility, and holder/transfer model are now represented as a coherent family;
  - this area improved substantially and is now one of the better examples of design being translated into explicit slices.
- Remaining gap:
  - later selector UX, safer transfer/handoff semantics, and broader item-model depth still remain.

### 7. Stats, Actors, Effects, and Combat Foundations

- Coverage: `Moderate`
- Slice families: `07`, `07.1`, `07.2`, `07.3`, `07.4`
- Current state:
  - the broad design is now sliced, and the intended order is clearer than before;
  - the major architecture questions are no longer floating only in chat.
- Remaining gap:
  - this family is still mostly future-facing;
  - the slice translation is decent, but implementation maturity is low.

### 8. Service Boundary, Auth, Runtime Hardening, and Observability

- Coverage: `Strong`
- Slice families: `02.14.x`, `02.15.x`, `02.17.x`, `02.18.x`
- Current state:
  - this is now a strong slice area;
  - the new `02.18.7` through `02.18.11` durability hardening train materially improves the translation of architecture-heavy runtime safety concerns into tracked work.
- Remaining gap:
  - many of the newer durability slices are still discussion-gated and unimplemented, but the tracking gap is much healthier than before.

### 9. Tick, Replay, Crash-Safety, and Recovery Architecture

- Coverage: `Moderate`
- Primary slice families: `02.18.7` through `02.18.11`, plus reconnect/failover slices and tick scheduler hardening
- Current state:
  - this was previously one of the biggest architecture-to-slice translation gaps;
  - the recent durability hardening slice pass has made it visible and trackable.
- Remaining gap:
  - implementation maturity is still far behind architecture ambition;
  - there is still likely more architecture in this area that should be decomposed into explicit slices over time.

### 10. Redis, Operations, Backup/Recovery, and Deployment Runbooks

- Coverage: `Moderate`
- Current state:
  - there is strong architecture/runbook documentation in `design/architecture` and `design/operations`;
  - some supporting slices exist through preview, reset/bootstrap, and service-boundary families.
- Remaining gap:
  - a fair amount of ops and Redis material is still runbook-first rather than slice-first;
  - this is one of the clearer remaining translation gaps.

### 11. Scripting and Automation

- Coverage: `Weak to Moderate`
- Current state:
  - architecture depth is high;
  - some related behavior is reflected in other slices, but the scripting architecture itself does not appear as fully decomposed into a dedicated slice family as the runtime/session/item areas.
- Remaining gap:
  - significant architecture-to-slice translation likely still needed.

### 12. Frontend and Dedicated First-Party Web

- Coverage: `Moderate`
- Current state:
  - some slice coverage exists, especially around first-party transport and web-service direction;
  - backend/runtime/platform work is better decomposed than frontend/product surface work.
- Remaining gap:
  - this area is not as systematically sliced as the core backend/runtime domains.

## Priority View: Where Slice Translation Is Strongest

These areas are already in good shape from a design-to-slice coverage perspective:

1. login/session/reconnect/transport
2. command/presentation/input-output model
3. settings model
4. inventory/container/item identity
5. service boundary and runtime hardening

These areas can mostly continue through existing slices without needing another broad translation pass first.

## Priority View: Where Slice Translation Still Looks Thin

These areas still appear under-sliced relative to the amount of existing design material:

1. tick/replay/crash-safety deeper runtime architecture
2. scripting and automation architecture
3. Redis/operations/recovery/runbook-heavy areas
4. some frontend/product surface areas
5. some backup/recovery/compliance-heavy areas if they are expected to become implementation work rather than remain operator guidance only

## Recommended Next Design-to-Slice Translation Passes

If the goal is to keep architecture-heavy design from drifting ahead of tracked work again, the recommended next broad translation passes are:

### Pass 1. Deep Runtime Durability and Replay

Current state:

- the first critical hardening train is now explicitly sliced as `02.18.7` through `02.18.11`;
- this area is no longer hidden, but it is still likely not fully decomposed.

Recommended next action:

- continue using those slices as the first grounding pass;
- then review the broader tick/replay docs again and check whether more specific child slices are needed after the first implementation discussions.

### Pass 2. Scripting / Automation Architecture

Current state:

- high design depth exists in architecture docs;
- slice representation looks weaker than for the core session/runtime platform.

Recommended next action:

- create or strengthen a dedicated scripting/automation slice family covering:
  - control plane;
  - scheduling/timers;
  - runtime execution;
  - quotas/operations;
  - designer-facing authoring/runtime boundaries.

### Pass 3. Redis / Ops / Recovery Translation

Current state:

- Redis and reset/recovery design is strong but still very runbook-first.

Recommended next action:

- identify which Redis/recovery areas are expected to become runtime implementation work and pull those into slices;
- leave purely operational guidance as docs/runbooks where that is the correct long-term home.

### Pass 4. Frontend / Product Surface Translation

Current state:

- some first-party/web direction exists, but the frontend is not sliced as thoroughly as the core backend platform.

Recommended next action:

- decide whether the frontend remains a bounded consumer of backend slices or whether it now needs a broader explicit slice family of its own.

## Suggested Working Rule

When reviewing a major design area, ask:

1. Is this area already represented by a coherent slice family?
2. If yes, are the open questions already tracked there?
3. If no, is the design expected to drive implementation soon?
4. If yes, create slices before more implementation continues.

This avoids repeating the recent pattern where important architecture was "known in docs" but not yet visible in `00-slice-progress.md` or the active vertical slice queue.

## Recommended Use In The Next Thread

The next review thread should use this document together with:

- [00-slice-progress.md](./00-slice-progress.md)
- [README.md](./README.md)

Suggested order:

1. review this high-level coverage document;
2. decide which under-sliced design area should get the next translation pass;
3. only then return to lower-level slice implementation work.
