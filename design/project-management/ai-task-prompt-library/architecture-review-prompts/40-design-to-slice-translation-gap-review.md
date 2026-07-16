# Architecture Review Prompt: Design-to-Slice Translation Gap Review

Best used for:

- reviewing whether major designed or implemented domains are still missing, underrepresented, or misleadingly tracked in the slice system

Review FireMUD's current design, slice planning, and implementation-tracking docs to find important architecture domains that are missing, underrepresented, or misleadingly represented in the slice system.

Context:

- Repo: `/home/ben/src/FireMUD-wsl-copy`
- Read `AGENTS.md` first and follow it as canonical instructions.
- FireMUD uses vertical slices to translate broad architecture into implementation-directed work.
- The goal is not to review one feature or one service. The goal is to check whether the tracked slice system still reflects the actual designed system and current implementation reality.

Read the following sources first. Follow references only when a listed doc clearly delegates a canonical contract needed to judge whether a domain is missing, under-sliced, or misleadingly tracked. Do not reread the entire design tree unless the listed docs clearly require it.

- `design/architecture/system-architecture-overview.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- `design/architecture/system-architecture-frontend.md`
- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-backup-recovery.md`
- `design/project-management/implementation-tracking/README.md`
- all ten domain trackers listed by that index

What to look for:

- first-class architecture domains that are missing from the slice system entirely
- major design areas that exist in architecture docs but only appear indirectly or fragmentarily in current slices
- domains that are implemented enough to be real platform areas but still do not have coherent slice coverage
- areas where the slice docs imply a maturity level that does not match the service-status docs or obvious implementation reality
- overlapping slices that should be merged or reframed because they are tracking the same domain awkwardly
- top-level areas that should be split into clearer child slices because the current slice family is too broad to guide implementation cleanly
- tracking docs that disagree with one another about what is active, implemented, blocked, or still only designed
- places where broad architecture has drifted ahead of tracked delivery work in a way likely to hide future implementation gaps

What I want in the output:

1. Findings first, ordered by severity
2. Focus on real tracking or decomposition problems, not generic architecture criticism
3. Include concrete file references
4. Distinguish:
   - fix now
   - fix soon
   - follow-up slice pass
5. Call out whether each finding is mainly about:
   - missing slice family
   - under-sliced design domain
   - implemented-but-undertracked area
   - misleading progress signal
   - overlapping or awkward slice decomposition
   - tracking-doc inconsistency
6. Prefer high-signal planning gaps over broad summaries

Constraints:

- Default to review and doc/planning analysis only unless explicitly asked to edit the slice docs
- Do not make code changes unless explicitly asked
- Do not spend time re-reviewing already-solid low-level slices if the real issue is at the domain or tracking level
- Keep the review grounded in whether the slice system is usable for directing future implementation
- Record reusable lessons in `design/project-management/ai-observations.md` if you discover them

Helpful framing:

- Assume the goal is for slice planning to be a trustworthy map from design to implementation
- Be skeptical of areas that are “known in architecture” but not clearly visible in the slice queue
- Distinguish clearly between:
  - designed but not sliced
  - sliced but barely implemented
  - implemented but poorly reflected in slices
  - fully translated and actively tracked
- Prefer recommendations that make the slice system easier to use as a real planning surface rather than a passive document set
