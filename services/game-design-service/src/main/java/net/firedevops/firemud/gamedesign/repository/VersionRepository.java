package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
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
public class VersionRepository {
  private static final Table<?> VERSION_TABLE = DSL.table(DSL.name("version"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
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

  private final DSLContext dsl;

  public VersionRepository(DSLContext dsl) {
    this.dsl = dsl;
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

  public Optional<Version> findByTenantIdAndId(String tenantId, Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(VERSION_TABLE)
            .where(TENANT_ID.eq(tenantId).and(ID.eq(id)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public Optional<Version> findTopByTenantIdAndScriptPatchVersionOrderByVersionNumberDesc(
      String tenantId, String scriptPatchVersion) {
    return Optional.ofNullable(
        dsl.selectFrom(VERSION_TABLE)
            .where(TENANT_ID.eq(tenantId).and(SCRIPT_PATCH_VERSION.eq(scriptPatchVersion)))
            .orderBy(VERSION_NUMBER.desc(), ID.desc())
            .limit(1)
            .fetchOne(this::toEntity));
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

  public Version save(Version version) {
    LocalDateTime createdAt =
        version.getCreatedAt() == null ? LocalDateTime.now() : version.getCreatedAt();
    LocalDateTime updatedAt =
        version.getUpdatedAt() == null ? LocalDateTime.now() : version.getUpdatedAt();
    if (version.getId() == null) {
      Record record =
          Objects.requireNonNull(
              dsl.insertInto(VERSION_TABLE)
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
                  .returning(
                      ID,
                      TENANT_ID,
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
      return toEntity(record);
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
    Version version = new Version();
    version.setId(record.get(ID));
    version.setTenantId(record.get(TENANT_ID));
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
}
