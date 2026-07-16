# Architecture Review Prompt: Design-to-Domain Tracking Gap Review

Best used for:

- reviewing whether major designed or implemented domains are still missing, underrepresented, or misleadingly represented in the domain implementation trackers

Review FireMUD's current canonical design and domain implementation trackers to find important architecture domains that are missing, underrepresented, or misleadingly represented in implementation tracking.

Context:

- Repo: `/home/ben/src/FireMUD-wsl-copy`
- Read `AGENTS.md` first and follow it as canonical instructions.
- FireMUD uses domain implementation trackers to translate broad architecture into implementation-directed work.
- The goal is not to review one feature or one service. The goal is to check whether domain tracking still reflects the actual designed system and current implementation reality.

Read the following sources first. Follow references only when a listed doc clearly delegates a canonical contract needed to judge whether a domain is missing, underrepresented, or misleadingly tracked. Do not reread the entire design tree unless the listed docs clearly require it.

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

- first-class architecture domains that are missing from the tracker set entirely
- major design areas that exist in architecture docs but only appear indirectly or fragmentarily in current trackers
- domains that are implemented enough to be real platform areas but still do not have coherent tracker coverage
- areas where tracker records imply a maturity level that does not match current implementation reality
- overlapping tracker scopes that should be merged or reframed because they track the same domain awkwardly
- tracker areas that should be split into clearer capability entries because the current scope is too broad to guide implementation cleanly
- tracking records that disagree with one another about what is active, implemented, blocked, or still only designed
- places where broad architecture has drifted ahead of tracked delivery work in a way likely to hide future implementation gaps

What I want in the output:

1. Findings first, ordered by severity
2. Focus on real tracking or decomposition problems, not generic architecture criticism
3. Include concrete file references
4. Distinguish:
   - fix now
   - fix soon
   - follow-up tracker pass
5. Call out whether each finding is mainly about:
   - missing domain tracker
   - underrepresented capability
   - implemented-but-undertracked area
   - misleading progress signal
   - overlapping or awkward tracker scope
   - tracking-doc inconsistency
6. Prefer high-signal planning gaps over broad summaries

Constraints:

- Default to review and doc/planning analysis only unless explicitly asked to edit the implementation trackers
- Do not make code changes unless explicitly asked
- Do not spend time re-reviewing already-solid low-level capabilities if the real issue is at the domain or tracking level
- Keep the review grounded in whether the domain trackers are usable for directing future implementation
- Record reusable lessons in `design/project-management/ai-observations.md` if you discover them

Helpful framing:

- Assume the goal is for domain tracking to be a trustworthy map from design to implementation
- Be skeptical of areas that are “known in architecture” but not clearly visible in the tracker set
- Distinguish clearly between:
  - designed but not tracked
  - tracked but barely implemented
  - implemented but poorly reflected in trackers
  - fully translated and actively tracked
- Prefer recommendations that make the domain trackers easier to use as a real planning surface rather than a passive document set
