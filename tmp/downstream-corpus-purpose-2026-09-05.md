# Parked downstream corpus purpose and preservation evidence

Date: 2026-09-05 (Pacific/Auckland)

This is a bounded provenance and preservation report for the two explicitly
scoped parked worktrees. It is not a corpus review or a claim that every
branch hunk is still needed.

## Scope and authority

- Pinned report worktree: `/home/ben/src/FireMUD-project-direction`, branch
  `codex/project-direction` (verified immediately before writing).
- Inspected worktrees only: `/home/ben/src/FireMUD-corpus-review-downstream-seed`
  (`codex/corpus-review-downstream-seed`) and
  `/home/ben/src/FireMUD-corpus-review-6d-7c`
  (`codex/corpus-review-6d-7c`).
- The pinned `design/project-management/design-alignment/README.md` says that
  architecture documents own target-state contracts, implementation trackers
  own implementation/proof status, and the workstream remains non-normative
  (lines 3, 23, 54, 83-94). Those rules govern the handoff classifications
  below.
- Neither parked worktree has a `tmp` directory or branch-local plan,
  manifest, handover, or status file. Purpose is therefore established from
  branch ancestry, commit subjects, and the changed contract/tool evidence.

## Provenance and chronology

Both branches have the exact same merge base with current `develop`:

```
2bdaaf7a10c2d349050cbf91788608b8c0abb173
```

That is the old pre-current-develop base (29 August). A shallow or negative
ancestry comparison against current `develop` is not evidence that a semantic
correction was discarded.

`codex/corpus-review-6d-7c` ends at
`c2af10ee75d395172e8b10201f5ba8c3cd5cbf66`, after a concentrated 29-August
sequence of corpus correction commits. The sequence starts with
`2ea3adf14` (effect roots and smoke isolation), passes through the replay,
identity, scripting, scheduler, gameplay, and cross-domain authority fixes,
and ends with `c2af10ee7` (recovery and incident proof authority). The branch
is therefore a 6D/7C recovery/proof correction seed on the old shared-runtime
stack, not an independent downstream design line.

The downstream seed instead starts from the common base `2bdaaf7a10c2d349050cbf91788608b8c0abb173` and has four commits of its own:

```
a111d0f6c docs: reconcile shared runtime corpus authority
af4e14548 docs: fix shared runtime review findings
5d3980682 docs: preserve downstream corpus review corrections
2c54fae88 docs: fix downstream review findings
```

The final commit is small and concrete: `2c54fae88` changes three documents
(Account API contracts, Gateway operations, and player-experience
commands/communication), 8 insertions and 8 deletions. This supports the
earlier split intent that the seed retained first-pass gameplay, authoring,
automation, access, and edge corrections after the oversized corpus split.

Against today’s `develop`, the parked snapshots are large because their base
predates subsequent integration, not because all of these files are unique
cleanup work:

| Branch | Files | Insertions | Deletions | Interpretation |
| --- | ---: | ---: | ---: | --- |
| `codex/corpus-review-6d-7c` | 116 | 1,974 | 967 | Recovery/proof seed plus old-stack shared-runtime divergence |
| `codex/corpus-review-downstream-seed` | 104 | 1,203 | 611 | 6D/7C seed plus four downstream/shared-runtime corrections |

The latest 6D/7C commit alone is 19 files, 886 insertions, and 378
deletions (`git show --stat c2af10ee7`). It includes restore tooling and two
contract tests as well as architecture/runbook changes, so it must not be
treated as documentation-only cleanup.

## What to preserve and who should own it

### 6D/7C recovery and incident material

Preserve the branch as evidence for a future platform-operations/recovery
implementation handoff. The most clearly identifiable corrections are in
`c2af10ee7`:

- Staging recovery makes the immutable recovery record’s
  `sourceEnvironmentBinding` authoritative and fails closed on missing,
  unknown, or contradictory provenance. It distinguishes production-origin
  sanitization evidence from same-environment or proven non-production
  restores (`design/operations/deployments/staging/recovery/README.md:25-30,50,56-66`).
- Redis incident handling marks reset/replay/Automation cleanup commands as
  target-state only; current operators preserve evidence and keep the affected
  scope closed when the controller is unavailable
  (`design/architecture/system-architecture-redis-incident-runbook.md:16-18,44,141-153`).
- Surviving session keys are only input to normal reconnect validation and
  never authorize reconnect by themselves; missing or unavailable authority
  fails closed (`system-architecture-redis-incident-runbook.md:74-75`).
- Post-restore hardening repeats that the complete controller and proof path is
  target-state architecture, not evidence that the workflow is live, and
  keeps missing or unsafe evidence quarantined
  (`system-architecture-post-restore-hardening.md:9,13,73,151-157,190`).

