# Authoring, Settings, Activation, And Automation Review

Use this prompt to review the creator workflow from editable source material through validation, publishing, activation, runtime execution, rollback, and later revision.

Apply the [shared review contract](./00-shared-review-contract.md).

## Starting Sources

- `design/product/user-journeys/creators.md`
- `design/user-guides/game-creator-guide.md`
- `design/architecture/system-architecture-game-customization.md`
- `design/architecture/system-architecture-settings-model.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- `design/architecture/system-architecture-scripting.md` and its canonical linked contracts
- `design/architecture/system-architecture-procedural-generation.md`
- `design/architecture/microservices/game-design-service/`
- `design/architecture/microservices/world-management-service/`
- `design/architecture/microservices/automation-scripting-service/`
- the owning implementation trackers, production code, schemas, and focused proof

## Review

Trace:

- game creation, templates, world and entity authoring, assets, customization, settings, abilities, actions, items, equipment, and balancing;
- validation, revision history, ownership, collaboration, preview, and playtest isolation;
- scripts and plugins from authoring through packaging, signing or trust checks, quotas, scheduling, runtime execution, observability, and disabling;
- immutable publication artifacts, manifests, hashes, attestations, versions, dependencies, and lineage;
- activation readiness, runtime pins, mixed-version behavior, cutover, rollback, and recovery;
- propagation of settings and content to every runtime consumer; and
- creator-visible errors and operator-visible evidence for rejection, partial publication, unavailable dependencies, failed cutover, and rollback.

Check that design-time ownership, runtime ownership, storage, public APIs, validation, security, and proof agree. Do not treat an editor or command surface as proof that the underlying publication and activation lifecycle exists.

## Output

Provide:

1. an author-to-runtime lifecycle coverage table;
2. ownership, validation, lineage, settings, version, activation, and rollback findings;
3. scripting, plugin, automation, and scheduling gaps;
4. creator-journey, implementation, and proof drift; and
5. the review state required by the shared contract.
