package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
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
public class VersionAssetArtifactRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("version_asset_artifact"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<Integer> EXPORTED_VERSION_NUMBER =
      DSL.field(DSL.name("exported_version_number"), Integer.class);
  private static final Field<String> ARTIFACT_STATE =
      DSL.field(DSL.name("artifact_state"), String.class);
  private static final Field<Long> STATE_EPOCH = DSL.field(DSL.name("state_epoch"), Long.class);
  private static final Field<String> MANIFEST_HASH =
      DSL.field(DSL.name("manifest_hash"), String.class);
  private static final Field<String> LAST_WORKFLOW_ID =
      DSL.field(DSL.name("last_workflow_id"), String.class);
  private static final Field<String> LAST_ERROR_CODE =
      DSL.field(DSL.name("last_error_code"), String.class);
  private static final Field<String> LAST_ERROR_MESSAGE =
      DSL.field(DSL.name("last_error_message"), String.class);
  private static final Field<String> EXPORTED_MANIFEST_ASSET_KEYS_JSON =
      DSL.field(DSL.name("exported_manifest_asset_keys_json"), String.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      DSL.field(DSL.name("updated_at"), LocalDateTime.class);

  private final DSLContext dsl;

  public VersionAssetArtifactRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<VersionAssetArtifact> findByTenantIdAndVersionId(
      String tenantId, Long versionId) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(TENANT_ID.eq(tenantId).and(VERSION_ID.eq(versionId)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public VersionAssetArtifact save(VersionAssetArtifact artifact) {
    LocalDateTime updatedAt =
        artifact.getUpdatedAt() == null ? LocalDateTime.now() : artifact.getUpdatedAt();
    if (artifact.getId() == null) {
      Record record =
          dsl.insertInto(TABLE_REF)
              .set(TENANT_ID, artifact.getTenantId())
              .set(VERSION_ID, artifact.getVersionId())
              .set(EXPORTED_VERSION_NUMBER, artifact.getExportedVersionNumber())
              .set(ARTIFACT_STATE, artifact.getArtifactState().name())
              .set(STATE_EPOCH, artifact.getStateEpoch())
              .set(MANIFEST_HASH, artifact.getManifestHash())
              .set(LAST_WORKFLOW_ID, artifact.getLastWorkflowId())
              .set(LAST_ERROR_CODE, artifact.getLastErrorCode())
              .set(LAST_ERROR_MESSAGE, artifact.getLastErrorMessage())
              .set(EXPORTED_MANIFEST_ASSET_KEYS_JSON, artifact.getExportedManifestAssetKeysJson())
              .set(UPDATED_AT, updatedAt)
              .returning()
              .fetchOne();
      return toEntity(record);
    }
    dsl.update(TABLE_REF)
        .set(TENANT_ID, artifact.getTenantId())
        .set(VERSION_ID, artifact.getVersionId())
        .set(EXPORTED_VERSION_NUMBER, artifact.getExportedVersionNumber())
        .set(ARTIFACT_STATE, artifact.getArtifactState().name())
        .set(STATE_EPOCH, artifact.getStateEpoch())
        .set(MANIFEST_HASH, artifact.getManifestHash())
        .set(LAST_WORKFLOW_ID, artifact.getLastWorkflowId())
        .set(LAST_ERROR_CODE, artifact.getLastErrorCode())
        .set(LAST_ERROR_MESSAGE, artifact.getLastErrorMessage())
        .set(EXPORTED_MANIFEST_ASSET_KEYS_JSON, artifact.getExportedManifestAssetKeysJson())
        .set(UPDATED_AT, updatedAt)
        .where(ID.eq(artifact.getId()))
        .execute();
    return findByTenantIdAndVersionId(artifact.getTenantId(), artifact.getVersionId())
        .orElseThrow();
  }

  private VersionAssetArtifact toEntity(Record record) {
    if (record == null) {
      return null;
    }
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setId(record.get(ID));
    artifact.setTenantId(record.get(TENANT_ID));
    artifact.setVersionId(record.get(VERSION_ID));
    artifact.setExportedVersionNumber(record.get(EXPORTED_VERSION_NUMBER));
    String artifactState = record.get(ARTIFACT_STATE);
    artifact.setArtifactState(
        artifactState == null ? null : VersionAssetArtifactState.valueOf(artifactState));
    artifact.setStateEpoch(record.get(STATE_EPOCH));
    artifact.setManifestHash(record.get(MANIFEST_HASH));
    artifact.setLastWorkflowId(record.get(LAST_WORKFLOW_ID));
    artifact.setLastErrorCode(record.get(LAST_ERROR_CODE));
    artifact.setLastErrorMessage(record.get(LAST_ERROR_MESSAGE));
    artifact.setExportedManifestAssetKeysJson(record.get(EXPORTED_MANIFEST_ASSET_KEYS_JSON));
    artifact.setUpdatedAt(record.get(UPDATED_AT));
    return artifact;
  }
}
