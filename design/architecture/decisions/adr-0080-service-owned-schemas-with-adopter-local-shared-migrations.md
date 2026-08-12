# ADR 0080: Service-Owned Schemas with Adopter-Local Shared Migrations

## Status

Accepted

## Implementation Status

Service-local ownership is documented, but migration wiring, adopter-local shared-migration versioning, compatibility enforcement, and focused proof are incomplete. Current test wiring also applies Saga migrations to non-adopting services and is not conformance evidence.

## Canonical Design

- [Database Migrations](../system-architecture-database-migrations.md)
- [Shared Libraries](../system-architecture-shared-libraries.md#short-synchronous-saga-orchestration)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `DB-02`
- Primary capability: `SF-2.1` database schema and migration management
- Affected capabilities: `SF-2.4`, `PO-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of service schema ownership, reusable shared migration artifacts, and central shared-schema alternatives
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Accepted
- Review source: `DB-02`

## Context

Service-owned persistence requires one authority for schema change, migration history, deployment, and failure recovery. FireMUD also has reusable persistence components such as the `common-saga` tables. Reusing their definition must not create a second operational owner or allow one service to mutate another service's data boundary.

A shared PostgreSQL installation does not by itself require a shared schema or shared migration authority. The ownership boundary must remain explicit even when several service schemas are physically hosted in the same database cluster or database.

## Decision

Each service owns its PostgreSQL schema, Flyway history table, migration deployment, and migration failure recovery. A service never applies DDL to another service's schema and never depends on another service to advance or repair its migration history.

`common-saga` owns the reusable definition of its saga tables and migration artifacts. Each adopting service deliberately includes and applies those migrations into its own schema through its own Flyway execution and records them in its own Flyway history. This is adopter-local schema ownership: the shared library defines the reusable component, while the adopter owns whether, when, and how that component is deployed and recovered in its persistence boundary.

Shared migration artifacts use a collision-free versioning convention relative to adopter-local migrations. A released shared migration remains compatible with the adopting library and service versions that may coexist during rollout. Adopters must not renumber, rewrite, or silently replace an applied shared migration; a shared schema change is introduced as a new compatible migration under the shared convention.

Service boundaries prohibit cross-service DDL, cross-service foreign keys, and direct reads of another service's tables. Cross-service data access uses the owning service's declared API, event, or workflow contract. These prohibitions apply even when service schemas share one physical PostgreSQL cluster or database. Shared physical PostgreSQL remains allowed as a deployment choice and does not weaken logical schema ownership.

## Consequences

- Every service has one accountable owner for schema state, Flyway history, deployment ordering, and migration recovery.
- `common-saga` can provide one reusable table definition without creating a central runtime schema owner.
- Each adopter carries its own saga tables and migration history, so shared migration rollout and recovery occur separately per service.
- Collision-free shared migration versioning and compatibility rules add release discipline to both the library and adopting services.
- A shared PostgreSQL deployment remains possible without permitting database-level coupling between services.
- Cross-service joins and foreign-key enforcement are unavailable; owners must expose required data and integrity outcomes through service contracts.

## Alternatives Considered

### Central Shared Saga Schema

Rejected because one shared schema and migration history would couple adopter deployment and recovery, create cross-service database access pressure, and leave schema authority split between the shared component and participating services. Centralized operational visibility does not justify changing persistence ownership.

## Implementation and Proof Obligations

Proof must cover service-specific schemas and Flyway history tables; deliberate inclusion of shared saga migrations only by adopters; collision-free ordering between shared and local migrations; repeat deployment and failure recovery per adopter; compatibility across supported shared-library and adopter rollout versions; and rejection of cross-service DDL, foreign keys, and direct table reads. The same proof must hold when services use separate databases and when their schemas share one physical PostgreSQL deployment.

Current test wiring applies Saga migrations to non-adopting services. That behavior does not prove adopter-local inclusion and must be removed from the proof boundary before conformance is claimed. The current migration wiring, collision-free shared version convention, compatibility enforcement, and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Physical database placement may change without revisiting this decision while service-owned schemas, Flyway histories, and access boundaries remain intact. Revisit only if a concrete reusable persistence component cannot be deployed and recovered safely through adopter-local migrations. Any central schema proposal must define one authority for deployment, failure recovery, compatibility, access control, and migration history without permitting implicit cross-service table ownership.
