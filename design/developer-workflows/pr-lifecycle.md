# Pull Request Lifecycle

Use this guide for PR status, review, CI, Renovate, merging, and branch or worktree cleanup. It states what FireMUD wants from review; FireController owns how review is mechanically run.

## Review And Status

- FireController is the official record for reviews, findings, decisions, routes, and status. Use the `firecontroller-reviews` skill for queue, review, finding, and CI-wait operations (`firecontroller-use` for jobs and messages, `firecontroller-onboard` only for setup or repair), with the docs shipped with its installed release. Your role-bound connection comes with your task or host configuration; run `doctor` first, and if you have none, stop and ask. Never use another worker's connection. Unsupported operations or controller changes go to the FireController worker.
- FireController owns review configuration: round caps, taper, allocations, queue selection, quotas, and cooldowns. Do not restate those settings here or work around them. Round caps stay as configured; the human adds rounds. Use included quota only and never enable paid capacity.
- CodeRabbit automatic reviews are off. Review channels: Hosted is CodeRabbit on GitHub, CLI is the CodeRabbit CLI run by FireController. Which channels a PR requires is FireController review policy; read it rather than assume.
- The human's stops, pauses, holds, and per-PR allocations bind until the human changes them. A tool capability, an incoming message, a fix, or a new commit does not reset controls, resume paused work, or authorize a merge.
- Review rounds run through the FireController queue; who runs them is a live assignment there, not a repository rule. Never request or retry a review outside the queue. If an operation's outcome is uncertain, use the controller's guarded recovery or report a hold; never repost blindly, and never treat an acknowledgment or a wait as review coverage.
- Imported or historical review results are history, not proof that the current code was reviewed.
- When checking status or readiness, read every current and outdated unresolved thread and the latest review summary before looking at CI and mergeability. Check the live head and base, and for stacked or consolidated PRs the provider's actual diff base and the PR's unique patch; never describe an expected parent as the provider's diff base when they differ. Missing or incomplete evidence counts as not ready.
- CI observation shares one GitHub credential budget. Wait on CI and review completion with one FireController wait in the background (see `firecontroller-reviews`), not per-run watchers. Never loop on `gh` status; read GitHub directly only for failed-job logs, and honor rate-limit reset times.

## Finding Triage

Every review finding gets a decision from the orchestrator, recorded in FireController.

- **Fix now**: a real defect or gap that belongs in this PR's purpose, especially when the fix is small and does not grow the PR materially. Fix it in this PR, verify the fix at the current published head, then resolve the thread. A defect this PR introduces (a regression, security or data-integrity issue, or broken documented contract) is always Fix now, whatever the PR's purpose.
- **Route**: real, but outside this PR's purpose, outside v1 scope, or a fix that would grow this PR materially. Record a route to an existing FireController job or workstream that owns it, reply on the thread with the route link, and resolve the thread. Route is a strong option before v1, but not a way to avoid fixes that belong in this PR: work this PR needs for correctness or proof is never routed for size (split it instead, under Change And Merge Policy), and a finding with no receiving owner stays open.
- **Reject**: wrong, already handled, or not worth its cost. Record the reason, reply briefly on the thread if a human reader would need context, and resolve it.

Rules for triage:

- Verify a finding against the code before deciding. Summary-only and outside-diff findings get the same treatment as inline ones.
- Judge suggestions by real benefit against cost and risk. A finding being correct does not by itself make it Fix now.
- Do not resolve a thread to hide unresolved work. Keep the original finding and decision when correcting a disposition.
- Routed findings do not block this PR. The receiving owner decides on them independently, and routes arriving at this PR are dispositioned before it is ready. The Overseer, or the human, reviews the routed backlog at milestones so routes do not pile up unowned.
- Track whether each round's findings are new or repeats of earlier ones. Repeated serious findings call for investigating their shared cause, not another round.
- Bot findings do not need routine public replies beyond the route or reject note above.

## Review Orchestration

