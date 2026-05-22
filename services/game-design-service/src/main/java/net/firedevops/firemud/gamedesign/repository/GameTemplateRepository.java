package net.firedevops.firemud.gamedesign.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.gamedesign.repository.JooqGameDesignRepositorySupport.jsonbParam;
import static net.firedevops.firemud.gamedesign.repository.JooqGameDesignRepositorySupport.nullableString;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameTemplateRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("game_templates"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final Field<String> DESCRIPTION = DSL.field(DSL.name("description"), String.class);
  private static final Field<JSONB> CONFIG = DSL.field(DSL.name("config"), JSONB.class);
  private static final Field<Long> DEFAULT_VERSION_ID =
      DSL.field(DSL.name("default_version_id"), Long.class);
  private static final Field<String> DEFAULT_SCRIPT_PATCH_VERSION =
      DSL.field(DSL.name("default_script_patch_version"), String.class);
  private static final Field<String> DEFAULT_RUNTIME_FLAGS_JSON =
      DSL.field(DSL.name("default_runtime_flags_json"), String.class);
  private static final Field<String> TEMPLATE_REFERENCE_PHASE =
      DSL.field(DSL.name("template_reference_phase"), String.class);
  private static final Field<Timestamp> CREATED_AT =
      DSL.field(DSL.name("created_at"), Timestamp.class);

  private final DSLContext dsl;

  public GameTemplateRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Page<GameTemplate> findByTenantId(String tenantId, Pageable pageable) {
    List<GameTemplate> content =
        dsl.selectFrom(TABLE_REF)
            .where(TENANT_ID.eq(tenantId))
            .orderBy(ID.asc())
            .limit(limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(offsetOrZero(pageable))
            .fetch(this::toEntity);
    long total = dsl.fetchCount(TABLE_REF, TENANT_ID.eq(tenantId));
    return new PageImpl<>(content, pageable, total);
  }

  public Optional<GameTemplate> findByTenantIdAndId(String tenantId, Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(TENANT_ID.eq(tenantId).and(ID.eq(id)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public Optional<GameTemplateLaunchConfigView> findLaunchConfigByTenantIdAndId(
      String tenantId, Long id) {
    return Optional.ofNullable(
        dsl.select(
                ID,
                TENANT_ID,
                DEFAULT_VERSION_ID,
                DEFAULT_SCRIPT_PATCH_VERSION,
                DEFAULT_RUNTIME_FLAGS_JSON,
                TEMPLATE_REFERENCE_PHASE)
            .from(TABLE_REF)
            .where(TENANT_ID.eq(tenantId).and(ID.eq(id)))
            .limit(1)
            .fetchOne(
                record ->
                    new GameTemplateLaunchConfigViewRecord(
                        record.get(ID),
                        record.get(TENANT_ID),
                        record.get(DEFAULT_VERSION_ID),
                        record.get(DEFAULT_SCRIPT_PATCH_VERSION),
                        record.get(DEFAULT_RUNTIME_FLAGS_JSON),
                        TemplateReferencePhase.valueOf(record.get(TEMPLATE_REFERENCE_PHASE)))));
  }

  public GameTemplate save(GameTemplate template) {
    LocalDateTime createdAt =
        template.getCreatedAt() == null ? LocalDateTime.now() : template.getCreatedAt();
    if (template.getId() == null) {
      Long generatedId =
          dsl.insertInto(TABLE_REF)
              .set(TENANT_ID, template.getTenantId())
              .set(NAME, template.getName())
              .set(DESCRIPTION, template.getDescription())
              .set(CONFIG, jsonbParam(template.getConfig()))
              .set(DEFAULT_VERSION_ID, template.getDefaultVersionId())
              .set(DEFAULT_SCRIPT_PATCH_VERSION, template.getDefaultScriptPatchVersion())
              .set(DEFAULT_RUNTIME_FLAGS_JSON, template.getDefaultRuntimeFlagsJson())
              .set(TEMPLATE_REFERENCE_PHASE, template.getTemplateReferencePhase().name())
              .set(CREATED_AT, Timestamp.valueOf(createdAt))
              .returningResult(ID)
              .fetchOne(ID);
      return findByTenantIdAndId(template.getTenantId(), generatedId).orElseThrow();
    }
    dsl.update(TABLE_REF)
        .set(TENANT_ID, template.getTenantId())
        .set(NAME, template.getName())
        .set(DESCRIPTION, template.getDescription())
        .set(CONFIG, jsonbParam(template.getConfig()))
        .set(DEFAULT_VERSION_ID, template.getDefaultVersionId())
        .set(DEFAULT_SCRIPT_PATCH_VERSION, template.getDefaultScriptPatchVersion())
        .set(DEFAULT_RUNTIME_FLAGS_JSON, template.getDefaultRuntimeFlagsJson())
        .set(TEMPLATE_REFERENCE_PHASE, template.getTemplateReferencePhase().name())
        .set(CREATED_AT, Timestamp.valueOf(createdAt))
        .where(ID.eq(template.getId()))
        .execute();
    return findByTenantIdAndId(template.getTenantId(), template.getId()).orElseThrow();
  }

  public long count() {
    return dsl.fetchCount(TABLE_REF);
  }

  public List<GameTemplate> findAll() {
    return dsl.selectFrom(TABLE_REF).orderBy(ID.asc()).fetch(this::toEntity);
  }

  private GameTemplate toEntity(Record record) {
    if (record == null) {
      return null;
    }
    GameTemplate template = new GameTemplate();
    template.setId(record.get(ID));
    template.setTenantId(record.get(TENANT_ID));
    template.setName(record.get(NAME));
    template.setDescription(record.get(DESCRIPTION));
    template.setConfig(nullableString(record.get(CONFIG)));
    template.setDefaultVersionId(record.get(DEFAULT_VERSION_ID));
    template.setDefaultScriptPatchVersion(record.get(DEFAULT_SCRIPT_PATCH_VERSION));
    template.setDefaultRuntimeFlagsJson(record.get(DEFAULT_RUNTIME_FLAGS_JSON));
    String phase = record.get(TEMPLATE_REFERENCE_PHASE);
    template.setTemplateReferencePhase(
        phase == null ? TemplateReferencePhase.ENFORCED : TemplateReferencePhase.valueOf(phase));
    Timestamp createdAt = record.get(CREATED_AT);
    template.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
    return template;
  }

  private static final class GameTemplateLaunchConfigViewRecord
      implements GameTemplateLaunchConfigView {
    private final Long id;
    private final String tenantId;
    private final Long defaultVersionId;
    private final String defaultScriptPatchVersion;
    private final String defaultRuntimeFlagsJson;
    private final TemplateReferencePhase templateReferencePhase;

    private GameTemplateLaunchConfigViewRecord(
        Long id,
        String tenantId,
        Long defaultVersionId,
        String defaultScriptPatchVersion,
        String defaultRuntimeFlagsJson,
        TemplateReferencePhase templateReferencePhase) {
      this.id = id;
      this.tenantId = tenantId;
      this.defaultVersionId = defaultVersionId;
      this.defaultScriptPatchVersion = defaultScriptPatchVersion;
      this.defaultRuntimeFlagsJson = defaultRuntimeFlagsJson;
      this.templateReferencePhase = templateReferencePhase;
    }

    @Override
    public Long getId() {
      return id;
    }

    @Override
    public String getTenantId() {
      return tenantId;
    }

    @Override
    public Long getDefaultVersionId() {
      return defaultVersionId;
    }

    @Override
    public String getDefaultScriptPatchVersion() {
      return defaultScriptPatchVersion;
    }

    @Override
    public String getDefaultRuntimeFlagsJson() {
      return defaultRuntimeFlagsJson;
    }

    @Override
    public TemplateReferencePhase getTemplateReferencePhase() {
      return templateReferencePhase;
    }
  }
}
