# Architecture Review Prompt: User Journeys and Experience

Read the following documents. Follow references and read nearby related files as required when a listed document clearly delegates a canonical contract or when a closely related file is needed to judge whether a journey is actually supported. Do not recursively expand into the full architecture set.

- `design/architecture/user-journeys.md`
- `design/architecture/user-journeys-players.md`
- `design/architecture/user-journeys-creators.md`
- `design/architecture/user-journeys-operators.md`
- `design/user-guides/game-creator-guide.md`
- `design/architecture/system-architecture-game-customization.md`
- `design/project-management/core-requirements.md`

Then:

- Review player, creator, and operator journeys as a single, coherent experience and compare them against the architectural constraints and features.
- Check each finding against the relevant domain tracker and implementation, in case the issue has already been resolved in code or tracking and the design now needs to import that decision back into the docs.
- Do not simply restate the journeys or list features that already align well.
- Focus on journey gaps that would block shipping a coherent first user experience or that reveal missing architecture needed to support the stated flows.
- If trackers, protos, or current implementation already resolve the seam but the design docs are stale, classify the issue as "import resolved decision back into design" rather than as an unresolved architecture blocker.
- Do not let polish, delight features, and secondary UX improvements crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the shipped experience or its supporting contracts.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
