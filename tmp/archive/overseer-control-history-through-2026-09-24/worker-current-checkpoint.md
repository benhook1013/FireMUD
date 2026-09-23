# Document worker resume card

Updated 2026-09-22 NZST. This is a subordinate resume card, not queue authority; [project-direction-notes.md](./project-direction-notes.md) controls order.

## Active task briefs

- Gameplay #2838 completion: `/home/ben/src/FireMUD-project-direction/tmp/task-briefs/gameplay-continuous-review-train.md`
- General review-control refactor: `/home/ben/src/FireMUD-project-direction/tmp/task-briefs/general-pr-review-control.md`
- Document #2839 → #2840 corpus work: `/home/ben/src/FireMUD-project-direction/tmp/task-briefs/document-corpus-2839-2840.md`

Each brief is authoritative for its worker. Later substantial corrections are appended there, or the brief is explicitly realigned under the Overseer policy in `AGENTS.md`.

Settled parent: combined 5C/5D draft #2839 is final at `a38861d006521b1e604b525a01d21ba0ce2de8f3`, 94 files. Cycles 14–15 were consecutive corrected-state dry cycles, so corpus taper is established. Final exact-head Validation executed database cases but remained red on four inherited Automation failures and one inherited Game Session assertion; #2839 changes none of those failing paths. #2661 is closed as superseded, and ADR 0183 remains pending and non-authoritative.

Current corpus tail: Unit 1A draft #2840 is published at `985391edca00410ddab2a15be0b7d7b011c1fb73`, 46 files unique to final #2839. Cycles 1–8 were all productive (`16/1`, `24/1`, `18/1`, `33/1`, `13/1`, `17/1`, `47/3`, `28/3`), so Unit 1A has no dry streak and is not tapered.

Resume boundary: start fresh whole-Unit-1A Cycle 9 on #2840. Require two consecutive corrected-state dry cycles; integrate and validate every useful correction before the next review. Cycles 9–12 are the next report boundary. Stop earlier on taper, otherwise report after Cycle 12. Do not begin 1B or use CodeRabbit/Hosted.

Context: 5A and 5B corpus discovery are closed by Overseer judgment; #2839 and #2840 remain corpus-review checkpoints rather than merge-ready claims. Do not claim this card owns the General programme, merge decisions, or other PR review capacity.