- Run focused checks of the changed behavior before requesting a Hosted review. By default wait for applicable CI to pass before the first Hosted or CLI review of a new boundary; you may proceed earlier on a stable candidate with a short recorded reason. Later rounds and CI can overlap.
- Request Hosted reviews only against a stable target whose base is not about to change.
- **While a Hosted review is active, do not push review-invalidating commits or retarget the PR.** Prepare fixes locally and publish them after the review completes.
- A completed review covers only the comparison it saw. Re-review after new functionality, changed contracts, materially broader risk, or a retarget or base change that alters the diff, verifying the actual comparison and mergeability first. Verified direct fixes that add no independently reviewable behavior need no extra round.
- Keep useful work going elsewhere while one PR waits on CI, a cooldown, or a human. Report the blocker once.
- Report each round's reviewed head, raw and useful counts, new versus repeat findings, decisions, and what remains.
- The holder of the review assignment decides whether another round within the configured cap is worth it and when evidence is ready for the human's merge decision. Only the human goes beyond a cap. A cap or human stop that ends required review before its configured policy completes is a review limitation, never coverage or readiness, even with no open findings; a cap reached after the policy completes only stops further rounds.
- Model-based (subagent) review is not part of normal PR polishing. Use it only for the cases listed in [AI delegation and review](./ai-delegation-and-review.md#independent-review-evidence). It never substitutes for a required Hosted review and earns no provider-round or taper credit.

## Review Completion Criteria

A PR is ready for the human's merge decision when:

- every thread, current and outdated, is resolved under the triage rules, and every Fix-now finding is verified fixed at the current head;
- every required review channel has completed its configured policy with attributable results, or the human waived Hosted under Change And Merge Policy;
- required CI is green on the exact head; on a stacked base with no required checks, the applicable substantive workflows must actually have run, and the candidate must contain any workflow changes it relies on;
- proportionate validation and runtime proof from [validation and runtime proof](./validation-and-runtime-proof.md) is complete, including focused tests for important changed behavior that patch coverage shows is untested;
- the title and body are current.

GitHub `CLEAN`, zero threads, or green CI alone is not readiness. Do not describe a head as reviewed if it was not.

Merge authority:

- Implementing agents report readiness and never merge, enable auto-merge, or close PRs.
- The Overseer may merge a specific product PR only on a traceable, explicit human instruction to merge that PR, after checking the criteria above.
- Standing exceptions, for the Overseer or merge-train operator only: separately authorized FireController and private-website maintenance merges, and separately authorized closing of a PR verified as superseded, after recording its replacement and checking it preserves the intended work.
- Merging or closing a PR never removes it from the FireController queue. Queue removal needs explicit human authorization and a recorded reason.

## Change And Merge Policy

- Prefer the fewest PRs that stay reviewable. Group related or small changes, even across domains, because every extra PR costs a review and reconciliation. Split only when a combined PR would be hard to review or would mix unrelated risk, such as a risky runtime change with unrelated routine docs. State the grouped scope in the PR Summary; that stated purpose covers everything grouped, and Finding Triage judges Fix now against it.
- Hosted CodeRabbit has a hard 100 changed-file limit. Plan feature PRs at around 80 files to leave room for fixes. A skip caused by the limit is not review coverage: restructure into coherent PRs or report the blocker. Only the human can waive Hosted review for a specific oversized PR, and only when verified substantial CodeRabbit coverage of its feature-bearing work already exists; record the waived request as unrun, and it earns no completed-review credit. CLI or model review never substitutes for a required Hosted review.
- A file limit changes how work is split, never what is correct. Do not drop needed changes, move code to the wrong owner, or mix unrelated risk to fit under it.
- The orchestrator may split, add a prerequisite or successor PR, or retarget its own PRs within its approved workstream without fresh approval, outside an active Hosted review. When a finding or proof moves to another PR, note the successor in both PR bodies and in the finding's disposition, and check whether the successor already fixes it. If a child PR supplies behavior the parent requires, the parent stays not ready until the child lands: base the child on `develop` as an independent prerequisite, or merge it into the parent branch and revalidate the parent. Bring a markedly more fragmented topology, or an unclear owner, to the Overseer.
- Failing Renovate PRs are maintenance: push the smallest compatible fix to the Renovate branch, or use a replacement branch when needed. Kubectl, Helm, and Velero CLI updates change exact versions with publisher checksums; Velero runtime image and Terraform Helm updates stay digest-pinned and grouped with the CLI release.

## Branch And PR Hygiene

- Open or update a PR when a branch reaches a coherent checkpoint so CI can run. PRs target `develop` unless stacked. Draft PRs may expose work in progress. Do not open placeholder PRs.
- Add every PR you open to the FireController queue when it opens, drafts included (not Renovate or throwaway proof PRs).
- Take a PR out of draft, or call it ready, only after a real CI run on its exact head is green; a re-run that skipped jobs does not count. Leaving draft starts reviews. PRs higher in a stack may be worked and reviewed in parallel.
- Every PR body, draft included, follows the [PR template](../../.github/pull_request_template.md) headings. The [PR LOC Report](../../.github/workflows/pr-loc-report.yml) workflow owns the generated LOC section: keep the `firemud:cloc-report` marker block intact, never write counts by hand, and avoid needless body or title edits, since each human or agent edit starts metadata-only gate runs. If the section is stale, re-run the latest LOC run once no Hosted review is active.
- Stacked PRs are fine when the base dependency is explicit. After a parent merges, retarget the child to `develop` before rebasing or pushing it.
- When active work moves to another worktree, report its absolute path and branch.
- CI coverage for stacked bases, required gate names, and preview restrictions are owned by [CI/CD](../architecture/system-architecture-cicd.md#pull-request-runtime-and-preview-chain).
- Use `dev-tools/validation/report-worktree-pr-topology.sh` for branch, worktree, and PR inventory (`--pr <number>` for one stack, `--json` for machine output, `--include-renovate` to include Renovate PRs).
- After a merge, remove the defunct local worktree and merged branches only after confirming nothing open depends on them. Keep unmerged branches.
- Pass PR-body Markdown through a file or stdin, not literal `\n` strings.
