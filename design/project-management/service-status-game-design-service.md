# Game Design Service Status

## Current Coverage

- Versioned design-time ownership, publish workflow, asset-storage rules, plugin/modding contracts, and creator-facing responsibilities are documented in depth.
- The service’s architecture docs now clearly separate API contracts, runtime/data responsibilities, operations, configuration, templates, assets, and modding concerns.
- The repo already reflects substantial design-time workflow modeling for templates, publishing, runtime flags, script patches, and plugin lifecycle, and now includes the first live plugin publication metadata path via `PublishPluginVersion` and `GetPublishedPluginVersion`.
- The target item/equipment design model is documented, including game-configured slot/body-layout concepts and authored stackability/compatibility controls. Entity Management now has the runtime schema substrate for slots, body layouts, and item slot-group compatibility, while broader creator-facing editors for those concepts remain future application work.

## Current Role In The Platform

- Owns design-time editing, publication, version metadata, and release-bundle/manifest production.
- Acts as the control-plane source of truth for design artifacts and publish-time orchestration inputs.
- Supplies creator-facing coordination for world, entity, scripting, and asset publication.

## Partial / Stubbed / Deferred Areas

- Large areas of creator tooling remain more strongly designed than implemented.
- Some CRUD/editor flows and richer balancing/design tools are still future application work rather than proven runtime slices.
- Signed bundle upload/extraction, richer signer-validation state, and broader creator-tooling surfaces remain future implementation work.
- Publish-time copy flows into downstream domain schemas remain an ongoing implementation concern across services.

## Planning Notes

- Future work here should be planned as creator-tooling or publication slices, not as a growing checkbox list in this file.
