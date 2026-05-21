package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.GenerationRule.GENERATION_RULE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.GenerationRule;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.GenerationRuleRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GenerationRuleRepository {
  private final DSLContext dsl;

  public GenerationRuleRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Page<GenerationRule> findByTenantId(Long tenantId, Pageable pageable) {
    var condition = GENERATION_RULE.TENANT_ID.eq(tenantId);
    long total = dsl.fetchCount(GENERATION_RULE, condition);
    var content =
        dsl.selectFrom(GENERATION_RULE)
            .where(condition)
            .orderBy(GENERATION_RULE.ID.asc())
            .limit(JooqWorldManagementRepositorySupport.limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(JooqWorldManagementRepositorySupport.offsetOrZero(pageable))
            .fetch(this::toEntity);
    return JooqWorldManagementRepositorySupport.page(content, pageable, total);
  }

  public List<GenerationRule> findByTenantIdOrderByIdAsc(Long tenantId) {
    return dsl.selectFrom(GENERATION_RULE)
        .where(GENERATION_RULE.TENANT_ID.eq(tenantId))
        .orderBy(GENERATION_RULE.ID.asc())
        .fetch(this::toEntity);
  }

  public List<GenerationRule> findByTenantIdAndVersionIdOrderByIdAsc(
      Long tenantId, Long versionId) {
    return dsl.selectFrom(GENERATION_RULE)
        .where(GENERATION_RULE.TENANT_ID.eq(tenantId).and(GENERATION_RULE.VERSION_ID.eq(versionId)))
        .orderBy(GENERATION_RULE.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<GenerationRule> findByTenantIdAndVersionIdAndId(
      Long tenantId, Long versionId, Long id) {
    return dsl.selectFrom(GENERATION_RULE)
        .where(
            GENERATION_RULE
                .TENANT_ID
                .eq(tenantId)
                .and(GENERATION_RULE.VERSION_ID.eq(versionId))
                .and(GENERATION_RULE.ID.eq(id)))
        .fetchOptional(this::toEntity);
  }

  public Optional<GenerationRule> findByTenantIdAndVersionIdAndScopeTypeAndScopeIdAndName(
      Long tenantId, Long versionId, String scopeType, String scopeId, String name) {
    return dsl.selectFrom(GENERATION_RULE)
        .where(
            GENERATION_RULE
                .TENANT_ID
                .eq(tenantId)
                .and(GENERATION_RULE.VERSION_ID.eq(versionId))
                .and(GENERATION_RULE.SCOPE_TYPE.eq(scopeType))
                .and(GENERATION_RULE.SCOPE_ID.eq(scopeId))
                .and(GENERATION_RULE.NAME.eq(name)))
        .fetchOptional(this::toEntity);
  }

  public List<GenerationRule> findByTenantIdAndVersionIdAndScopeTypeAndScopeIdOrderByIdAsc(
      Long tenantId, Long versionId, String scopeType, String scopeId) {
    return dsl.selectFrom(GENERATION_RULE)
        .where(
            GENERATION_RULE
                .TENANT_ID
                .eq(tenantId)
                .and(GENERATION_RULE.VERSION_ID.eq(versionId))
                .and(GENERATION_RULE.SCOPE_TYPE.eq(scopeType))
                .and(GENERATION_RULE.SCOPE_ID.eq(scopeId)))
        .orderBy(GENERATION_RULE.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<GenerationRule> findById(Long id) {
    return dsl.selectFrom(GENERATION_RULE)
        .where(GENERATION_RULE.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public long count() {
    return dsl.fetchCount(GENERATION_RULE);
  }

  public GenerationRule save(GenerationRule entity) {
    if (entity.getId() == null) {
      GenerationRuleRecord record = dsl.newRecord(GENERATION_RULE);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(GENERATION_RULE)
            .set(GENERATION_RULE.TENANT_ID, entity.getTenantId())
            .set(GENERATION_RULE.VERSION_ID, entity.getVersionId())
            .set(GENERATION_RULE.NAME, entity.getName())
            .set(GENERATION_RULE.SCOPE_TYPE, entity.getScopeType())
            .set(GENERATION_RULE.SCOPE_ID, entity.getScopeId())
            .set(GENERATION_RULE.VALUE, entity.getValue())
            .set(GENERATION_RULE.VERSION, entity.getVersion() + 1)
            .where(
                GENERATION_RULE
                    .ID
                    .eq(entity.getId())
                    .and(GENERATION_RULE.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("generation_rule", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(GenerationRule entity) {
    dsl.deleteFrom(GENERATION_RULE).where(GENERATION_RULE.ID.eq(entity.getId())).execute();
  }

  public void deleteAll(List<GenerationRule> entities) {
    if (entities.isEmpty()) {
      return;
    }
    dsl.deleteFrom(GENERATION_RULE)
        .where(GENERATION_RULE.ID.in(entities.stream().map(GenerationRule::getId).toList()))
        .execute();
  }

  private void populate(GenerationRuleRecord record, GenerationRule entity) {
    record.setTenantId(entity.getTenantId());
    record.setVersionId(entity.getVersionId());
    record.setName(entity.getName());
    record.setScopeType(entity.getScopeType());
    record.setScopeId(entity.getScopeId());
    record.setValue(entity.getValue());
    record.setVersion(entity.getVersion());
  }

  private GenerationRule toEntity(Record record) {
    GenerationRule entity = new GenerationRule();
    entity.setId(record.get(GENERATION_RULE.ID));
    entity.setTenantId(record.get(GENERATION_RULE.TENANT_ID));
    entity.setVersionId(record.get(GENERATION_RULE.VERSION_ID));
    entity.setName(record.get(GENERATION_RULE.NAME));
    entity.setScopeType(record.get(GENERATION_RULE.SCOPE_TYPE));
    entity.setScopeId(record.get(GENERATION_RULE.SCOPE_ID));
    entity.setValue(record.get(GENERATION_RULE.VALUE));
    Integer version = record.get(GENERATION_RULE.VERSION);
    entity.setVersion(version == null ? 0 : version);
    return entity;
  }
}
