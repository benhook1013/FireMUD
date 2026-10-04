package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
  private static final Table<?> VERSION_SOURCE =
      DSL.table(DSL.name("version")).as("source_version");
  private static final Table<?> GAME_SOURCE = DSL.table(DSL.name("game")).as("source_game");
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<UUID> CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
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

  private static final Field<Long> SOURCE_VERSION_ID =
      DSL.field(DSL.name("source_version", "id"), Long.class);
  private static final Field<String> SOURCE_VERSION_TENANT_ID =
      DSL.field(DSL.name("source_version", "tenant_id"), String.class);
  private static final Field<UUID> SOURCE_VERSION_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("source_version", "canonical_tenant_id"), UUID.class);
  private static final Field<UUID> SOURCE_VERSION_CANONICAL_VERSION_ID =
      DSL.field(DSL.name("source_version", "canonical_version_id"), UUID.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_version", "identity_source_game_row_id"), Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_version", "identity_source_game_tenant_key"), String.class);
  private static final Field<String> SOURCE_PROVENANCE_KIND =
      DSL.field(DSL.name("source_version", "identity_source_provenance_kind"), String.class);
  private static final Field<Long> SOURCE_GAME_ID =
      DSL.field(DSL.name("source_game", "id"), Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_ID =
      DSL.field(DSL.name("source_game", "tenant_id"), String.class);
  private static final Field<UUID> SOURCE_GAME_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("source_game", "canonical_tenant_id"), UUID.class);
  private static final Field<String> SOURCE_GAME_PROVENANCE_KIND =
      DSL.field(DSL.name("source_game", "tenant_identity_provenance_kind"), String.class);
  private static final Field<Long> SOURCE_GAME_SOURCE_ROW_ID =
      DSL.field(DSL.name("source_game", "tenant_identity_source_game_id"), Long.class);
  private static final Field<String> SOURCE_GAME_SOURCE_TENANT_KEY =
      DSL.field(DSL.name("source_game", "tenant_identity_source_legacy_tenant_id"), String.class);

  private static final UUID NIL_UUID = new UUID(0L, 0L);

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
    Objects.requireNonNull(bundle, "bundle");
    if (bundle.getId() != null) {
      throw new IllegalStateException(
          "Published release bundle is immutable; read the stored row instead of saving it again");
    }
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          LocalDateTime publishedAt =
              bundle.getPublishedAt() == null ? LocalDateTime.now() : bundle.getPublishedAt();
          return insertNewBundle(tx, bundle, publishedAt);
        });
  }

  private PublishedReleaseBundle insertNewBundle(
      DSLContext tx, PublishedReleaseBundle bundle, LocalDateTime publishedAt) {
    CanonicalSource source = findExactCanonicalSource(tx, bundle);
    rejectCallerIdentitySubstitution(bundle, source);

    Long generatedId =
        tx.insertInto(TABLE_REF)
            .set(TENANT_ID, bundle.getTenantId())
            .set(VERSION_ID, bundle.getVersionId())
            .set(CANONICAL_TENANT_ID, source.canonicalTenantId())
            .set(CANONICAL_VERSION_ID, source.canonicalVersionId())
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
    if (generatedId == null) {
      throw new IllegalStateException("Published release bundle insert returned no row id");
    }

    PublishedReleaseBundle persisted = findById(tx, generatedId).orElseThrow();
    verifyPersistedSource(persisted, bundle, source);
    return persisted;
  }

  private CanonicalSource findExactCanonicalSource(DSLContext tx, PublishedReleaseBundle bundle) {
    if (bundle.getTenantId() == null || bundle.getVersionId() == null) {
      throw new IllegalArgumentException(
          "Published release bundle requires tenant and Version row");
    }

    Record source =
        tx.select(SOURCE_VERSION_CANONICAL_TENANT_ID, SOURCE_VERSION_CANONICAL_VERSION_ID)
            .from(VERSION_SOURCE.join(GAME_SOURCE).on(SOURCE_GAME_ROW_ID.eq(SOURCE_GAME_ID)))
            .where(
                SOURCE_VERSION_ID
                    .eq(bundle.getVersionId())
                    .and(SOURCE_VERSION_TENANT_ID.eq(bundle.getTenantId()))
                    .and(SOURCE_VERSION_CANONICAL_TENANT_ID.isNotNull())
                    .and(SOURCE_VERSION_CANONICAL_TENANT_ID.ne(NIL_UUID))
                    .and(SOURCE_VERSION_CANONICAL_VERSION_ID.isNotNull())
                    .and(SOURCE_VERSION_CANONICAL_VERSION_ID.ne(NIL_UUID))
                    .and(SOURCE_VERSION_TENANT_ID.eq(SOURCE_GAME_TENANT_ID))
                    .and(SOURCE_GAME_TENANT_KEY.eq(SOURCE_GAME_TENANT_ID))
                    .and(SOURCE_VERSION_CANONICAL_TENANT_ID.eq(SOURCE_GAME_CANONICAL_TENANT_ID))
                    .and(SOURCE_PROVENANCE_KIND.eq(SOURCE_GAME_PROVENANCE_KIND))
                    .and(SOURCE_PROVENANCE_KIND.in("NEW_GAME_ROW", "RETAINED_GAME_V30"))
                    .and(SOURCE_GAME_SOURCE_ROW_ID.eq(SOURCE_GAME_ID))
                    .and(SOURCE_GAME_SOURCE_TENANT_KEY.eq(SOURCE_GAME_TENANT_ID)))
            .fetchOne();
    if (source == null) {
      throw new IllegalStateException(
          "No exact canonical Game Design source association exists for the requested Version");
    }

    UUID canonicalTenantId = source.get(SOURCE_VERSION_CANONICAL_TENANT_ID);
    UUID canonicalVersionId = source.get(SOURCE_VERSION_CANONICAL_VERSION_ID);
    if (canonicalTenantId == null
        || NIL_UUID.equals(canonicalTenantId)
        || canonicalVersionId == null
        || NIL_UUID.equals(canonicalVersionId)) {
      throw new IllegalStateException("Exact Game Design source has an invalid canonical identity");
    }
    return new CanonicalSource(canonicalTenantId, canonicalVersionId);
  }

  private void rejectCallerIdentitySubstitution(
      PublishedReleaseBundle bundle, CanonicalSource source) {
    if ((bundle.getCanonicalTenantId() != null
            && !bundle.getCanonicalTenantId().equals(source.canonicalTenantId()))
        || (bundle.getCanonicalVersionId() != null
            && !bundle.getCanonicalVersionId().equals(source.canonicalVersionId()))) {
      throw new IllegalArgumentException(
          "Caller-supplied canonical release identity does not match the exact Version source");
    }
  }

  private void verifyPersistedSource(
      PublishedReleaseBundle persisted, PublishedReleaseBundle requested, CanonicalSource source) {
    if (!Objects.equals(persisted.getTenantId(), requested.getTenantId())
        || !Objects.equals(persisted.getVersionId(), requested.getVersionId())
        || !Objects.equals(persisted.getCanonicalTenantId(), source.canonicalTenantId())
        || !Objects.equals(persisted.getCanonicalVersionId(), source.canonicalVersionId())) {
      throw new IllegalStateException(
          "Published release bundle readback does not match its exact Version source");
    }
  }

  private Optional<PublishedReleaseBundle> findById(DSLContext tx, Long id) {
    return Optional.ofNullable(tx.selectFrom(TABLE_REF).where(ID.eq(id)).fetchOne(this::toEntity));
  }

  private PublishedReleaseBundle toEntity(Record record) {
    if (record == null) {
      return null;
    }
    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setId(record.get(ID));
    bundle.setTenantId(record.get(TENANT_ID));
    bundle.setVersionId(record.get(VERSION_ID));
    bundle.setCanonicalTenantId(record.get(CANONICAL_TENANT_ID));
    bundle.setCanonicalVersionId(record.get(CANONICAL_VERSION_ID));
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

  private record CanonicalSource(UUID canonicalTenantId, UUID canonicalVersionId) {}
}
