# Project Direction Working Notes

Local scratch notes for the strategic task, recorded 2026-09-05. These notes are non-normative and do not change repository workflows or accepted architecture. Refresh operational facts before acting.

## Resume here

Last updated: 2026-09-05, after receiving the old worker’s final #2682 handoff and checking the deployment sequencing implication.

The initial strategic assessment and the old worker's project handoff have been read. Ben wants this task to maintain direction and continuity. The old worker supplied its final #2682 handoff and reports it is paused. Main task verified the published/local Automation head, clean worktrees, and the two unique proof edits; CI/review claims remain worker-reported pending any actual merge assessment. No replacement worker or play lane has been started by this task. No repository process rules have been changed.

The read-only `worktree_branch_inventory` Luna xhigh agent has completed Ben's requested audit. Its [summary](./worktree-branch-inventory-2026-09-05.md) and [complete evidence](./worktree-branch-inventory-2026-09-05.json) are saved locally. The main task read both and independently confirmed preservation ancestry for all 23 candidate branch heads. Ben subsequently authorized autonomous disposal of dead/useless material. The completed non-active cleanup is recorded below; active 5B/open-PR material remains excluded.

Read this note first when resuming. Use the pending list below rather than restarting the assessment. The [received worker handoff](./worker-handoff-2026-09-05.txt) preserves its full account; treat its execution details as dated reports.

Scoped role reminder: before operational work, read the Astra Overseer block in `../AGENTS.md`. It applies only to this personal strategic worktree and requires delegation of operational investigation and execution.

## Pending discussions and actions

