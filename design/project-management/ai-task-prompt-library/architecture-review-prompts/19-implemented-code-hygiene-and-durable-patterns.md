# Architecture Review Prompt: Implemented Code Hygiene and Durable Patterns

Best used for:

- reviewing framework, lifecycle, transaction, persistence, RPC, and configuration patterns in already-implemented code

Read the following sources first. Follow references only when a listed doc clearly delegates a canonical contract needed to judge a finding. Then inspect the concrete code paths implicated by the docs and current branch state.

- `design/architecture/system-architecture-overview.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/microservices/game-session-service/README.md`
- `design/architecture/microservices/entity-management-service/README.md`
- `design/architecture/microservices/world-management-service/README.md`
- `design/project-management/service-status-game-session-service.md`
- `design/project-management/service-status-entity-management-service.md`
- `design/project-management/service-status-world-management-service.md`

Review the current FireMUD branch for code hygiene, consistency, and durable implementation patterns, especially in Spring, Spring Boot, Spring Data, and gRPC usage.

Context:

- Repo: `/home/ben/src/FireMUD-wsl-copy`
- Read `AGENTS.md` first and follow it as canonical instructions.
- FireMUD is still in initial development.
- Many documented slices have now been implemented.
- I want review attention on cleanup, correctness, maintainability, and pattern quality before the codebase gets larger.

What to look for:

- Spring or Spring Boot best-practice issues
- bad transaction boundaries
- blocking behavior in reactive code
- bad dependency injection or configuration patterns
- poor lifecycle or autoconfiguration choices
- risky persistence or Flyway usage
- Redis keying or atomicity issues
- gRPC contract misuse
- security boundary mistakes
- misleading metrics or logging
- tenant, identity, or ownership invariant leaks
- places where current code is technically working but setting a bad long-term pattern
- any nearby related issues you notice while reviewing the implemented functionality

What I want in the output:

1. Findings first, ordered by severity
2. Focus on real bugs, correctness risks, scaling risks, security issues, and bad architectural patterns
3. Include concrete file references
4. Distinguish:
   - fix now
   - fix soon
   - refactor/hygiene
5. If you notice an issue outside the exact area you started from but clearly related, include it
6. Prefer high-signal findings over broad summaries

Constraints:

- Default to static review unless a small targeted test/run materially helps confirm a concern
- Do not make code changes unless explicitly asked
- Do not spend time re-explaining implemented slice docs unless it directly supports a finding
- Record reusable lessons in `design/project-management/ai-observations.md` if you discover them

Helpful framing:

- Assume the goal is to lock in strong patterns early
- Be skeptical of "works for now" solutions that will become expensive later
- Call out consistency problems across services, not just isolated bugs
- Prefer framework, lifecycle, persistence, transaction, RPC, and configuration pattern issues over broader canonical-substrate debates unless the implementation pattern itself is the problem
