# Architecture Review Prompt: Main Architecture Overview

Read the main architecture overview and structure docs below. Follow references only when one of these documents clearly defers a canonical contract that is necessary to judge a contradiction or an implementation-blocking gap. Do not recursively traverse the whole architecture corpus.

- `design/architecture/system-architecture-overview.md`
- `design/architecture/system-architecture-diagram.md`
- `design/architecture/system-context-diagram.md`
- `design/architecture/repository-structure.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/README.md`
- `design/architecture/microservices/README.md`

Then:

- Evaluate the overall system architecture, service boundaries, and high-level data and traffic flows described in these documents.
- Do not summarize or praise what is already clear or working well.
- Focus on implementation-blocking issues first: missing decisions, contradictory statements between docs, unclear or overlapping service responsibilities, undocumented assumptions, or architecture choices that would cause different teams to implement different behavior.
- Do not try to exhaustively enumerate every possible improvement. Ignore minor wording cleanup, optional future-scale refinements, and speculative edge cases unless they block the first implementation slice.
- Return at most 5 issues, ordered by severity. If there are more than 5, include only the highest-leverage ones.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no blocking issues remain, say `No implementation-blocking issues found.` and optionally list up to 3 deferred follow-ups.
- Stop once only non-blocking polish remains.
