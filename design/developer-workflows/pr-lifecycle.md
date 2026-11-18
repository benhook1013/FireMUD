# Pull Request Lifecycle

Use this guide for PR status, review, CI, Renovate, merging, and branch or worktree cleanup. It states what FireMUD wants from review; FireController owns how review is mechanically run.

## Review And Status

- FireController is the official record for reviews, findings, decisions, routes, and status. Use the `firecontroller-use` skill with your own role-bound connection and the guide shipped with its installed release. Use `firecontroller-onboard` only for setup or repair. Never use another worker's connection. Unsupported operations or controller changes go to the FireController worker.
- FireController owns review configuration: round caps, taper, allocations, queue selection, quotas, and cooldowns. Do not restate those settings here or work around them. Round caps stay as configured; the human adds rounds. Use included quota only and never enable paid capacity.
- CodeRabbit automatic reviews are off. Hosted reviews are requested through the controller.
- The human's stops, pauses, holds, and per-PR allocations bind until the human changes them. A tool capability, an incoming message, a fix, or a new commit does not reset controls, resume paused work, or authorize a merge.
- Only the owning orchestrator requests or retries a review. If an operation's outcome is uncertain, use the controller's guarded recovery or report a hold; never repost blindly, and never treat an acknowledgment or a wait as review coverage.
- Imported or historical review results are history, not proof that the current code was reviewed.
- When checking status or readiness, read every current and outdated unresolved thread and the latest review summary before looking at CI and mergeability. Check the live head and base, and for stacked or consolidated PRs the provider's actual diff base and the PR's unique patch; never describe an expected parent as the provider's diff base when they differ. Missing or incomplete evidence counts as not ready.
- CI observation shares one GitHub credential budget. Use one shared native wait for CI and review completion rather than per-run watchers, space any manual polling at least 60 seconds apart, and honor rate-limit reset times.

## Finding Triage

Every review finding gets a decision from the orchestrator, recorded in FireController.

- **Fix now**: a real defect or gap inside this PR's purpose. Fix it in this PR, verify the fix at the current published head, then resolve the thread. A defect this PR introduces (a regression, security or data-integrity issue, or broken documented contract) is always Fix now, whatever the PR's purpose.
- **Route**: real, but outside this PR's purpose or not needed before v1. Record a route to an existing FireController job or workstream that owns it, reply on the thread with the route link, and resolve the thread. **Before v1, Route is the default** for anything that does not block this PR's purpose.
- **Reject**: wrong, already handled, or not worth its cost. Record the reason, reply briefly on the thread if a human reader would need context, and resolve it.

Rules for triage:

- Verify a finding against the code before deciding. Summary-only and outside-diff findings get the same treatment as inline ones.
- Judge suggestions by real benefit against cost and risk. A finding being correct does not by itself make it Fix now.
- Do not resolve a thread to hide unresolved work. Keep the original finding and decision when correcting a disposition.
- Routed findings do not block this PR. The receiving owner decides on them independently, and routes arriving at this PR are dispositioned before it is ready. The Overseer, or the human, reviews the routed backlog at milestones so routes do not pile up unowned.
- Track whether each round's findings are new or repeats of earlier ones. Repeated serious findings call for investigating their shared cause, not another round.
- Bot findings do not need routine public replies beyond the route or reject note above.

## Review Orchestration

