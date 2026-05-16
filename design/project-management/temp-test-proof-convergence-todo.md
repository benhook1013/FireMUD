# Temporary Test Proof Convergence Todo

Temporary capture of the follow-up work from the gameplay/smoke test audit that still looks valid in the current tree as of 2026-05-15.

## Agreed Items

- [x] High: Move the local websocket, local telnet, and hosted telnet smoke transport executor loops into `dev-tools/smoke/smoke_common.py` so they share timeout, drain, and incremental-response semantics as well as command plans.
- [x] High: Add a smaller shared login-play-look smoke step helper in `smoke_common.py` so hosted proof stops carrying its own four-step inline plan next to the local smokes.
- [x] High: Add gameplay scenario helpers for the login-then-`PLAY` accept-or-deny seam on both transports. The current shared websocket/telnet scenario layers still stop at admitted/ready/reconnect-ready, so replay-admission and stale-session denial tests still have to drop below the harness.
- [x] Medium: Add named `GameplayCrossServiceStack` helpers for common off-screen live-session seeding patterns, starting with the shared “Sora target is online elsewhere” shape that is currently rebuilt in both websocket and telnet communication suites.
- [x] Medium: Collapse the duplicated game-session override wiring used by the large gateway-backed telnet cross-service suite onto a shared fixture bundle when the overlap is real. `CrossServiceAppHarness` already carries most of the common Game Session test overrides, but the telnet suite still layers a local override class with overlapping beans and gateway-specific extras.
- [x] Low: Replace the remaining one-off polling loops with shared eventual-assertion helpers where the behavior under test is not intentionally transport-level. The remaining gateway bridge connection-count wait and telnet disconnect-metric wait are now expressed through shared eventual assertions instead of local busy-wait helpers.

## Explicitly Not Included

- [x] Do not abstract the raw telnet line-echo bridge tests onto gameplay-ready helpers. Those are still valid low-level transport proof.
- [x] Do not force the gateway bridge restart tests onto the gameplay driver layer. Those also still look like intentional low-level exceptions.
- [x] Do not open a new formal slice doc yet in this temp note. The audit is probably right that the remaining work is bounded enough for a dedicated convergence slice, but that is a planning decision rather than an agreed implementation task by itself.

## Notes

- The strongest agreement points are still the smoke executor split and the missing login-then-`PLAY` scenario helpers. Both are clear shared-pattern gaps rather than one-off cleanup.
- The off-screen live-session seeding duplication is real, but it should stay narrow and named. A generic over-abstracted seeding DSL would be worse than the current duplication.
