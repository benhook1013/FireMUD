# Current Runtime Follow-Ups

Verified against current branch on 2026-04-10.

## Current

- [x] Fix `GameInstanceServiceImpl.startSession(..., true)` replacement rollback so the prior running session snapshot is captured before the stop mutation and can be restored correctly if the new start path fails.
- [x] Re-test the replacement rollback path at unit level and integration level where practical.
- [ ] Re-assess the `beforeCommit` coupling issue in `GameInstanceServiceImpl` and decide whether there is a safe, already-discussed implementation step now, or whether it should stay tracked as a design/slice follow-up.

## Notes

- The claim that `GameInstanceService.startSession(request)` still defaults to replacement is stale; the default path now delegates to `startSession(request, false)`.
- The `beforeCommit` durability/coupling concern is still current.
- The replacement rollback bug was real and is now fixed locally with unit and integration proof.
