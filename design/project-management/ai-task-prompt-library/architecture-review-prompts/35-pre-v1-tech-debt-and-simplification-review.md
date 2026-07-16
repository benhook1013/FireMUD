# Architecture Review Prompt: Pre-v1 Tech Debt and Simplification Review

Best used for:

- reviewing an already-implemented codebase for architectural drift, unnecessary backwards-compatibility, pre-v1 baggage, and AI-generated incoherence that should likely be simplified or deleted rather than preserved

Read the following sources first. Follow references only when a listed doc clearly delegates a canonical contract needed to judge a finding. Then inspect representative production code in the most important or central areas so the review is grounded in real implementation, not just repository structure or docs.

- `design/architecture/system-architecture-overview.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/repository-structure.md`
- `design/architecture/microservices/README.md`
- `design/project-management/implementation-tracking/README.md` and the relevant domain trackers

Review the current FireMUD branch for high-leverage architectural debt, unneeded backwards-compatibility, and pre-v1 simplification opportunities.

Context:

- Repo: `/home/ben/src/FireMUD-wsl-copy`
- Read `AGENTS.md` first and follow it as canonical instructions.
- FireMUD is still pre-v1 / initial development.
- We do not need to preserve migrations, compatibility layers, transitional code paths, legacy adapters, or upgrade scaffolding unless they are clearly still necessary right now.
- A large amount of the codebase was AI-assisted, so the review should actively look for inconsistency, overengineering, duplicated architectural patterns, shallow abstractions, and areas where the codebase no longer feels coherent.
- This is not primarily a framework best-practice review, test review, or narrow cross-service contract audit. Use the dedicated prompts for those when needed.

How to work:

- Keep a running scratchpad in a temp file outside the repo and continuously write notes to it while reviewing so context survives compaction.
- Do not stay purely at the docs or directory-structure level.
- Go low enough into representative production code to validate that the findings are real.
- Keep the final output high-level and selective: prefer the highest-value issues over a broad catalog.
- Sample representative code in the heaviest, most central, or most architecturally important areas rather than pretending to do an exhaustive full read.

What to look for:

- major architectural or structural tech debt
- unneeded backwards-compatibility or pre-v1 baggage:
  - migration buildup that should probably be squashed
  - dual-path behavior
  - compatibility shims
  - transitional adapters
  - legacy-preserving structure
  - old and new models coexisting without a strong reason
- high-level coherence problems:
  - service boundaries that no longer make sense
  - ownership that is blurred or duplicated
  - orchestration concentrated in the wrong layer
  - unnecessary indirection
  - repeated wrapper or adapter patterns
  - competing abstractions for the same concern
  - control-plane or domain flows that have become harder to understand than they should be
- AI-heavy codebase failure patterns at the architectural level:
  - repeated boilerplate with slight variations across services
  - inconsistent dependency structure
  - unnecessary layering
  - testability hacks leaking into production design
  - redundant DTO, client, mapper, or wrapper layers
  - abstractions that exist mainly because they were easy to generate, not because they improve the design
  - production code shaped around convenience rather than a clean canonical model

What I want in the output:

1. Findings first, ordered by severity or leverage
2. Focus on the most important architectural and structural findings, not an exhaustive list
3. Include concrete production-code file references
4. For each finding, include:
   - the issue
   - why it matters
   - whether it is mainly:
     - tech debt
     - backwards-compatibility debt
     - architectural drift
     - AI-pattern inconsistency
   - whether the right direction is:
     - incremental refactor
     - aggressive pre-v1 simplification or reset
     - both
5. After the findings, provide:
   - the top architectural simplification targets
   - the top things that should probably be deleted, collapsed, or reset because this is pre-v1
6. Prefer deletion, collapse, reset, or simplification opportunities over generic refactor suggestions

Constraints:

- Default to static review unless a small targeted command materially helps confirm a concern
- Do not make code changes unless explicitly asked
- Do not focus on tests except where a production design issue is only visible because production code was shaped to support tests
- Do not focus on documentation or process debt
- Do not produce a cleanup plan yet; identify and frame the issues clearly first
- Be critical and opinionated. Do not soften real simplification opportunities into vague “maybe refactor later” language

Helpful framing:

- Assume pre-v1 simplification is usually better than preserving history
- Be skeptical of code that keeps old and new paths alive “just in case”
- Prefer one clean canonical model over transition-heavy intermediate structures
- Look for places where AI-assisted implementation added local structure without improving the system
- Prefer high-signal findings that would meaningfully simplify the repo or reduce future drift

If useful, quantify patterns such as:

- very large production classes
- migration counts per service
- duplicated client or wrapper layers
- repeated endpoint or service shells
- repeated helper abstractions or compatibility branches
