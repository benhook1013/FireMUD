# Architecture Review Prompt: Cross-Service Contract Consistency Review

Review the current FireMUD branch for cross-service contract consistency across gRPC, REST, events, Redis-backed runtime handoff, and shared identity or ownership seams.

Context:

- Repo: `/home/ben/src/FireMUD-wsl-copy`
- Read `AGENTS.md` first and follow it as canonical instructions.
- FireMUD is still in initial development.
- Many service boundaries are already implemented, and the goal is to catch places where adjacent services appear to integrate but still disagree about contract shape, authority, naming, ownership, or lifecycle semantics.

What to look for:

- request/response contracts that describe the same concept differently across services
- duplicated authority where more than one service appears to own the same truth
- mismatched naming or identity shapes for tenants, accounts, sessions, characters, worlds, regions, items, or runtime instances
- incompatible assumptions around status values, lifecycle states, or terminal outcomes
- one-sided contract evolution where a producer and consumer no longer match cleanly
- gRPC application-error handling that is inconsistent across neighboring services
- REST and gRPC surfaces that represent the same workflow differently without an intentional reason
- Redis/runtime handoff contracts that are only implicitly agreed rather than explicitly modeled
- places where docs, service-status notes, and implementation disagree about which service owns a boundary
- nearby related inconsistencies that would cause different teams or future slices to implement different behavior

What I want in the output:

1. Findings first, ordered by severity
2. Focus on real contract mismatches, duplicated authority, ownership confusion, and integration seams that can drift or break quietly
3. Include concrete file references
4. Distinguish:
   - fix now
   - fix soon
   - design follow-up
5. Call out whether each finding is mainly about:
   - contract mismatch
   - duplicated authority
   - identity-shape drift
   - lifecycle/state mismatch
   - undocumented integration seam
   - inconsistent error model
6. Prefer high-signal findings over broad summaries

Constraints:

- Default to static review unless a small targeted test/run materially helps confirm a concern
- Do not make code changes unless explicitly asked
- Do not spend time re-explaining already accepted slice docs unless it directly supports a finding
- Keep the review focused on service boundaries rather than generic local code hygiene
- Record reusable lessons in `design/project-management/ai-observations.md` if you discover them

Helpful framing:

- Assume the goal is one canonical contract per cross-service seam, not “close enough” parallel interpretations
- Be skeptical of integrations that currently work only because both sides happen to share undocumented assumptions
- Prefer findings that would otherwise cause future slices, services, or operators to rely on the wrong source of truth
- Review across multiple services and their shared docs, not just one service in isolation
