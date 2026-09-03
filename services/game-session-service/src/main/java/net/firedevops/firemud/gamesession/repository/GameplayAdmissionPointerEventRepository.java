package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayAdmissionPointerEvent.GAMEPLAY_ADMISSION_POINTER_EVENT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameplayAdmissionPointerEventRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameplayAdmissionPointerEventRepository {
  private final DSLContext dsl;

  public GameplayAdmissionPointerEventRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<GameplayAdmissionPointerEvent> findByWorldSlugAndRealmSlugOrderByOccurredAtDesc(
      String worldSlug, String realmSlug) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER_EVENT)
        .where(
            GAMEPLAY_ADMISSION_POINTER_EVENT
                .WORLD_SLUG
                .eq(worldSlug)
                .and(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_SLUG.eq(realmSlug)))
        .orderBy(
            GAMEPLAY_ADMISSION_POINTER_EVENT.OCCURRED_AT.desc(),
            GAMEPLAY_ADMISSION_POINTER_EVENT.ID.desc())
        .fetch(this::toEntity);
  }

  public List<GameplayAdmissionPointerEvent>
      findByTenantIdAndWorldSlugAndRealmSlugOrderByOccurredAtDesc(
          Long tenantId, String worldSlug, String realmSlug) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER_EVENT)
        .where(
            GAMEPLAY_ADMISSION_POINTER_EVENT
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_ADMISSION_POINTER_EVENT.WORLD_SLUG.eq(worldSlug))
                .and(GAMEPLAY_ADMISSION_POINTER_EVENT.REALM_SLUG.eq(realmSlug)))
        .orderBy(
            GAMEPLAY_ADMISSION_POINTER_EVENT.OCCURRED_AT.desc(),
            GAMEPLAY_ADMISSION_POINTER_EVENT.ID.desc())
        .fetch(this::toEntity);
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
            .where(GAMEPLAY_ADMISSION_POINTER_EVENT.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException(
          "Failed to update gameplay_admission_pointer_event id=" + entity.getId());
    }
    return findById(entity.getId());
  }

  public void deleteAllInBatch() {
    dsl.deleteFrom(GAMEPLAY_ADMISSION_POINTER_EVENT).execute();
  }

  private GameplayAdmissionPointerEvent findById(Long id) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER_EVENT)
        .where(GAMEPLAY_ADMISSION_POINTER_EVENT.ID.eq(id))
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
