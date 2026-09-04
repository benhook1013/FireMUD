package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptDeadLetterReplayRequests.SCRIPT_DEAD_LETTER_REPLAY_REQUESTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

class ScriptDeadLetterReplayRepositoryTest {
  @Test
  void saveResultPreservesFirstConcurrentOutcome() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicInteger callCount = new AtomicInteger();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          if (callCount.getAndIncrement() == 0) {
            Field<?>[] ownerFields = {SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.TENANT_ID};
            Result<Record> ownerResult = resultDsl.newResult(ownerFields);
            Record owner = resultDsl.newRecord(ownerFields);
            owner.set(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.TENANT_ID, "tenant-1");
            ownerResult.add(owner);
            return new MockResult[] {new MockResult(1, ownerResult)};
          }
          return new MockResult[] {new MockResult(1)};
        };
    ScriptDeadLetterReplayRepository repository =
        new ScriptDeadLetterReplayRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.saveResult(
        1L, 42L, 42L, "retried_evaluation", "", "", 3L, 4L, 5L, 2L, Instant.EPOCH);

    assertThat(sqlRef.get().toLowerCase(Locale.ROOT)).contains("on conflict", "do nothing");
  }

  @Test
  void completeUsesRunningCasSoAConcurrentCompletionCannotOverwriteCounts() {
    AtomicInteger callCount = new AtomicInteger();
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          bindingsRef.set(context.bindings());
          return new MockResult[] {new MockResult(callCount.getAndIncrement() == 0 ? 1 : 0)};
        };
    ScriptDeadLetterReplayRepository repository =
        new ScriptDeadLetterReplayRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(repository.complete(7L, 3L, 1L, Instant.EPOCH)).isTrue();
    assertThat(repository.complete(7L, 1L, 3L, Instant.EPOCH)).isFalse();
    String sql = sqlRef.get().toLowerCase(Locale.ROOT);
    String whereClause = sql.substring(sql.indexOf(" where "));
    assertThat(whereClause.replaceAll("\\s+", " ")).contains("\"status\" = ?");
    assertThat(bindingsRef.get()).contains("RUNNING");
  }

  @Test
  void deleteExpiredResultsRetainsResultsForActiveParents() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          return new MockResult[] {new MockResult(0)};
        };
    ScriptDeadLetterReplayRepository repository =
        new ScriptDeadLetterReplayRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.deleteExpiredResults(
        Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"));

    String sql = sqlRef.get().toLowerCase(Locale.ROOT);
    assertThat(sql).as(sql).contains("not exists", "status\" not in");
  }

  @Test
  void deleteExpiredResultsAllowsExpiredResultsForTerminalParents() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          bindingsRef.set(context.bindings());
          return new MockResult[] {new MockResult(1)};
        };
    ScriptDeadLetterReplayRepository repository =
        new ScriptDeadLetterReplayRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(
            repository.deleteExpiredResults(
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z")))
        .isEqualTo(1L);
    String sql = sqlRef.get().toLowerCase(Locale.ROOT);
    assertThat(sql).as(sql).contains("not exists", "status\" not in");
    assertThat(bindingsRef.get()).contains("HANDED_OFF", "CANCELED", "DEAD_LETTERED");
    assertThat(bindingsRef.get()).doesNotContain("FAILED");
  }
}
