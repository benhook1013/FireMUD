# FireMUD AI Contributor Notes

This file is the always-on project, authority, and safety kernel for AI work. Repository documentation and scripts are the system of record. Use the linked workflow guides only when their trigger applies.

## Authority And Orientation

- Read [repository structure](design/architecture/repository-structure.md) when locating a concern; read [system architecture](design/architecture/system-architecture-overview.md) before changing shared contracts or runtime behavior; read [infrastructure](design/architecture/infrastructure/README.md) for deployment, gateway, protocol, environment, or preview work.
- For service-scoped work, read the matching documentation under `design/architecture/microservices/<service>/` before inferring behavior from another service.
- Architecture and design documents own target-state technical contracts. Implementation trackers record capability, code, and proof status; code and tests demonstrate the implemented boundary. Do not make `AGENTS.md` the authority for a technical design rule.
- Before declaring a slice complete, verify the claimed boundary across its public contract, implementation, and focused proof. Completion reports must distinguish confirmed proof from unrun, partial, or unavailable validation.

## Working Tree Safety

- Expect concurrent and dirty worktrees. Preserve unrelated edits and work in scope; do not stop merely because unrelated files changed.
- Do not run `git restore`, `git checkout`, `git reset`, `git clean`, or `git stash` unless a human explicitly requests that action. Delete an untracked file only when it is unquestionably disposable and in scope; temporary-looking names or lint failures are not sufficient.
- Edit a modified target in place. If overlapping changes make intent unclear or risky, ask the human. Never revert or clean up unrelated work; changes produced by the repository's canonical automatic formatters are allowed, but preserve unrelated semantic edits.
- Open and update coherent pull requests autonomously for active implementation work so CI and review can run; use clearly based stacked PRs when that keeps work moving without mixing unrelated changes. Merge or close pull requests only with explicit human authorization.

## Development And Documentation

- FireMUD is pre-v1: converge directly on one canonical state rather than preserving obsolete schemas, APIs, routes, or compatibility scaffolding. Widen a slice when the same invariant spans services and a broader pass is cheaper and clearer.
- Direct convergence remains qualified by live-data retention, security, and protocol constraints documented by the owning architecture. When a contract changes, update its call sites, tests, and documentation together and remove obsolete paths.
- Do not seek approval merely because work becomes cross-service, breaking, or larger when the canonical design is clear. Stop for genuine design ambiguity, competing target states, or changes to consequential accepted decisions.
- For implementation or branch reconciliation, read the owning implementation tracker before interpreting branch names, commits, or raw diffs. Proactively repair nearby in-scope drift when practical.
- Prefer batches that measurably advance an owned capability, close a declared gap, or strengthen its focused proof rather than accumulating seams that leave tracked capability state unchanged.
- Keep design docs target-state first. Put partial implementation status near the top in a dedicated section; document one canonical current behavior and remove obsolete transitional guidance unless history is requested.
- Mark an AI-authored ADR `Proposed - Pending Human Review` with pending human-review metadata. An agent may record a completed human review only when a checked consequential-decision queue entry links the exact `[ADR NNNN]` outcome and the ADR metadata matches the queue's aggregated review date, disposition, and source decision keys. A checked reference alone establishes review eligibility, not acceptance. Reversible work may continue only when existing canonical design supports it and the work does not rely on the proposal as accepted.
- Do not manually hard-wrap documentation. Use GitHub-compatible relative links without line suffixes, and do not use emojis in Markdown headings.

## Execution Basics

- Use Gradle task paths without a `services:` prefix, for example `./gradlew :tcp-proxy-service:test`. Run heavier Gradle work from WSL in this repository and use the default daemon unless debugging it. Prefer native Linux Docker against `unix:///var/run/docker.sock` over Windows wrappers.
- Prefer standard CLI tools for routine inspection. `gh` and `python3` are available and may be used when requested.
- Record reusable process, tooling, environment, or design lessons in [AI observations](design/project-management/ai-observations.md). Append dated entries; do not log one-off fixes or rewrite prior observations without a requested cleanup.

## Conditional Workflows

- PR status, CodeRabbit, CI, Renovate, merge authorization, branch topology, and post-merge cleanup use [PR lifecycle](design/developer-workflows/pr-lifecycle.md).
- Every code or documentation change uses [validation and runtime proof](design/developer-workflows/validation-and-runtime-proof.md) to select and report the required formatting and checks; use its runtime sections when the change affects runtime or smoke behavior.
- Subagent selection, delegation boundaries, and independent review use [AI delegation and review](design/developer-workflows/ai-delegation-and-review.md). That linked workflow is authoritative for complete delegation boundaries; keep this root file lean rather than duplicating its full list.
- PR or review status handling starts with unresolved non-outdated review threads and the latest completed review summary, reports outdated unresolved threads separately, and checks CI and mergeability second. Summary-only duplicate and outside-diff findings still require verification. Before calling a review complete or merge-ready, use `python3 dev-tools/validation/check-coderabbit-review.py --repo <owner/repo> --pr <number>`; request a full CodeRabbit review at meaningful checkpoints only after current findings are resolved and no review is active or rate limited.

## Orchestration

The main `gpt-5.6-sol` thread owns planning, human design discussion, task decomposition, integration, validation, and final repository decisions. It does not delegate product or architecture design decisions. Subagent approval is model-specific: `gpt-5.6-sol` may run at medium autonomously, but using it at high requires explicit human approval. Luna reasoning levels are not approval-gated.

- Once the human selects an active workstream, continue through its next unambiguous tracked steps, including implementation, validation, PR publication, and review fixes, without seeking confirmation at every boundary. A progress update is not a stopping point.
- An explicit pause or stop overrides autonomous continuation. Finish only the stated in-progress boundary, publish any promised safe checkpoint, report the state, and do not begin substitute work.
- Use process proportional to the risk and size of the change. Do not create ledgers, audit suites, repeated review machinery, or speculative governance infrastructure for small edits unless a concrete correctness risk requires it.
- Delegate bounded bulk reading, mechanical work, or focused investigation only with a disjoint scope and explicit success conditions.
- Every delegation prompt repeats the authoritative workflow's full prohibition text; omitting it grants no permission.
- Preserve one authority direction: architecture defines target behavior; implementation tracking records status; workflow guidance selects process; the orchestrator evaluates evidence and makes the final decision.
