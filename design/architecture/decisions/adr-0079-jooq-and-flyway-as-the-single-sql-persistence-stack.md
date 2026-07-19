# ADR 0079: jOOQ and Flyway as the Single SQL Persistence Stack

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `DB-01`
- Primary capability: `SF-2.1` relational persistence and schema evolution
- Affected capabilities: `SF-1.5`, `PO-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of Flyway and jOOQ authority, generated schema types, Hibernate/JPA dual authority, and hand-written SQL/DAO alternatives

## Context

FireMUD needs one SQL schema authority and one standard application access path. Allowing migration SQL, generated schema types, ORM-managed mappings, and hand-written DAOs to evolve as independent authorities would make schema drift difficult to detect and let different code paths disagree about identifiers, nullability, constraints, and transaction behavior.

Some PostgreSQL capabilities may not be expressible through the generated jOOQ surface used by a service. That requires a narrow escape hatch without turning plain SQL into a parallel persistence stack.

## Decision

Flyway is the sole authority for SQL schema creation and evolution. Every table, column, constraint, index, function, extension, and other durable database object used by application code is introduced or changed through the owning service's Flyway migrations. Application startup, jOOQ generation, tests, and operational migration tooling consume that same migration lineage; no application framework may generate or mutate the production schema as a second authority.

jOOQ is the standard SQL execution and query path. Generated jOOQ schema types derived from the Flyway-managed schema are the default for tables, fields, records, joins, predicates, and writes. Service repositories and transaction code use that generated surface rather than maintaining a second hand-written schema model.

Hibernate and JPA are not a second persistence authority. FireMUD does not maintain parallel Hibernate/JPA entity mappings, repositories, DDL generation, or an ORM-managed schema path alongside jOOQ and Flyway.

Dynamic or plain SQL is a narrow escape hatch only for a required PostgreSQL feature that the applicable jOOQ generated or DSL surface does not support. Each use must remain aligned with the Flyway-owned object names and schema contract, remain inside the owning service's persistence boundary, and carry focused proof against PostgreSQL for its parameters, result mapping, transaction behavior, and relevant failure cases. Convenience, avoiding code generation, or preserving a parallel DAO style does not qualify for the escape hatch.

## Consequences

- Schema history and application SQL share one Flyway-derived contract.
- Generated types expose many schema changes as compile-time integration work instead of latent runtime drift.
- Services avoid the operational and review burden of keeping jOOQ and Hibernate/JPA models synchronized.
- PostgreSQL-specific behavior remains available where jOOQ cannot express it, but every exception requires explicit alignment and focused proof.
- Schema changes require regeneration and coordinated updates to affected jOOQ call sites.
- The current Gateway schema implementation remains unknown and is not claimed as conforming or non-conforming by this decision.

## Alternatives Considered

### Flyway with Hand-Written SQL and DAO Repositories

This is the strongest alternative because Flyway would still provide one schema authority and direct SQL can express every required PostgreSQL feature without code-generation coupling. It is rejected as the standard path because string-based object and result mappings duplicate schema knowledge across repositories, weaken compile-time alignment, and make broad schema changes harder to inventory. The narrow unsupported-feature escape hatch retains its necessary capability without adopting hand-written SQL/DAO as a parallel default.

### Hibernate/JPA alongside Flyway and jOOQ

Rejected because entity mappings, repository behavior, and optional ORM DDL or validation would create a second schema and persistence interpretation that must remain synchronized with both Flyway and generated jOOQ types.

## Implementation and Proof Obligations

All SQL-backed services must run the owning Flyway lineage and generate jOOQ schema types from it. Repository and transaction code must use generated jOOQ types by default, and dependency and configuration checks must prevent Hibernate/JPA schema generation or a parallel ORM repository stack.

Proof must cover migration-to-generation alignment; compilation and focused persistence tests after representative table, column, constraint, and type changes; service-local schema ownership; transaction and failure behavior through jOOQ; and absence of Hibernate/JPA schema or repository authority. Every dynamic or plain-SQL exception must identify the unsupported PostgreSQL feature and prove Flyway object alignment, safe parameter handling, exact result mapping, transaction behavior, and relevant PostgreSQL failure cases.

The current repository-wide migration-to-generation alignment, removal of every parallel schema model, exception inventory, focused proof, and Gateway schema implementation are not claimed by this decision.

## Reversibility and Revisit Triggers

Revisit the standard SQL access path only if measured jOOQ generation or runtime costs materially impede delivery, or a required PostgreSQL capability repeatedly forces broad plain-SQL exceptions. Any replacement must retain one Flyway-owned schema lineage, one canonical application persistence model, and equivalent schema-alignment and PostgreSQL proof.
