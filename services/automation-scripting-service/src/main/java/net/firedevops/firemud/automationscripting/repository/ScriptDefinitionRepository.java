package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.Scripts.SCRIPTS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptDefinitionRepository {
  private final DSLContext dsl;

  public ScriptDefinitionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ScriptDefinition> findByTenantIdAndScriptVersionAndName(
      Long tenantId, String scriptVersion, String name) {
    return dsl.selectFrom(SCRIPTS)
        .where(
            SCRIPTS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPTS.VERSION.eq(scriptVersion))
                .and(SCRIPTS.NAME.eq(name)))
        .fetchOptional(this::toEntity);
  }

  public List<ScriptDefinition> findByTenantIdAndScriptVersionAndNameIn(
      Long tenantId, String scriptVersion, List<String> names) {
    if (names == null || names.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(SCRIPTS)
        .where(
            SCRIPTS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPTS.VERSION.eq(scriptVersion))
                .and(SCRIPTS.NAME.in(names)))
        .orderBy(SCRIPTS.NAME.asc())
        .fetch(this::toEntity);
  }

  public List<ScriptDefinition> findByTenantIdAndScriptVersionOrderByNameAsc(
      Long tenantId, String scriptVersion) {
    return dsl.selectFrom(SCRIPTS)
        .where(SCRIPTS.TENANT_ID.eq(tenantId).and(SCRIPTS.VERSION.eq(scriptVersion)))
        .orderBy(SCRIPTS.NAME.asc())
        .fetch(this::toEntity);
  }

  public List<ScriptDefinition> findByTenantIdOrderByNameAscScriptVersionAsc(Long tenantId) {
    return dsl.selectFrom(SCRIPTS)
        .where(SCRIPTS.TENANT_ID.eq(tenantId))
        .orderBy(SCRIPTS.NAME.asc(), SCRIPTS.VERSION.asc())
        .fetch(this::toEntity);
  }

  public ScriptDefinition save(ScriptDefinition entity) {
    if (entity.getId() == null) {
      ScriptsRecord record = dsl.newRecord(SCRIPTS);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPTS)
            .set(SCRIPTS.TENANT_ID, entity.getTenantId())
            .set(SCRIPTS.NAME, entity.getName())
            .set(SCRIPTS.VERSION, entity.getScriptVersion())
            .set(SCRIPTS.DEFINITION, entity.getDefinition())
            .set(SCRIPTS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPTS.ID.eq(entity.getId()).and(SCRIPTS.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite("scripts", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  public List<ScriptDefinition> saveAll(Collection<ScriptDefinition> entities) {
    if (entities == null || entities.isEmpty()) {
      return List.of();
    }
    return entities.stream().map(this::save).toList();
  }

  public void delete(ScriptDefinition entity) {
    if (entity == null || entity.getId() == null) {
      return;
    }
    dsl.deleteFrom(SCRIPTS).where(SCRIPTS.ID.eq(entity.getId())).execute();
  }

  private Optional<ScriptDefinition> findById(Long id) {
    return dsl.selectFrom(SCRIPTS).where(SCRIPTS.ID.eq(id)).fetchOptional(this::toEntity);
  }

  private void populate(ScriptsRecord record, ScriptDefinition entity) {
    record.setTenantId(entity.getTenantId());
    record.setName(entity.getName());
    record.setVersion(entity.getScriptVersion());
    record.setDefinition(entity.getDefinition());
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptDefinition toEntity(Record record) {
    ScriptDefinition entity = new ScriptDefinition();
    entity.setId(record.get(SCRIPTS.ID));
    entity.setTenantId(record.get(SCRIPTS.TENANT_ID));
    entity.setName(record.get(SCRIPTS.NAME));
    entity.setScriptVersion(record.get(SCRIPTS.VERSION));
    entity.setDefinition(record.get(SCRIPTS.DEFINITION));
    Integer rowVersion = record.get(SCRIPTS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
