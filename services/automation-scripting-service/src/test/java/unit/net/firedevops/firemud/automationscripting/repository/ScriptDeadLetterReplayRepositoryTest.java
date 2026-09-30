package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptDeadLetterReplayResults.SCRIPT_DEAD_LETTER_REPLAY_RESULTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          callCount.incrementAndGet();
          return new MockResult[] {new MockResult(1)};
        };
    ScriptDeadLetterReplayRepository repository =
        new ScriptDeadLetterReplayRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.saveResult(
        "tenant-1", 1L, 42L, 42L, "retried_evaluation", "", "", 3L, 4L, 5L, 2L, Instant.EPOCH);

    assertThat(sqlRef.get().toLowerCase(Locale.ROOT)).contains("on conflict", "do nothing");
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  void saveResultBindsImmutableOriginalFailureEvidenceSeparatelyFromRecoveryFailure() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    AtomicInteger callCount = new AtomicInteger();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          bindingsRef.set(context.bindings());
          callCount.incrementAndGet();
          return new MockResult[] {new MockResult(1)};
        };
    ScriptDeadLetterReplayRepository repository =
        new ScriptDeadLetterReplayRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.saveResult(
        "tenant-1",
        1L,
        42L,
        42L,
        "retried_evaluation",
        "",
        "",
        3L,
        4L,
        5L,
        2L,
        "TICK_HANDOFF",
        "GAME_SESSION_UNAVAILABLE",
        Instant.EPOCH);

    assertThat(sqlRef.get().toLowerCase(Locale.ROOT))
        .contains("original_failure_stage", "original_failure_reason");
    assertThat(bindingsRef.get()).contains("tenant-1", "TICK_HANDOFF", "GAME_SESSION_UNAVAILABLE");
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  void completeUsesTenantAndRunningCasSoWrongTenantCannotCompleteRequest() {
    AtomicInteger callCount = new AtomicInteger();
    AtomicBoolean completed = new AtomicBoolean();
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          bindingsRef.set(context.bindings());
          callCount.incrementAndGet();
          boolean matchingTenant = java.util.Arrays.asList(context.bindings()).contains("tenant-1");
          return new MockResult[] {
            new MockResult(matchingTenant && completed.compareAndSet(false, true) ? 1 : 0)
          };
        };
    ScriptDeadLetterReplayRepository repository =
        new ScriptDeadLetterReplayRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(repository.complete("tenant-2", 7L, 1L, 3L, Instant.EPOCH)).isFalse();
    assertThat(bindingsRef.get()).contains("tenant-2", "RUNNING");
    assertThat(repository.complete("tenant-1", 7L, 3L, 1L, Instant.EPOCH)).isTrue();
    assertThat(repository.complete("tenant-1", 7L, 1L, 3L, Instant.EPOCH)).isFalse();
    String sql = sqlRef.get().toLowerCase(Locale.ROOT);
    String whereClause = sql.substring(sql.indexOf(" where "));
    assertThat(whereClause.replaceAll("\\s+", " "))
        .contains("\"id\" = ?", "\"tenant_id\" = ?", "\"status\" = ?");
    assertThat(bindingsRef.get()).contains("tenant-1", "RUNNING");
    assertThat(callCount.get()).isEqualTo(3);
  }

  @Test
  void findResultsScopesByTenantAndRequestId() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    var emptyResults =
        DSL.using(SQLDialect.POSTGRES).newResult(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.fields());
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          bindingsRef.set(context.bindings());
          return new MockResult[] {new MockResult(0, emptyResults)};
        };
    ScriptDeadLetterReplayRepository repository =
        new ScriptDeadLetterReplayRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(repository.findResults("tenant-1", 41L)).isEmpty();

    String sql = sqlRef.get().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    assertThat(sql.substring(sql.indexOf(" where "))).contains("tenant_id", "replay_request_id");
    assertThat(bindingsRef.get()).contains("tenant-1", 41L);
  }

  @Test
  void retentionHoldUpdatesAreTenantQualifiedAndAllowClearing() {
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
    Instant holdUntil = Instant.parse("2026-08-03T00:00:00Z");

    assertThat(repository.setRequestRetentionHold("tenant-1", 41L, holdUntil)).isTrue();
    String requestSql = sqlRef.get().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    assertThat(requestSql).contains("retention_hold_until", "tenant_id", "id");
    assertThat(bindingsRef.get()).contains("tenant-1", "2026-08-03 00:00:00+00:00");

    assertThat(repository.setRequestRetentionHold("tenant-1", 41L, null)).isTrue();
    assertThat(bindingsRef.get()).contains("tenant-1", (Object) null);

    assertThat(repository.setResultRetentionHold("tenant-1", 42L, holdUntil)).isTrue();
    String resultSql = sqlRef.get().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    assertThat(resultSql).contains("retention_hold_until", "tenant_id", "id");
  }
}