- [x] Integrate the delegated worktree/branch inventory and inspect its preservation evidence. Snapshot: 48 worktrees, 50 local branches, 18 live origin heads, 9 open PRs; 22 worktrees/23 branches have positive commit preservation and clean Git state. These are provisional cleanup candidates, not authorized deletions. Details and risks follow below.
- [x] Finish the focused purpose/preservation investigation outside active 5B/open-PR work. Three Luna lanes completed: two reused for their existing history/inventory domains and one fresh scratch-purpose worker. Main task inspected all reports and checked selected primary evidence. Ben explicitly excludes 5B/open-PR material, including contained helper branches, from the current cleanup pool. Ben subsequently authorized routine cleanup/disposition without more questions. Results and executed actions below.
- [x] Complete authorized non-active cleanup/disposition: removed the idle 6D/7C and downstream-seed checkouts and their generated caches; preserved both exact local and remote branch heads, retained design work, and useful future-topic notes. No further cleanup decision is pending from Ben for this scope.
- [x] Receive the old worker’s final wrap-up. Source: [final #2682 handoff](./worker-final-handoff-pr2682-2026-09-05.md). Published head `2b6a14f455baf86baf78c33754dc70ddee4650a7`; worker paused, reports green CI and no known actionable findings, but final commits lack hosted-review coverage. Details below.
- [ ] Decide and carry out the worker transition when Ben authorizes it. Recommendation: retain the old task until the replacement has checked its inherited state; do not interrupt the final known-fix boundary or request another discovery round.
- [ ] Give the replacement a bounded initial reconstruction of the preserved 5B stack. Establish unique work versus changes already in develop, semantic dependencies, and a short completion queue. Preserve coherent existing work rather than automatically merging everything and reseeding from fresh Luna findings. This is a proposed assignment, not work already dispatched.
- [ ] Establish the #2682/#2680 integration and deployment boundary before recommending integration: Automation and Game Session cover separate halves of exact-pin behavior. The handoff does not prove the intermediate state is safe. Respect the current stop instruction and do not start successor work implicitly.
- [ ] Choose the finite inherited 5B closeout scope, then the documentation lane's next corpus unit. Existing substantial runtime slices need explicit completion boundaries; documentation review must not absorb all adjacent capabilities indefinitely.
- [ ] Discuss Ben's ideas for the separate implementation/play lane before selecting its first assignment. He specifically wants AI to connect to and use the actual game through a terminal, protocol tool, or computer use, exercising real user-visible behavior beyond test suites. The aim is to find problems before Ben encounters them and reduce his need to operate testing manually. Environment, transport, first scenario, evidence, and safe mutation/reset boundaries are not selected yet. No testing or deployment is authorized by merely recording this idea. Existing `/home/ben/src/FireMUD-wsl-copy/tmp/ai-assisted-manual-testing-tracker.md` already records this intent and a candidate first real transport LOGIN/PLAY/LOOK path; use it as a dated discussion seed, refreshing environment and tool assumptions.
- [ ] Carry forward the existing shipped-game/profile discussion seed at `/home/ben/src/FireMUD-wsl-copy/tmp/shipped-game-profiles-idea.md`: primary authored fantasy MUD, reusable creator content, possible later profiles, v1 boundary undecided. The note asks for broader product/content exploration in ChatGPT before concrete repository decisions; this is an inherited pending topic, not a newly selected workstream.
- [ ] Assess the proposed whole-app migration-baseline squash separately, including purpose and sequencing; do not inherit it as an unquestioned prerequisite.
- [x] Implement, validate, and publish the review-completion policy change: PR #2683, head3ca737e06dc99828cbc956631aeb49378d87c3e9, two documentation files. One Luna worker effected the edits; root inspected and ran `./gradlew linkCheck lintMarkdown` (6,717 good links,535 Markdown files, zero issues) and `git diff --check`. Ben explicitly waived CodeRabbit and authorized merge, then auto-merge. Auto-merge is enabled using MERGE (squash is disallowed). PR was OPEN at confirmation; merging is not claimed. Ben said to forget about it and stop, so no monitoring or further cleanup is pending from this task.

## Continuity method

- Keep this one file as the strategic task's live index of agreed direction, pending questions, current boundary, and next actions. Update it after meaningful decisions or handoffs; correct superseded live status rather than leaving competing current-state sections.
- Preserve received handoffs separately as dated source material and link them here. Avoid duplicating the repository's capability trackers or creating another comprehensive findings ledger.
- Label recommendations, user decisions, reported facts, and independently verified evidence separately. Mark finished pending items and record their outcome so context loss does not restart completed work.
- This worktree and its ignored tmp directory are reserved for this task's continuity. They persist locally across conversation context changes, but are not committed or remotely backed up.
- When Ben delegates an audit through this task, stay with it through agent completion, evidence integration, and a useful result. Ending with only a dispatched/running status was an error Ben explicitly corrected.

## Worktree preservation snapshot, 2026-09-05 05:32 UTC

- Coverage: all 48 registered worktrees, 50 local branches, 18 live origin heads, and 9 open PRs. Immediate FireMUD sibling checkout scan found no additional checkout; the Windows Codex worktrees directory examined was absent. This is not an exhaustive search of all disks.
- Open project PRs: #2682 Automation pin, #2680 Game Session pin, #2681 dependent proof, #2676-2679 residual/publication/handoff/execution chain, and #2661 Redis/replay/workflow corpus work. #2458 is the ninth open PR, a Renovate TypeScript upgrade.
- #2661 still uses `codex/corpus-review-5b-persistence-stack` as its base: retain that dependency even though its local commit is contained by develop. Local worktrees do not necessarily match live PR heads.
- GitHub origin develop was `37dd58934451d4e43a7d511067d857e833fff44d`, while local develop and this strategy checkout remained `1ea2c93404ac813bcfc2a437c6a607e515821456`. #2682 was published at `5273d62207aad2fd2f0591b35b9f0899acef9d68`. These are inventory facts only, not validation, review readiness, or confirmation of the retiring worker's final pause.
- Seven dirty worktrees must be retained: original FireMUD-wsl-copy (4 tracked paths, including 2 conflicts); corpus-review-5b-authority (28 tracked, 2 untracked); corpus-review-5b-exact-pin-proof (1 tracked, 1 untracked); corpus-review-5b-followup on residual branch (180 tracked, 38 untracked); corpus-review-5b-runtime-spillover (9 tracked, 1 untracked); pr2669-published-check (8 tracked); retained-design-reconciliation (6 tracked). Dirty does not establish unique semantics, but requires reconciliation before removal.
- 22 clean worktrees plus the no-worktree `automation-carry` branch are provisional candidates with positive commit preservation. The main task independently rechecked all 23 branch heads. Many are held by an open PR, not merged develop. The final-integration helper may still be in use by the retiring worker; do not remove it based on cleanliness alone.
- Ten worktrees remain unresolved, including earlier repacks, downstream seed, 6D/7C material, detached PR2669 inspection, and several final PR2682 fix/proof branches. Negative ancestry in this shallow/rewritten history is inconclusive; no patch-equivalence pass was performed. Keep them until inspected for content preservation.
- A further no-worktree local followup branch is unresolved. Remote-only archive `design/adversarial-decision-review` remains unclassified for cleanup; remote-only Renovate head is an open PR and its current commit is not available locally.
- Valuable ignored tmp notes/patches exist in the original and strategic worktrees. The inventory records selected scratch metadata, not a complete analysis of every ignored artifact. Fresh pre-deletion inspection is still needed.
- Agent recheck found no ref/status changes within its snapshot window. No source edits, git ref/index changes, fetch/prune, review/CI operations, tests, or deletion were performed. Only local report/continuity artifacts were written.


## Non-active material purpose check, 2026-09-05

- [Retained design report](./retained-design-purpose-2026-09-05.md): keep the six-file uncommitted Weather patch and retained identity/recovery commits. Preserve remote adversarial review archive as source evidence. An archived region-Weather disposition and current canonical unresolved status need a targeted provenance check during handoff, not an automatic repeat decision or renewed corpus audit. Missing plan-ordinal keywords/exact patch identity does not prove missing equivalent behavior.
- [Downstream corpus report](./downstream-corpus-purpose-2026-09-05.md): parked seed contains useful recovery/incident tooling and tests plus downstream corrections. Correction: fresh explicit exit-code checks show 6D/7C is NOT contained in downstream seed. They share base2bdaaf7 and have separate 29-commit and four-commit lines. Earlier agent and root ancestry conclusions were wrong; the root failed to inspect the ancestry exit code separately from a succeeding log command. Preserve both branch refs. A clean inactive checkout can be removed separately from preserving its branch and unique ignored material. Do not apply the old branch wholesale or infer all changes are missing from develop.
- [Scratch-purpose report](./nonactive-scratch-purpose-2026-09-05.md): keep both shipped-game and AI manual-testing idea notes; root read both and linked their pending discussions above. Tiny Python caches are reproducible but provide no meaningful cleanup benefit. Preserve handoff and active coordination notes.
- Agent comparisons used local develop `1ea2c934`, not newer remote develop or current PR implementations. Exact semantic carry-forward remains unresolved where stated. No tests, runtime actions, tracked edits, Git mutations, or deletions were performed. Only ignored reports and this continuity index changed.

## Workspace and authority

- Worktree: `/home/ben/src/FireMUD-project-direction`.
- Branch: `codex/project-direction`, initially based on local `develop` at `1ea2c93404ac813bcfc2a437c6a607e515821456`.
- Purpose: strategic assessment, handoff evaluation, and temporary notes. No implementation or workflow edits are currently assigned here.
- Ben wants this task to help lead project direction, challenge scope and sequencing, and assess worker evidence. Ben retains consequential product and architecture decisions.
- Ben has not yet authorized this task to contact, redirect, or replace the existing worker. The initial handoff request was drafted for Ben; he supplied the resulting response, which is saved locally. Await the final wrap-up and further direction.
- App instructions describe cross-task communication tools, but tool discovery in this task did not expose callable list/read/send/wait task tools. Native subagent communication is available. Do not promise direct worker-task messaging until availability is actually verified; shared files and Ben-relayed messages are the current confirmed handoff mechanisms.

## Agreed direction and preferences

- Preserve FireMUD's broad ambition, documentation-first approach, and foundation-up construction. Ben deliberately accepts patience and broad upfront design to reduce avoidable replacement work; do not silently substitute an incremental simplified architecture strategy.
- Remain critical: implementation and operational evidence are necessary to test design assumptions. Prefer small complete slices of the intended architecture; any disposable experiment should be identified explicitly.
- Continue whole-corpus documentation consolidation alongside a separate implementation/proof lane. The concrete playable lane will be discussed separately.
- The implementation worker owns the detailed design clarification needed to complete its selected capability, with consequential alternatives brought to Ben.
- Merging through `develop` is the main coordination mechanism. Explicit handoffs are needed for pending semantic changes that would invalidate substantial concurrent work; avoid another coordination ledger.
- Both lanes share constrained hosted review capacity. Ben reports one free hourly CodeRabbit hosted review opportunity and a current 100-file ceiling. Treat these as session-supplied constraints, not independently verified provider terms.
- Repeated review has found serious real defects; preserve high correctness standards. The concern is allowing unrestricted discovery to determine scope indefinitely. Distinguish a valid observation, a blocker to the selected boundary, and justification for another broad pass.
- Finite capability boundaries and risk-driven additional review are the proposed direction. Existing repository review rules remain in force until explicitly changed; no approval to bypass them has been given.

## Initial evidence and limitations

- Initial read-only assessment covered architecture/product orientation, trackers, selected ADRs, temporary plans, representative code/tests, and local branch history. Three Luna agents supplied bounded evidence. No tests or hosted-state checks were run and no repository files were changed during the assessment.
- Local `develop` tracker snapshot: 79 leaves; 1 implemented, 76 partial, 2 not implemented; verification 34 proven, 12 audited, 32 drift-found, 1 unverified. These are broad target-boundary labels, not percentages complete or evidence of regression. Newer worktrees contain additional work.
- Concrete bounded login, gameplay entry, LOOK, movement, communication, inventory, and publication substrates exist. Complete first-time JOIN/character entry and creator publication-to-launch journeys remain incomplete in the inspected baseline.
- Temporary whole-corpus plan specifies 23 units and a 72-pass unrestricted review floor plus CLI/hosted review. Recent 5B work has expanded into substantial runtime implementation. Useful findings include authorization, tenant scope, migration ownership, recovery, and concurrency issues.
- Existing ignored coordination records under `/home/ben/src/FireMUD-wsl-copy/tmp/` include `main-slice-worker-program.md`, `main-slice-worker-handover.md`, `whole-corpus-authority-review-plan.md`, `corpus-review-status.json`, and `corpus-review-5b-followup-seed.md`. Some snapshots are stale or inconsistent. Do not treat them as live PR state.
- The original worktree has concurrent dirty/conflicted scripting documents and workflow edits. Preserve them. Other workers are advancing branches during this conversation.

## Next checkpoint

Both the broad handoff and final #2682 pause report have been received and saved. The next substantive task is replacement-worker transition, with a bounded #2682/#2680/#2681 reconciliation and integration/deployment plan; no new exhaustive discovery round. Do not ask for the old worker handoff again or implicitly resume its paused work.


## Worker handoff received 2026-09-05

Ben supplied the worker response as an attachment. An exact local copy is `worker-handoff-2026-09-05.txt` in this directory (17,176 bytes; 130 newline-counted lines). All six requested sections are present and the final Candid revision paragraph ends with a complete sentence; no apparent truncation.

This is worker-reported evidence, not an independently refreshed operational snapshot. The report says Ben has stopped additional CLI, hosted, and broad Luna review for the current wrap-up. Its stated boundary is to finish the active binding correction, integrate four isolated batches, validate, publish, resolve demonstrably addressed threads, report, and pause. Do not reinterpret its proposed later sequence as authorization to proceed beyond that pause.

Reported active PR is #2682 Automation exact-pin work; Game Session owner/admission enforcement remains #2680. Draft proof #2681 and residual #2676-2679 plus older #2661 need preservation and later reconciliation. Reported worktree commits are volatile; refresh only before acting. The worker acknowledges 5B has become too broad and implementation-heavy and recommends separate semantic implementation slices alongside corpus work.

The proposed whole-app migration-baseline squash after #2680 is a sequencing item to assess, not an established strategic prerequisite from this conversation. The handoff also reports Docker-backed proof unavailable locally; distinguish test sources and CI requirements from completed executable proof.

## Disposition authority update

Ben explicitly authorized this task to dispose of dead/useless material or consume useful information autonomously; he does not want routine cleanup decisions sent back to him. Apply this to the inspected non-active scope, preserving active 5B/open-PR work. Summarize outcomes briefly.

## Completed cleanup, 2026-09-05

Ben delegated disposition rather than requesting a decision menu. After fresh checks, the main task removed two clean inactive checkouts with `git worktree remove` (no force): `/home/ben/src/FireMUD-corpus-review-6d-7c` and `/home/ben/src/FireMUD-corpus-review-downstream-seed`. Their ignored contents were only generated caches/build diagnostics, approximately 396 MB combined. No open PR used either branch as head/base, no process cwd/open descriptor referenced either checkout, neither worktree was locked, and no tracked edits or nonignored untracked files existed immediately before removal.

The useful committed work remains intact locally and on origin: `codex/corpus-review-6d-7c` at `c2af10ee75d395172e8b10201f5ba8c3cd5cbf66`, and `codex/corpus-review-downstream-seed` at `2c54fae8806bf8d4e59d84b03ec785a747c490ad`. Future workers should read these refs or recreate a checkout as needed; old absolute checkout links in dated audit reports are historical. These separate branches are not proven duplicates. No branch, remote ref, or active 5B/open-PR work was removed.

Retained Weather/identity/recovery work and the adversarial decision-source archive remain preserved with their purposes recorded. Product/profile and actual-game-testing ideas have been consumed into the pending strategic topics, rather than sent back as maintenance questions. One existing Luna scratch agent was reused for the ignored-content check; the main task performed preservation/use/PR-metadata checks and both removals. No tests, CI/review activity, or tracked source changes were made.

## Final #2682 handoff integration

Source: [worker final handoff](./worker-final-handoff-pr2682-2026-09-05.md). The main task confirmed local and live remote #2682 branch head `2b6a14f455baf86baf78c33754dc70ddee4650a7`, with a clean checkout. #2680 remains clean locally at `1d5dd677310ba83700cd7ce85c674fe50e201531` and published at `475b3ac77a8ec982cf8e762e8b7f04b36bb8fc45`; the reported 50-commit unpublished delta still needs reconciliation, not overwriting with the remote state. #2681 still has the exact two reported uncommitted test paths. Active 5B and helper worktrees remain preserved.

Worker reports all final CI green, including real Docker/PostgreSQL proof, while local Docker-dependent checks were unavailable. It reports zero unresolved review threads and all 13 latest findings fixed, but final commits were not covered by hosted review. These are handoff claims, not independently rerun review/CI checks, and do not establish the repository’s formal review-complete/merge-ready gate. No new review was requested.

The important sequencing correction: `.github/workflows/dev-demo.yml` triggers deployment on pushes to develop and derives deploy action from the pushed SHA (lines 3-6 and 67 onward); its blob is identical in the pinned strategy snapshot and #2682 HEAD (`59e52bd7fbed5ec655fc18c39463b0854cab74ff`). Therefore integrating into develop is not operationally separate from deployment. Actual hosted deployment may depend on runner/environment availability, but it is configured to run automatically. The worker says #2682 alone rejects affected instance-scoped Automation events until #2680 supplies/enforces the owner identity/positive epoch/exact tuple. That is an availability limitation even when fail-closed integrity is correct. Do not recommend merging on the assumption deployment can be deferred automatically.

Proposed replacement worker first boundary: preserve/reconcile the unpublished #2680 delta and dirty #2681 proof; prepare them against the fixed #2682 checkpoint before requiring a develop merge; establish combined capability proof and an explicit merge/deployment sequence that accounts for dev-demo’s automatic trigger; refresh current review evidence only at the appropriate finite checkpoint. No broad 5B reseed, migration squash, or whole-corpus pass belongs in this initial handoff boundary. Consequential choices remain Ben’s; routine preservation/integration decisions belong to the worker/orchestrator. No replacement task has yet been created or contacted.

## Delegated execution preference

Ben reiterated that this strategic task should command subagents to effect repository changes, while retaining policy decisions and integration responsibility itself. Delegate bounded implementation/mechanical edits rather than doing them in the strategic context.

## Latest stop boundary

PR https://github.com/benhook1013/FireMUD/pull/2683 has auto-merge enabled, verified at2026-09-05T06:53:01Z. The PR description includes `@coderabbitai ignore` under Ben’s explicit one-PR review waiver. Working checkout remains `/home/ben/src/FireMUD-review-completion-policy`; do not resume watching this PR automatically. The general human-only merge policy remains in the guidance; this PR used explicit user authorization. Current worker needs the merged guidance on its next develop synchronization; no task-to-task message was sent.

## Hosted-fix checkpoint and strategic decision, 2026-09-05

Ben relayed the worker’s completed hosted-fix checkpoint: #2682 published at `63f3fac9134415e1e835f2ce6d55ce014a5792d7`, combined #2680 at `2d1ad2ac74eb9380cccf16159b75728e5c3e7e59`. Worker reports clean synchronized worktrees, 654 Automation/129 Game Design local unit tests passing, final-SHA #2682 hosted validation/Docker/full-stack/preview/TCP proof passing, and combined local service/docs checks passing; local Docker checks were skipped. These validation/review claims were not independently rerun by the strategic task.

Reported defects fixed include conflicting handoff owner tuples, ingress/rollout coherence, nondeterministic digest ordering and schema-v4 enforcement, timer patch/epoch identity, plugin/binding retry identity, and associated proof/docs. Last hosted review covered ffa8d9032; later commits change persistence, retry identity, token construction and digest semantics. This is material production change and justifies a new substantive checkpoint under the approved completion policy.

Main task verified live PR topology: #2682 targets develop with91 files; #2680 targets #2682’s branch with60 files. Crucially, explicit `git merge-base --is-ancestor` returned0 for #2682’s current head into combined #2680’s head. Both remain OPEN.

Strategic decision: one new hosted #2682 review is justified; complete its findings/focused proof and report before another discovery round. Carry any parent fixes into #2680 before reviewing the dependent delta. Expected eventual release is one combined #2680 merge after retargeting to develop and required combined validation, preserving substantive coverage of parent and delta. This supersedes the earlier #2682-then-#2680 merge proposal: #2682 is a reviewed component checkpoint, not an independent deployable merge. No direct worker message or CodeRabbit command was sent by this strategic task in this checkpoint.

## Pre-v1 database rule clarified by Ben

Ben explicitly states there are no users and no obligation to preserve development migration history. Accept that project rule without reopening hypothetical compatibility/retention investigation. Squash to clean service baselines after the exact-pin work; preserve actual current-schema invariants and fresh-start proof. Do not spend more effort defending obsolete upgrade paths. This is the accepted direction, not a pending request for more justification.

## Current task coordination tools

Cross-task tools are now callable in this strategic task. Worker task: `01a07037-e403-7b91-a500-34fc9f876a12`, host `local`, title `Worker`. Read compact `wait_threads` snapshots or filter `read_thread` results to the latest `agentMessage` items before emitting them; returning all tool items consumed excessive context in one earlier read. No standing monitoring automation was requested. Sending instructions remains separate from read-only inspection; no cross-task message has yet been sent.

## Unattended-work correction and live worker checkpoint

Ben identified that item-by-item overseer checkpoints cause the worker to go idle while he is away. Main task accepted that the checkpoint had become a permission barrier and directly updated Worker task01a07037-e403-7b91-a500-34fc9f876a12 with an end-to-end inherited 5B closeout mandate. Routine reports are informational and do not pause work. Sequence: finish combined #2680 gate/presentation; clean pre-v1 migration baselines once the exact-pin boundary is coherent; reconcile and complete the finite remaining 5B scope with explicit ownership of unrelated residual work. Independently useful or safely stacked work continues while human merges/external checks are pending. No play-lane work or blanket AI merge authority was added. Further hosted review is decided by the worker against material changes/concrete risks under the new policy, not preapproval from this task for every round.

Worker's direct status reply: combined #2680 is now based on develop at f971876d14d70f72902c9fbe82f995c9f09e66f3. One #2682 hosted review yielded16 findings (13 fixed,3 optional/invalid); one #2680 delta review yielded11 findings, all adjudicated with actionable items fixed and threads resolved. Reported fixes include persisted owner evidence, lifecycle DTO propagation, publisher failure proof, duplicate/unused test cleanup, and V12 NOT VALID/V13 validation sequencing. Worker reports no scope expansion, green combined local checks, green hosted CodeQL/security/license/dev-tool/full-stack-smoke checks, and remaining hosted artifact cleanup/final gates plus stale preservation-context refresh. Its planned stop after #2680 presentation was explicitly superseded by the continuous mandate.

Main task independently ran the review checker on both PRs before receiving the status: zero current/outdated threads and zero summary finding candidates, with final-head review gaps flagged on e12d2c99 (#2682) and f971876d (#2680). Those flags require the worker's direct-fix/material-change adjudication; they are not automatic reasons to restart broad review. No new CodeRabbit run or CI operation was triggered by the strategic check-in.

## Direct wakeup and handoff protocol authorized

Ben explicitly requested that Worker and Overseer message and wake each other so unattended work continues without human relaying. This is now authorized ongoing cross-task coordination within the existing project scope. Root sent the protocol to Worker01a07037-e403-7b91-a500-34fc9f876a12 (local); Overseer is01a06fc0-9af1-7c93-9866-faecba39d3c0 (local).

Worker sends a direct app message on full mandate completion, a meaningful boundary needing strategic direction, or a genuine blocker without independent work. Include outcome, branch/PR/head, relevant proof/blockers and proposed next action. The message wakes the Overseer; the Overseer assesses it and sends an actionable decision or authorized next assignment back, waking Worker. Ordinary progress continues autonomously and does not need Overseer acknowledgement. No acknowledgement-only ping-pong, duplicated handoffs, routine still-working pings or polling automation. No immediate confirmation reply was requested. Human merge/consequential-decision/stop boundaries remain intact; this is not general merge authorization.

When awakened by a worker handoff, use these notes to resume, assess the actual evidence at proportionate depth, delegate any bulk investigation, and advance the already-authorized program. Ask Ben only for a genuinely necessary consequential decision. Do not convert each reported checkpoint back into a permission barrier or manufacture more work after the mandate is complete.

## Settled review/merge correction — supersedes conflicting topology advice

Ben instructed Overseer to resolve the confusion and update Worker directly. Root sent a concrete conditional procedure, not another speculative reversal: preserve current combined #2680 develop-based topology and code; assess exact post-review changes against existing component reviews and direct-fix exception. If coverage plus verified direct corrections and required final-base CI/proof suffice, present the single combined #2680 for human merge. Aggregate148-file size alone does not invalidate component review coverage or demand a new review.

If actual material uncovered changes require hosted review, reuse the existing #2682 parent and #2680 dependent review scopes under100 files; Worker may temporarily restore #2680's parent base when no review is active, review the dependent delta after parent changes settle, then restore develop base and required final-base checks. Do not split/rebuild implementation or create deployment-control machinery. Escalate only an actual coverage gap that existing component scopes cannot accommodate. No independent #2682 merge is required by this settled procedure. General human merge authority remains unchanged.

Root owns the planning/communication mistake: it endorsed final retargeting without clearly explaining that the combined148-file diff exceeds the whole-PR review ceiling, then prematurely suggested reversing it before checking whether further review was needed. No implementation was lost or reverted. Do not replay that indecision; follow the concrete coverage procedure sent to Worker.

## Current authoritative merge sequence: ordinary stacked PRs

Ben challenged the unnecessary combined-PR retargeting and asked why we were not simply using stacked PRs. Root accepted that the extra topology work was motivated by overvaluing uninterrupted dev-demo operation despite no users. Root directly instructed Worker to return to the existing ordinary stack. THIS SUPERSEDES all earlier single-combined-#2680-merge instructions in these historical notes.

#2682 targets develop and is the first human merge candidate. #2680 targets codex/corpus-review-5b-automation-pin-authority until #2682 actually merges; restore that base only when no hosted review is active. Preserve every commit and completed review; no new PR split/rebuild. Prepare both components and combined proof as far as practical before presenting the parent. After #2682's human merge, retarget #2680 to develop, reconcile actual base changes, refresh required final-base gates and present #2680 for human merge. Parent files naturally leave the child diff once included in develop. Review remains based on substantive coverage and verified corrections, not a clean streak or automatic rereview caused by metadata changes.

Temporary fail-closed behaviour in disposable dev-demo between these sequential merges is accepted for this plan; mention it in the merge handoff and do not create deployment-control work to avoid it. General human-only merge/no auto-merge rules remain. The rest of the full5B mandate, baseline squash, autonomous continuation and event-driven Overseer handoffs are unchanged. Root owns the previous indecision and should not oscillate back to the oversized single-merge approach.

## Autonomous review runway clarified

Ben explicitly wants the worker to use its unattended runway to obtain multiple hosted-review/fix rounds on the two stacked PRs as useful. Root sent a clarification: no one-round allowance or Overseer approval between reviews; old round-then-hold instructions are superseded. Continue substantive reviews where serious defects/material changes justify them, with stable parent/child review scopes and normal hourly/file-limit safeguards. The removed mandatory two-clean-round taper was not reinstated. Prioritize parent#2682, propagate changes, review child#2680 once its base is stable, and keep working on independent authorized tasks during waits. Ordinary reports are non-blocking; actionable handoffs still wake Overseer under the established protocol.

## Near-ceiling review instruction

Ben noted that parent#2682 at92 files could encounter pressure to merge prematurely or churn through splits as further findings arrive. Root directly instructed Worker: hold semantic boundaries stable; count distinct paths against actual review base (rounds touching existing paths do not increase scope). At95 files or a known necessary fix threatening100, send one actionable early warning to Overseer with count/new paths/finding/blocking classification/proposed disposition; continue independent work. Fix real in-scope defects, never merge past them or downgrade them to fit the ceiling. Separate nonblocking out-of-scope improvements may have an explicit successor owner without weakening current claims/proof. No automatic split/repack/reseed, tiny overflow PR, review-invalidating publish, or knowingly over-limit full review. Preserve necessary fixes locally and hand off a genuine coverage/topology blockage before surgery; existing human approval for active-PR splits remains. File count is not a merge criterion and does not create a clean-review streak. This is targeted near-cap guidance, not a new per-round approval barrier.

## Parent closeout priority — latest instruction

Ben correctly challenged the coexistence of open-ended additional review and a parent already at92 files. Root explicitly chose convergence and sent Worker a parent-specific CLOSEOUT instruction, narrowing the earlier permissive multiple-round runway for#2682. Finish known actionable findings and actual post-review material-change coverage/proof; no discretionary broad hosted/CLI/Luna discovery just because time/quota permits. No new features/opportunistic adjacent cleanup in the parent. Let active review finish and handle actual findings; never waive material coverage. Once canonical completion criteria hold, present#2682 for human merge immediately, with no clean streak, then prioritize child/remaining program. Worker continues#2680 preparation while human merge is pending.

If a real essential blocking correction cannot fit100 files, correctness wins: do not merge incomplete, and no automatic overflow/repack churn. Produce one concrete coherent re-scope/split proposal with exact paths and retained evidence for the existing human-approval boundary. This exception does not justify restarting general exploration. Root acknowledged that its previous permissive guidance failed to reconcile the two goals; use this latest explicit priority.

## First parent human-merge checkpoint, 2026-09-06 NZ

Worker sent actionable completion handoff: #2682 at e12d2c99c,92 files, is the first human merge candidate under the documented direct-review-fix exception. Its substantive review covered63f3fac and later changes directly corrected findings. #2680 is restored to the Automation-parent base at0c0036e6e; worker reports combined develop-based hosted proof fully green, including repaired PostgreSQL Game Session tests and11-minute fresh full-stack smoke. Temporary dev-demo fail-closed limitation is stated in PR body.

Root independently refreshed #2682 checker and hosted metadata: exact head e12d2c99c03e8b3b48035e74cf59c767a836cf28,OPEN,CLEAN,92 files; zero current/outdated threads,zero outside-diff/duplicate candidates;54 SUCCESS,40 SKIPPED,1 NEUTRAL contexts and no failed/pending contexts. Checker exits1 only for absent final-head explicit/substantive review. Worker’s documented direct-fix assessment supplies the narrow approved exception; no claim final head received a new hosted review.

Root is presenting #2682 for Ben’s merge. Directly assigned Worker to preserve ready #2682/#2680 heads and continue authorized baseline squash preparation in an isolated successor based on coherent combined #2680 while awaiting human merges. Use coherent service batches within review ceiling; no reopening hypothetical legacy-data obligations. When parent merge is observed at a natural repository checkpoint, retarget #2680 to develop and refresh final-base requirements before presenting it. Human-only merge/no auto-merge remains. No acknowledgement-only response requested.

## Delegation correction, 2026-09-06

Ben explicitly required durable role guidance after repeated failures to delegate. In this personal strategic worktree, Astra Overseer owns strategic direction, scope and priority decisions, bounded delegation, evidence adjudication, cross-task coordination, and user communication. Before operational investigation, delegate repository searches and reading, provenance and comparisons, audits, implementation and documentation edits, tests and validation, and bulk tooling work. If no worker is available, report the constraint rather than doing grunt work. Current pending task: have Luna verify #2681 proof incorporation before any human-authorized close; preserve the branch, and after closure root coordinates Worker. This continuity note does not alter technical or review policy.

## PR #2681 closure disposition, 2026-09-06

The bounded provenance check against published #2680 head `0c0036e6e2cbb7dcb072c896ee4d9d1b2a059fbf` confirmed that the #2681 code and saved proof work were preserved in the target or its retained history. The earlier statement that all useful proof was fully incorporated was too broad: five rollout read-path scenarios remain active Worker ownership for canonical equivalence or restoration—same-epoch/different-request filtering, stale-owner projection preservation, positive-epoch rollback gating, blank rollout-event tenant rejection, and pinned request-ID forwarding. No implementation was stranded. Ben explicitly authorized closure; PR #2681 is closed, with its branch and history retained. No tests or checks were run by this provenance task.

## Overseer/Worker guidance publication, 2026-09-06

Repo-owned role guidance is prepared for human review in [PR #2684](https://github.com/benhook1013/FireMUD/pull/2684), branch `codex/overseer-worker-guidance`, commit `8a79759c8`. Current guidance pointers are [`AGENTS.md`](../../FireMUD-overseer-worker-guidance/AGENTS.md) and [`ai-delegation-and-review.md`](../../FireMUD-overseer-worker-guidance/design/developer-workflows/ai-delegation-and-review.md). No review polling was performed.

## Current exact-pin review contingency, 2026-09-06

Ben explicitly authorized a coherent third FRONT PR if subsequent hosted fixes would exceed 100 files; no further human split approval is required for that condition. Worker was directly instructed to check findings against downstream code and proof first, move necessary existing fixes to the earliest dependent PR, reconcile downstream duplicates, and preserve active review-head/base safety. The exact-pin stack remains #2682 -> #2680; #2681 is closed; five proof scenarios are assigned to existing #2682 files. No changes to the published role-guidance scope are needed for this task-specific note.

## Hosted review runway directive, 2026-09-06

Ben expects another 4–5 hosted cycles from experience. Worker was directly instructed to treat this as normal autonomous runway, superseding the two-round handback. No forced stop or permission checkpoint applies at 2 or 5 cycles; the estimate is neither a minimum nor a maximum and does not create a clean streak. Continue justified fixes and reviews, the authorized coherent third-front split, and cross-stack duplicate-fix checks; meaningful reports remain nonblocking. PR #2684 was published at commit `8a79759c8` with two instruction files and passing docs checks; it awaits human merge. No tests or validation are authorized for this continuity update.

## Delegation routing and reporting refinements, 2026-09-06

Ben’s standing authorization covers these agreed routing and reporting refinements. The current repo-owned guidance pointer is [`ai-delegation-and-review.md`](../../FireMUD-delegation-reporting/design/developer-workflows/ai-delegation-and-review.md) on `codex/delegation-reporting`: Sol lane Workers orchestrate substantial operational work and delegate bounded bulk reading, coding, and repetitive testing; the lane main Worker owns decomposition, integration, targeted evidence and diff review, consolidated proof decisions, final experience verification, and lane PR/review operations. Luna is the default value execution model rather than universal; competing options, conflicting evidence, consequential inflections, and repeated inconclusive passes guide routing reconsideration. Substantial round endings, handoffs, and blockers include one concise actual-delegation line or a brief no-delegation reason; cross-task summaries remain limited to meaningful milestones, decisions, and blockers.

## Delegation refinement checkpoint, 2026-09-06

The one-file delegation/reporting refinement was approved and validated; local commit `f775fe29a` on `codex/delegation-reporting` awaits publication until preview capacity resolves. Current Worker/Gameplay already received operational instructions. Worker was asked to assess the #2676 preview retirement; Gameplay owns live movement, bootstrap, and session tooling fixes and will not provision a fourth stack. Publish this small docs change when practical; no automation.

## Delegation refinement publication, 2026-09-06

Ben clarified that preview capacity is not a prerequisite for this documentation-only change. PR [#2685](https://github.com/benhook1013/FireMUD/pull/2685) is published from `codex/delegation-reporting` at full head `f775fe29acaa25643c7e4d5ff15e229331929b5f`, with one changed file; no hosted review cycle or preview testing was requested. Ben authorized one-off merge-queue enablement using the merge method; the single readback confirmed `autoMergeRequest.mergeMethod` is `MERGE`, state `OPEN`, and `mergedAt` is null. No CI or review polling was performed.

- 2026-09-07: [Gameplay follow-ups](./overseer-gameplay-followups.md): consult this queue at Gameplay delivery or phase completion and restart recovery before deciding the next assignment.
