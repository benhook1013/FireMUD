package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.Scripts.SCRIPTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptsRecord;
import net.firedevops.firemud.automationscripting.model.ScriptDefinitionIdentityConflictException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class ScriptDefinitionRepositoryTest {
  @Test
  void identityUpsertReturnsDurableWinnerAndUsesStableIdentityConflictUpdate() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          var result = resultDsl.newResult(SCRIPTS);
          result.add(scriptRecord(17L, "{\"winner\":true}", 4));
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptDefinitionRepository repository = repository(provider);

    ScriptDefinition winner = repository.save(script(null, "{\"winner\":true}"));

    assertThat(winner.getId()).isEqualTo(17L);
    assertThat(winner.getRowVersion()).isEqualTo(4);
    String normalizedSql = normalizeSql(sqlRef.get());
    assertThat(normalizedSql)
        .contains("on conflict (tenant_id, version, name) do update set")
        .contains("definition", "row_version", "is distinct from", "excluded", "returning");
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
          var result = resultDsl.newResult(SCRIPTS);
          result.add(scriptRecord(17L, "{\"winner\":true}", 9));
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptDefinitionRepository repository = repository(provider);
    ScriptDefinition retry = script(17L, "{\"winner\":true}");
    retry.setRowVersion(9);

    ScriptDefinition persisted = repository.save(retry);

    assertThat(persisted.getRowVersion()).isEqualTo(9);
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
          var result = resultDsl.newResult(SCRIPTS);
          result.add(scriptRecord(17L, "{\"replacement\":true}", 10));
          return new MockResult[] {new MockResult(1, result)};
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
          var result = resultDsl.newResult(SCRIPTS);
          result.add(scriptRecord(17L, "{\"changed\":true}", 5));
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptDefinitionRepository repository = repository(provider);

    ScriptDefinition replacement = repository.save(script(null, "{\"changed\":true}"));

    assertThat(sqlRef.get().toLowerCase(Locale.ROOT))
        .contains("on conflict", "do update", "excluded", "returning", "row_version");
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
