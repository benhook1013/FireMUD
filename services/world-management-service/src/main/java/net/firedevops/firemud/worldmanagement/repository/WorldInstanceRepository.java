package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldInstance.WORLD_INSTANCE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.WorldInstanceRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class WorldInstanceRepository {
  private final DSLContext dsl;

  public WorldInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<WorldInstance> findByTenantIdAndGameInstanceId(
      Long tenantId, Long gameInstanceId) {
    return dsl.selectFrom(WORLD_INSTANCE)
        .where(
            WORLD_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<WorldInstance> findById(Long id) {
    return dsl.selectFrom(WORLD_INSTANCE)
        .where(WORLD_INSTANCE.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public WorldInstance save(WorldInstance entity) {
    if (entity.getId() == null) {
      WorldInstanceRecord record = dsl.newRecord(WORLD_INSTANCE);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    long nextRowVersion = entity.getRowVersion() + 1L;
    int updated =
        dsl.update(WORLD_INSTANCE)
            .set(WORLD_INSTANCE.TENANT_ID, entity.getTenantId())
            .set(WORLD_INSTANCE.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(WORLD_INSTANCE.GAME_TEMPLATE_ID, entity.getGameTemplateId())
            .set(WORLD_INSTANCE.CONTROL_PLANE_REQUEST_ID, entity.getControlPlaneRequestId())
            .set(WORLD_INSTANCE.LAUNCH_DESCRIPTOR_ID, entity.getLaunchDescriptorId())
            .set(WORLD_INSTANCE.VERSION_ID, entity.getVersionId())
            .set(WORLD_INSTANCE.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(WORLD_INSTANCE.RUNTIME_FLAGS_JSON, entity.getRuntimeFlagsJson())
            .set(WORLD_INSTANCE.GENERATION_CONFIG_REVISION, entity.getGenerationConfigRevision())
            .set(WORLD_INSTANCE.RELEASE_BUNDLE_ID, entity.getReleaseBundleId())
            .set(WORLD_INSTANCE.PUBLISHED_RELEASE_BUNDLE_REF, entity.getPublishedReleaseBundleRef())
            .set(WORLD_INSTANCE.VERSION_STATE_EPOCH, entity.getVersionStateEpoch())
            .set(WORLD_INSTANCE.LIFECYCLE_EPOCH, entity.getLifecycleEpoch())
            .set(WORLD_INSTANCE.STATUS, entity.getStatus())
            .set(WORLD_INSTANCE.FAILURE_REASON, entity.getFailureReason())
            .set(
                WORLD_INSTANCE.CREATED_AT,
                JooqWorldManagementRepositorySupport.toLocalDateTime(entity.getCreatedAt()))
            .set(
                WORLD_INSTANCE.UPDATED_AT,
                JooqWorldManagementRepositorySupport.toLocalDateTime(entity.getUpdatedAt()))
            .set(WORLD_INSTANCE.ROW_VERSION, nextRowVersion)
            .set(WORLD_INSTANCE.TERMINATION_REQUEST_ID, entity.getTerminationRequestId())
            .set(
                WORLD_INSTANCE.TERMINATED_AT,
                JooqWorldManagementRepositorySupport.toLocalDateTime(entity.getTerminatedAt()))
            .set(WORLD_INSTANCE.REMAP_SET_ID, entity.getRemapSetId())
            .where(
                WORLD_INSTANCE
                    .ID
                    .eq(entity.getId())
                    .and(WORLD_INSTANCE.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("world_instance", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private void populate(WorldInstanceRecord record, WorldInstance entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setGameTemplateId(entity.getGameTemplateId());
    record.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    record.setLaunchDescriptorId(entity.getLaunchDescriptorId());
    record.setVersionId(entity.getVersionId());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setRuntimeFlagsJson(entity.getRuntimeFlagsJson());
    record.setGenerationConfigRevision(entity.getGenerationConfigRevision());
    record.setReleaseBundleId(entity.getReleaseBundleId());
    record.setPublishedReleaseBundleRef(entity.getPublishedReleaseBundleRef());
    record.setVersionStateEpoch(entity.getVersionStateEpoch());
    record.setLifecycleEpoch(entity.getLifecycleEpoch());
    record.setStatus(entity.getStatus());
    record.setFailureReason(entity.getFailureReason());
    record.setCreatedAt(
        JooqWorldManagementRepositorySupport.toLocalDateTime(entity.getCreatedAt()));
    record.setUpdatedAt(
        JooqWorldManagementRepositorySupport.toLocalDateTime(entity.getUpdatedAt()));
    record.setRowVersion(entity.getRowVersion());
    record.setTerminationRequestId(entity.getTerminationRequestId());
    record.setTerminatedAt(
        JooqWorldManagementRepositorySupport.toLocalDateTime(entity.getTerminatedAt()));
    record.setRemapSetId(entity.getRemapSetId());
  }

  private WorldInstance toEntity(Record record) {
    WorldInstance entity = new WorldInstance();
    entity.setId(record.get(WORLD_INSTANCE.ID));
    entity.setTenantId(record.get(WORLD_INSTANCE.TENANT_ID));
    entity.setGameInstanceId(record.get(WORLD_INSTANCE.GAME_INSTANCE_ID));
    entity.setGameTemplateId(record.get(WORLD_INSTANCE.GAME_TEMPLATE_ID));
    entity.setControlPlaneRequestId(record.get(WORLD_INSTANCE.CONTROL_PLANE_REQUEST_ID));
    entity.setLaunchDescriptorId(record.get(WORLD_INSTANCE.LAUNCH_DESCRIPTOR_ID));
    entity.setVersionId(record.get(WORLD_INSTANCE.VERSION_ID));
    entity.setScriptPatchVersion(record.get(WORLD_INSTANCE.SCRIPT_PATCH_VERSION));
    entity.setRuntimeFlagsJson(record.get(WORLD_INSTANCE.RUNTIME_FLAGS_JSON));
    entity.setGenerationConfigRevision(record.get(WORLD_INSTANCE.GENERATION_CONFIG_REVISION));
    entity.setReleaseBundleId(record.get(WORLD_INSTANCE.RELEASE_BUNDLE_ID));
    entity.setPublishedReleaseBundleRef(record.get(WORLD_INSTANCE.PUBLISHED_RELEASE_BUNDLE_REF));
    entity.setVersionStateEpoch(record.get(WORLD_INSTANCE.VERSION_STATE_EPOCH));
    entity.setLifecycleEpoch(record.get(WORLD_INSTANCE.LIFECYCLE_EPOCH));
    entity.setStatus(record.get(WORLD_INSTANCE.STATUS));
    entity.setFailureReason(record.get(WORLD_INSTANCE.FAILURE_REASON));
    entity.setCreatedAt(
        JooqWorldManagementRepositorySupport.toInstant(record.get(WORLD_INSTANCE.CREATED_AT)));
    entity.setUpdatedAt(
        JooqWorldManagementRepositorySupport.toInstant(record.get(WORLD_INSTANCE.UPDATED_AT)));
    entity.setRowVersion(record.get(WORLD_INSTANCE.ROW_VERSION));
    entity.setTerminationRequestId(record.get(WORLD_INSTANCE.TERMINATION_REQUEST_ID));
    entity.setTerminatedAt(
        JooqWorldManagementRepositorySupport.toInstant(record.get(WORLD_INSTANCE.TERMINATED_AT)));
    entity.setRemapSetId(record.get(WORLD_INSTANCE.REMAP_SET_ID));
    return entity;
  }
}
