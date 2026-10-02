package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayInitialAdmissionBindCatalog.GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayTenantSharedPlayableStateNamespace.GAMEPLAY_TENANT_SHARED_PLAYABLE_STATE_NAMESPACE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindCatalogDescriptor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class InitialAdmissionBindCatalogRepository {
  private static final String TENANT_CATALOG_LOCK_PREFIX = "initial-admission-bind-catalog:";
  private static final String SHARED_NAMESPACE_LOCK_PREFIX = "tenant-shared-playable-state:";

  private final DSLContext dsl;

  public InitialAdmissionBindCatalogRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<InitialAdmissionBindCatalog> findByTenantId(long tenantId) {
    return dsl.selectFrom(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG)
        .where(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.TENANT_ID.eq(tenantId))
        .fetchOptional(this::toEntity);
  }

  public Optional<InitialAdmissionBindCatalog> findByTenantIdAndSelectors(
      long tenantId, String worldSlug, String realmSlug) {
    return dsl.selectFrom(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG)
        .where(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_SLUG.eq(worldSlug))
                .and(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_SLUG.eq(realmSlug)))
        .fetchOptional(this::toEntity);
  }

  public void lockTenantCatalogAndSharedNamespace(long tenantId) {
    if (dsl.dialect().family() != SQLDialect.POSTGRES) {
      return;
    }
    lock(TENANT_CATALOG_LOCK_PREFIX + tenantId);
    lock(SHARED_NAMESPACE_LOCK_PREFIX + tenantId);
  }

  public UUID getOrCreateTenantSharedNamespace(long tenantId) {
    var table = GAMEPLAY_TENANT_SHARED_PLAYABLE_STATE_NAMESPACE;
    UUID existing =
        dsl.select(table.PLAYABLE_STATE_NAMESPACE_ID)
            .from(table)
            .where(table.TENANT_ID.eq(tenantId))
            .fetchOne(table.PLAYABLE_STATE_NAMESPACE_ID);
    if (existing != null) {
      return existing;
    }
    UUID candidate = UUID.randomUUID();
    dsl.insertInto(table)
        .set(table.TENANT_ID, tenantId)
        .set(table.PLAYABLE_STATE_NAMESPACE_ID, candidate)
        .set(table.ALLOCATED_AT, toLocalDateTime(Instant.now()))
        .execute();
    return candidate;
  }

  public InitialAdmissionBindCatalog insert(
      InitialAdmissionBindCatalogDescriptor descriptor,
      UUID realmId,
      UUID playableStateNamespaceId) {
    dsl.insertInto(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG)
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_ID, realmId)
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.TENANT_ID, descriptor.tenantId())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.GAME_TEMPLATE_ID, descriptor.gameTemplateId())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_SLUG, descriptor.worldSlug())
        .set(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_DISPLAY_NAME,
            descriptor.worldDisplayName())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_SLUG, descriptor.realmSlug())
        .set(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_DISPLAY_NAME,
            descriptor.realmDisplayName())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CATALOG_REVISION, 1L)
        .set(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.PLAYABLE_STATE_NAMESPACE_ID,
            playableStateNamespaceId)
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.VISIBLE, true)
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.PUBLIC_PRODUCTION_REALM, true)
        .set(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REQUIRES_CHARACTER_SELECTION,
            descriptor.requiresCharacterSelection())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.STATE_SCOPE, "SHARED")
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CHARACTER_CREATION_POLICY, "ALLOW_NEW")
        .execute();
    return findByTenantId(descriptor.tenantId()).orElseThrow();
  }

  private void lock(String key) {
    dsl.fetch("select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))", key);
  }

  private InitialAdmissionBindCatalog toEntity(Record record) {
    return new InitialAdmissionBindCatalog(
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.TENANT_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.GAME_TEMPLATE_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_SLUG),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_DISPLAY_NAME),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_SLUG),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_DISPLAY_NAME),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CATALOG_REVISION),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.PLAYABLE_STATE_NAMESPACE_ID),
        Boolean.TRUE.equals(record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.VISIBLE)),
        Boolean.TRUE.equals(
            record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.PUBLIC_PRODUCTION_REALM)),
        Boolean.TRUE.equals(
            record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REQUIRES_CHARACTER_SELECTION)),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.STATE_SCOPE),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CHARACTER_CREATION_POLICY),
        toInstant(record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CREATED_AT)));
  }
}