The same commit changes `.github/workflows/manual-backup-restore.yml`,
`dev-tools/restores/restore-cluster.sh`,
`dev-tools/restores/validate-external-credentials.sh`, adds two restore
contract tests, and changes `k8s/velero/README.md`. These are implementation
and proof artifacts for the recovery owner, not candidates for a design-only
cleanup pass. No test was run here.

### Shared runtime, replay, scripting, and downstream corrections

Preserve targeted evidence and hand it to the current domain owners; do not
port the old branch wholesale. The representative corrections are:

- `2ea3adf14` records deterministic command-plan/root allocation, stable
  `planOrdinal`, zero-effect status behavior, and fresh roots only for
  post-abandon re-drive. The branch’s exact contract evidence is visible at
  `system-architecture-tick-execution-flows.md:47,54-55,76,301` and
  `system-architecture-transactions.md:9,35,88-94`. The transaction document
  explicitly records that the live handoff remains an implementation gap
  (`:9,91`), so this belongs with Game Session/Transactions and the active
  gameplay/automation owners.
- `834a2fa1f` reconciles scheduler Redis scope and Automation ownership. Treat
  it as an Automation/scheduler handoff, especially where it overlaps active
  5B runtime work.
- `f7614b13f` adjusts scripting namespace identity and runtime proof across 17
  documents. Treat it as a scripting/shared-runtime owner handoff rather than
  an independent cleanup candidate.
- `2c54fae88` is the residual three-document downstream correction described
  above. Its likely future owners are Account, Gateway, and player-experience
  documentation; retain it until those owners establish exact current
  equivalence.

The 6D/7C effect-root, replay, Redis, and scripting material overlaps the
active 5B/5C/5D and automation implementation lanes. Classify that overlap as
preserve/hand off; do not audit or delete the active stack from this report.

## Exact identity versus inferred equivalence

The parked commits are not present as exact patches in current `develop`
(`git log --cherry --right-only` reports no equivalent patch for the branch
commit sequence). That is the only identity conclusion made here. It does not
prove that later current-develop work lacks the same semantics, because both
branches start from the old 2bdaaf7 base and current develop has moved on.
Semantic equivalence remains unresolved until the owning implementation
trackers and current canonical documents deliberately reconcile each targeted
correction.

The alignment README’s explicit next phase is a whole-corpus authority pass
that preserves useful local consequences and removes only competing normative
authority (lines 81-85, 108-111). That supports retaining these branches as
preservation evidence while active owners finish convergence.

## Disposition

- Keep both parked worktrees and this report for the current handoff. Do not
  delete or rewrite them as part of corpus cleanup.
- Route 6D/7C recovery/runbook/tooling material to the platform-operations and
  recovery implementation owner; route effect-root/replay to
  Game Session/Transactions/gameplay; route scheduler and namespace material
  to Automation/Scripting; route the final three downstream docs to their
  service owners.
- After each owner confirms that a targeted correction is carried or
  intentionally superseded in current `develop`, a later maintenance decision
  may rebase or retire duplicate branch material. This report does not make
  that decision and does not establish deletion authority.

## Work performed

- Changed files: this new ignored report only:
  `tmp/downstream-corpus-purpose-2026-09-05.md` in the pinned project-direction
  worktree. No repository design, implementation, tracker, or parked-worktree
  files were edited.
- Checks: deferred. No tests, formatting, validation, CI, review, or PR
  operations were run or inspected.
- Unresolved concerns: semantic equivalence to later current-develop work is
  not established; the old shared-runtime base makes whole-branch diff sizes
  misleading; the active 5B owner must adjudicate overlapping runtime
  contracts before any eventual cleanup disposition.

## Main-task correction and integration qualification

Fresh explicit exit-code checks corrected the earlier ancestry conclusion: 6D/7C is NOT an ancestor of downstream seed (`git merge-base --is-ancestor` returns 1). The common base is `2bdaaf7a10c2d349050cbf91788608b8c0abb173`; 6D/7C has 29 branch-side commits, while downstream seed has four on its own line. The earlier main-task check incorrectly inferred containment from a one-sided log and did not separately inspect the ancestry command exit code. Both branches contain separately preserved history and neither may be deleted on a containment claim. Their current local and remote refs preserve the committed source; a clean inactive checkout could still be removed independently of keeping its branch after ignored-file and use checks. All develop comparisons use the pinned local snapshot, not newer origin/develop. Negative patch identity does not establish semantic absence.

## Subsequent authorized disposition

The main task subsequently removed the inactive 6D/7C and downstream-seed checkouts after fresh preservation/use checks. Both local and remote branch tips remain intact. See `project-direction-notes.md` for the executed outcome; checkout paths in this dated report are historical.
