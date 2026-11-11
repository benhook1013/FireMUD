package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionInspectionOwner;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionInspectionOwner.Blocker;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Controlled read-only inspection proof, not approval, live hold authority or deletion proof. */
class AccountCapacityEvaluationRetentionInspectionOwnerTest {
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @Test
  void inspectionBoundsApplyBeforeAnyDatabaseWork() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    var owner = new AccountCapacityEvaluationRetentionInspectionOwner(dsl, transactions);
    for (int invalid : new int[] {-1, 0, 1001, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> owner.observe(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(dsl, transactions);
  }

  @Test
  void observationUsesOneBoundedOldestWindowAndFixedBlockerCategoriesWithoutReadingPayloads() {
    DSLContext dsl = mock(DSLContext.class);
    Record metadata = mock(Record.class);
    when(metadata.get("observed_at", OffsetDateTime.class)).thenReturn(at(NOW));
    when(metadata.get("relation_bytes", Long.class)).thenReturn(4096L);
    when(metadata.get("estimated_rows", Long.class)).thenReturn(-1L);
    when(dsl.fetchOne(anyString())).thenReturn(metadata);
    Result<Record> rows = result();
    when(rows.size()).thenReturn(4);
    Record referenced = row(NOW.minusSeconds(30), NOW.minusSeconds(20), true, 262144);
    Record expired = row(NOW.minusSeconds(20), NOW.minusSeconds(1), false, 3);
    Record live = row(NOW.minusSeconds(10), NOW.plusSeconds(1), false, 5);
    when(rows.get(0)).thenReturn(referenced);
    when(rows.get(1)).thenReturn(expired);
    when(rows.get(2)).thenReturn(live);
    when(dsl.fetch(anyString(), any(Object[].class))).thenReturn(rows);
    var owner = new AccountCapacityEvaluationRetentionInspectionOwner(dsl, new ReadTransactions());

    var observation = owner.observe(3);

    assertThat(observation.totalRelationBytes()).isEqualTo(4096);
    assertThat(observation.estimatedTableRows()).isEmpty();
    assertThat(observation.sampledRows()).isEqualTo(3);
    assertThat(observation.sampleTruncated()).isTrue();
    assertThat(observation.governanceBlocker())
        .isEqualTo(Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE);
    assertThat(observation.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).evidenceBytes())
        .isEqualTo(262144);
    assertThat(observation.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).oldestEvaluatedAt())
        .contains(NOW.minusSeconds(30));
    assertThat(observation.blockers().get(Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE).rows())
        .isEqualTo(1);
    assertThat(observation.blockers().get(Blocker.LIVE_EVALUATION).rows()).isEqualTo(1);
    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    verify(dsl).fetch(query.capture(), eq(4));
    assertThat(query.getValue())
        .contains("LIMIT ?", "octet_length(canonical_evidence)")
        .contains("reservation.tenant_uuid = sample.tenant_uuid")
        .contains("reservation.owner_service = sample.owner_service")
        .doesNotContain("reservation.evaluation_identity", "DELETE", "UPDATE", "SUM(", "COUNT(");
    assertThatThrownBy(() -> observation.blockers().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void emptyTableHasNoInventedOldestBlockerAndCatalogEstimateStaysExplicit() {
    DSLContext dsl = mock(DSLContext.class);
    Record metadata = mock(Record.class);
    when(metadata.get("observed_at", OffsetDateTime.class)).thenReturn(at(NOW));
    when(metadata.get("relation_bytes", Long.class)).thenReturn(8192L);
    when(metadata.get("estimated_rows", Long.class)).thenReturn(0L);
    when(dsl.fetchOne(anyString())).thenReturn(metadata);
    Result<Record> rows = result();
    when(rows.size()).thenReturn(0);
    when(dsl.fetch(anyString(), any(Object[].class))).thenReturn(rows);
    var observation =
        new AccountCapacityEvaluationRetentionInspectionOwner(dsl, new ReadTransactions())
            .observe(1000);
    assertThat(observation.sampledRows()).isZero();
    assertThat(observation.sampleTruncated()).isFalse();
    assertThat(observation.estimatedTableRows()).hasValue(0);
    assertThat(observation.governanceBlocker())
        .isEqualTo(Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE);
    assertThat(observation.blockers().values())
        .allSatisfy(
            window -> {
              assertThat(window.rows()).isZero();
              assertThat(window.evidenceBytes()).isZero();
              assertThat(window.oldestEvaluatedAt()).isEmpty();
            });
    verify(dsl).fetch(anyString(), eq(1001));
  }

  @SuppressWarnings("unchecked")
  private static Result<Record> result() {
    return mock(Result.class);
  }

  private static Record row(Instant evaluated, Instant expires, boolean referenced, long bytes) {
    Record row = mock(Record.class);
    when(row.get("evaluated_at", OffsetDateTime.class)).thenReturn(at(evaluated));
    when(row.get("expires_at", OffsetDateTime.class)).thenReturn(at(expires));
    when(row.get("scope_referenced", Boolean.class)).thenReturn(referenced);
    when(row.get("evidence_bytes", Long.class)).thenReturn(bytes);
    return row;
  }

  private static OffsetDateTime at(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private static final class ReadTransactions implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(
        @org.jspecify.annotations.Nullable TransactionDefinition definition) {
      assertThat(definition).isNotNull();
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      assertThat(definition.isReadOnly()).isTrue();
      assertThat(definition.getTimeout()).isEqualTo(5);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
