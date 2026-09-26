package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.Scripts.SCRIPTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptsRecord;
import net.firedevops.firemud.automationscripting.model.ScriptDefinitionIdentityConflictException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class ScriptDefinitionRepositoryTest {
  @Test
  void identityInsertReturnsDurableWinnerAndReportsCreation() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          return returningResult(resultDsl, scriptRecord(17L, "{\"winner\":true}", 4), true);
        };
    ScriptDefinitionRepository repository = repository(provider);

    ScriptDefinitionRepository.SaveResult saveResult =
        repository.saveWithCreationResult(script(null, "{\"winner\":true}"));
    ScriptDefinition winner = saveResult.definition();

    assertThat(winner.getId()).isEqualTo(17L);
    assertThat(winner.getRowVersion()).isEqualTo(4);
    assertThat(saveResult.created()).isTrue();
    String normalizedSql = normalizeSql(sqlRef.get());
    assertThat(normalizedSql)
        .contains("on conflict (tenant_id, version, name) do update", "returning", "xmax = 0")
        .contains("excluded.definition", "is distinct from");
  }

  @Test
  void identityConflictUpdatesStableRowAndReportsNonCreation() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicInteger callCount = new AtomicInteger();
    MockDataProvider provider =
        context -> {
          callCount.incrementAndGet();
          sqlRef.set(context.sql());
          return returningResult(resultDsl, scriptRecord(17L, "{\"winner\":true}", 4), false);
        };
    ScriptDefinitionRepository repository = repository(provider);

    ScriptDefinitionRepository.SaveResult saveResult =
        repository.saveWithCreationResult(script(null, "{\"winner\":true}"));

    assertThat(saveResult.created()).isFalse();
    assertThat(saveResult.definition().getId()).isEqualTo(17L);
    assertThat(callCount).hasValue(1);
    assertThat(normalizeSql(sqlRef.get()))
        .contains("on conflict (tenant_id, version, name) do update", "returning", "xmax = 0")
        .contains("definition", "row_version", "is distinct from");
  }

  @Test
  void identityUpsertWithoutReturnedRowFailsClosed() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> new MockResult[] {new MockResult(0, resultDsl.newResult(SCRIPTS))};
    ScriptDefinitionRepository repository = repository(provider);

    assertThatThrownBy(() -> repository.saveWithCreationResult(script(null, "{\"winner\":true}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script definition identity upsert did not return a row");
  }

  @Test
  void explicitIdIdenticalDefinitionRetryPreservesCurrentRowVersion() {
    AtomicReference<String> updateSql = new AtomicReference<>();
    AtomicReference<Object[]> updateBindings = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          String sql = context.sql();
          if (sql.trim().toLowerCase(Locale.ROOT).startsWith("update")) {
            updateSql.set(sql);
            updateBindings.set(context.bindings());
            return new MockResult[] {new MockResult(1)};
          }
          return rowResult(resultDsl, scriptRecord(17L, "{\"winner\":true}", 9));
        };
    ScriptDefinitionRepository repository = repository(provider);
    ScriptDefinition retry = script(17L, "{\"winner\":true}");
    retry.setRowVersion(9);

    ScriptDefinitionRepository.SaveResult saveResult = repository.saveWithCreationResult(retry);
    ScriptDefinition persisted = saveResult.definition();

    assertThat(persisted.getRowVersion()).isEqualTo(9);
    assertThat(saveResult.created()).isFalse();
    assertThat(updateSql.get().toLowerCase(Locale.ROOT))
        .contains("is distinct from", "row_version", "where");
    assertExpectedRowVersionBinding(updateSql.get(), updateBindings.get(), 9);
  }

  @Test
  void explicitIdChangedDefinitionReplacementUsesExpectedCurrentRowVersion() {
    AtomicReference<String> updateSql = new AtomicReference<>();
    AtomicReference<Object[]> updateBindings = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          String sql = context.sql();
          if (sql.trim().toLowerCase(Locale.ROOT).startsWith("update")) {
            updateSql.set(sql);
            updateBindings.set(context.bindings());
            return new MockResult[] {new MockResult(1)};
          }
          return rowResult(resultDsl, scriptRecord(17L, "{\"replacement\":true}", 10));
        };
    ScriptDefinitionRepository repository = repository(provider);
    ScriptDefinition replacement = script(17L, "{\"replacement\":true}");
    replacement.setRowVersion(9);

    ScriptDefinition persisted = repository.save(replacement);

    assertThat(persisted.getDefinition()).isEqualTo("{\"replacement\":true}");
    assertThat(persisted.getRowVersion()).isEqualTo(10);
    assertThat(updateSql.get().toLowerCase(Locale.ROOT)).contains("row_version", "where");
    assertExpectedRowVersionBinding(updateSql.get(), updateBindings.get(), 9);
  }

  @Test
  void identityUpsertReplacesChangedDefinitionAndIncrementsRowVersion() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          return returningResult(resultDsl, scriptRecord(17L, "{\"changed\":true}", 5), false);
        };
    ScriptDefinitionRepository repository = repository(provider);

    ScriptDefinitionRepository.SaveResult saveResult =
        repository.saveWithCreationResult(script(null, "{\"changed\":true}"));
    ScriptDefinition replacement = saveResult.definition();

    assertThat(sqlRef.get().toLowerCase(Locale.ROOT))
        .contains("on conflict", "do update", "returning", "row_version", "xmax = 0");
    assertThat(saveResult.created()).isFalse();
    assertThat(replacement.getDefinition()).isEqualTo("{\"changed\":true}");
    assertThat(replacement.getRowVersion()).isEqualTo(5);
  }

  @Test
  void existingIdIdentityMutationIsRejectedByCasAndLeavesOriginalRowUntouched() {
    AtomicReference<String> updateSql = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          String sql = context.sql();
          if (sql.trim().toLowerCase(Locale.ROOT).startsWith("update")) {
            updateSql.set(sql);
            return new MockResult[] {new MockResult(0)};
          }
          var result = resultDsl.newResult(SCRIPTS);
          result.add(scriptRecord(17L, "{\"original\":true}", 4));
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptDefinitionRepository repository = repository(provider);
    ScriptDefinition changedIdentity = script(17L, "{\"replacement\":true}");
    changedIdentity.setName("renamed");
    changedIdentity.setRowVersion(4);

    assertThatThrownBy(() -> repository.save(changedIdentity))
        .isInstanceOf(ScriptDefinitionIdentityConflictException.class)
        .hasMessageStartingWith("SCRIPT_DEFINITION_CONFLICT: ");

    assertThat(updateSql.get().toLowerCase(Locale.ROOT))
        .contains("tenant_id", "version", "name", "row_version")
        .contains("where");
  }

  private static ScriptDefinitionRepository repository(MockDataProvider provider) {
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    return new ScriptDefinitionRepository(dsl);
  }

  private static ScriptDefinition script(Long id, String definition) {
    ScriptDefinition script = new ScriptDefinition();
    script.setId(id);
    script.setTenantId(1L);
    script.setName("script-1");
    script.setScriptVersion("v1");
    script.setDefinition(definition);
    return script;
  }

  private static ScriptsRecord scriptRecord(Long id, String definition, int rowVersion) {
    ScriptsRecord record = new ScriptsRecord();
    record.setId(id);
    record.setTenantId(1L);
    record.setName("script-1");
    record.setVersion("v1");
    record.setDefinition(definition);
    record.setRowVersion(rowVersion);
    return record;
  }

  private static MockResult[] returningResult(
      DSLContext resultDsl, ScriptsRecord row, boolean inserted) {
    Field<Boolean> insertedField = DSL.field("xmax = 0", Boolean.class).as("inserted");
    List<Field<?>> fields = new ArrayList<>();
    Collections.addAll(fields, SCRIPTS.fields());
    fields.add(insertedField);
    Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
    returned.from(row);
    returned.set(insertedField, inserted);
    Result<Record> result = resultDsl.newResult(fields.toArray(new Field<?>[0]));
    result.add(returned);
    return new MockResult[] {new MockResult(1, result)};
  }

  private static MockResult[] rowResult(DSLContext resultDsl, ScriptsRecord row) {
    Result<ScriptsRecord> result = resultDsl.newResult(SCRIPTS);
    result.add(row);
    return new MockResult[] {new MockResult(1, result)};
  }

  private static String normalizeSql(String sql) {
    return sql.toLowerCase(Locale.ROOT).replace("\"", "").replaceAll("\\s+", " ").trim();
  }

  private static void assertExpectedRowVersionBinding(
      String sql, Object[] bindings, int expectedRowVersion) {
    String normalizedSql = sql.toLowerCase(Locale.ROOT);
    int whereStart = normalizedSql.indexOf(" where ");
    assertThat(whereStart).isGreaterThanOrEqualTo(0);
    int rowVersionPredicate = normalizedSql.indexOf("row_version", whereStart);
    assertThat(rowVersionPredicate).isGreaterThanOrEqualTo(0);
    int placeholder = normalizedSql.indexOf('?', rowVersionPredicate);
    assertThat(placeholder).isGreaterThan(rowVersionPredicate);
    assertThat(normalizedSql.substring(rowVersionPredicate, placeholder)).contains("=");

    int bindingIndex =
        (int)
            normalizedSql
                .substring(0, placeholder)
                .chars()
                .filter(character -> character == '?')
                .count();
    assertThat(bindings).hasSizeGreaterThan(bindingIndex);
    assertThat(bindings[bindingIndex]).isEqualTo(expectedRowVersion);
  }
}
