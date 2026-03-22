# Service Documentation Structure

This directory uses a standard documentation shape for service-level design docs so ownership stays clear as services grow.

## Standard Shape

- `README.md`
  - Service overview, boundaries, major responsibilities, ownership map, and links to subdocs.
- `api-contracts.md`
  - REST/gRPC/control-plane surfaces, canonical errors, and wire/source-of-truth references.
- `runtime-and-data.md`
  - Runtime state ownership, Redis/PostgreSQL/object-store boundaries, lifecycle invariants, and durable versus transient data rules.
- `operations.md`
  - Readiness/liveness, observability, operator-facing behavior, local/dev verification, and runbook-adjacent notes.
- `configuration.md`
  - Environment variables, service discovery, TLS/trust knobs, and service-local configuration source locations.
- `protocols.md` or `client-behavior.md`
  - Only when a service owns a wire/text/browser/client-flow contract that would otherwise dominate the README.
- `appendix-*.md`
  - Optional. Use only for worked examples, catalogs, or supporting reference material that would blur the core owner docs.

Not every service needs every file immediately, but every service should trend toward this shape so future growth has an obvious home.

## Canonical Ownership Rules

- Keep one canonical parent doc per major concept.
- Do not split invariants across many peers without a clear owner.
- Move catalogs, worked examples, cookbooks, and protocol appendices out of the parent doc first.
- `README.md` should link to every active sibling doc in the set and should state what the service doc set owns versus what other services or system docs own.

## Backup Convention

- Before refactoring an existing service doc, create an untouched backup copy in the same directory.
- The default naming convention is `README.pre-doc-refactor-backup.md` for README refactors and `<original-name>.pre-doc-refactor-backup.md` for other files.
- Backup copies must preserve the exact pre-refactor content and must not be edited during the refactor pass.

## Required Refactor Tracking

For each moved section in a refactor checklist, capture:

- the original owner file
- the new owner file
- whether the section was moved verbatim, condensed, or intentionally split
- whether a backup-vs-refactor subagent pass found omissions that required restoration

## Required Verification Loop

For each refactor target:

1. Create the untouched backup copy.
2. Draft the new file map before moving sections.
3. Move sections into new files and rewrite the parent doc as an index plus canonical owner where appropriate.
4. Update local links and references in the doc set.
5. Run a subagent comparison pass against the backup copy and the new split docs.
6. Restore any missing-but-still-valid details found by the comparison pass.
7. Run `./gradlew linkCheck lintMarkdown`.
8. Record any follow-up cleanup items separately instead of bloating the completed refactor.
