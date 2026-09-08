# Retained Design Reconciliation Purpose and Preservation Report

Audit date: 2026-09-05 (Pacific/Auckland)

Pinned audit cwd: `/home/ben/src/FireMUD-project-direction`

Pinned audit branch: `codex/project-direction`

The pre-write pin assertion passed immediately before this artifact was created. This bounded continuation covers only `/home/ben/src/FireMUD-retained-design-reconciliation` and the remote-only `origin/design/adversarial-decision-review`. Ben's direction excludes 5B and open-PR material from cleanup consideration. This is a preservation assessment, not an architecture decision, code review, PR review, CI review, or broad corpus correctness audit.

## Finding

Retain the `codex/retained-design-reconciliation` worktree as **keep-dirty-or-unique**. It contains a coherent six-file uncommitted design patch and two exact local commits whose command-plan root/ordinal and recovery/smoke material is not present as the same Git history on the pinned `develop` line. The dirty patch proposes importing the archived region-scoped Weather disposition into the owning World contracts, but pinned `develop` still records Weather scope as unresolved. That patch therefore needs human reconciliation before it can be treated as canonical or discarded.

Retain `origin/design/adversarial-decision-review` as **keep-strategic/source-evidence**. Its exact live head is `08794d72fdf68c47d79a817953ee831e5b12e89c` (`Clarify plugin generation fence`, 2026-08-18). It is remote-only in this audit: no local branch or registered worktree was found. The archive contains the completed 183/183 review queue and human-owned dispositions, including the archived `MS-GR-AMBIENT-STATE-AUTHORITY` region-Weather disposition and `TICK-16` command-plus-plan-ordinal disposition. Archive evidence does not by itself make those changes canonical on `develop`.

## Retained worktree evidence

- Path: `/home/ben/src/FireMUD-retained-design-reconciliation`
- Branch: `codex/retained-design-reconciliation`
- HEAD: `2e4ad90396c19e3d51b3cb899f4bc2e504ba9c9d`
- Latest commits: `3be751a91d9af28cd41be03142a3b9027cb4ab68` (`docs: reconcile effect roots and smoke isolation`) followed by `2e4ad90396c19e3d51b3cb899f4bc2e504ba9c9d` (`docs: clarify effect root recovery authority`), both dated 2026-08-29.
- Tracked state: six unstaged modified files, no staged changes, no conflict markers reported by porcelain status, and no untracked paths.
- Dirty patch size: 6 files, 43 insertions and 33 deletions.
- Committed branch-only range from shared base `7c5098c979c789bda6910ad186474c0b661664d3`: 19 files, 148 insertions and 57 deletions. The two commits are both `+` in `git cherry -v refs/heads/develop HEAD`.
- Local `develop` is `1ea2c93404ac813bcfc2a437c6a607e515821456`; its tree is `999e80989e0a176527c410285c4bec6871c095a5`, versus retained HEAD tree `98a557253986b3ec0c9ad094e5b1a9b346fe25e4`. Neither branch is an ancestor of the other. The shallow/diverged history makes negative ancestry inconclusive; the exact commit and tree identities are positive preservation evidence.

The six dirty files form one coherent World Weather reconciliation patch:

| File | Preserved purpose of the dirty change |
| --- | --- |
| `design/architecture/decisions/adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md` | Defines one World-owned `region_instance.weather` aggregate per runtime region, authoritative room membership, fail-closed mismatch handling, the future region-targeted operation, and one-time activation seeding while retaining implementation/proof gaps. |
| `design/architecture/microservices/world-management-service/api-contracts.md` | Separates room-scoped ambient patches from the future region-targeted Weather operation and records that the RPC/handler, guard, and proof are not yet implemented. |
| `design/architecture/microservices/world-management-service/runtime-and-data.md` | Replaces the unresolved Weather selector language with the proposed region target, makes `world_event` scheduling/provenance rather than a second authority, and records the direct event-service mutation as drift. |
| `design/architecture/microservices/world-management-service/world-creation-workflow.md` | Replaces deferred initial Weather event scheduling with one-time authored `Region.weather` seeding, including the absent-value/no-event case, while marking implementation and proof incomplete. |
| `design/architecture/system-architecture-spatial-and-ambient-effects-catalog.md` | Defines `ApplyRegionAmbientStatePatch` inputs, region fencing, digest/attestation binding, replay behavior, and the remaining direct-write gap. |
| `design/project-management/implementation-tracking/world-runtime-and-movement.md` | Updates `GR-2.3` and the active gap list to track region-scoped Weather, activation seeding, fenced effect admission, and focused proof obligations. |

