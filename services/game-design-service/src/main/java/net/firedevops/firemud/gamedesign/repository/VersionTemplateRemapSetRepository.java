package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.VersionTemplateRemapEntry;
import net.firedevops.firemud.gamedesign.entity.VersionTemplateRemapSet;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
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
public class VersionTemplateRemapSetRepository {
  private static final Table<?> SET_TABLE = DSL.table(DSL.name("version_template_remap_set"));
  private static final Field<Long> SET_ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> REMAP_SET_ID =
      DSL.field(DSL.name("remap_set_id"), String.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> SOURCE_VERSION_ID =
      DSL.field(DSL.name("source_version_id"), Long.class);
  private static final Field<Long> TARGET_VERSION_ID =
      DSL.field(DSL.name("target_version_id"), Long.class);
  private static final Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
  private static final Field<String> CREATED_REASON =
      DSL.field(DSL.name("created_reason"), String.class);
  private static final Field<String> APPROVAL_REASON =
      DSL.field(DSL.name("approval_reason"), String.class);
  private static final Field<LocalDateTime> CREATED_AT =
      DSL.field(DSL.name("created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> APPROVED_AT =
      DSL.field(DSL.name("approved_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      DSL.field(DSL.name("updated_at"), LocalDateTime.class);

  private static final Table<?> ENTRY_TABLE = DSL.table(DSL.name("version_template_remap_entry"));
  private static final Field<Long> ENTRY_ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<Long> ENTRY_REMAP_SET_PK =
      DSL.field(DSL.name("remap_set_pk"), Long.class);
  private static final Field<String> ENTRY_MAPPING_DOMAIN =
      DSL.field(DSL.name("mapping_domain"), String.class);
  private static final Field<String> ENTRY_MAPPING_TYPE =
      DSL.field(DSL.name("mapping_type"), String.class);
  private static final Field<String> ENTRY_SOURCE_TEMPLATE_KEY =
      DSL.field(DSL.name("source_template_key"), String.class);
  private static final Field<String> ENTRY_TARGET_TEMPLATE_KEY =
      DSL.field(DSL.name("target_template_key"), String.class);
  private static final Field<LocalDateTime> ENTRY_CREATED_AT =
      DSL.field(DSL.name("created_at"), LocalDateTime.class);

  private final DSLContext dsl;

  public VersionTemplateRemapSetRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<VersionTemplateRemapSet> findByTenantIdAndRemapSetId(
      String tenantId, String remapSetId) {
    return Optional.ofNullable(
        dsl.selectFrom(SET_TABLE)
            .where(TENANT_ID.eq(tenantId).and(REMAP_SET_ID.eq(remapSetId)))
            .fetchOne(this::toSetEntity));
  }

  public List<VersionTemplateRemapSet>
      findAllByTenantIdAndSourceVersionIdAndTargetVersionIdAndStatusOrderByCreatedAtAsc(
          String tenantId,
          Long sourceVersionId,
          Long targetVersionId,
          TemplateRemapSetStatus status) {
    return dsl.selectFrom(SET_TABLE)
        .where(
            TENANT_ID
                .eq(tenantId)
                .and(SOURCE_VERSION_ID.eq(sourceVersionId))
                .and(TARGET_VERSION_ID.eq(targetVersionId))
                .and(STATUS.eq(status.name())))
        .orderBy(CREATED_AT.asc())
        .fetch(this::toSetEntity);
  }

  public boolean existsByTenantIdAndSourceVersionIdAndStatus(
      String tenantId, Long sourceVersionId, TemplateRemapSetStatus status) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(SET_TABLE)
            .where(
                TENANT_ID
                    .eq(tenantId)
                    .and(SOURCE_VERSION_ID.eq(sourceVersionId))
                    .and(STATUS.eq(status.name()))));
  }

  public boolean existsByTenantIdAndTargetVersionIdAndStatus(
      String tenantId, Long targetVersionId, TemplateRemapSetStatus status) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(SET_TABLE)
            .where(
                TENANT_ID
                    .eq(tenantId)
                    .and(TARGET_VERSION_ID.eq(targetVersionId))
                    .and(STATUS.eq(status.name()))));
  }

  public VersionTemplateRemapSet save(VersionTemplateRemapSet remapSet) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime createdAt = remapSet.getCreatedAt() == null ? now : remapSet.getCreatedAt();
    LocalDateTime updatedAt = now;
    if (remapSet.getId() == null) {
      Record record =
          Objects.requireNonNull(
              dsl.insertInto(SET_TABLE)
                  .set(REMAP_SET_ID, remapSet.getRemapSetId())
                  .set(TENANT_ID, remapSet.getTenantId())
                  .set(SOURCE_VERSION_ID, remapSet.getSourceVersionId())
                  .set(TARGET_VERSION_ID, remapSet.getTargetVersionId())
                  .set(STATUS, remapSet.getStatus().name())
                  .set(CREATED_REASON, remapSet.getCreatedReason())
                  .set(APPROVAL_REASON, remapSet.getApprovalReason())
                  .set(CREATED_AT, createdAt)
                  .set(APPROVED_AT, remapSet.getApprovedAt())
                  .set(UPDATED_AT, updatedAt)
                  .returning()
                  .fetchOne());
      remapSet.setId(record.get(SET_ID));
      remapSet.setCreatedAt(record.get(CREATED_AT));
      remapSet.setUpdatedAt(record.get(UPDATED_AT));
    } else {
      dsl.update(SET_TABLE)
          .set(REMAP_SET_ID, remapSet.getRemapSetId())
          .set(TENANT_ID, remapSet.getTenantId())
          .set(SOURCE_VERSION_ID, remapSet.getSourceVersionId())
          .set(TARGET_VERSION_ID, remapSet.getTargetVersionId())
          .set(STATUS, remapSet.getStatus().name())
          .set(CREATED_REASON, remapSet.getCreatedReason())
          .set(APPROVAL_REASON, remapSet.getApprovalReason())
          .set(CREATED_AT, createdAt)
          .set(APPROVED_AT, remapSet.getApprovedAt())
          .set(UPDATED_AT, updatedAt)
          .where(SET_ID.eq(remapSet.getId()))
          .execute();
      dsl.deleteFrom(ENTRY_TABLE).where(ENTRY_REMAP_SET_PK.eq(remapSet.getId())).execute();
    }
    persistEntries(remapSet);
    return findByTenantIdAndRemapSetId(remapSet.getTenantId(), remapSet.getRemapSetId())
        .orElseThrow();
  }

