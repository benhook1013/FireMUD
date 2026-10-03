package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
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

class ActorResourceStateRepositoryTest {
  private static final Long ROW_ID = 83L;
  private static final Long TENANT_ID = 17L;
  private static final String PLAYABLE_STATE_KEY = "realm:state:9";
  private static final Long CHARACTER_ID = 37L;

  @Test
  void saveUpdateUsesPersistedIdentityPredicatesAndOwnerResolvedGuard() {
    AtomicReference<String> updateSql = new AtomicReference<>();
    AtomicReference<Object[]> updateBindings = new AtomicReference<>();
    ActorResourceStateRepository repository =
        repository(providerForUpdate(1, updateSql, updateBindings));
    var entity = state();

    repository.save(entity);

    String sql = updateSql.get().toLowerCase(Locale.ROOT);
    String whereClause = sql.substring(sql.indexOf(" where ")).replaceAll("\\s+", " ");
    assertThat(whereClause)
        .contains(
            "\"id\" = ?",
            "\"tenant_id\" = ?",
            "\"playable_state_key\" = ?",
            "\"character_id\" = ?",
            "exists (select 1",
            "\"actor_identity_status\" = ?");
    assertThat(Arrays.asList(updateBindings.get()))
        .contains(ROW_ID, TENANT_ID, PLAYABLE_STATE_KEY, CHARACTER_ID, "OWNER_RESOLVED");
    assertThat(entity.getVersion()).isEqualTo(6);
  }

  @Test
  void saveUpdateWithZeroRowsFailsWithoutChangingVersion() {
    assertUpdateFailure(state(), 0);
  }

  @Test
  void saveUpdateWithMultipleRowsFailsWithoutChangingVersion() {
    assertUpdateFailure(state(), 2);
  }

  private static void assertUpdateFailure(ActorResourceState entity, int updatedRows) {
    ActorResourceStateRepository repository =
        repository(
            providerForUpdate(updatedRows, new AtomicReference<>(), new AtomicReference<>()));

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ACTOR_IDENTITY_NOT_OWNER_RESOLVED");
    assertThat(entity.getVersion()).isEqualTo(5);
  }

  private static MockDataProvider providerForUpdate(
      int updatedRows,
      AtomicReference<String> updateSql,
      AtomicReference<Object[]> updateBindings) {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    Field<Integer> countField = DSL.field("count", Integer.class);
    Record1<Integer> countRow = resultDsl.newRecord(countField);
    countRow.set(countField, 1);
    Result<Record1<Integer>> countResult = resultDsl.newResult(countField);
    countResult.add(countRow);
    return context -> {
      String sql = context.sql().trim();
      if (sql.toLowerCase(Locale.ROOT).startsWith("update")) {
        updateSql.set(sql);
        updateBindings.set(context.bindings());
        return new MockResult[] {new MockResult(updatedRows)};
      }
      return new MockResult[] {new MockResult(1, countResult)};
    };
  }

  private static ActorResourceStateRepository repository(MockDataProvider provider) {
    return new ActorResourceStateRepository(
        DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
  }

  private static ActorResourceState state() {
    var entity = new ActorResourceState();
    entity.setId(ROW_ID);
    entity.setTenantId(TENANT_ID);
    entity.setPlayableStateKey(PLAYABLE_STATE_KEY);
    entity.setCharacterId(CHARACTER_ID);
    entity.setStatKey("resource-unique");
    entity.setCurrentValue(11L);
    entity.setMaxValue(19L);
    entity.setBaseValue(23L);
    entity.setSourceType("source-unique");
    entity.setSourceId("source-id-unique");
    entity.setCreatedAt(Instant.parse("2024-02-01T00:00:00Z"));
    entity.setUpdatedAt(Instant.parse("2024-02-02T00:00:00Z"));
    entity.setVersion(5);
    return entity;
  }
}
