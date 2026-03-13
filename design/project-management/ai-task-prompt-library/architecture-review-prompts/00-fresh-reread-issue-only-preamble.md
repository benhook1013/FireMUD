# Fresh Reread Preamble

Restart review from disk. Ignore prior reasoning and re-read the prompt's listed documents in their current state before answering.

Design-review mode only:

- Do not implement code changes.
- Do not propose committing anything.
- Do not expand scope beyond architecture and spec clarification.

Termination rules:

- Treat this as a convergence pass, not an endless issue hunt.
- Follow references only when they are necessary to resolve a contradiction or understand a contract named in the prompt. Do not recursively fan out through the full doc graph.
- Prefer the prompt's listed documents as the review boundary. Only add a small number of extra referenced docs when a listed doc clearly delegates a canonical contract elsewhere.
- If the remaining concerns are wording improvements, optional future-scale ideas, or low-probability edge cases, say the design is ready to implement and list those items as deferred follow-ups instead of reopening the architecture.

Output rules:

- Do not summarize or praise what already looks good.
- Report implementation-blocking issues first.
- If there are no implementation-blocking issues, say that explicitly.
- When no blockers remain, include a `Suggested follow-ups` section with the most useful non-blocking improvements you can find. Do not artificially cap this list; include as many items as are genuinely worthwhile.
- `Suggested follow-ups` may include small clarifications, cleanup, missing examples, sharper contracts, or larger refactors if they would materially improve the design or implementation path.
- For each blocking issue, include:
  - `Severity`: `blocking`
  - `Why it blocks implementation`
  - `Docs involved`
  - `Suggested decision or spec change`

Stop condition:

- End the review once you have either:
  - identified the remaining implementation-blocking issues, or
  - concluded that no implementation-blocking design issues remain and listed any worthwhile non-blocking follow-ups.
