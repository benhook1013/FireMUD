# Non-active scratch purpose and preservation review

Reviewed 2026-09-05 from `/home/ben/src/FireMUD-project-direction` on branch `codex/project-direction`. The required path and branch guard passed. This is a read-only purpose review; no cleanup is authorized by this report.

## Scope and exclusions

The inventory source was `tmp/worktree-branch-inventory-2026-09-05.json`, supplemented by root-level metadata in `/home/ben/src/FireMUD-wsl-copy/tmp`. All known 5B and open-PR material is excluded from the cleanup pool. That includes the corpus-review-5A/5B/6D-7C bodies, manifests, patches, status snapshots, wait helper, `pr2669-followups.md` (recorded as 5B spillover), and the main-slice/whole-corpus coordination material. Retain these for their active or historical handoff/provenance roles until their owning workers complete reconciliation.

The strategic worktree's `tmp/project-direction-notes.md` and `tmp/worker-handoff-2026-09-05.txt` are continuity records for the current task. Preserve them while this direction/handoff work remains active; they contain volatile reports and pending decisions, not canonical design authority.

## Small non-5B ideas and handoff artifacts

| Artifact | Evidence and purpose | Classification | Preservation requirement |
| --- | --- | --- | --- |
| `/home/ben/src/FireMUD-wsl-copy/tmp/shipped-game-profiles-idea.md` (3,272 bytes; mtime 2026-08-03 UTC) | Explicitly labels itself a temporary discussion seed and says the idea is outstanding. It proposes authored shipped game profiles, a primary fantasy MUD, possible later profiles, asset reuse/copy semantics, and a handback of a small set of implementation-facing decisions. | Valuable future product/content idea; not canonical and not landed. | Preserve as a discussion seed until the separate product/play lane decides whether it becomes canonical starter-profile design, a content roadmap, or implementation tracking. Do not infer v1 scope from it. |
| `/home/ben/src/FireMUD-wsl-copy/tmp/ai-assisted-manual-testing-tracker.md` (3,273 bytes; mtime 2026-08-03 UTC) | Explicitly labels itself temporary working material and says the end-to-end path is not established. It records the intended first hosted player path (`LOGIN`/`PLAY`/`LOOK`), target identity/evidence fields, and reset/isolation questions. | Valuable future implementation/proof idea; not current proof or authorization to test/deploy. | Preserve for the future play lane. The canonical playtest and login/smoke guides already define the related evidence shape and supported paths, but this tracker records the unresolved operational setup and should not be treated as a completed test result. |
| `/home/ben/src/FireMUD-project-direction/tmp/project-direction-notes.md` (11,586 bytes; mtime 2026-09-05 UTC) | Current strategic index with explicit resume point, pending discussions, worktree preservation snapshot, and continuity method. | Active task continuity record. | Preserve while the strategic task is active; refresh volatile status rather than deleting or treating old snapshots as live state. |
| `/home/ben/src/FireMUD-project-direction/tmp/worker-handoff-2026-09-05.txt` (17,176 bytes; mtime 2026-09-05 UTC) | Dated worker handoff covering direction, boundaries, unresolved implementation/proof status, and pause recommendations. | Historical handoff source material; strategically relevant. | Preserve separately from the live index until the inherited worker state has been reconciled and its unique/unpublished work is accounted for. |

The two idea files are not contradicted by current canonical material. Current creator guidance says starter-profile materialization/upgrades are target-only and unimplemented, while typed Game Design APIs are the supported authoring boundary (`design/product/user-journeys/overview.md`, implementation-status section). Creator guidance also says a starter profile is copied into an editable Draft and later profile edits do not silently overwrite that Draft (`design/product/user-journeys/creators.md`). This supports preserving `shipped-game-profiles-idea.md` as future direction without claiming that shipped profiles already exist. The player playtest checklist and login/session smoke guide provide the canonical operational context for `ai-assisted-manual-testing-tracker.md`; neither proves the hosted AI-assisted loop is established.

## Root-level metadata classification

The original worktree's `tmp` contains 19 root-level files plus `__pycache__`. The 5B/open-PR and active coordination items are excluded as stated above. Among the remaining material, the two idea files are the only clear future-direction artifacts. `__pycache__/corpus-review-status.cpython-310.pyc` (8,755 bytes) and `__pycache__/wait-for-transition.cpython-310.pyc` (7,324 bytes) are obvious interpreter-generated outputs. They are reproducible from the adjacent scripts, but this review does not authorize removal; ownership and active process use should be checked in any later cleanup batch.

The patch files, split manifests, PR bodies, status JSON/script, and wait helper are provenance or coordination outputs associated with the excluded review/PR work. They may be reproducible in principle, but their reproducibility does not remove their handoff or evidence value, and no deletion decision is made here. File names and age alone are insufficient evidence for cleanup.

## Unknowns and deferred checks

This bounded review did not inspect retained-design-reconciliation, downstream-seed, 6D/7C branch diffs, large 5B review logs, or active review/CI/PR state. It did not establish whether any idea has since been adopted in code or a newer canonical tracker beyond the targeted documentation checks above. No tests, formatting, validation, connectivity, deployment, or cleanup commands were run.

### Evidence basis

- `AGENTS.md` and `design/developer-workflows/ai-delegation-and-review.md` were read before inspection.
- `tmp/worktree-branch-inventory-2026-09-05.json` supplied the selected scratch inventory and records that review/CI inspection and deletion were not performed.
- Root-level file sizes and modification times were inspected with `find`; no recursive cache/build scan was performed.
