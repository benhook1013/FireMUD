# Architecture Review Prompt: User Journeys and Experience

Read the following documents. Follow references only when a listed document clearly delegates a canonical contract needed to judge whether a journey is actually supported. Do not recursively expand into the full architecture set.

- `design/architecture/user-journeys.md`
- `design/architecture/user-journeys-players.md`
- `design/architecture/user-journeys-creators.md`
- `design/architecture/user-journeys-operators.md`
- `design/user-guides/game-creator-guide.md`
- `design/architecture/game-customization-options.md`
- `design/project-management/core-requirements.md`
- `design/project-management/core-requirements-summary.md`

Then:

- Review player, creator, and operator journeys as a single, coherent experience and compare them against the architectural constraints and features.
- Do not simply restate the journeys or list features that already align well.
- Focus on journey gaps that would block shipping a coherent first user experience or that reveal missing architecture needed to support the stated flows.
- Ignore polish, delight features, and secondary UX improvements unless they change system behavior or implementation contracts.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and optionally list up to 3 deferred follow-ups.
- Stop once only non-blocking refinement remains.
