package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class QuarantinedActorRetentionRepositoryTest {
  private static final Long TENANT_ID = 29L;
  private static final String GAME_INSTANCE_ID = "runtime-opaque";

  @Test
  void readsTypedBooleanWithOneTargetBindingPairAndHoldsRowsNeedingClassification() {
    AtomicReference<String> sqlRead = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRead = new AtomicReference<>();
    QuarantinedActorRetentionRepository repository = repository(true, sqlRead, bindingsRead);

    assertThat(repository.hasUnclassifiedOrQuarantinedRuntimeEvidence(TENANT_ID, GAME_INSTANCE_ID))
        .isTrue();

    String sql = sqlRead.get().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    assertThat(sql)
        .contains("with target as")
        .contains("cast(? as bigint)")
        .contains("cast(? as text)")
        .contains("from item_instances item_instance")
        .contains("from container_instances container")
        .contains("from item_stacks stack")
        .contains("from room_ground_inventory ground")
        .contains("actor.actor_identity_status = 'quarantined'")
        .contains("audit.source_game_instance_id = target.game_instance_id")
        .contains("audit.destination_game_instance_id = target.game_instance_id")
        .contains("as boolean)");
    assertThat(Arrays.asList(bindingsRead.get())).containsExactly(TENANT_ID, GAME_INSTANCE_ID);
  }

  @Test
  void falseBooleanReportsNoObservedUnclassifiedOrQuarantinedEvidence() {
    QuarantinedActorRetentionRepository repository = repository(false, null, null);

    assertThat(repository.hasUnclassifiedOrQuarantinedRuntimeEvidence(TENANT_ID, GAME_INSTANCE_ID))
        .isFalse();
  }

  @Test
  void nullBooleanReadbackFailsClosed() {
    QuarantinedActorRetentionRepository repository = repository(null, null, null);

    assertThatThrownBy(
            () ->
                repository.hasUnclassifiedOrQuarantinedRuntimeEvidence(TENANT_ID, GAME_INSTANCE_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("RUNTIME_EVIDENCE_READBACK_UNAVAILABLE");
  }

  private static QuarantinedActorRetentionRepository repository(
      Boolean readback, AtomicReference<String> sqlRead, AtomicReference<Object[]> bindingsRead) {
    MockDataProvider provider =
        context -> {
          if (sqlRead != null) {
            sqlRead.set(context.sql());
          }
          if (bindingsRead != null) {
            bindingsRead.set(context.bindings());
          }
          DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
          Field<Boolean> retainedField = DSL.field("retained", Boolean.class);
          Record1<Boolean> row = resultDsl.newRecord(retainedField);
          row.set(retainedField, readback);
          Result<Record1<Boolean>> result = resultDsl.newResult(retainedField);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    return new QuarantinedActorRetentionRepository(
        DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
  }
}
