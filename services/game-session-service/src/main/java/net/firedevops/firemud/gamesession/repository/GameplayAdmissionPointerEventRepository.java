package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayAdmissionPointerEvent.GAMEPLAY_ADMISSION_POINTER_EVENT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameplayAdmissionPointerEventRecord;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService.PointerAuditKey;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameplayAdmissionPointerEventRepository {
  private static final Field<Integer> REPRESENTATION_VERSION =
      DSL.field(DSL.name("representation_version"), Integer.class);
  private static final int RETAINED_REPRESENTATION_VERSION = 1;
  private static final int POINTER_KEY_QUERY_CHUNK_SIZE = 500;

  private final DSLContext dsl;

  public GameplayAdmissionPointerEventRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<GameplayAdmissionPointerEvent> findByWorldSlugAndRealmSlugOrderByIdDesc(
      String worldSlug, String realmSlug) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER_EVENT)
        .where(
            GAMEPLAY_ADMISSION_POINTER_EVENT
                .WORLD_SLUG
                .eq(worldSlug)
                .and(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_SLUG.eq(realmSlug))
                .and(REPRESENTATION_VERSION.eq(RETAINED_REPRESENTATION_VERSION)))
        .orderBy(GAMEPLAY_ADMISSION_POINTER_EVENT.ID.desc())
        .fetch(this::toEntity);
  }

  public List<GameplayAdmissionPointerEvent> findByTenantIdAndWorldSlugAndRealmSlugOrderByIdDesc(
      Long tenantId, String worldSlug, String realmSlug) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER_EVENT)
        .where(
            GAMEPLAY_ADMISSION_POINTER_EVENT
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_ADMISSION_POINTER_EVENT.WORLD_SLUG.eq(worldSlug))
                .and(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_SLUG.eq(realmSlug))
                .and(REPRESENTATION_VERSION.eq(RETAINED_REPRESENTATION_VERSION)))
        .orderBy(GAMEPLAY_ADMISSION_POINTER_EVENT.ID.desc())
        .fetch(this::toEntity);
  }

  public Optional<GameplayAdmissionPointerEvent> findLatestByTenantIdAndWorldSlugAndRealmSlug(
      Long tenantId, String worldSlug, String realmSlug) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER_EVENT)
        .where(
            GAMEPLAY_ADMISSION_POINTER_EVENT
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_ADMISSION_POINTER_EVENT.WORLD_SLUG.eq(worldSlug))
                .and(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_SLUG.eq(realmSlug))
                .and(REPRESENTATION_VERSION.eq(RETAINED_REPRESENTATION_VERSION)))
        .orderBy(GAMEPLAY_ADMISSION_POINTER_EVENT.ID.desc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<GameplayAdmissionPointerEvent> findLatestByPointerKeys(List<PointerAuditKey> keys) {
    if (keys.isEmpty()) {
      return List.of();
    }
    List<PointerAuditKey> uniqueKeys = new ArrayList<>(new LinkedHashSet<>(keys));
    List<GameplayAdmissionPointerEvent> latestEvents = new ArrayList<>();
    for (int offset = 0; offset < uniqueKeys.size(); offset += POINTER_KEY_QUERY_CHUNK_SIZE) {
      List<PointerAuditKey> keyChunk =
          uniqueKeys.subList(
              offset, Math.min(offset + POINTER_KEY_QUERY_CHUNK_SIZE, uniqueKeys.size()));
      Condition selectedKeys = DSL.falseCondition();
      for (PointerAuditKey key : keyChunk) {
        selectedKeys =
            selectedKeys.or(
                GAMEPLAY_ADMISSION_POINTER_EVENT
                    .TENANT_ID
                    .eq(key.tenantId())
                    .and(GAMEPLAY_ADMISSION_POINTER_EVENT.WORLD_SLUG.eq(key.worldSlug()))
                    .and(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_SLUG.eq(key.realmSlug())));
      }
      var latestIds =
          dsl.select(DSL.max(GAMEPLAY_ADMISSION_POINTER_EVENT.ID))
              .from(GAMEPLAY_ADMISSION_POINTER_EVENT)
              .where(selectedKeys.and(REPRESENTATION_VERSION.eq(RETAINED_REPRESENTATION_VERSION)))
              .groupBy(
                  GAMEPLAY_ADMISSION_POINTER_EVENT.TENANT_ID,
                  GAMEPLAY_ADMISSION_POINTER_EVENT.WORLD_SLUG,
                  GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_SLUG);
      latestEvents.addAll(
          dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER_EVENT)
              .where(GAMEPLAY_ADMISSION_POINTER_EVENT.ID.in(latestIds))
              .orderBy(GAMEPLAY_ADMISSION_POINTER_EVENT.ID.asc())
              .fetch(this::toEntity));
    }
    latestEvents.sort(Comparator.comparing(GameplayAdmissionPointerEvent::getId));
    return latestEvents;
  }

  public GameplayAdmissionPointerEvent save(GameplayAdmissionPointerEvent entity) {
    if (entity.getId() == null) {
      GameplayAdmissionPointerEventRecord record = dsl.newRecord(GAMEPLAY_ADMISSION_POINTER_EVENT);
      populate(record, entity);
      record.store();
      return findById(record.getId());
    }
    int updated =
        dsl.update(GAMEPLAY_ADMISSION_POINTER_EVENT)
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.WORLD_SLUG, entity.getWorldSlug())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_SLUG, entity.getRealmSlug())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.WORLD_DISPLAY_NAME, entity.getWorldDisplayName())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_DISPLAY_NAME, entity.getRealmDisplayName())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.TENANT_ID, entity.getTenantId())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.POINTER_VERSION, entity.getPointerVersion())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.CATALOG_REVISION, entity.getCatalogRevision())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_ID, entity.getRealmId())
            .set(
                GAMEPLAY_ADMISSION_POINTER_EVENT.PLAYABLE_STATE_NAMESPACE_ID,
                entity.getPlayableStateNamespaceId())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.VISIBLE, entity.isVisible())
            .set(
                GAMEPLAY_ADMISSION_POINTER_EVENT.PUBLIC_PRODUCTION_REALM,
                entity.isPublicProductionRealm())
            .set(
                GAMEPLAY_ADMISSION_POINTER_EVENT.REQUIRES_CHARACTER_SELECTION,
                entity.isRequiresCharacterSelection())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.STATE_SCOPE, entity.getStateScope())
            .set(
                GAMEPLAY_ADMISSION_POINTER_EVENT.CHARACTER_CREATION_POLICY,
                entity.getCharacterCreationPolicy())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.ACTOR_PRINCIPAL, entity.getActorPrincipal())
            .set(GAMEPLAY_ADMISSION_POINTER_EVENT.REASON, entity.getReason())
            .set(
                GAMEPLAY_ADMISSION_POINTER_EVENT.CONTROL_PLANE_REQUEST_ID,
                entity.getControlPlaneRequestId())
            .set(
                GAMEPLAY_ADMISSION_POINTER_EVENT.PREPARED_VERSION_UPGRADE_ID,
                entity.getPreparedVersionUpgradeId())
            .set(
                GAMEPLAY_ADMISSION_POINTER_EVENT.OCCURRED_AT,
                toLocalDateTime(entity.getOccurredAt()))
            .where(
                GAMEPLAY_ADMISSION_POINTER_EVENT
                    .ID
                    .eq(entity.getId())
                    .and(REPRESENTATION_VERSION.eq(RETAINED_REPRESENTATION_VERSION)))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException(
          "Failed to update gameplay_admission_pointer_event id=" + entity.getId());
    }
    return findById(entity.getId());
  }

  public void deleteAllInBatch() {
    dsl.deleteFrom(GAMEPLAY_ADMISSION_POINTER_EVENT)
        .where(REPRESENTATION_VERSION.eq(RETAINED_REPRESENTATION_VERSION))
        .execute();
  }

  /** Appends the single canonical CLOSED audit event inside its pointer owner transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public long appendCanonicalClosed(
      String targetNamespace,
      UUID canonicalTenantId,
      UUID realmId,
      String worldSlug,
      String realmSlug,
      long catalogRevision,
      String actorPrincipal,
      String reason,
      UUID requestId,
      Instant occurredAt) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(realmId, "realmId");
    Objects.requireNonNull(worldSlug, "worldSlug");
    Objects.requireNonNull(realmSlug, "realmSlug");
    Objects.requireNonNull(actorPrincipal, "actorPrincipal");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (catalogRevision <= 0L) {
      throw new IllegalArgumentException("catalogRevision must be positive");
    }
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO gameplay_admission_pointer_event ("
                + "world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
                + "game_instance_id, pointer_version, visible, requires_character_selection, "
                + "state_scope, character_creation_policy, actor_principal, reason, "
                + "control_plane_request_id, occurred_at, prepared_version_upgrade_id, "
                + "public_production_realm, representation_version, target_namespace, "
                + "canonical_tenant_id, realm_id, catalog_revision, admission_state) "
                + "VALUES (?, ?, NULL, NULL, NULL, NULL, 1, NULL, NULL, NULL, NULL, ?, ?, ?, ?, "
                + "NULL, NULL, 2, ?, ?, ?, ?, 'CLOSED') RETURNING id",
            worldSlug,
            realmSlug,
            actorPrincipal,
            reason,
            requestId.toString(),
            toLocalDateTime(occurredAt),
            targetNamespace,
            canonicalTenantId,
            realmId,
            catalogRevision);
    if (inserted == null) {
      throw new IllegalStateException(
          "Canonical CLOSED admission-pointer audit insert returned no id");
    }
    Long eventId = inserted.get("id", Long.class);
    if (eventId == null || eventId <= 0L) {
      throw new IllegalStateException("Canonical CLOSED admission-pointer event id is invalid");
    }
    return eventId;
  }

  private GameplayAdmissionPointerEvent findById(Long id) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER_EVENT)
        .where(
            GAMEPLAY_ADMISSION_POINTER_EVENT
                .ID
                .eq(id)
                .and(REPRESENTATION_VERSION.eq(RETAINED_REPRESENTATION_VERSION)))
        .fetchOptional(this::toEntity)
        .orElseThrow();
  }

  private void populate(
      GameplayAdmissionPointerEventRecord record, GameplayAdmissionPointerEvent entity) {
    record.setWorldSlug(entity.getWorldSlug());
    record.setRealmSlug(entity.getRealmSlug());
    record.setWorldDisplayName(entity.getWorldDisplayName());
    record.setRealmDisplayName(entity.getRealmDisplayName());
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setPointerVersion(entity.getPointerVersion());
    record.setCatalogRevision(entity.getCatalogRevision());
    record.setRealmId(entity.getRealmId());
    record.setPlayableStateNamespaceId(entity.getPlayableStateNamespaceId());
    record.setVisible(entity.isVisible());
    record.setPublicProductionRealm(entity.isPublicProductionRealm());
    record.setRequiresCharacterSelection(entity.isRequiresCharacterSelection());
    record.setStateScope(entity.getStateScope());
    record.setCharacterCreationPolicy(entity.getCharacterCreationPolicy());
    record.setActorPrincipal(entity.getActorPrincipal());
    record.setReason(entity.getReason());
    record.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    record.setPreparedVersionUpgradeId(entity.getPreparedVersionUpgradeId());
    record.setOccurredAt(toLocalDateTime(entity.getOccurredAt()));
  }

  private GameplayAdmissionPointerEvent toEntity(Record record) {
    GameplayAdmissionPointerEvent entity = new GameplayAdmissionPointerEvent();
    entity.setId(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.ID));
    entity.setWorldSlug(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.WORLD_SLUG));
    entity.setRealmSlug(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_SLUG));
    entity.setWorldDisplayName(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.WORLD_DISPLAY_NAME));
    entity.setRealmDisplayName(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_DISPLAY_NAME));
    entity.setTenantId(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.TENANT_ID));
    entity.setGameInstanceId(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.GAME_INSTANCE_ID));
    entity.setPointerVersion(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.POINTER_VERSION));
    entity.setCatalogRevision(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.CATALOG_REVISION));
    entity.setRealmId(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_ID));
    entity.setPlayableStateNamespaceId(
        record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.PLAYABLE_STATE_NAMESPACE_ID));
    entity.setVisible(Boolean.TRUE.equals(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.VISIBLE)));
    entity.setPublicProductionRealm(
        Boolean.TRUE.equals(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.PUBLIC_PRODUCTION_REALM)));
    entity.setRequiresCharacterSelection(
        Boolean.TRUE.equals(
            record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.REQUIRES_CHARACTER_SELECTION)));
    entity.setStateScope(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.STATE_SCOPE));
    entity.setCharacterCreationPolicy(
        record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.CHARACTER_CREATION_POLICY));
    entity.setActorPrincipal(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.ACTOR_PRINCIPAL));
    entity.setReason(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.REASON));
    entity.setControlPlaneRequestId(
        record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.CONTROL_PLANE_REQUEST_ID));
    entity.setPreparedVersionUpgradeId(
        record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.PREPARED_VERSION_UPGRADE_ID));
    entity.setOccurredAt(toInstant(record.get(GAMEPLAY_ADMISSION_POINTER_EVENT.OCCURRED_AT)));
    return entity;
  }
}
