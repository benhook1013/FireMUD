package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventBindings.SCRIPT_EVENT_BINDINGS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptEventBindingsRecord;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class ScriptEventBindingRepositoryTest {
  @Test
  void restoreWithIdUsesStrictFixedIdInsert() {
    AtomicReference<String> sql = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sql.set(context.sql());
          var result = resultDsl.newResult(SCRIPT_EVENT_BINDINGS);
          result.add(bindingRecord());
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventBindingRepository repository =
        new ScriptEventBindingRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventBinding restored = repository.restoreWithId(binding());

    assertThat(restored.getId()).isEqualTo(91L);
    assertThat(sql.get().toLowerCase(Locale.ROOT))
        .contains("insert into", "script_event_bindings", "id", "returning")
        .doesNotContain("on conflict", "excluded");
  }

  private static ScriptEventBindingsRecord bindingRecord() {
    ScriptEventBindingsRecord record = new ScriptEventBindingsRecord();
    record.setId(91L);
    record.setTenantId(1L);
    record.setScriptPatchVersion("patch-1");
    record.setEventType("onCommand");
    record.setEventSchemaVersion("v1");
    record.setScriptId("script-1");
    record.setTargetScopeType("GLOBAL");
    record.setTargetScopeId("");
    record.setPriority(0);
    record.setPriorityTag("normal");
    record.setRequiresExclusiveEvent(false);
    record.setEnabled(true);
    record.setRowVersion(0);
    return record;
  }

  private static ScriptEventBinding binding() {
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setId(91L);
    binding.setTenantId(1L);
    binding.setScriptPatchVersion("patch-1");
    binding.setEventType("onCommand");
    binding.setEventSchemaVersion("v1");
    binding.setScriptId("script-1");
    binding.setTargetScopeType("GLOBAL");
    binding.setTargetScopeId("");
    binding.setPriority(0);
    binding.setPriorityTag("normal");
    binding.setRequiresExclusiveEvent(false);
    binding.setEnabled(true);
    binding.setRowVersion(0);
    return binding;
  }
}
