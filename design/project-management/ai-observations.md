# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, code smells, and "this should be shaped better" patterns discovered during AI work.

Entry dates use Pacific/Auckland unless stated otherwise.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work. Prefer logging reusable observations that suggest a better repo rule, CI guard, design refinement, or shared implementation pattern.

During ordinary autonomous work, this file is append-only: add dated reusable observations, and do not silently rewrite or delete prior entries. Workers should append reusable observations, helpers should send candidate entries to the owning worker, and ordinary bugs fixed in the same work should not become entries. Explicitly assigned Overseer stewardship may consume entries under the bounded process in [AI delegation and review](../developer-workflows/ai-delegation-and-review.md); a human-requested [repository health check](../developer-workflows/repository-health-check.md) separately authorizes the worker to remove an entry only after evidence shows that it is addressed, obsolete, or disproved. Retain an entry only when a genuine blocker or deliberate postponement remains, recording the reason and reconsideration trigger. A health-check pass should reduce this inbox toward zero.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-09-09`: Bind cert-manager Secret bytes to durable issuance evidence
  - Context: a hosted identity controller initially treated a ready Certificate revision and a separately read TLS Secret as one issuance snapshot during renewal.
  - Observation: cert-manager writes the Secret before advancing Certificate status, so independent reads can combine different revisions; Ready status and Secret metadata do not prove that the observed certificate bytes belong to the recorded revision.
  - Expected pattern: select the uniquely owned CertificateRequest for the ready revision, require its issued certificate bytes to match the Secret exactly, carry those validated bytes forward, and re-read the Certificate snapshot before acceptance. Treat absent or ambiguous issuance evidence as retryable and remember that `CertificateRequest.status.ca` is optional.
  - Current status: [PR #2853](https://github.com/benhook1013/FireMUD/pull/2853) owns the source-side TLS rotation and hosted identity proof work. Its current scope does not prove that live CertificateRequest bytes equal the TLS Secret, that the Ready Certificate snapshot is re-read afterward, or that the served identity uses the rotated certificate; these checks remain an activation gate.
  - Reconsideration trigger: revisit during an authorized activation or renewal observation that can compare the uniquely owned CertificateRequest bytes with the TLS Secret and re-read the Ready Certificate snapshot.

- `2026-09-20`: Workflow-code fixes need an environment-policy cutover for old branches
  - Context: preview deployment secrets were available to a branch-selectable workflow on a persistent self-hosted runner; new workflow code routes privileged jobs through the default branch.
  - Observation: an existing PR branch can retain its old workflow revision after the corrected default-branch workflow merges, and an unrestricted GitHub environment can still release secrets to that old revision.
  - Expected pattern: alongside the code change, restrict privileged GitHub environments to the trusted deployment branch and clear or rotate credentials exposed by old runner state; verify the live environment policies before declaring the trust boundary effective.
  - Current status: The live `trusted-hosted-cluster` Environment allows only `develop`; its environment-scoped secret-name list is empty, while repository secrets still include legacy `PREVIEW_KUBECONFIG`. The scoped credential bootstrap/finalization, token rotation, legacy binding removal, and old-identity RBAC denial proof remain unverified.
  - Reconsideration trigger: before the next privileged deployment or declaring cutover complete, an authorized operator should provision and verify the canonical scoped credentials, finalize removal and revocation of the legacy credential and binding, and prove the old identity cannot read Secrets, create CertificateRequests, or change an Issuer. Secret-name presence or absence alone is not rotation or RBAC proof.

- `2026-09-30`: Waiter handoffs must separate terminal outcomes from later eligibility
  - Context: a #2909 Hosted sentinel withheld an already-terminal rate-limit response until its reset time, delaying publication of prepared fixes by about fifteen minutes. A later CLI sentinel could not access the parent's native tool session and correctly reported `Unknown process id` instead of claiming a review was being observed.
  - Observation: provider completion and permission to retry are separate events; native tool-session handles are caller-scoped in this harness and cannot be assumed transferable to a subagent.
  - Expected pattern: deliver terminal response identity, exit status, and any authoritative reset immediately, before a separate cooldown wait. Keep a native process with its owning caller, or bind a read-only sentinel to the verified OS process and exact capture; never launch another review merely to recover observation.
  - Outcome: terminal reporting was corrected to surface completion before later eligibility; caller-scoped native process handles remain unavailable to a separate sentinel.
  - Reconsideration trigger: revisit if terminal outcomes are delayed again or during authorized observation stewardship of cross-caller process ownership; do not relaunch a review just to recover a handle.

- `2026-09-30`: Separate request preparation latency from provider review duration
  - Context: the canonical Hosted request for #2909 at `e39956a56` spent about five minutes in its operator process before returning the posted trigger `5919007416`; its eventual provider response was separately rate-limited.
  - Observation: recorded provider durations exclude preparation, so they cannot explain the whole cadence. The exact slow command phase is not established; neither a silent process nor elapsed time proves a deadlock or an active provider review.
  - Expected pattern: measure preparation phases before optimizing and retain independent live head, ancestry, request, and cooldown safety checks. Do not replace live cooldown verification with an unsupported age cutoff or a stored terminal label.
  - Outcome: the canonical request posted without bypassing safety; the authoritative retry deadline remains owned by one silent timer sentinel. No speculative performance rewrite was added.
  - Reconsideration trigger: use a bounded timing investigation when preparation repeatedly threatens eligible review windows or an authorized controller performance pass is undertaken.

- `2026-10-02`: Docs hash lock automation waits on stable pip-compile headers
  - Context: Renovate's open docs dependency updates changed pins in the hashed requirements lock without regenerating its hash artifacts; a new `config/docs/requirements.in` now records the two direct documentation roots.
  - Observation: pip-tools 7.6.1 in an isolated generation check emitted an unsupported `--no-index` option in its header unless `CUSTOM_COMPILE_COMMAND` recorded the effective indexed command. Renovate's pip-compile artifact updater reuses header arguments but does not pass that environment override during regeneration.
  - Expected pattern: Keep generating the full hashed lock from its `.in` source and preserve package/hash integrity; do not enable Renovate pip-compile updates until ordinary header regeneration and the manager's next-cycle parsing are verified. Do not hand-edit hashes.
  - Current status: Bot automation is deferred because the observed generator header is not safely round-trippable through the current manager path.
  - Reconsideration trigger: Revisit when an upstream pip-tools or Renovate fix produces and accepts a standard header through a complete regeneration cycle.