  private void persistEntries(VersionTemplateRemapSet remapSet) {
    if (remapSet.getRemapEntries() == null) {
      return;
    }
    for (VersionTemplateRemapEntry entry : remapSet.getRemapEntries()) {
      LocalDateTime createdAt =
          entry.getCreatedAt() == null ? LocalDateTime.now() : entry.getCreatedAt();
      Record record =
          Objects.requireNonNull(
              dsl.insertInto(ENTRY_TABLE)
                  .set(ENTRY_REMAP_SET_PK, remapSet.getId())
                  .set(ENTRY_MAPPING_DOMAIN, entry.getMappingDomain())
                  .set(ENTRY_MAPPING_TYPE, entry.getMappingType())
                  .set(ENTRY_SOURCE_TEMPLATE_KEY, entry.getSourceTemplateKey())
                  .set(ENTRY_TARGET_TEMPLATE_KEY, entry.getTargetTemplateKey())
                  .set(ENTRY_CREATED_AT, createdAt)
                  .returning()
                  .fetchOne());
      entry.setId(record.get(ENTRY_ID));
      entry.setRemapSet(remapSet);
      entry.setCreatedAt(record.get(ENTRY_CREATED_AT));
    }
  }

  private VersionTemplateRemapSet toSetEntity(Record record) {
    if (record == null) {
      return null;
    }
    VersionTemplateRemapSet remapSet = new VersionTemplateRemapSet();
    remapSet.setId(record.get(SET_ID));
    remapSet.setRemapSetId(record.get(REMAP_SET_ID));
    remapSet.setTenantId(record.get(TENANT_ID));
    remapSet.setSourceVersionId(record.get(SOURCE_VERSION_ID));
    remapSet.setTargetVersionId(record.get(TARGET_VERSION_ID));
    remapSet.setStatus(TemplateRemapSetStatus.valueOf(record.get(STATUS)));
    remapSet.setCreatedReason(record.get(CREATED_REASON));
    remapSet.setApprovalReason(record.get(APPROVAL_REASON));
    remapSet.setCreatedAt(record.get(CREATED_AT));
    remapSet.setApprovedAt(record.get(APPROVED_AT));
    remapSet.setUpdatedAt(record.get(UPDATED_AT));
    remapSet.setRemapEntries(loadEntries(remapSet));
    return remapSet;
  }

  private List<VersionTemplateRemapEntry> loadEntries(VersionTemplateRemapSet remapSet) {
    return dsl.selectFrom(ENTRY_TABLE)
        .where(ENTRY_REMAP_SET_PK.eq(remapSet.getId()))
        .orderBy(ENTRY_ID.asc())
        .fetch(
            record -> {
              VersionTemplateRemapEntry entry = new VersionTemplateRemapEntry();
              entry.setId(record.get(ENTRY_ID));
              entry.setRemapSet(remapSet);
              entry.setMappingDomain(record.get(ENTRY_MAPPING_DOMAIN));
              entry.setMappingType(record.get(ENTRY_MAPPING_TYPE));
              entry.setSourceTemplateKey(record.get(ENTRY_SOURCE_TEMPLATE_KEY));
              entry.setTargetTemplateKey(record.get(ENTRY_TARGET_TEMPLATE_KEY));
              entry.setCreatedAt(record.get(ENTRY_CREATED_AT));
              return entry;
            });
  }
}
