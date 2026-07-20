# ADR 0110: Defer Whole-Game Portability and External Authoring Formats

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CONTENT-05`
- Primary capability: `AR-1.2` procedural, LLM-assisted, and external authoring tools
- Affected capabilities: `AR-1.1`, `AR-1.3`, `AR-1.5`, `SF-1.2`, `PO-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of external authoring, whole-game portability, creator recovery, hosted and self-hosted migration, AI tooling, profiles, plugins, backup, and Git-oriented workflows

## Context

Early FireMUD design notes proposed bulk JSON import and export, a filesystem project format, and Git synchronization for game content. Those proposals did not define a stable whole-game schema, complete cross-service reference closure, asset and plugin inclusion, identity preservation or remapping, conflict behavior, or how imported content becomes ordinary Game Design revisions. No current creator journey or runtime capability requires that public compatibility surface.

The use cases are also materially different. Platform backup and disaster recovery restore an environment-wide durable state and cannot be replaced by a content package. AI and other external authoring tools can use bounded typed APIs. Starter profiles, content packs, and plugins have narrower application or package contracts. A readable tenant recovery export does not imply that its output is an application-importable game project. A future first-party clone or migration can be implemented as server-side orchestration without first freezing a public file format.

Game Design owns authoring history and publication coordination but does not store a separate complete copy of every domain-owned Draft graph. A whole-game package would therefore be a new cross-service compatibility and orchestration contract, not a serialization switch over one existing aggregate.

## Decision

Whole-game package import or export, round-trip JSON authoring, filesystem projects, and external Git synchronization are not part of the current FireMUD target. FireMUD does not promise a future portable snapshot format.

First-party authoring remains inside the Game Design revision and version model. The web creator interface, AI-assisted features, and external tools submit authenticated, service-owned typed Draft mutations or future purpose-specific batch operations. They do not write domain databases, object storage, or runtime state directly, and a local repository does not become a second content authority.

FireMUD preserves architectural non-preclusion through independently useful contracts:

- authored objects use stable logical identities where the owning domain contract requires them;
- cross-service references are normalized and validated;
- Draft writes use typed, versioned, service-owned APIs and ordinary revision provenance;
- Published versions are immutable; and
- asset bytes and release artifacts use content-addressed or digest-attested identities where specified by their owning contracts.

These properties do not create a public package schema or compatibility promise. No implementation should add package-only abstractions, indefinite format support, or merge semantics merely to reserve a hypothetical portability feature.

A future platform clone or copy may read authoritative state and create a new Draft through first-party server-side orchestration. A tenant recovery export may provide readable tenant-owned records under its own export and privacy contract. Neither surface is implicitly re-importable, round-trip stable, or suitable for Git synchronization.

Starter profiles and content packs remain curated materialization mechanisms. Immutable platform-attested linked-plugin bundles remain the narrower independently activated extension format governed by the plugin trust contract. Neither mechanism defines or extends a whole-game package.

## Consequences

- FireMUD avoids freezing a public schema across Game Design, World Management, Entity Management, Game Logic, Automation and Scripting, and asset storage while those authoring models continue to evolve.
- External and AI-assisted tools depend on authenticated APIs rather than editing a complete local game repository.
- Creators do not initially receive whole-game offline round trips, Git-native review and merge, cross-install import, or a guaranteed anti-lock-in package.
- Backup, tenant recovery export, server-side copy, content-pack distribution, and plugin distribution keep purpose-specific contracts instead of being forced through one universal archive.
- A later portability decision may require new schema-version, identity-remapping, asset, provenance, validation, security, and conflict contracts; this decision neither promises nor prohibits that work.

## Alternatives Considered

### Commit Now to a Portable Snapshot Imported as a New Draft

Define a deterministic whole-game export and promise that another FireMUD installation can import it as a new Draft without supporting merge. This is the strongest bounded portability alternative and could support creator ownership and hosted-to-self-hosted migration. It is not selected as a current target because no accepted product journey requires it, the complete authored graph spans multiple owners, and even import-as-copy would freeze identity, schema, asset, and compatibility behavior prematurely.

### Support Full Filesystem Projects and Git Synchronization

Make the local project or Git repository a round-trip authoring surface with branch mapping, webhook synchronization, and conflict or merge behavior. This is rejected for the current target because it duplicates authority, expands the security and credential surface, and requires a durable public schema plus merge semantics that the database-backed revision model does not currently provide.

### Treat Published Release Bundles or Tenant Recovery Exports as Import Packages

Reuse an existing artifact to avoid a new format. This is rejected because a release bundle is an attestation rather than a complete content container, while recovery export has privacy, access, and readability obligations rather than application-import semantics.

### Prohibit Future Portability by Design

Permit database-local identities, opaque references, or authoring APIs that make later copying impossible. This is rejected because stable identities, normalized references, and typed versioned writes already benefit ordinary authoring, validation, migration, and publication and preserve future options without a package commitment.

## Implementation and Proof Reality

The current Game Design proto and implementation have no general whole-game graph enumeration, import, export, filesystem-project, merge, or Git-synchronization contract. `SaveRevision` provides revision ingress with a typed World Design mutation family; asset export serves immutable runtime publication; and plugin upload handles the separate immutable plugin package. None of those surfaces is a whole-game round-trip format.

Future external or AI tooling should prove authenticated typed reads and writes, validation, optimistic-concurrency failure, tenant isolation, revision provenance, and rejection of direct authoritative-store mutation. A future first-party clone should prove that it creates ordinary Draft state through owning services and does not introduce a hidden content authority.

## Reversibility and Revisit Triggers

Revisit this decision when a concrete hosted-to-self-hosted or cross-install migration journey is accepted, measured creator portability or anti-lock-in demand justifies a compatibility commitment, offline tooling cannot be served adequately by typed or batch APIs, or whole-game distribution becomes a product requirement. Evaluate a server-side copy or migration workflow before committing to a public package format, and require a separate decision before promising round-trip compatibility, external Git synchronization, or merge behavior.
