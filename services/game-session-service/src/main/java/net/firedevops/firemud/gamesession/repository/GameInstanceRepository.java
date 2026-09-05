package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameInstances.GAME_INSTANCES;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameInstancesRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameInstanceRepository {
  private static final Field<?>[] SELECT_FIELDS = {
    GAME_INSTANCES.ID,
    GAME_INSTANCES.TENANT_ID,
    GAME_INSTANCES.RUNTIME_VERSION,
    GAME_INSTANCES.SCRIPT_PATCH_VERSION,
    GAME_INSTANCES.SCRIPT_PIN_EPOCH,
    GAME_INSTANCES.GAME_TEMPLATE_ID,
    GAME_INSTANCES.LAUNCH_DESCRIPTOR_ID,
    GAME_INSTANCES.VERSION_ID,
    GAME_INSTANCES.RELEASE_BUNDLE_ID,
    GAME_INSTANCES.VERSION_STATE_EPOCH,
    GAME_INSTANCES.GENERATION_CONFIG_REVISION,
    GAME_INSTANCES.REMAP_SET_ID,
    GAME_INSTANCES.SCRIPT_PATCH_PINNED_AT,
    GAME_INSTANCES.SCRIPT_PATCH_PINNED_BY,
    GAME_INSTANCES.SCRIPT_PATCH_PINNED_REASON,
    GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID,
    GAME_INSTANCES.OWNER_ACCOUNT_ID,
    GAME_INSTANCES.STATUS,
    GAME_INSTANCES.ROW_VERSION
  };

  private final DSLContext dsl;

  public GameInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<GameInstance> findById(Long id) {
    return selectGameInstances().where(GAME_INSTANCES.ID.eq(id)).fetchOptional(this::toEntity);
  }

  public long count() {
    return dsl.fetchCount(GAME_INSTANCES);
  }

  public List<GameInstance> findAll() {
    return selectGameInstances().orderBy(GAME_INSTANCES.ID.asc()).fetch(this::toEntity);
  }

  public Optional<GameInstance> findFirstByTenantIdAndOwnerAccountIdAndStatus(
      Long tenantId, Long ownerAccountId, String status) {
    return selectGameInstances()
        .where(
            GAME_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(GAME_INSTANCES.OWNER_ACCOUNT_ID.eq(ownerAccountId))
                .and(GAME_INSTANCES.STATUS.eq(status)))
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<GameInstance> findByStatus(String status) {
    return selectGameInstances()
        .where(GAME_INSTANCES.STATUS.eq(status))
        .orderBy(GAME_INSTANCES.ID.asc())
        .fetch(this::toEntity);
  }

  public List<GameInstance> findByTenantIdAndOwnerAccountIdInAndStatus(
      Long tenantId, Collection<Long> ownerAccountIds, String status) {
    if (ownerAccountIds == null || ownerAccountIds.isEmpty()) {
      return List.of();
    }
    return selectGameInstances()
        .where(
            GAME_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(GAME_INSTANCES.OWNER_ACCOUNT_ID.in(ownerAccountIds))
                .and(GAME_INSTANCES.STATUS.eq(status)))
        .orderBy(GAME_INSTANCES.ID.asc())
        .fetch(this::toEntity);
  }

  public GameInstance save(GameInstance entity) {
    if (entity.getId() == null) {
      GameInstancesRecord record = dsl.newRecord(GAME_INSTANCES);
      populate(record, entity);
      long initialRowVersion = entity.getRowVersion() == null ? 0L : entity.getRowVersion();
      record.setRowVersion(initialRowVersion);
      record.store();
      return findById(record.getId()).orElseThrow();
    }

    long currentRowVersion = entity.getRowVersion() == null ? 0L : entity.getRowVersion();
    long nextRowVersion = currentRowVersion + 1L;
    int updated =
        dsl.update(GAME_INSTANCES)
            .set(GAME_INSTANCES.TENANT_ID, entity.getTenantId())
            .set(GAME_INSTANCES.RUNTIME_VERSION, entity.getRuntimeVersion())
            .set(GAME_INSTANCES.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(GAME_INSTANCES.SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
            .set(GAME_INSTANCES.GAME_TEMPLATE_ID, entity.getGameTemplateId())
            .set(GAME_INSTANCES.LAUNCH_DESCRIPTOR_ID, entity.getLaunchDescriptorId())
            .set(GAME_INSTANCES.VERSION_ID, entity.getVersionId())
            .set(GAME_INSTANCES.RELEASE_BUNDLE_ID, entity.getReleaseBundleId())
            .set(GAME_INSTANCES.VERSION_STATE_EPOCH, entity.getVersionStateEpoch())
            .set(GAME_INSTANCES.GENERATION_CONFIG_REVISION, entity.getGenerationConfigRevision())
            .set(GAME_INSTANCES.REMAP_SET_ID, entity.getRemapSetId())
            .set(
                GAME_INSTANCES.SCRIPT_PATCH_PINNED_AT,
                toLocalDateTime(entity.getScriptPatchPinnedAt()))
            .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_BY, entity.getScriptPatchPinnedBy())
            .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_REASON, entity.getScriptPatchPinnedReason())
            .set(
                GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID,
                entity.getScriptPatchPinnedControlPlaneRequestId())
            .set(GAME_INSTANCES.OWNER_ACCOUNT_ID, entity.getOwnerAccountId())
            .set(GAME_INSTANCES.STATUS, entity.getStatus())
            .set(GAME_INSTANCES.ROW_VERSION, nextRowVersion)
            .where(
                GAME_INSTANCES
                    .ID
                    .eq(entity.getId())
                    .and(GAME_INSTANCES.ROW_VERSION.eq(currentRowVersion)))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update game_instance id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public GameInstance saveAndFlush(GameInstance entity) {
    return save(entity);
  }

  public void deleteById(Long id) {
    dsl.deleteFrom(GAME_INSTANCES).where(GAME_INSTANCES.ID.eq(id)).execute();
  }

  public void deleteAll() {
    dsl.deleteFrom(GAME_INSTANCES).execute();
  }

  private SelectJoinStep<Record> selectGameInstances() {
    return dsl.select(SELECT_FIELDS).from(GAME_INSTANCES);
  }

  private void populate(GameInstancesRecord record, GameInstance entity) {
    record.setTenantId(entity.getTenantId());
    record.setRuntimeVersion(entity.getRuntimeVersion());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
    record.setGameTemplateId(entity.getGameTemplateId());
    record.setLaunchDescriptorId(entity.getLaunchDescriptorId());
    record.setVersionId(entity.getVersionId());
    record.setReleaseBundleId(entity.getReleaseBundleId());
    record.setVersionStateEpoch(entity.getVersionStateEpoch());
    record.setGenerationConfigRevision(entity.getGenerationConfigRevision());
    record.setRemapSetId(entity.getRemapSetId());
    record.setScriptPatchPinnedAt(toLocalDateTime(entity.getScriptPatchPinnedAt()));
    record.setScriptPatchPinnedBy(entity.getScriptPatchPinnedBy());
    record.setScriptPatchPinnedReason(entity.getScriptPatchPinnedReason());
    record.setScriptPatchPinnedControlPlaneRequestId(
        entity.getScriptPatchPinnedControlPlaneRequestId());
    record.setOwnerAccountId(entity.getOwnerAccountId());
    record.setStatus(entity.getStatus());
  }

  private GameInstance toEntity(Record record) {
    GameInstance entity = new GameInstance();
    entity.setId(record.get(GAME_INSTANCES.ID));
    entity.setTenantId(record.get(GAME_INSTANCES.TENANT_ID));
    entity.setRuntimeVersion(record.get(GAME_INSTANCES.RUNTIME_VERSION));
    entity.setScriptPatchVersion(record.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION));
    entity.setScriptPinEpoch(record.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH));
    entity.setGameTemplateId(record.get(GAME_INSTANCES.GAME_TEMPLATE_ID));
    entity.setLaunchDescriptorId(record.get(GAME_INSTANCES.LAUNCH_DESCRIPTOR_ID));
    entity.setVersionId(record.get(GAME_INSTANCES.VERSION_ID));
    entity.setReleaseBundleId(record.get(GAME_INSTANCES.RELEASE_BUNDLE_ID));
    entity.setVersionStateEpoch(record.get(GAME_INSTANCES.VERSION_STATE_EPOCH));
    entity.setGenerationConfigRevision(record.get(GAME_INSTANCES.GENERATION_CONFIG_REVISION));
    entity.setRemapSetId(record.get(GAME_INSTANCES.REMAP_SET_ID));
    entity.setScriptPatchPinnedAt(toInstant(record.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_AT)));
    entity.setScriptPatchPinnedBy(record.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_BY));
    entity.setScriptPatchPinnedReason(record.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_REASON));
    entity.setScriptPatchPinnedControlPlaneRequestId(
        record.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID));
    entity.setOwnerAccountId(record.get(GAME_INSTANCES.OWNER_ACCOUNT_ID));
    entity.setStatus(record.get(GAME_INSTANCES.STATUS));
    entity.setRowVersion(record.get(GAME_INSTANCES.ROW_VERSION));
    return entity;
  }
}
