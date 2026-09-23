# Document task brief: finish #2839, then resume #2840

Status: active

Owner: Document

## Current state

- #2839 is the combined 5C/5D successor, based on final #2829.
- Published checkpoint: `bd9728099cae2e219245c75b5a23a7937c3c6b51`, 94 unique files.
- Cycles 9–12 were `17/0 → 12/1 → 26/2 → 8/0`. Cycle 12 is the first dry corrected-state cycle after productive Cycle 11, so taper is not established.
- Exact-head preview and dependency submission pass. Full exact-head Validation and Docker/Testcontainers runtime proof remain absent.
- The independent no-loss audit accounts for all #2661 source hunks with zero lost obligations; #2661 is closed as superseded.
- ADR 0183 remains `Proposed - Pending Human Review` and non-authoritative.
- #2840 is the Unit 1A child at `1dc0ddb5449047554ff19ff288a9c12e02857568`, 17 unique files. Cycles 1–4 were all productive: `16/1 → 24/1 → 18/1 → 33/1`.

## Assignment

Continue #2839 only from the exact published checkpoint with a fresh whole-unit Cycle 13.

- If Cycle 13 is dry, it combines with Cycle 12 to establish taper. Complete the best available exact-head validation, publish and ledger the final checkpoint, then propagate that exact parent normally into #2840 without changing #2840's unique patch.
- If Cycle 13 is productive, adjudicate, integrate, validate, publish, and continue serial whole-unit cycles through Cycle 16 as the next report boundary.
- Warn at 99 files and stop before publishing #2839 above 100 files. Do not silently split or alter topology.
- Keep Docker/Testcontainers proof limitations explicit; do not infer runtime proof from compilation or unrelated green checks.

After #2839 tapers and its exact checkpoint is propagated, resume #2840 at fresh whole-unit Cycle 5. Continue serial corrected-state cycles with the established two-consecutive-dry taper and four-cycle report boundary. Integrate and validate every useful cycle before the next.

## Boundaries

- Do not use CodeRabbit or Hosted capacity.
- Do not merge or enable auto-merge.
- Do not begin Unit 1B while Unit 1A remains unfinished.
- Do not touch Gameplay, General, or earlier train PRs except for the normal #2839-to-#2840 parent propagation described above.
- Do not implement proposal-dependent ADR 0183 behavior.

## Handoff

At each report boundary, provide exact head/base, unique file count, raw/useful cycle sequence, accepted-finding significance, validation with executed/skipped proof, clean/publication state, taper state, and the precise next resume point. A report boundary is not merge readiness.
