package net.firedevops.firemud.accountservice.service;

import static net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionPolicy.classify;
import static net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionPolicy.current;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionPolicy.Classification;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionPolicy.Hold;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionPolicy.Safety;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionPolicy.Schedule;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AccountCapacityEvaluationRetentionPolicyTest {
  private static final String VERSION = "test-capacity-retention/v1";
  private static final String EVALUATION = "sha256:" + "a".repeat(64);
  private static final Instant EVALUATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant EXPIRES = EVALUATED.plus(Duration.ofDays(1));
  private static final Instant OBSERVED = EVALUATED.plus(Duration.ofDays(5));

  @Test
  void candidateRequiresAllWindowsToCloseAndIncludesTheWatermarkBoundary() {
    var schedule = schedule(Duration.ofDays(6), Duration.ofDays(2));
    var safety = safety(VERSION, EVALUATED, Map.of(EVALUATION, Hold.CLEAR));

    assertThat(classify(schedule, safety, OBSERVED, EVALUATION, EVALUATED, EXPIRES, false))
        .isEqualTo(Classification.CANDIDATE);
    assertThat(
            classify(
                schedule, safety, OBSERVED.minusNanos(1), EVALUATION, EVALUATED, EXPIRES, false))
        .isEqualTo(Classification.RETENTION_WINDOW_OPEN);
    assertThat(
            classify(
                schedule,
                safety(VERSION, EVALUATED.minusNanos(1), Map.of(EVALUATION, Hold.CLEAR)),
                OBSERVED,
                EVALUATION,
                EVALUATED,
                EXPIRES,
                false))
        .isEqualTo(Classification.ABOVE_SAFE_WATERMARK);
    assertThat(
            classify(
                schedule, safety, OBSERVED, EVALUATION, EVALUATED, OBSERVED.plusNanos(1), false))
        .isEqualTo(Classification.LIVE_EVALUATION);
    assertThat(classify(schedule, safety, OBSERVED, EVALUATION, EVALUATED, EXPIRES, true))
        .isEqualTo(Classification.RETAINED_RESERVATION_SCOPE);
  }

  @Test
  void heldMissingStaleAndMismatchedObservationsRefuse() {
    var schedule = schedule(Duration.ofDays(6), Duration.ofDays(2));
    var holds = new HashMap<>(Map.of(EVALUATION, Hold.HELD));
    var held = safety(VERSION, EVALUATED, holds);
    holds.put(EVALUATION, Hold.CLEAR);
    assertThat(classify(schedule, held, OBSERVED, EVALUATION, EVALUATED, EXPIRES, false))
        .isEqualTo(Classification.HELD);
    assertThat(current(schedule, held, held.observedAt())).isTrue();
    assertThat(current(schedule, held, held.observedAt().minusNanos(1))).isFalse();
    assertThat(current(schedule, held, held.validUntil())).isFalse();
    assertThat(current(null, held, OBSERVED)).isFalse();
    assertThat(current(schedule, null, OBSERVED)).isFalse();
    assertThat(classify(schedule, held, OBSERVED, EVALUATION, EVALUATED, EXPIRES, null))
        .isEqualTo(Classification.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE);
    assertThat(classify(schedule, held, held.validUntil(), EVALUATION, EVALUATED, EXPIRES, false))
        .isEqualTo(Classification.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE);
    assertThat(classify(schedule, null, OBSERVED, EVALUATION, EVALUATED, EXPIRES, false))
        .isEqualTo(Classification.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE);
    for (var unavailable :
        new Safety[] {
          safety("other-version", EVALUATED, Map.of(EVALUATION, Hold.CLEAR)),
          safety(VERSION, EVALUATED, Map.of())
        }) {
      assertThat(classify(schedule, unavailable, OBSERVED, EVALUATION, EVALUATED, EXPIRES, false))
          .isEqualTo(Classification.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE);
    }
  }

  @Test
  void malformedScheduleAndUnboundedOrContradictorySafetyRefuse() {
    assertThatThrownBy(() -> schedule(Duration.ofDays(6), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> schedule(Duration.ofDays(6), Duration.ofDays(7)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Safety(VERSION, OBSERVED, OBSERVED, EVALUATED, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> safety(VERSION, OBSERVED.plusSeconds(1), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, Hold> oversized =
        IntStream.rangeClosed(0, AccountCapacityEvaluationRetentionInspectionOwner.MAX_SAMPLE_ROWS)
            .boxed()
            .collect(Collectors.toMap(index -> "test-evaluation-" + index, index -> Hold.CLEAR));
    assertThatThrownBy(() -> safety(VERSION, EVALUATED, oversized))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void qualifiedObservationCountsOnlyUnheldUnreferencedCandidatesWithoutWrites() {
    DSLContext database = mock(DSLContext.class);
    Record relation = mock(Record.class);
    when(relation.get("observed_at", OffsetDateTime.class))
        .thenReturn(OBSERVED.atOffset(ZoneOffset.UTC));
    when(relation.get("relation_bytes", Long.class)).thenReturn(4096L);
    when(relation.get("estimated_rows", Long.class)).thenReturn(4L);
    when(database.fetchOne(anyString())).thenReturn(relation);
    Result<Record> rows = mock(Result.class);
    when(rows.size()).thenReturn(4);
    String held = "sha256:" + "b".repeat(64);
    String unknown = "sha256:" + "c".repeat(64);
    String referenced = "sha256:" + "d".repeat(64);
    var candidateRow = evaluation(EVALUATION, false);
    var heldRow = evaluation(held, false);
    var unknownRow = evaluation(unknown, false);
    var referencedRow = evaluation(referenced, true);
    when(rows.get(0)).thenReturn(candidateRow);
    when(rows.get(1)).thenReturn(heldRow);
    when(rows.get(2)).thenReturn(unknownRow);
    when(rows.get(3)).thenReturn(referencedRow);
    when(database.fetch(anyString(), any(Object[].class))).thenReturn(rows);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    var owner = new AccountCapacityEvaluationRetentionInspectionOwner(database, transactions);
    var schedule = schedule(Duration.ofDays(6), Duration.ofDays(2));
    var safety =
        safety(
            VERSION,
            EVALUATED,
            Map.of(EVALUATION, Hold.CLEAR, held, Hold.HELD, referenced, Hold.CLEAR));

    var report = owner.observe(4, schedule, safety);

    assertThat(report.scheduleVersion()).contains(VERSION);
    assertThat(report.governanceBlocker()).isNull();
    assertThat(report.candidates().rows()).isEqualTo(1);
    assertThat(report.candidates().evidenceBytes()).isEqualTo(12);
    assertThat(
            report
                .blockers()
                .get(AccountCapacityEvaluationRetentionInspectionOwner.Blocker.HELD)
                .rows())
        .isEqualTo(1);
    assertThat(
            report
                .blockers()
                .get(
                    AccountCapacityEvaluationRetentionInspectionOwner.Blocker
                        .POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE)
                .rows())
        .isEqualTo(1);
    assertThat(
            report
                .blockers()
                .get(
                    AccountCapacityEvaluationRetentionInspectionOwner.Blocker
                        .RETAINED_RESERVATION_SCOPE)
                .rows())
        .isEqualTo(1);
    verify(database, never()).execute(anyString(), any(Object[].class));
  }

  private static Record evaluation(String identity, boolean referenced) {
    Record row = mock(Record.class);
    when(row.get("evaluation_identity", String.class)).thenReturn(identity);
    when(row.get("evaluated_at", OffsetDateTime.class))
        .thenReturn(EVALUATED.atOffset(ZoneOffset.UTC));
    when(row.get("expires_at", OffsetDateTime.class)).thenReturn(EXPIRES.atOffset(ZoneOffset.UTC));
    when(row.get("evidence_bytes", Long.class)).thenReturn(12L);
    when(row.get("scope_referenced", Boolean.class)).thenReturn(referenced);
    return row;
  }

  private static Schedule schedule(Duration maximum, Duration evidence) {
    return new Schedule(
        VERSION,
        maximum,
        evidence,
        Duration.ofDays(1),
        Duration.ofDays(3),
        Duration.ofDays(5),
        Duration.ofDays(4));
  }

  private static Safety safety(String version, Instant watermark, Map<String, Hold> holds) {
    return new Safety(version, OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(1), watermark, holds);
  }
}
