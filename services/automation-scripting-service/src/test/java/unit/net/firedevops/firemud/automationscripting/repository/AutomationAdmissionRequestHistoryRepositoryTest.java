package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.AutomationAdmissionRequestHistory.AUTOMATION_ADMISSION_REQUEST_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionRequestHistory;
import net.firedevops.firemud.automationscripting.jooq.tables.records.AutomationAdmissionRequestHistoryRecord;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class AutomationAdmissionRequestHistoryRepositoryTest {
  private static final String LONG_ACTOR_PRINCIPAL = "x".repeat(129);

  @Test
  void insertOrGetReturnsDurableWinnerForExactScopeAndMode() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          bindingsRef.set(context.bindings());
          var result = resultDsl.newResult(AUTOMATION_ADMISSION_REQUEST_HISTORY);
          result.add(historyRecord());
          return new MockResult[] {new MockResult(1, result)};
        };
    AutomationAdmissionRequestHistoryRepository repository =
        new AutomationAdmissionRequestHistoryRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    AutomationAdmissionRequestHistory saved = repository.insertOrGet(history());

    assertThat(saved.getControlPlaneRequestId()).isEqualTo("request-1");
    assertThat(saved.getRequestFingerprint()).isEqualTo("durable-winner-fingerprint");
    assertThat(saved.getOutcome()).isEqualTo("ALREADY_APPLIED");
    assertThat(saved.getActorPrincipal()).isEqualTo("durable-winner-actor");
    assertThat(bindingsRef.get()).contains((Object) LONG_ACTOR_PRINCIPAL);
    assertThat(sqlRef.get().toLowerCase(Locale.ROOT))
        .contains("on conflict", "region_id", "mode", "do update", "returning")
        .doesNotContain("do nothing");
  }

  private static AutomationAdmissionRequestHistory history() {
    AutomationAdmissionRequestHistory history = new AutomationAdmissionRequestHistory();
    history.setTenantId("tenant-1");
    history.setGameInstanceId("game-1");
    history.setRegionId("region-1");
    history.setMode("PAUSED_FOR_ROLLBACK");
    history.setControlPlaneRequestId("request-1");
    history.setRequestFingerprint("fingerprint-1");
    history.setAdmissionEpoch(2L);
    history.setOutcome("APPLIED");
    history.setActorPrincipal(LONG_ACTOR_PRINCIPAL);
    history.setReason("rollback");
    history.setCreatedAt(Instant.EPOCH);
    return history;
  }

  private static AutomationAdmissionRequestHistoryRecord historyRecord() {
    AutomationAdmissionRequestHistoryRecord record = new AutomationAdmissionRequestHistoryRecord();
    record.setId(7L);
    record.setTenantId("tenant-1");
    record.setGameInstanceId("game-1");
    record.setRegionId("region-1");
    record.setMode("PAUSED_FOR_ROLLBACK");
    record.setControlPlaneRequestId("request-1");
    record.setRequestFingerprint("durable-winner-fingerprint");
    record.setAdmissionEpoch(9L);
    record.setOutcome("ALREADY_APPLIED");
    record.setActorPrincipal("durable-winner-actor");
    record.setReason("durable-winner-reason");
    record.setCreatedAt(
        java.time.OffsetDateTime.ofInstant(Instant.EPOCH, java.time.ZoneOffset.UTC));
    return record;
  }
}