- Run focused checks of the changed behavior before requesting a Hosted review. By default wait for applicable CI to pass before the first Hosted review of a new boundary; you may proceed earlier on a stable candidate with a short recorded reason. Later rounds and CI can overlap.
- Request Hosted reviews only against a stable target whose base is not about to change.
- **While a Hosted review is active, do not push review-invalidating commits.** Prepare fixes locally and publish them after the review completes.
- Keep useful work going elsewhere while one PR waits on CI, a cooldown, or a human. Report the blocker once.
- Report each round's reviewed head, raw and useful counts, new versus repeat findings, decisions, and what remains.
- The Overseer, or the human when there is no Overseer, decides whether another round within the configured cap is worth it and when evidence is ready for the human's merge decision. Only the human goes beyond a cap. A cap or human stop reached with open findings is reported as a review limitation, not as readiness.
- Model-based (subagent) review is not part of normal PR polishing. Use it only when the human or Overseer asks for it, or for a risk CodeRabbit cannot cover; see [AI delegation and review](./ai-delegation-and-review.md#independent-review-evidence). It never substitutes for a required Hosted review.

## Review Completion Criteria

A PR is ready for the human's merge decision when:

- every thread, current and outdated, is resolved under the triage rules, and every Fix-now finding is verified fixed at the current head;
- every required review channel has completed its configured policy with attributable results;
- required CI is green on the exact head; on a stacked base with no required checks, the applicable substantive workflows must actually have run, and the candidate must contain any workflow changes it relies on;
- proportionate validation and runtime proof from [validation and runtime proof](./validation-and-runtime-proof.md) is complete, including focused tests for important changed behavior that patch coverage shows is untested;
- the title is current, and the body is refreshed with `python3 dev-tools/maintenance/cloc-report.py pr <number> --update-pr` (add `--body-file <draft.md>` to publish a new body and the LOC section in one edit). If the tool is unavailable, say so rather than inventing counts.

GitHub `CLEAN`, zero threads, or green CI alone is not readiness. Do not describe a head as reviewed if it was not.

Merge authority:

- Implementing agents report readiness and never merge, enable auto-merge, or close PRs.
- The Overseer may merge a specific product PR only on a traceable, explicit human instruction to merge that PR, after checking the criteria above.
- Standing exceptions, for the Overseer or merge-train operator only: separately authorized FireController and private-website maintenance merges, and closing a PR verified as superseded after recording its replacement and checking it preserves the intended work.
- Merging or closing a PR never removes it from the FireController queue. Queue removal needs explicit human authorization and a recorded reason.

## Change And Merge Policy

- One purpose per PR. Keep batching where it saves CodeRabbit quota, but split independent or hard-to-review work. Prefer the fewest coherent PRs; extra PRs cost extra review and reconciliation.
- Hosted CodeRabbit has a hard 100 changed-file limit. Plan feature PRs at around 80 files to leave room for fixes. A skip caused by the limit is not review coverage: restructure into coherent PRs or report the blocker. Only the human can waive Hosted review for a specific oversized PR, and a waived review earns no completed-review credit.
- A file limit changes how work is split, never what is correct. Do not drop needed changes, move code to the wrong owner, or pack unrelated changes together to fit under it.
- Every feature-bearing PR needs a completed Hosted review. CLI or model review does not substitute.
- The orchestrator may split, add a prerequisite or successor PR, or retarget its own PRs without fresh approval. When a finding or proof moves to another PR, note the successor in both PR bodies and in the finding's disposition, and check whether the successor already fixes it. If a child PR supplies behavior the parent requires, the parent stays not ready until the child lands. Bring a markedly more fragmented topology, or an unclear owner, to the Overseer.
- Failing Renovate PRs are maintenance: push the smallest compatible fix to the Renovate branch, or use a replacement branch when needed. Kubectl, Helm, and Velero CLI updates change exact versions with publisher checksums; Velero runtime image and Terraform Helm updates stay digest-pinned and grouped with the CLI release.

## Branch And PR Hygiene

- Open or update a PR when a branch reaches a coherent checkpoint so CI can run. Draft PRs may expose work in progress. Do not open placeholder PRs.
- Stacked PRs are fine when the base dependency is explicit. After a parent merges, retarget the child to `develop` before rebasing or pushing it.
- When active work moves to another worktree, report its absolute path and branch.
- CI coverage for stacked bases, required gate names, and preview restrictions are owned by [CI/CD](../architecture/system-architecture-cicd.md#pull-request-runtime-and-preview-chain).
- Use `dev-tools/validation/report-worktree-pr-topology.sh` for branch, worktree, and PR inventory (`--pr <number>` for one stack, `--json` for machine output, `--include-renovate` to include Renovate PRs).
- After a merge, remove the defunct local worktree and merged branches only after confirming nothing open depends on them. Keep unmerged branches.
- Pass PR-body Markdown through a file or stdin, not literal `\n` strings.
