package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.Scripts.SCRIPTS;
import static org.jooq.impl.DSL.field;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptsRecord;
import net.firedevops.firemud.automationscripting.model.ScriptDefinitionIdentityConflictException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptDefinitionRepository {
  private static final Field<Boolean> INSERTED_ROW =
      field("xmax = 0", Boolean.class).as("inserted");

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The mutable definition is the repository save result contract.")
  public record SaveResult(ScriptDefinition definition, boolean created) {}

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
    return saveWithCreationResult(entity).definition();
  }

  /** Saves a definition and reports whether this invocation inserted its natural-identity row. */
  public SaveResult saveWithCreationResult(ScriptDefinition entity) {
    if (entity.getId() == null) {
      return insertOrGetByIdentity(entity);
    }
    return new SaveResult(saveWithExplicitId(entity), false);
  }

  private ScriptDefinition saveWithExplicitId(ScriptDefinition entity) {
    int updated =
        dsl.update(SCRIPTS)
            .set(SCRIPTS.DEFINITION, entity.getDefinition())
            .set(
                SCRIPTS.ROW_VERSION,
                DSL.when(
                        SCRIPTS.DEFINITION.isDistinctFrom(entity.getDefinition()),
                        SCRIPTS.ROW_VERSION.add(1))
                    .otherwise(SCRIPTS.ROW_VERSION))
            .where(
                SCRIPTS
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPTS.ROW_VERSION.eq(entity.getRowVersion()))
                    .and(SCRIPTS.TENANT_ID.eq(entity.getTenantId()))
                    .and(SCRIPTS.VERSION.eq(entity.getScriptVersion()))
                    .and(SCRIPTS.NAME.eq(entity.getName())))
            .execute();
    if (updated != 1) {
      ScriptDefinition current = findById(entity.getId()).orElse(null);
      if (current != null) {
        if (!sameIdentity(current, entity)) {
          throw identityConflict(current, entity);
        }
        throw AutomationScriptingJooqRepositorySupport.staleWrite("scripts", entity.getId());
      }
      ScriptDefinition stableIdentityRow =
          findByTenantIdAndScriptVersionAndName(
                  entity.getTenantId(), entity.getScriptVersion(), entity.getName())
              .orElse(null);
      if (stableIdentityRow != null
          && !java.util.Objects.equals(stableIdentityRow.getId(), entity.getId())) {
        throw identityConflict(stableIdentityRow, entity);
      }
      throw AutomationScriptingJooqRepositorySupport.staleWrite("scripts", entity.getId());
    }
    ScriptDefinition persisted = findById(entity.getId()).orElseThrow();
    entity.setRowVersion(persisted.getRowVersion());
    return persisted;
  }

  /** Atomically inserts or replaces the definition for its stable identity. */
  private SaveResult insertOrGetByIdentity(ScriptDefinition entity) {
    ScriptsRecord record = dsl.newRecord(SCRIPTS);
    populate(record, entity);
    List<SelectFieldOrAsterisk> returningFields = new ArrayList<>();
    Collections.addAll(returningFields, SCRIPTS.fields());
    returningFields.add(INSERTED_ROW);
    return dsl.insertInto(SCRIPTS)
        .set(record)
        .onConflict(SCRIPTS.TENANT_ID, SCRIPTS.VERSION, SCRIPTS.NAME)
        .doUpdate()
        .set(SCRIPTS.DEFINITION, DSL.excluded(SCRIPTS.DEFINITION))
        .set(
            SCRIPTS.ROW_VERSION,
            DSL.when(
                    SCRIPTS.DEFINITION.isDistinctFrom(DSL.excluded(SCRIPTS.DEFINITION)),
                    SCRIPTS.ROW_VERSION.add(1))
                .otherwise(SCRIPTS.ROW_VERSION))
        .returningResult(returningFields)
        .fetchOptional(
            returned -> {
              ScriptDefinition definition = toEntity(returned);
              if (!sameIdentity(definition, entity)) {
                throw new IllegalStateException(
                    "script definition identity upsert returned an unexpected row");
              }
              return new SaveResult(definition, Boolean.TRUE.equals(returned.get(INSERTED_ROW)));
            })
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "script definition identity upsert did not return a row"));
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

  public Optional<ScriptDefinition> findById(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return dsl.selectFrom(SCRIPTS).where(SCRIPTS.ID.eq(id)).fetchOptional(this::toEntity);
  }

  private static boolean sameIdentity(ScriptDefinition left, ScriptDefinition right) {
    return java.util.Objects.equals(left.getTenantId(), right.getTenantId())
        && java.util.Objects.equals(left.getScriptVersion(), right.getScriptVersion())
        && java.util.Objects.equals(left.getName(), right.getName());
  }

  private static ScriptDefinitionIdentityConflictException identityConflict(
      ScriptDefinition existing, ScriptDefinition requested) {
    return new ScriptDefinitionIdentityConflictException(
        "SCRIPT_DEFINITION_CONFLICT: immutable stable identity cannot be changed for id="
            + requested.getId()
            + "; existing=(tenantId="
            + existing.getTenantId()
            + ", version="
            + existing.getScriptVersion()
            + ", name="
            + existing.getName()
            + "), requested=(tenantId="
            + requested.getTenantId()
            + ", version="
            + requested.getScriptVersion()
            + ", name="
            + requested.getName()
            + ")");
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
