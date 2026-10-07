package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class VersionRepository {
  private static final String ENTITY_DIGEST_BASELINE_MIGRATION_VERSION_LOCK_FUNCTION_NAME =
      "lock_version_for_entity_digest_baseline_migration";
  private static final Table<?> VERSION_TABLE = DSL.table(DSL.name("version"));
  private static final Table<?> GAME_TABLE = DSL.table(DSL.name("game"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<UUID> CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<Long> IDENTITY_SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("identity_source_game_row_id"), Long.class);
  private static final Field<String> IDENTITY_SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("identity_source_game_tenant_key"), String.class);
  private static final Field<String> IDENTITY_SOURCE_PROVENANCE_KIND =
      DSL.field(DSL.name("identity_source_provenance_kind"), String.class);
  private static final Field<String> VERSION_TENANT_ID =
      DSL.field(DSL.name("version", "tenant_id"), String.class);
  private static final Field<UUID> VERSION_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("version", "canonical_tenant_id"), UUID.class);
  private static final Field<Long> VERSION_IDENTITY_SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("version", "identity_source_game_row_id"), Long.class);
  private static final Field<String> VERSION_IDENTITY_SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("version", "identity_source_game_tenant_key"), String.class);
  private static final Field<String> VERSION_IDENTITY_SOURCE_PROVENANCE_KIND =
      DSL.field(DSL.name("version", "identity_source_provenance_kind"), String.class);
  private static final Field<Long> GAME_ID = DSL.field(DSL.name("game", "id"), Long.class);
  private static final Field<String> GAME_TENANT_ID =
      DSL.field(DSL.name("game", "tenant_id"), String.class);
  private static final Field<UUID> GAME_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("game", "canonical_tenant_id"), UUID.class);
  private static final Field<String> GAME_TENANT_PROVENANCE_KIND =
      DSL.field(DSL.name("game", "tenant_identity_provenance_kind"), String.class);
  private static final Field<Long> GAME_TENANT_SOURCE_GAME_ID =
      DSL.field(DSL.name("game", "tenant_identity_source_game_id"), Long.class);
  private static final Field<String> GAME_TENANT_SOURCE_LEGACY_KEY =
      DSL.field(DSL.name("game", "tenant_identity_source_legacy_tenant_id"), String.class);
  private static final Field<Integer> VERSION_NUMBER =
      DSL.field(DSL.name("version_number"), Integer.class);
  private static final Field<String> VERSION_STATE =
      DSL.field(DSL.name("version_state"), String.class);
  private static final Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);
  private static final Field<String> SCRIPT_PATCH_VERSION =
      DSL.field(DSL.name("script_patch_version"), String.class);
  private static final Field<Long> BASE_VERSION_ID =
      DSL.field(DSL.name("base_version_id"), Long.class);
  private static final Field<Boolean> IS_SCRIPT_ONLY =
      DSL.field(DSL.name("is_script_only"), Boolean.class);
  private static final Field<String> NOTES = DSL.field(DSL.name("notes"), String.class);
  private static final Field<Timestamp> CREATED_AT =
      DSL.field(DSL.name("created_at"), Timestamp.class);
  private static final Field<Timestamp> UPDATED_AT =
      DSL.field(DSL.name("updated_at"), Timestamp.class);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final String postgresSchema;

  public VersionRepository(DSLContext dsl, PostgresProperties postgres) {
    this.dsl = dsl;
    this.postgresSchema = postgres == null ? null : postgres.getSchema();
  }

  public List<Version> findAllByTenantIdOrderByVersionNumberAsc(String tenantId) {
    return dsl.selectFrom(VERSION_TABLE)
        .where(TENANT_ID.eq(tenantId))
        .orderBy(VERSION_NUMBER.asc(), ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<Version> findTopByTenantIdOrderByVersionNumberDesc(String tenantId) {
    return Optional.ofNullable(
        dsl.selectFrom(VERSION_TABLE)
            .where(TENANT_ID.eq(tenantId))
            .orderBy(VERSION_NUMBER.desc(), ID.desc())
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public Optional<Version> findByTenantIdAndVersionNumber(String tenantId, Integer versionNumber) {
    return Optional.ofNullable(
        dsl.selectFrom(VERSION_TABLE)
            .where(TENANT_ID.eq(tenantId).and(VERSION_NUMBER.eq(versionNumber)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public Optional<Version> findByTenantIdAndId(String tenantId, Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(VERSION_TABLE)
            .where(TENANT_ID.eq(tenantId).and(ID.eq(id)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  /** Reads and locks the exact version row for owner-local guarded lifecycle writes. */
  public Optional<Version> findByTenantIdAndIdForUpdate(String tenantId, Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(VERSION_TABLE)
            .where(TENANT_ID.eq(tenantId).and(ID.eq(id)))
            .forUpdate()
            .fetchOne(this::toEntity));
  }

  /**
   * Reads and locks the exact version row through the restricted migration-only database function.
   * The function performs the lock with its owner privileges so the migration writer need not have
   * direct Version UPDATE privilege.
   */
  public Optional<Version> findByTenantIdAndIdForEntityDigestBaselineMigration(
      String tenantId, Long id) {
    if (postgresSchema == null || postgresSchema.isBlank()) {
      throw new IllegalStateException(
          "Game Design PostgreSQL schema must be configured for the "
              + "Entity digest baseline Version lock");
    }
    String versionLockFunction =
        dsl.render(
            DSL.quotedName(
                postgresSchema, ENTITY_DIGEST_BASELINE_MIGRATION_VERSION_LOCK_FUNCTION_NAME));
    return Optional.ofNullable(
        dsl.resultQuery(
                "SELECT id, tenant_id, version_number, version_state, version_state_epoch, "
                    + "script_patch_version, base_version_id, is_script_only, notes, "
                    + "created_at, updated_at FROM "
                    + versionLockFunction
                    + "(?, ?)",
                tenantId,
                id)
            .fetchOne(this::toEntityWithoutCanonicalIdentity));
  }

  /**
   * Resolves only a canonical version UUID whose persisted tenant/source provenance still matches
   * the exact Game Design owner row. Numeric Version IDs remain private to owner-local callers.
   */
  public Optional<Version> findByCanonicalTenantIdAndCanonicalVersionId(
      UUID canonicalTenantId, UUID canonicalVersionId) {
    if (canonicalTenantId == null
        || canonicalVersionId == null
        || canonicalTenantId.equals(NIL_UUID)
        || canonicalVersionId.equals(NIL_UUID)) {
      return Optional.empty();
    }

    return Optional.ofNullable(
        dsl.selectFrom(VERSION_TABLE)
            .where(
                CANONICAL_VERSION_ID
                    .eq(canonicalVersionId)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(
                        DSL.exists(
                            DSL.selectOne()
                                .from(GAME_TABLE)
                                .where(
                                    GAME_ID
                                        .eq(VERSION_IDENTITY_SOURCE_GAME_ROW_ID)
                                        .and(
                                            GAME_TENANT_ID.eq(
                                                VERSION_IDENTITY_SOURCE_GAME_TENANT_KEY))
                                        .and(GAME_TENANT_ID.eq(VERSION_TENANT_ID))
                                        .and(
                                            GAME_CANONICAL_TENANT_ID.eq(
                                                VERSION_CANONICAL_TENANT_ID))
                                        .and(
                                            GAME_TENANT_PROVENANCE_KIND.eq(
                                                VERSION_IDENTITY_SOURCE_PROVENANCE_KIND))
                                        .and(GAME_TENANT_SOURCE_GAME_ID.eq(GAME_ID))
                                        .and(GAME_TENANT_SOURCE_LEGACY_KEY.eq(GAME_TENANT_ID))))))
            .fetchOne(this::toEntity));
  }

  /**
   * Returns every retained publication metadata candidate for an exact tenant/base/patch scope.
   * Callers reject anything other than one row so missing or duplicate retained scope is
   * fail-closed. Historical ACTIVE and RETIRED rows remain valid metadata for this lookup; new
   * runtime activation admission applies its own PUBLISHED-only gate.
   */
  public List<Version> findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
      String tenantId, Long baseVersionId, String scriptPatchVersion) {
    return dsl.selectFrom(VERSION_TABLE)
        .where(
            TENANT_ID
                .eq(tenantId)
                .and(BASE_VERSION_ID.eq(baseVersionId))
                .and(SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
                .and(
                    VERSION_STATE.in(
                        VersionLifecycleState.PUBLISHED.name(),
                        VersionLifecycleState.ACTIVE.name(),
                        VersionLifecycleState.RETIRED.name()))
                .and(IS_SCRIPT_ONLY.isTrue()))
        .orderBy(VERSION_NUMBER.desc(), ID.desc())
        .fetch(this::toEntity);
  }

  /**
   * Returns every retained script-only row for an exact effective artifact identity. The caller
   * holds the tenant game-row lock before using this read and must reject a non-empty result when
   * no exact publish-request replay exists. The database uniqueness index remains the concurrency
   * backstop for callers that race outside this service boundary.
   */
  public List<Version> findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
      String tenantId, Long baseVersionId, String scriptPatchVersion) {
    return dsl.selectFrom(VERSION_TABLE)
        .where(
            TENANT_ID
                .eq(tenantId)
                .and(BASE_VERSION_ID.eq(baseVersionId))
                .and(SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
                .and(IS_SCRIPT_ONLY.isTrue()))
        .orderBy(VERSION_NUMBER.asc(), ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<Version> findById(Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(VERSION_TABLE).where(ID.eq(id)).limit(1).fetchOne(this::toEntity));
  }

  public List<Version> findAll() {
    return dsl.selectFrom(VERSION_TABLE).orderBy(ID.asc()).fetch(this::toEntity);
  }

  public long count() {
    return dsl.fetchCount(VERSION_TABLE);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Version save(Version version) {
    if (version.getId() == null
        && version.getVersionState() == VersionLifecycleState.DRAFT
        && !version.isScriptOnly()
        && version.getBaseVersionId() == null
        && version.getScriptPatchVersion() == null
        && (!TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))) {
      throw new IllegalStateException(
          "New full Draft creation requires one writable READ_COMMITTED source transaction");
    }
    LocalDateTime createdAt =
        version.getCreatedAt() == null ? LocalDateTime.now() : version.getCreatedAt();
    LocalDateTime updatedAt =
        version.getUpdatedAt() == null ? LocalDateTime.now() : version.getUpdatedAt();
    if (version.getId() == null) {
      VersionSource source = findExactGameSource(version.getTenantId());
      UUID canonicalVersionId = newNonNilUuid();
      Record record =
          Objects.requireNonNull(
              dsl.insertInto(VERSION_TABLE)
                  .set(TENANT_ID, version.getTenantId())
                  .set(CANONICAL_VERSION_ID, canonicalVersionId)
                  .set(CANONICAL_TENANT_ID, source.canonicalTenantId())
                  .set(IDENTITY_SOURCE_GAME_ROW_ID, source.sourceGameRowId())
                  .set(IDENTITY_SOURCE_GAME_TENANT_KEY, source.sourceGameTenantKey())
                  .set(IDENTITY_SOURCE_PROVENANCE_KIND, source.provenanceKind())
                  .set(VERSION_NUMBER, version.getVersionNumber())
                  .set(VERSION_STATE, version.getVersionState().name())
                  .set(VERSION_STATE_EPOCH, version.getVersionStateEpoch())
                  .set(SCRIPT_PATCH_VERSION, version.getScriptPatchVersion())
                  .set(BASE_VERSION_ID, version.getBaseVersionId())
                  .set(IS_SCRIPT_ONLY, version.isScriptOnly())
                  .set(NOTES, version.getNotes())
                  .set(CREATED_AT, Timestamp.valueOf(createdAt))
                  .set(UPDATED_AT, Timestamp.valueOf(updatedAt))
                  .returning(
                      ID,
                      TENANT_ID,
                      CANONICAL_VERSION_ID,
                      CANONICAL_TENANT_ID,
                      IDENTITY_SOURCE_GAME_ROW_ID,
                      IDENTITY_SOURCE_GAME_TENANT_KEY,
                      IDENTITY_SOURCE_PROVENANCE_KIND,
                      VERSION_NUMBER,
                      VERSION_STATE,
                      VERSION_STATE_EPOCH,
                      SCRIPT_PATCH_VERSION,
                      BASE_VERSION_ID,
                      IS_SCRIPT_ONLY,
                      NOTES,
                      CREATED_AT,
                      UPDATED_AT)
                  .fetchOne());
      Version inserted = toEntity(record);
      Version persisted = findById(inserted.getId()).orElseThrow();
      requireIssuedIdentityReadback(persisted, inserted.getTenantId(), canonicalVersionId, source);
      if (persisted.getVersionState() == VersionLifecycleState.DRAFT
          && !persisted.isScriptOnly()
          && persisted.getBaseVersionId() == null
          && persisted.getScriptPatchVersion() == null) {
        new GameDesignSourceRepository(dsl)
            .enrollFreshDraft(
                new TargetProof(
                    persisted.getCanonicalTenantId(),
                    persisted.getCanonicalVersionId(),
                    persisted.getId(),
                    persisted.getTenantId(),
                    persisted.getIdentitySourceGameRowId(),
                    persisted.getIdentitySourceGameTenantKey(),
                    persisted.getIdentitySourceProvenanceKind()));
      }
      return persisted;
    }
    dsl.update(VERSION_TABLE)
        .set(TENANT_ID, version.getTenantId())
        .set(VERSION_NUMBER, version.getVersionNumber())
        .set(VERSION_STATE, version.getVersionState().name())
        .set(VERSION_STATE_EPOCH, version.getVersionStateEpoch())
        .set(SCRIPT_PATCH_VERSION, version.getScriptPatchVersion())
        .set(BASE_VERSION_ID, version.getBaseVersionId())
        .set(IS_SCRIPT_ONLY, version.isScriptOnly())
        .set(NOTES, version.getNotes())
        .set(CREATED_AT, Timestamp.valueOf(createdAt))
        .set(UPDATED_AT, Timestamp.valueOf(updatedAt))
        .where(ID.eq(version.getId()))
        .execute();
    return findById(version.getId()).orElseThrow();
  }

  public void delete(Version version) {
    if (version.getId() != null) {
      dsl.deleteFrom(VERSION_TABLE).where(ID.eq(version.getId())).execute();
    }
  }

  private Version toEntity(Record record) {
    return toEntity(record, true);
  }

  /** The restricted baseline-lock function intentionally returns only its historical projection. */
  private Version toEntityWithoutCanonicalIdentity(Record record) {
    return toEntity(record, false);
  }

  private Version toEntity(Record record, boolean includeCanonicalIdentity) {
    Version version = new Version();
    version.setId(record.get(ID));
    version.setTenantId(record.get(TENANT_ID));
    if (includeCanonicalIdentity) {
      version.setCanonicalVersionId(record.get(CANONICAL_VERSION_ID));
      version.setCanonicalTenantId(record.get(CANONICAL_TENANT_ID));
      version.setIdentitySourceGameRowId(record.get(IDENTITY_SOURCE_GAME_ROW_ID));
      version.setIdentitySourceGameTenantKey(record.get(IDENTITY_SOURCE_GAME_TENANT_KEY));
      version.setIdentitySourceProvenanceKind(record.get(IDENTITY_SOURCE_PROVENANCE_KIND));
    }
    version.setVersionNumber(record.get(VERSION_NUMBER));
    String versionState = record.get(VERSION_STATE);
    version.setVersionState(
        versionState == null
            ? VersionLifecycleState.DRAFT
            : VersionLifecycleState.valueOf(versionState));
    version.setVersionStateEpoch(record.get(VERSION_STATE_EPOCH));
    version.setScriptPatchVersion(record.get(SCRIPT_PATCH_VERSION));
    version.setBaseVersionId(record.get(BASE_VERSION_ID));
    version.setScriptOnly(Boolean.TRUE.equals(record.get(IS_SCRIPT_ONLY)));
    version.setNotes(record.get(NOTES));
    Timestamp createdAt = record.get(CREATED_AT);
    version.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
    Timestamp updatedAt = record.get(UPDATED_AT);
    version.setUpdatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime());
    return version;
  }

  private VersionSource findExactGameSource(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalStateException(
          "New Version requires an exact Game Design source tenant key");
    }
    Record source =
        dsl.select(
                GAME_ID,
                GAME_TENANT_ID,
                GAME_CANONICAL_TENANT_ID,
                GAME_TENANT_PROVENANCE_KIND,
                GAME_TENANT_SOURCE_GAME_ID,
                GAME_TENANT_SOURCE_LEGACY_KEY)
            .from(GAME_TABLE)
            .where(GAME_TENANT_ID.eq(tenantId))
            .fetchOne();
    if (source == null) {
      throw new IllegalStateException("New Version tenant has no exact Game Design source row");
    }

    Long sourceGameRowId = source.get(GAME_ID);
    String sourceGameTenantKey = source.get(GAME_TENANT_ID);
    UUID canonicalTenantId = source.get(GAME_CANONICAL_TENANT_ID);
    String provenanceKind = source.get(GAME_TENANT_PROVENANCE_KIND);
    Long tenantSourceGameRowId = source.get(GAME_TENANT_SOURCE_GAME_ID);
    String tenantSourceGameTenantKey = source.get(GAME_TENANT_SOURCE_LEGACY_KEY);
    if (sourceGameRowId == null
        || sourceGameRowId <= 0
        || !tenantId.equals(sourceGameTenantKey)
        || canonicalTenantId == null
        || canonicalTenantId.equals(NIL_UUID)
        || provenanceKind == null
        || !("NEW_GAME_ROW".equals(provenanceKind) || "RETAINED_GAME_V30".equals(provenanceKind))
        || !sourceGameRowId.equals(tenantSourceGameRowId)
        || !tenantId.equals(tenantSourceGameTenantKey)) {
      throw new IllegalStateException(
          "New Version tenant source provenance is incomplete or invalid");
    }
    return new VersionSource(
        canonicalTenantId, sourceGameRowId, sourceGameTenantKey, provenanceKind);
  }

  private void requireIssuedIdentityReadback(
      Version persisted, String requestedTenantKey, UUID canonicalVersionId, VersionSource source) {
    if (persisted.getId() == null
        || persisted.getId() <= 0
        || !requestedTenantKey.equals(persisted.getTenantId())
        || !canonicalVersionId.equals(persisted.getCanonicalVersionId())
        || !source.canonicalTenantId().equals(persisted.getCanonicalTenantId())
        || !source.sourceGameRowId().equals(persisted.getIdentitySourceGameRowId())
        || !source.sourceGameTenantKey().equals(persisted.getIdentitySourceGameTenantKey())
        || !source.provenanceKind().equals(persisted.getIdentitySourceProvenanceKind())) {
      throw new IllegalStateException("Persisted Version identity did not match owner issuance");
    }
  }

  private UUID newNonNilUuid() {
    UUID uuid;
    do {
      uuid = UUID.randomUUID();
    } while (NIL_UUID.equals(uuid));
    return uuid;
  }

  private record VersionSource(
      UUID canonicalTenantId,
      Long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {}
}
