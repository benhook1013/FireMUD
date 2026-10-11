package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldInstance.WORLD_INSTANCE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
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

  public Optional<WorldInstance> findByTenantIdAndGameInstanceIdForUpdate(
      Long tenantId, Long gameInstanceId) {
    return dsl.selectFrom(WORLD_INSTANCE)
        .where(
            WORLD_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .forUpdate()
        .fetchOptional(this::toEntity);
  }

  /** Serializes create/retry decisions for one tenant-owned fixture operation in PostgreSQL. */
  public void lockRunOwnedFixtureOperation(Long tenantId, String controlPlaneRequestId) {
    if (tenantId == null || tenantId <= 0L || controlPlaneRequestId == null) {
      throw new IllegalArgumentException("RUN_OWNED_WORLD_FIXTURE_OPERATION_INVALID");
    }
    String lockIdentity =
        tenantId
            + ":"
            + controlPlaneRequestId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + ":"
            + controlPlaneRequestId;
    dsl.fetchOne("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", lockIdentity);
  }

  public Optional<WorldInstance> findRunOwnedFixtureByTenantIdAndControlPlaneRequestIdForUpdate(
      Long tenantId, String controlPlaneRequestId) {
    return dsl.selectFrom(WORLD_INSTANCE)
        .where(
            WORLD_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_INSTANCE.CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId))
                .and(WORLD_INSTANCE.RUN_OWNED_FIXTURE_BINDING_SHA256.isNotNull()))
        .forUpdate()
        .fetchOptional(this::toEntity);
  }

  public Optional<WorldInstance> findRunOwnedFixtureByTenantIdAndControlPlaneRequestId(
      Long tenantId, String controlPlaneRequestId) {
    return dsl.selectFrom(WORLD_INSTANCE)
        .where(
            WORLD_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_INSTANCE.CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId))
                .and(WORLD_INSTANCE.RUN_OWNED_FIXTURE_BINDING_SHA256.isNotNull()))
        .fetchOptional(this::toEntity);
  }

  public boolean hasNonterminalInitialAdmissionBindHold(Long tenantId, Long gameInstanceId) {
    Record result =
        dsl.fetchOne(
            "SELECT EXISTS (SELECT 1 FROM initial_admission_bind_hold "
                + "WHERE tenant_id = ? AND game_instance_id = ? "
                + "AND status IN ('PENDING', 'RECONCILIATION_REQUIRED'))",
            tenantId,
            gameInstanceId);
    if (result == null) {
      throw new IllegalStateException(
          "INITIAL_ADMISSION_BIND_HOLD_LOOKUP_FAILED: lifecycle hold lookup returned no row");
    }
    return Boolean.TRUE.equals(result.get(0, Boolean.class));
  }

  public Optional<WorldInstance> findById(Long id) {
    return dsl.selectFrom(WORLD_INSTANCE)
        .where(WORLD_INSTANCE.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public WorldInstance save(WorldInstance entity) {
    if (entity.getId() == null) {
      Record inserted =
          dsl.insertInto(WORLD_INSTANCE)
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
              .set(
                  WORLD_INSTANCE.PUBLISHED_RELEASE_BUNDLE_REF,
                  entity.getPublishedReleaseBundleRef())
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
              .set(WORLD_INSTANCE.ROW_VERSION, entity.getRowVersion())
              .set(WORLD_INSTANCE.TERMINATION_REQUEST_ID, entity.getTerminationRequestId())
              .set(
                  WORLD_INSTANCE.TERMINATED_AT,
                  JooqWorldManagementRepositorySupport.toLocalDateTime(entity.getTerminatedAt()))
              .set(WORLD_INSTANCE.REMAP_SET_ID, entity.getRemapSetId())
              .set(
                  WORLD_INSTANCE.RUN_OWNED_FIXTURE_BINDING_SHA256,
                  entity.getRunOwnedFixtureBindingSha256())
              .set(
                  WORLD_INSTANCE.RUN_OWNED_FIXTURE_PREPARING_LIFECYCLE_EPOCH,
                  entity.getRunOwnedFixturePreparingLifecycleEpoch())
              .returning(WORLD_INSTANCE.ID)
              .fetchOne();
      if (inserted == null) {
        throw new IllegalStateException("world_instance insert returned no row");
      }
      return findById(inserted.get(WORLD_INSTANCE.ID)).orElseThrow();
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
    entity.setRunOwnedFixtureBindingSha256(
        record.get(WORLD_INSTANCE.RUN_OWNED_FIXTURE_BINDING_SHA256));
    entity.setRunOwnedFixturePreparingLifecycleEpoch(
        record.get(WORLD_INSTANCE.RUN_OWNED_FIXTURE_PREPARING_LIFECYCLE_EPOCH));
    return entity;
  }
}
