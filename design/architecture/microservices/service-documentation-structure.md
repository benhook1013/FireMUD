# Service Documentation Structure

This guide describes the preferred shape for service-level design docs so ownership stays clear as service doc sets grow.

## Standard Shape

- `README.md`
  - service overview, boundaries, major responsibilities, ownership map, and links to subdocs
- `api-contracts.md`
  - REST/gRPC/control-plane surfaces, canonical errors, and wire/source-of-truth references
- `runtime-and-data.md`
  - runtime state ownership, Redis/PostgreSQL/object-store boundaries, lifecycle invariants, and durable versus transient data rules
- `operations.md`
  - readiness/liveness, observability, operator-facing behavior, local/dev verification, and runbook-adjacent notes
- `configuration.md`
  - environment variables, service discovery, TLS/trust knobs, and service-local configuration source locations
- `protocols.md` or `client-behavior.md`
  - only when a service owns a wire/text/browser/client-flow contract that would otherwise dominate the README
- `appendix-*.md`
  - optional supporting catalogs, worked examples, or reference material that would otherwise blur the core owner docs

Not every service needs every file immediately, but every service should trend toward this shape so future growth has an obvious home.

## Canonical Ownership Rules

- Keep one canonical parent doc per major concept.
- Do not split invariants across many peer docs without a clear owner.
- Move catalogs, worked examples, cookbooks, and protocol appendices out of the parent doc before splitting core invariants across multiple owners.
- `README.md` should link to every active sibling doc in the set and should state what the service doc set owns versus what top-level architecture docs own.

## Refactor Discipline

When refactoring an existing service doc set:

1. Create an untouched backup copy if the refactor is large enough that preserving the old structure is useful.
2. Draft the new file map before moving sections.
3. Move sections into their new owner docs and rewrite the parent README as an overview plus ownership map.
4. Update local links and references in the doc set.
5. Run a comparison pass against the backup and the new split docs to catch dropped still-valid details.
6. Restore any missing but still-valid details found by that comparison pass.
7. Run `./gradlew linkCheck lintMarkdown`.
8. Record follow-up cleanup work separately instead of bloating the completed refactor.
