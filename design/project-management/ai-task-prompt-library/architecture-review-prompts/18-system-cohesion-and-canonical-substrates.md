# Architecture Review Prompt: System Cohesion and Canonical Substrates

Review the current FireMUD branch for system cohesion, canonical-substrate quality, and "proper implementation first" alignment across recently implemented slices.

Context:

- Repo: `/home/ben/src/FireMUD-wsl-copy`
- Read `AGENTS.md` first and follow it as canonical instructions.
- FireMUD is in initial development.
- Many design slices have now been implemented, especially around bootstrap, gameplay routing, account/auth, gateway, and game-session flows.
- The goal is not just bug-finding. The goal is to detect where implemented systems may technically work but are still composed from local glue, duplicated authority, transitional thinking, or mismatched seams.

What to look for:

- places where a design was implemented through multiple local catalogs/configs instead of one canonical substrate
- flows that appear unified at the API/command surface but still diverge underneath
- remnants of single-tenant/single-instance assumptions inside newer realm/multi-tenant flows
- account/identity/membership/admission invariants that are inconsistently modeled across services
- bootstrap, gateway, auth, and runtime paths that should share the same routing truth but do not
- command/UI surfaces that are ahead of the real underlying authority model
- places where current code lands on a local seam instead of the intended shared substrate
- nearby related issues that clearly affect system cohesion or durable architecture

What I want in the output:

1. Findings first, ordered by severity
2. Focus on real correctness risks, architectural mismatches, duplicated authority, and bad long-term implementation patterns
3. Include concrete file references
4. Distinguish:
   - fix now
   - fix soon
   - refactor/hygiene
5. Call out whether each finding is:
   - wrong substrate
   - duplicated authority
   - leaky temporary path
   - contract mismatch
   - consistency problem across services
6. Prefer high-signal findings over broad summaries

Constraints:

- Default to static review unless a small targeted test/run materially helps confirm a concern
- Do not make code changes unless explicitly asked
- Do not spend time re-explaining slice docs unless it directly supports a finding
- Keep a working tracking doc under `design/project-management/` if needed
- Record reusable lessons in `design/project-management/ai-observations.md` if you discover them

Helpful framing:

- Assume the goal is to lock in clean canonical systems early
- Be skeptical of solutions that preserve local shortcuts or partial substrates
- Prefer "is this the right system?" over "does this path currently work?"
- Review across service boundaries, not just within one module
- Prefer substrate/cohesion findings over generic framework or style cleanup unless the implementation pattern directly creates the cohesion problem
