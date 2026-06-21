package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayAdmissionPointer.GAMEPLAY_ADMISSION_POINTER;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameplayAdmissionPointerRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameplayAdmissionPointerRepository {
  private final DSLContext dsl;

  public GameplayAdmissionPointerRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public long count() {
    return dsl.fetchCount(GAMEPLAY_ADMISSION_POINTER);
  }

  public Optional<GameplayAdmissionPointer> findByWorldSlugAndRealmSlug(
      String worldSlug, String realmSlug) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER)
        .where(
            GAMEPLAY_ADMISSION_POINTER
                .WORLD_SLUG
                .eq(worldSlug)
                .and(GAMEPLAY_ADMISSION_POINTER.REALM_SLUG.eq(realmSlug)))
        .fetchOptional(this::toEntity);
  }

  public Optional<GameplayAdmissionPointer> findByTenantIdAndRealmSlug(
      Long tenantId, String realmSlug) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER)
        .where(
            GAMEPLAY_ADMISSION_POINTER
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_ADMISSION_POINTER.REALM_SLUG.eq(realmSlug)))
        .fetchOptional(this::toEntity);
  }

  public Optional<GameplayAdmissionPointer> findByTenantIdAndGameInstanceId(
      Long tenantId, Long gameInstanceId) {
    return findAllByTenantIdAndGameInstanceId(tenantId, gameInstanceId).stream().findFirst();
  }

  public List<GameplayAdmissionPointer> findAllByTenantIdAndGameInstanceId(
      Long tenantId, Long gameInstanceId) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER)
        .where(
            GAMEPLAY_ADMISSION_POINTER
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_ADMISSION_POINTER.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .orderBy(
            GAMEPLAY_ADMISSION_POINTER.WORLD_SLUG.asc(),
            GAMEPLAY_ADMISSION_POINTER.REALM_SLUG.asc())
        .fetch(this::toEntity);
  }

  public List<GameplayAdmissionPointer> findAllByOrderByWorldSlugAscRealmSlugAsc() {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER)
        .orderBy(
            GAMEPLAY_ADMISSION_POINTER.WORLD_SLUG.asc(),
            GAMEPLAY_ADMISSION_POINTER.REALM_SLUG.asc())
        .fetch(this::toEntity);
  }

  public GameplayAdmissionPointer save(GameplayAdmissionPointer entity) {
    if (entity.getId() == null) {
      GameplayAdmissionPointerRecord record = dsl.newRecord(GAMEPLAY_ADMISSION_POINTER);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(GAMEPLAY_ADMISSION_POINTER)
            .set(GAMEPLAY_ADMISSION_POINTER.WORLD_SLUG, entity.getWorldSlug())
            .set(GAMEPLAY_ADMISSION_POINTER.WORLD_DISPLAY_NAME, entity.getWorldDisplayName())
            .set(GAMEPLAY_ADMISSION_POINTER.REALM_SLUG, entity.getRealmSlug())
            .set(GAMEPLAY_ADMISSION_POINTER.REALM_DISPLAY_NAME, entity.getRealmDisplayName())
            .set(GAMEPLAY_ADMISSION_POINTER.TENANT_ID, entity.getTenantId())
            .set(GAMEPLAY_ADMISSION_POINTER.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(GAMEPLAY_ADMISSION_POINTER.POINTER_VERSION, entity.getPointerVersion())
            .set(GAMEPLAY_ADMISSION_POINTER.VISIBLE, entity.isVisible())
            .set(
                GAMEPLAY_ADMISSION_POINTER.PUBLIC_PRODUCTION_REALM,
                entity.isPublicProductionRealm())
            .set(
                GAMEPLAY_ADMISSION_POINTER.REQUIRES_CHARACTER_SELECTION,
                entity.isRequiresCharacterSelection())
            .set(GAMEPLAY_ADMISSION_POINTER.STATE_SCOPE, entity.getStateScope())
            .set(
                GAMEPLAY_ADMISSION_POINTER.CHARACTER_CREATION_POLICY,
                entity.getCharacterCreationPolicy())
            .set(GAMEPLAY_ADMISSION_POINTER.LAST_UPDATED_BY, entity.getLastUpdatedBy())
            .set(GAMEPLAY_ADMISSION_POINTER.LAST_UPDATE_REASON, entity.getLastUpdateReason())
            .set(GAMEPLAY_ADMISSION_POINTER.CREATED_AT, toLocalDateTime(entity.getCreatedAt()))
            .set(GAMEPLAY_ADMISSION_POINTER.UPDATED_AT, toLocalDateTime(entity.getUpdatedAt()))
            .where(GAMEPLAY_ADMISSION_POINTER.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException(
          "Failed to update gameplay_admission_pointer id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void deleteAllInBatch() {
    dsl.deleteFrom(GAMEPLAY_ADMISSION_POINTER).execute();
  }

  private Optional<GameplayAdmissionPointer> findById(Long id) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER)
        .where(GAMEPLAY_ADMISSION_POINTER.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(GameplayAdmissionPointerRecord record, GameplayAdmissionPointer entity) {
    record.setWorldSlug(entity.getWorldSlug());
    record.setWorldDisplayName(entity.getWorldDisplayName());
    record.setRealmSlug(entity.getRealmSlug());
    record.setRealmDisplayName(entity.getRealmDisplayName());
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setPointerVersion(entity.getPointerVersion());
    record.setVisible(entity.isVisible());
    record.setPublicProductionRealm(entity.isPublicProductionRealm());
    record.setRequiresCharacterSelection(entity.isRequiresCharacterSelection());
    record.setStateScope(entity.getStateScope());
    record.setCharacterCreationPolicy(entity.getCharacterCreationPolicy());
    record.setLastUpdatedBy(entity.getLastUpdatedBy());
    record.setLastUpdateReason(entity.getLastUpdateReason());
    record.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
    record.setUpdatedAt(toLocalDateTime(entity.getUpdatedAt()));
  }

  private GameplayAdmissionPointer toEntity(Record record) {
    GameplayAdmissionPointer entity = new GameplayAdmissionPointer();
    entity.setId(record.get(GAMEPLAY_ADMISSION_POINTER.ID));
    entity.setWorldSlug(record.get(GAMEPLAY_ADMISSION_POINTER.WORLD_SLUG));
    entity.setWorldDisplayName(record.get(GAMEPLAY_ADMISSION_POINTER.WORLD_DISPLAY_NAME));
    entity.setRealmSlug(record.get(GAMEPLAY_ADMISSION_POINTER.REALM_SLUG));
    entity.setRealmDisplayName(record.get(GAMEPLAY_ADMISSION_POINTER.REALM_DISPLAY_NAME));
    entity.setTenantId(record.get(GAMEPLAY_ADMISSION_POINTER.TENANT_ID));
    entity.setGameInstanceId(record.get(GAMEPLAY_ADMISSION_POINTER.GAME_INSTANCE_ID));
    entity.setPointerVersion(record.get(GAMEPLAY_ADMISSION_POINTER.POINTER_VERSION));
    entity.setVisible(Boolean.TRUE.equals(record.get(GAMEPLAY_ADMISSION_POINTER.VISIBLE)));
    entity.setPublicProductionRealm(
        Boolean.TRUE.equals(record.get(GAMEPLAY_ADMISSION_POINTER.PUBLIC_PRODUCTION_REALM)));
    entity.setRequiresCharacterSelection(
        Boolean.TRUE.equals(record.get(GAMEPLAY_ADMISSION_POINTER.REQUIRES_CHARACTER_SELECTION)));
    entity.setStateScope(record.get(GAMEPLAY_ADMISSION_POINTER.STATE_SCOPE));
    entity.setCharacterCreationPolicy(
        record.get(GAMEPLAY_ADMISSION_POINTER.CHARACTER_CREATION_POLICY));
    entity.setLastUpdatedBy(record.get(GAMEPLAY_ADMISSION_POINTER.LAST_UPDATED_BY));
    entity.setLastUpdateReason(record.get(GAMEPLAY_ADMISSION_POINTER.LAST_UPDATE_REASON));
    entity.setCreatedAt(toInstant(record.get(GAMEPLAY_ADMISSION_POINTER.CREATED_AT)));
    entity.setUpdatedAt(toInstant(record.get(GAMEPLAY_ADMISSION_POINTER.UPDATED_AT)));
    return entity;
  }
}
