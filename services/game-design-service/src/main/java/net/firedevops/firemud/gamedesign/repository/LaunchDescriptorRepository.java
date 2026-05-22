package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class LaunchDescriptorRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("launch_descriptor"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> LAUNCH_DESCRIPTOR_ID =
      DSL.field(DSL.name("launch_descriptor_id"), String.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> GAME_TEMPLATE_ID =
      DSL.field(DSL.name("game_template_id"), Long.class);
  private static final Field<String> CONTROL_PLANE_REQUEST_ID =
      DSL.field(DSL.name("control_plane_request_id"), String.class);
  private static final Field<String> REQUEST_HASH =
      DSL.field(DSL.name("request_hash"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<String> SCRIPT_PATCH_VERSION =
      DSL.field(DSL.name("script_patch_version"), String.class);
  private static final Field<String> RUNTIME_FLAGS_JSON =
      DSL.field(DSL.name("runtime_flags_json"), String.class);
  private static final Field<String> GENERATION_CONFIG_REVISION =
      DSL.field(DSL.name("generation_config_revision"), String.class);
  private static final Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);
  private static final Field<Long> RELEASE_BUNDLE_ID =
      DSL.field(DSL.name("release_bundle_id"), Long.class);
  private static final Field<String> PUBLISHED_RELEASE_BUNDLE_REF =
      DSL.field(DSL.name("published_release_bundle_ref"), String.class);
  private static final Field<String> REMAP_SET_ID =
      DSL.field(DSL.name("remap_set_id"), String.class);
  private static final Field<Timestamp> CREATED_AT =
      DSL.field(DSL.name("created_at"), Timestamp.class);

  private final DSLContext dsl;

  public LaunchDescriptorRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<LaunchDescriptor> findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
      String tenantId, Long gameTemplateId, String controlPlaneRequestId) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(
                TENANT_ID
                    .eq(tenantId)
                    .and(GAME_TEMPLATE_ID.eq(gameTemplateId))
                    .and(CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public boolean existsByTenantIdAndVersionId(String tenantId, Long versionId) {
    return dsl.fetchExists(TABLE_REF, TENANT_ID.eq(tenantId).and(VERSION_ID.eq(versionId)));
  }

  public LaunchDescriptor save(LaunchDescriptor descriptor) {
    LocalDateTime createdAt =
        descriptor.getCreatedAt() == null ? LocalDateTime.now() : descriptor.getCreatedAt();
    if (descriptor.getId() == null) {
      dsl.insertInto(TABLE_REF)
          .set(LAUNCH_DESCRIPTOR_ID, descriptor.getLaunchDescriptorId())
          .set(TENANT_ID, descriptor.getTenantId())
          .set(GAME_TEMPLATE_ID, descriptor.getGameTemplateId())
          .set(CONTROL_PLANE_REQUEST_ID, descriptor.getControlPlaneRequestId())
          .set(REQUEST_HASH, descriptor.getRequestHash())
          .set(VERSION_ID, descriptor.getVersionId())
          .set(SCRIPT_PATCH_VERSION, descriptor.getScriptPatchVersion())
          .set(RUNTIME_FLAGS_JSON, descriptor.getRuntimeFlagsJson())
          .set(GENERATION_CONFIG_REVISION, descriptor.getGenerationConfigRevision())
          .set(VERSION_STATE_EPOCH, descriptor.getVersionStateEpoch())
          .set(RELEASE_BUNDLE_ID, descriptor.getReleaseBundleId())
          .set(PUBLISHED_RELEASE_BUNDLE_REF, descriptor.getPublishedReleaseBundleRef())
          .set(REMAP_SET_ID, descriptor.getRemapSetId())
          .set(CREATED_AT, Timestamp.valueOf(createdAt))
          .execute();
      return findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
              descriptor.getTenantId(),
              descriptor.getGameTemplateId(),
              descriptor.getControlPlaneRequestId())
          .orElseThrow();
    }
    dsl.update(TABLE_REF)
        .set(LAUNCH_DESCRIPTOR_ID, descriptor.getLaunchDescriptorId())
        .set(TENANT_ID, descriptor.getTenantId())
        .set(GAME_TEMPLATE_ID, descriptor.getGameTemplateId())
        .set(CONTROL_PLANE_REQUEST_ID, descriptor.getControlPlaneRequestId())
        .set(REQUEST_HASH, descriptor.getRequestHash())
        .set(VERSION_ID, descriptor.getVersionId())
        .set(SCRIPT_PATCH_VERSION, descriptor.getScriptPatchVersion())
        .set(RUNTIME_FLAGS_JSON, descriptor.getRuntimeFlagsJson())
        .set(GENERATION_CONFIG_REVISION, descriptor.getGenerationConfigRevision())
        .set(VERSION_STATE_EPOCH, descriptor.getVersionStateEpoch())
        .set(RELEASE_BUNDLE_ID, descriptor.getReleaseBundleId())
        .set(PUBLISHED_RELEASE_BUNDLE_REF, descriptor.getPublishedReleaseBundleRef())
        .set(REMAP_SET_ID, descriptor.getRemapSetId())
        .set(CREATED_AT, Timestamp.valueOf(createdAt))
        .where(ID.eq(descriptor.getId()))
        .execute();
    return findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
            descriptor.getTenantId(),
            descriptor.getGameTemplateId(),
            descriptor.getControlPlaneRequestId())
        .orElseThrow();
  }

  private LaunchDescriptor toEntity(Record record) {
    if (record == null) {
      return null;
    }
    LaunchDescriptor descriptor = new LaunchDescriptor();
    descriptor.setId(record.get(ID));
    descriptor.setLaunchDescriptorId(record.get(LAUNCH_DESCRIPTOR_ID));
    descriptor.setTenantId(record.get(TENANT_ID));
    descriptor.setGameTemplateId(record.get(GAME_TEMPLATE_ID));
    descriptor.setControlPlaneRequestId(record.get(CONTROL_PLANE_REQUEST_ID));
    descriptor.setRequestHash(record.get(REQUEST_HASH));
    descriptor.setVersionId(record.get(VERSION_ID));
    descriptor.setScriptPatchVersion(record.get(SCRIPT_PATCH_VERSION));
    descriptor.setRuntimeFlagsJson(record.get(RUNTIME_FLAGS_JSON));
    descriptor.setGenerationConfigRevision(record.get(GENERATION_CONFIG_REVISION));
    descriptor.setVersionStateEpoch(record.get(VERSION_STATE_EPOCH));
    descriptor.setReleaseBundleId(record.get(RELEASE_BUNDLE_ID));
    descriptor.setPublishedReleaseBundleRef(record.get(PUBLISHED_RELEASE_BUNDLE_REF));
    descriptor.setRemapSetId(record.get(REMAP_SET_ID));
    Timestamp createdAt = record.get(CREATED_AT);
    descriptor.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
    return descriptor;
  }
}
