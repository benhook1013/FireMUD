package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventBindings.SCRIPT_EVENT_BINDINGS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class ScriptEventBindingRepositoryTest {
  @Test
  void newBindingDefaultsToAnEmptyBindingId() {
    assertThat(new ScriptEventBinding().getBindingId()).isEmpty();
  }

  @Test
  void updatesDistinctBindingRowsWithoutDroppingStableIdentity() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    List<String> updates = new ArrayList<>();
    List<List<Object>> bindings = new ArrayList<>();
    MockDataProvider provider =
        context -> {
          if (context.sql().trim().toLowerCase(Locale.ROOT).startsWith("update")) {
            updates.add(context.sql().toLowerCase(Locale.ROOT));
            bindings.add(Arrays.asList(context.bindings()));
            return new MockResult[] {new MockResult(1)};
          }
          return new MockResult[] {new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_BINDINGS))};
        };
    ScriptEventBindingRepository repository =
        new ScriptEventBindingRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.save(binding(1L, "binding-a"));
    repository.save(binding(2L, "binding-b"));

    assertThat(updates).hasSize(2).allMatch(sql -> sql.contains("binding_id"));
    assertThat(bindings).hasSize(2);
    assertThat(bindings.get(0)).contains("binding-a");
    assertThat(bindings.get(1)).contains("binding-b");
  }

  @Test
  void resolvesBindingsWithStableIdentityAndRowTieBreakers() {
    java.util.concurrent.atomic.AtomicReference<String> sql =
        new java.util.concurrent.atomic.AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sql.set(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_BINDINGS))};
        };
    ScriptEventBindingRepository repository =
        new ScriptEventBindingRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository
        .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
            1L, "patch-1", "onCommand", "v1");

    assertThat(sql.get())
        .containsSubsequence(
            "order by", "priority\" asc", "script_id\" asc", "binding_id\" asc", "id\" asc");
  }

  private static ScriptEventBinding binding(Long id, String bindingId) {
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setId(id);
    binding.setTenantId(1L);
    binding.setScriptPatchVersion("patch-1");
    binding.setEventType("onCommand");
    binding.setEventSchemaVersion("v1");
    binding.setScriptId("script-1");
    binding.setBindingId(bindingId);
    binding.setTargetScopeType("GLOBAL");
    binding.setTargetScopeId("");
    binding.setEnabled(true);
    return binding;
  }
}