The committed content has two related preservation purposes:

- `3be751a91` updates 19 architecture, workflow, and implementation-tracker files around deterministic effect roots, plan ordinals, identity binding, replay/recovery, and smoke/reset isolation.
- `2e4ad9039` refines seven of those files around Transaction Strategies ownership, post-abandon re-drive identity, recovery consequences, and the smoke client endpoint.

The pinned `develop` line already contains later smoke-boundary work, including the run-owned Compose contract and exact run/capability checks, through separate commits (`3d69e2b2e`, `3c60ce6a6`, `b10d2ace4`, `c8f2ed746`, `ea802d050`, and `8f1c33542`). That is semantic overlap, not exact patch equivalence: both retained commits remain Git-unique, and the retained branch's plan-root material is absent from the current `develop` search for `planOrdinal`/`plan_ordinal`. Preserve the commits until their unique content is deliberately reconciled.

## Human-input and integration status

| Topic | Evidence and status | Preservation consequence |
| --- | --- | --- |
| Weather aggregate selector | The archived source ledger records human review accepting region-scoped Weather with derived room views. The pinned `develop` World ADR, API/runtime contracts, creation workflow, effects catalog, and `GR-2.3` tracker still explicitly say region-versus-room is unresolved and current direct `WorldEventServiceImpl` mutation is unsafe drift. The six-file dirty patch applies the archived choice locally but is uncommitted. | Human confirmation/application of the archived disposition, or a revised decision, remains required before this patch is canonical. Keep the dirty six-file patch. |
| Command plan-root identity and ordinal | Archived `TICK-16` records human acceptance of stable command-plus-plan-ordinal roots. The retained commits carry the detailed plan-root allocation, frozen command binding, and recovery identity rules. Current pinned `develop` has general root identity language but no `planOrdinal`/`plan_ordinal` occurrence in the searched canonical architecture/tracker corpus. The handoff note warns that older temporary references are stale. | Treat the retained material as a preserved, unintegrated decision/application candidate. Do not infer that its absence from `develop` means it is disposable; human reconciliation is still needed. |
| Destructive smoke/reset isolation | Current `develop` contains the run-owned local Compose boundary, protected per-run capability, and a persistent/shared boundary prohibition. The platform tracker still says the `coordination-maintenance` reset/control-plane path and environment-wide recovery are unavailable or unproved. No destructive run was authorized or executed here. | The local baseline is already represented on `develop`; any future persistent/shared or whole-stack destructive run still needs explicit environment, scope, owner, and recovery authorization plus proof. Preserve the retained smoke/reset text until its cross-document reconciliation is intentionally completed. |

The pinned alignment README currently describes selective application through ADR 0181 as complete while the owning Weather documents retain the unresolved selector. That status/document mismatch is itself a reason to retain the branch and archive evidence for deliberate reconciliation. This report does not resolve the mismatch.

## Eventual cleanup conditions

No deletion candidate is recommended now. A later cleanup review could consider the retained worktree only after a fresh status/HEAD check confirms that the six-file patch was either intentionally integrated with exact evidence or deliberately archived/rejected, and after the unique committed plan-root/recovery content has an explicit destination or human disposition. The remote archive should remain unless Ben identifies an authoritative replacement archive and approves its removal; its lack of an open PR or local checkout is not deletion evidence.

## Coverage and checks

Read sources included `AGENTS.md`, the design-alignment README, the World Runtime and Movement tracker, the owning runtime trackers for effect-root/smoke/reset consumers, the pinned Weather contracts, the handoff notes, and the exact retained worktree/remote ref metadata. Positive evidence is limited to exact local commit/tree/ref identities, exact dirty paths and diff sizes, and exact archived ledger text. Shallow negative ancestry, semantic equivalence, and broad historical equivalence remain unresolved where not proven.

No tests, linters, Gradle tasks, smoke commands, validation commands, PR queries, review/CI inspection, fetch, prune, ref mutation, index mutation, or worktree mutation were run for this scoped continuation. The only write was this new ignored report artifact.

## Main-task integration qualification

The main task independently inspected the six-file dirty patch and confirmed that the pinned canonical Weather ADR and transaction contract still mark the selector unresolved. The archived disposition needs reconciliation with current decision provenance; this does not automatically mean Ben must make the same decision again. Likewise, absence of the names `planOrdinal`/`plan_ordinal` and absence of exact patch identity do not prove absence of equivalent semantics. The committed identity/recovery material is preserved pending targeted reconciliation, not declared wholly unimplemented. All develop comparisons in this report use the pinned local snapshot, not the newer live origin/develop head or active PR stack.
