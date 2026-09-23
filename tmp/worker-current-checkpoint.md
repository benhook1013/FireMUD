# Document worker resume card

Updated 2026-09-22 NZST. This is a subordinate resume card, not queue authority; [project-direction-notes.md](./project-direction-notes.md) controls order.

Parked checkpoint: combined 5C/5D draft #2839 is published at `3e294152b748dd21f41290b6cb5bddde8c557c51`, 92 files. Cycles 5–8 were all productive (`41/2`, `18/1`, `18/1`, `20/1`). Cycle 8's P1 retry-fence correction revalidates current Game Session ownership for local and remote retries before enqueue; terminal/materialized duplicate readback is unchanged. Consolidated local validation is green, including 45 focused retry-fence tests; Docker/Testcontainers-dependent proof remains unavailable. Exact-head `submit-gradle` CI is in progress without a watcher. Taper is not established. Final independent no-loss proof found zero lost obligations; #2661 is closed as superseded. ADR 0183 remains pending and non-authoritative.

Current corpus tail: Unit 1A draft #2840 is published at `1dc0ddb5449047554ff19ff288a9c12e02857568`, 17 files on #2839. Cycles 1–4 were all productive (`16/1`, `24/1`, `18/1`, `33/1`), so Unit 1A is not tapered.

Current boundary: #2839 is parked at its completed Cycle 8 report boundary. Do not start Cycle 9, propagate into #2840, resume Unit 1A, begin 1B, or use CodeRabbit/Hosted without a new Overseer instruction. Passing pending CI would strengthen proof but would not establish the missing two-cycle dry taper.

Context: 5A and 5B corpus discovery are closed by Overseer judgment; #2839 and #2840 remain corpus-review checkpoints rather than merge-ready claims. Do not claim this card owns the General programme, merge decisions, or other PR review capacity.
