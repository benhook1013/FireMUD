# Architecture Review Prompt: Per-Service Deep Dive Template

Use this template to review any individual microservice in the context of the overall architecture.

First, gather and read. Follow references only when the target service docs clearly defer a canonical contract needed to resolve a contradiction or an implementation-blocking gap. Do not recursively traverse unrelated docs.

- The main architecture overview documents you need for context, such as:
  - `design/architecture/system-architecture-overview.md`
  - `design/architecture/system-context-diagram.md`
  - `design/architecture/service-responsibility-matrix.md`
- The service-specific docs for the target service, for example:
  - `design/architecture/microservices/<service-name>/README.md`
  - Any additional design docs under `design/architecture/microservices/<service-name>/`
  - Any related system-architecture docs that describe this service’s protocols, data, or operations.

Then:

- Review the chosen service’s responsibilities, interfaces, and data flows in the context of the whole system.
- Do not summarize the service or describe what is already working well.
- Focus on issues that would cause implementers of this service or its neighbors to make incompatible decisions.
- Do not let non-blocking cleanup, local wording improvements, and future enhancement ideas crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements, whether small or substantial.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
