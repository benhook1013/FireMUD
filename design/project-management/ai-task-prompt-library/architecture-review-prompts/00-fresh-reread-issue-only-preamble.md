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
- Report only implementation-blocking issues by default.
- If there are no implementation-blocking issues, say that explicitly.
- Optionally include a short `Deferred follow-ups` section with at most 3 non-blocking items.
- For each blocking issue, include:
  - `Severity`: `blocking`
  - `Why it blocks implementation`
  - `Docs involved`
  - `Suggested decision or spec change`

Stop condition:

- End the review once you have either:
  - identified the remaining implementation-blocking issues, or
  - concluded that no implementation-blocking design issues remain.
