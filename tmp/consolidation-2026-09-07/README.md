# Temporary-file consolidation inventory

These archives are historical and non-active. The pre-Overseer corpus plans, review ledger, handover notes, patch artifacts, and helper bytecode are at `/home/ben/src/FireMUD-project-direction/tmp/archive/pre-overseer-corpus/`; the generated corpus-review process note is at `/home/ben/src/FireMUD-project-direction/tmp/archive/overseer-process/`. Nothing in these archives is an active execution queue, review command, or current assignment.

The source worktree was `/home/ben/src/FireMUD-wsl-copy` on `develop`, which had pre-existing conflicts. The destination is `/home/ben/src/FireMUD-project-direction` on `codex/project-direction`. Source files were moved only when their inventory size and mtime still matched; SHA-256 was captured immediately before the move and verified after arrival. Active Worker and Gameplay lane artifacts, project-direction notes, and system validation logs remain in place.

Potential follow-up candidates found in the archived notes:

- The manual-testing tracker still proposes choosing an active preview or dev-demo target and performing one read-only connectivity/gameplay pass; it does not establish a recurring test program.
- The shipped-game-profiles file is an outstanding product/content ideation seed and has no canonical design or delivery assignment.
- The old 5B follow-up ledger contains residual scope/authentication, migration-proof, and routing candidates that require fresh develop-head adjudication; it is not an active queue.
- The whole-corpus plan and handover describe historical review sequencing. They record a paused or completed review phase and do not authorize restarting it.
- The old handover marks plan-root/ordinal material as stale and records no pending human decision blocking the current wrap-up.

Current direction is recorded in [../project-direction-notes.md](../project-direction-notes.md). The archived plans and ledger remain historical; current authority is the PR-body scope plus the owning corpus documents, with no parallel 5B ledger.

See `inventory.json` for the complete pre-move candidate inventory and `moves.json` for the final source/destination paths and SHA-256 hashes. All 21 final destinations were rechecked after relocation; no duplicate archive copies were created.
