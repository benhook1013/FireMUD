package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
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
public class PublishedReleaseBundleRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("published_release_bundle"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<Integer> VERSION_NUMBER =
      DSL.field(DSL.name("version_number"), Integer.class);
  private static final Field<String> ATTESTATION_SCHEMA_VERSION =
      DSL.field(DSL.name("attestation_schema_version"), String.class);
  private static final Field<String> PUBLISH_WORKFLOW_ID =
      DSL.field(DSL.name("publish_workflow_id"), String.class);
  private static final Field<String> MANIFEST_HASH =
      DSL.field(DSL.name("manifest_hash"), String.class);
  private static final Field<String> GENERATION_CONFIG_REVISION =
      DSL.field(DSL.name("generation_config_revision"), String.class);
  private static final Field<String> REQUIRED_MANIFEST_ASSET_KEYS_JSON =
      DSL.field(DSL.name("required_manifest_asset_keys_json"), String.class);
  private static final Field<String> PARTICIPANT_DIGESTS_JSON =
      DSL.field(DSL.name("participant_digests_json"), String.class);
  private static final Field<String> COMMAND_DEFINITIONS_JSON =
      DSL.field(DSL.name("command_definitions_json"), String.class);
  private static final Field<Boolean> SCRIPT_ONLY =
      DSL.field(DSL.name("script_only"), Boolean.class);
  private static final Field<String> SCRIPT_PATCH_VERSION =
      DSL.field(DSL.name("script_patch_version"), String.class);
  private static final Field<Timestamp> PUBLISHED_AT =
      DSL.field(DSL.name("published_at"), Timestamp.class);

  private final DSLContext dsl;

  public PublishedReleaseBundleRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<PublishedReleaseBundle> findByTenantIdAndVersionId(
      String tenantId, Long versionId) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(TENANT_ID.eq(tenantId).and(VERSION_ID.eq(versionId)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public PublishedReleaseBundle save(PublishedReleaseBundle bundle) {
    LocalDateTime publishedAt =
        bundle.getPublishedAt() == null ? LocalDateTime.now() : bundle.getPublishedAt();
    if (bundle.getId() == null) {
      Long generatedId =
          dsl.insertInto(TABLE_REF)
              .set(TENANT_ID, bundle.getTenantId())
              .set(VERSION_ID, bundle.getVersionId())
              .set(VERSION_NUMBER, bundle.getVersionNumber())
              .set(ATTESTATION_SCHEMA_VERSION, bundle.getAttestationSchemaVersion())
              .set(PUBLISH_WORKFLOW_ID, bundle.getPublishWorkflowId())
              .set(MANIFEST_HASH, bundle.getManifestHash())
              .set(GENERATION_CONFIG_REVISION, bundle.getGenerationConfigRevision())
              .set(REQUIRED_MANIFEST_ASSET_KEYS_JSON, bundle.getRequiredManifestAssetKeysJson())
              .set(PARTICIPANT_DIGESTS_JSON, bundle.getParticipantDigestsJson())
              .set(COMMAND_DEFINITIONS_JSON, bundle.getCommandDefinitionsJson())
              .set(SCRIPT_ONLY, bundle.isScriptOnly())
              .set(SCRIPT_PATCH_VERSION, bundle.getScriptPatchVersion())
              .set(PUBLISHED_AT, Timestamp.valueOf(publishedAt))
              .returningResult(ID)
              .fetchOne(ID);
      return dsl.selectFrom(TABLE_REF).where(ID.eq(generatedId)).fetchOne(this::toEntity);
    }
    dsl.update(TABLE_REF)
        .set(TENANT_ID, bundle.getTenantId())
        .set(VERSION_ID, bundle.getVersionId())
        .set(VERSION_NUMBER, bundle.getVersionNumber())
        .set(ATTESTATION_SCHEMA_VERSION, bundle.getAttestationSchemaVersion())
        .set(PUBLISH_WORKFLOW_ID, bundle.getPublishWorkflowId())
        .set(MANIFEST_HASH, bundle.getManifestHash())
        .set(GENERATION_CONFIG_REVISION, bundle.getGenerationConfigRevision())
        .set(REQUIRED_MANIFEST_ASSET_KEYS_JSON, bundle.getRequiredManifestAssetKeysJson())
        .set(PARTICIPANT_DIGESTS_JSON, bundle.getParticipantDigestsJson())
        .set(COMMAND_DEFINITIONS_JSON, bundle.getCommandDefinitionsJson())
        .set(SCRIPT_ONLY, bundle.isScriptOnly())
        .set(SCRIPT_PATCH_VERSION, bundle.getScriptPatchVersion())
        .set(PUBLISHED_AT, Timestamp.valueOf(publishedAt))
        .where(ID.eq(bundle.getId()))
        .execute();
    return findByTenantIdAndVersionId(bundle.getTenantId(), bundle.getVersionId()).orElseThrow();
  }

  private PublishedReleaseBundle toEntity(Record record) {
    if (record == null) {
      return null;
    }
    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setId(record.get(ID));
    bundle.setTenantId(record.get(TENANT_ID));
    bundle.setVersionId(record.get(VERSION_ID));
    bundle.setVersionNumber(record.get(VERSION_NUMBER));
    bundle.setAttestationSchemaVersion(record.get(ATTESTATION_SCHEMA_VERSION));
    bundle.setPublishWorkflowId(record.get(PUBLISH_WORKFLOW_ID));
    bundle.setManifestHash(record.get(MANIFEST_HASH));
    bundle.setGenerationConfigRevision(record.get(GENERATION_CONFIG_REVISION));
    bundle.setRequiredManifestAssetKeysJson(record.get(REQUIRED_MANIFEST_ASSET_KEYS_JSON));
    bundle.setParticipantDigestsJson(record.get(PARTICIPANT_DIGESTS_JSON));
    bundle.setCommandDefinitionsJson(record.get(COMMAND_DEFINITIONS_JSON));
    bundle.setScriptOnly(Boolean.TRUE.equals(record.get(SCRIPT_ONLY)));
    bundle.setScriptPatchVersion(record.get(SCRIPT_PATCH_VERSION));
    Timestamp publishedAt = record.get(PUBLISHED_AT);
    bundle.setPublishedAt(publishedAt == null ? null : publishedAt.toLocalDateTime());
    return bundle;
  }
}
