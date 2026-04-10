# Current Runtime Follow-Ups

Verified against current branch on 2026-04-10.

## Current

- [x] Fix `GameInstanceServiceImpl.startSession(..., true)` replacement rollback so the prior running session snapshot is captured before the stop mutation and can be restored correctly if the new start path fails.
- [x] Re-test the replacement rollback path at unit level and integration level where practical.
- [x] Replace the `beforeCommit` lifecycle coupling in `GameInstanceServiceImpl` with staged DB lifecycle states plus post-commit runtime finalization/compensation so external Redis/dependency work no longer runs inside the open DB transaction.

## Notes

- The claim that `GameInstanceService.startSession(request)` still defaults to replacement is stale; the default path now delegates to `startSession(request, false)`.
- The old `beforeCommit` durability/coupling concern was current and is now resolved by staged lifecycle state plus explicit post-commit finalization/compensation.
- The replacement rollback bug was real and is now fixed locally with unit and integration proof.
