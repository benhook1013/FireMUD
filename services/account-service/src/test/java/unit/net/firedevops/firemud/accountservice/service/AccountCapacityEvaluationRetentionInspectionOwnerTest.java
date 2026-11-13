package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionInspectionOwner.Blocker;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
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
    assertThat(observation.scheduleVersion()).isEmpty();
    assertThat(observation.candidates().rows()).isZero();
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

  @Test
  void currentPolicyUsesExactHistoryLineageAndLeavesAnUnrelatedEvaluationUnblocked() {
    DSLContext dsl = mock(DSLContext.class);
    metadata(dsl);
    TenantCapacityAdmission.Request request = CapacityAdmissionTestFixture.playerRequest();
    String referencedIdentity = request.evidence().evaluationIdentity();
    String unrelatedIdentity = "sha256:" + "c".repeat(64);
    Result<Record> samples = result();
    when(samples.size()).thenReturn(2);
    Instant evaluated = NOW.minusSeconds(10 * 86400L);
    doReturn(sample(referencedIdentity, true, false, evaluated)).when(samples).get(0);
    doReturn(sample(unrelatedIdentity, true, false, evaluated)).when(samples).get(1);
    Result<Record> reservations = result();
    when(reservations.size()).thenReturn(1);
    when(reservations.iterator()).thenAnswer(ignored -> List.of(reservations.get(0)).iterator());
    doReturn(reservation(request)).when(reservations).get(0);
    Result<Record> historyKeys = result();
    when(historyKeys.size()).thenReturn(1);
    when(historyKeys.iterator()).thenAnswer(ignored -> List.of(historyKeys.get(0)).iterator());
    doReturn(historyKey(request)).when(historyKeys).get(0);
    Result<Record> payloads = result();
    when(payloads.size()).thenReturn(1);
    when(payloads.iterator()).thenAnswer(ignored -> List.of(payloads.get(0)).iterator());
    doReturn(historyPayload(request)).when(payloads).get(0);
    when(dsl.fetch(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String query = invocation.getArgument(0);
              if (query.contains("WITH sample AS MATERIALIZED")) return samples;
              if (query.contains("WITH scopes(tenant_uuid")) return reservations;
              if (query.contains("WITH reservations(tenant_uuid")) return historyKeys;
              if (query.contains("WITH selected(tenant_uuid")) return payloads;
              throw new AssertionError("Unexpected inspection query: " + query);
            });

    var observation =
        owner(dsl).observe(2, schedule(), safety(referencedIdentity, unrelatedIdentity));

    assertThat(observation.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows()).isEqualTo(1);
    assertThat(observation.candidates().rows()).isEqualTo(1);
    assertThat(observation.governanceBlocker()).isNull();
    verify(dsl, times(4)).fetch(anyString(), any(Object[].class));
  }

  @Test
  void historyAbovePerScopeBoundFailsClosedWithoutReadingPayloads() {
    DSLContext dsl = mock(DSLContext.class);
    metadata(dsl);
    TenantCapacityAdmission.Request request = CapacityAdmissionTestFixture.playerRequest();
    Result<Record> samples = result();
    when(samples.size()).thenReturn(1);
    doReturn(sample("sha256:" + "c".repeat(64), true, false, NOW.minusSeconds(10 * 86400L)))
        .when(samples)
        .get(0);
    Result<Record> reservations = result();
    when(reservations.size()).thenReturn(1);
    when(reservations.iterator()).thenAnswer(ignored -> List.of(reservations.get(0)).iterator());
    doReturn(reservation(request)).when(reservations).get(0);
    Result<Record> historyKeys = result();
    when(historyKeys.size()).thenReturn(9);
    when(historyKeys.iterator())
        .thenAnswer(
            ignored ->
                java.util.stream.IntStream.range(0, 9).mapToObj(historyKeys::get).iterator());
    for (int index = 0; index < 9; index++) {
      doReturn(historyKey(request, UUID.randomUUID())).when(historyKeys).get(index);
    }
    when(dsl.fetch(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String query = invocation.getArgument(0);
              if (query.contains("WITH sample AS MATERIALIZED")) return samples;
              if (query.contains("WITH scopes(tenant_uuid")) return reservations;
              if (query.contains("WITH reservations(tenant_uuid")) return historyKeys;
              throw new AssertionError("Over-limit lineage must not fetch payload bytes");
            });

    var observation = owner(dsl).observe(1, schedule(), safety("sha256:" + "c".repeat(64)));

    assertThat(observation.candidates().rows()).isZero();
    assertThat(observation.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows()).isOne();
  }

  @Test
  void malformedHistoryPayloadFailsClosedForItsScope() {
    DSLContext dsl = mock(DSLContext.class);
    metadata(dsl);
    TenantCapacityAdmission.Request request = CapacityAdmissionTestFixture.playerRequest();
    Result<Record> samples = result();
    when(samples.size()).thenReturn(1);
    doReturn(sample("sha256:" + "c".repeat(64), true, false, NOW.minusSeconds(10 * 86400L)))
        .when(samples)
        .get(0);
    Result<Record> reservations = result();
    when(reservations.size()).thenReturn(1);
    when(reservations.iterator()).thenAnswer(ignored -> List.of(reservations.get(0)).iterator());
    doReturn(reservation(request)).when(reservations).get(0);
    Result<Record> historyKeys = result();
    when(historyKeys.size()).thenReturn(1);
    when(historyKeys.iterator()).thenAnswer(ignored -> List.of(historyKeys.get(0)).iterator());
    doReturn(historyKey(request)).when(historyKeys).get(0);
    Result<Record> payloads = result();
    when(payloads.size()).thenReturn(1);
    when(payloads.iterator()).thenAnswer(ignored -> List.of(payloads.get(0)).iterator());
    Record malformed = historyPayload(request);
    when(malformed.get("canonical_payload", byte[].class)).thenReturn(new byte[] {1});
    doReturn(malformed).when(payloads).get(0);
    when(dsl.fetch(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String query = invocation.getArgument(0);
              if (query.contains("WITH sample AS MATERIALIZED")) return samples;
              if (query.contains("WITH scopes(tenant_uuid")) return reservations;
              if (query.contains("WITH reservations(tenant_uuid")) return historyKeys;
              if (query.contains("WITH selected(tenant_uuid")) return payloads;
              throw new AssertionError("Unexpected inspection query: " + query);
            });

    var observation = owner(dsl).observe(1, schedule(), safety("sha256:" + "c".repeat(64)));

    assertThat(observation.candidates().rows()).isZero();
    assertThat(observation.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows()).isOne();
  }

  @Test
  void missingFenceInRetainedHistoryChainFailsClosed() {
    DSLContext dsl = mock(DSLContext.class);
    metadata(dsl);
    TenantCapacityAdmission.Request request = CapacityAdmissionTestFixture.playerRequest();
    Result<Record> samples = result();
    when(samples.size()).thenReturn(1);
    doReturn(sample("sha256:" + "c".repeat(64), true, false, NOW.minusSeconds(10 * 86400L)))
        .when(samples)
        .get(0);
    Result<Record> reservations = result();
    when(reservations.size()).thenReturn(1);
    when(reservations.iterator()).thenAnswer(ignored -> List.of(reservations.get(0)).iterator());
    doReturn(reservation(request, 3L)).when(reservations).get(0);
    Result<Record> historyKeys = result();
    when(historyKeys.size()).thenReturn(1);
    when(historyKeys.iterator()).thenAnswer(ignored -> List.of(historyKeys.get(0)).iterator());
    doReturn(historyKey(request)).when(historyKeys).get(0);
    when(dsl.fetch(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String query = invocation.getArgument(0);
              if (query.contains("WITH sample AS MATERIALIZED")) return samples;
              if (query.contains("WITH scopes(tenant_uuid")) return reservations;
              if (query.contains("WITH reservations(tenant_uuid")) return historyKeys;
              throw new AssertionError(
                  "A gap in the fence chain must be found before payload reads");
            });

    var observation = owner(dsl).observe(1, schedule(), safety("sha256:" + "c".repeat(64)));

    assertThat(observation.candidates().rows()).isZero();
    assertThat(observation.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows()).isOne();
  }

  @Test
  void aggregateHistoryBoundFailsClosedForAllQueriedScopesWithoutPayloadReads() {
    DSLContext dsl = mock(DSLContext.class);
    metadata(dsl);
    Result<Record> samples = result();
    Result<Record> reservations = result();
    Result<Record> historyKeys = result();
    int scopeCount = 4;
    when(samples.size()).thenReturn(scopeCount);
    when(reservations.size()).thenReturn(scopeCount);
    when(reservations.iterator())
        .thenAnswer(
            ignored ->
                java.util.stream.IntStream.range(0, scopeCount)
                    .mapToObj(reservations::get)
                    .iterator());
    when(historyKeys.size()).thenReturn(33);
    Instant evaluated = NOW.minusSeconds(10 * 86400L);
    List<String> identities = new java.util.ArrayList<>();
    for (int scopeIndex = 0; scopeIndex < scopeCount; scopeIndex++) {
      UUID tenantId = UUID.randomUUID();
      UUID requestId = UUID.randomUUID();
      UUID admissionId = UUID.randomUUID();
      UUID evaluationId = UUID.randomUUID();
      String identity = "sha256:" + evaluationId.toString().replace("-", "");
      identities.add(identity);
      doReturn(sample(identity, tenantId, true, false, evaluated)).when(samples).get(scopeIndex);
      doReturn(reservation(tenantId, requestId, admissionId)).when(reservations).get(scopeIndex);
    }
    for (int index = 0; index < 33; index++) {
      int scopeIndex = index % scopeCount;
      Record reservation = reservations.get(scopeIndex);
      doReturn(
              historyKey(
                  reservation.get("tenant_uuid", UUID.class),
                  reservation.get("request_id", UUID.class),
                  reservation.get("admission_id", UUID.class),
                  UUID.randomUUID()))
          .when(historyKeys)
          .get(index);
    }
    when(dsl.fetch(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String query = invocation.getArgument(0);
              if (query.contains("WITH sample AS MATERIALIZED")) return samples;
              if (query.contains("WITH scopes(tenant_uuid")) return reservations;
              if (query.contains("WITH reservations(tenant_uuid")) return historyKeys;
              throw new AssertionError("Aggregate over-limit lineage must not fetch payload bytes");
            });

    var observation = owner(dsl).observe(4, schedule(), safety(identities.toArray(String[]::new)));

    assertThat(observation.candidates().rows()).isZero();
    assertThat(observation.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows())
        .isEqualTo(scopeCount);
    verify(dsl, times(3)).fetch(anyString(), any(Object[].class));
  }

  @SuppressWarnings("unchecked")
  private static Result<Record> result() {
    return mock(Result.class);
  }

  private static Record row(Instant evaluated, Instant expires, boolean referenced, long bytes) {
    Record row = mock(Record.class);
    when(row.get("evaluation_identity", String.class)).thenReturn("sha256:" + "a".repeat(64));
    when(row.get("tenant_uuid", UUID.class)).thenReturn(CapacityAdmissionTestFixture.TENANT);
    when(row.get("owner_service", String.class)).thenReturn("game-session-service");
    when(row.get("evaluated_at", OffsetDateTime.class)).thenReturn(at(evaluated));
    when(row.get("expires_at", OffsetDateTime.class)).thenReturn(at(expires));
    when(row.get("scope_referenced", Boolean.class)).thenReturn(referenced);
    when(row.get("evidence_bytes", Long.class)).thenReturn(bytes);
    return row;
  }

  private static Record metadata(DSLContext dsl) {
    Record metadata = mock(Record.class);
    when(metadata.get("observed_at", OffsetDateTime.class)).thenReturn(at(NOW));
    when(metadata.get("relation_bytes", Long.class)).thenReturn(4096L);
    when(metadata.get("estimated_rows", Long.class)).thenReturn(2L);
    when(dsl.fetchOne(anyString())).thenReturn(metadata);
    return metadata;
  }

  private static AccountCapacityEvaluationRetentionInspectionOwner owner(DSLContext dsl) {
    return new AccountCapacityEvaluationRetentionInspectionOwner(dsl, new ReadTransactions());
  }

  private static AccountCapacityEvaluationRetentionPolicy.Schedule schedule() {
    return new AccountCapacityEvaluationRetentionPolicy.Schedule(
        "fixture/v1",
        java.time.Duration.ofDays(30),
        java.time.Duration.ofDays(2),
        java.time.Duration.ofDays(3),
        java.time.Duration.ofDays(4),
        java.time.Duration.ofDays(5),
        java.time.Duration.ofDays(6));
  }

  private static AccountCapacityEvaluationRetentionPolicy.Safety safety(String... identities) {
    Map<String, AccountCapacityEvaluationRetentionPolicy.Hold> holds = new java.util.HashMap<>();
    for (String identity : identities)
      holds.put(identity, AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR);
    return new AccountCapacityEvaluationRetentionPolicy.Safety(
        "fixture/v1", NOW.minusSeconds(1), NOW.plusSeconds(60), NOW.minusSeconds(1), holds);
  }

  private static Record sample(
      String identity, boolean scopeReferenced, boolean direct, Instant evaluated) {
    return sample(
        identity, CapacityAdmissionTestFixture.TENANT, scopeReferenced, direct, evaluated);
  }

  private static Record sample(
      String identity, UUID tenantId, boolean scopeReferenced, boolean direct, Instant evaluated) {
    Record row = mock(Record.class);
    when(row.get("evaluation_identity", String.class)).thenReturn(identity);
    when(row.get("tenant_uuid", UUID.class)).thenReturn(tenantId);
    when(row.get("owner_service", String.class)).thenReturn("game-session-service");
    when(row.get("evaluated_at", OffsetDateTime.class)).thenReturn(at(evaluated));
    when(row.get("expires_at", OffsetDateTime.class)).thenReturn(at(evaluated.plusSeconds(1)));
    when(row.get("evidence_bytes", Long.class)).thenReturn(10L);
    when(row.get("scope_referenced", Boolean.class)).thenReturn(scopeReferenced);
    when(row.get("directly_referenced", Boolean.class)).thenReturn(direct);
    return row;
  }

  private static Record reservation(TenantCapacityAdmission.Request request) {
    return reservation(request, 2L);
  }

  private static Record reservation(TenantCapacityAdmission.Request request, long rearmFence) {
    Record row =
        reservation(
            request.identity().tenantId(),
            request.identity().requestId(),
            request.identity().admissionId());
    when(row.get("owner_service", String.class)).thenReturn(request.context().owner().service());
    when(row.get("caller_workload", String.class)).thenReturn(request.evidence().callerWorkload());
    when(row.get("capacity_rearm_fence", Long.class)).thenReturn(rearmFence);
    return row;
  }

  private static Record reservation(UUID tenantId, UUID requestId, UUID admissionId) {
    Record row = mock(Record.class);
    when(row.get("tenant_uuid", UUID.class)).thenReturn(tenantId);
    when(row.get("owner_service", String.class)).thenReturn("game-session-service");
    when(row.get("request_id", UUID.class)).thenReturn(requestId);
    when(row.get("admission_id", UUID.class)).thenReturn(admissionId);
    when(row.get("capacity_rearm_fence", Long.class)).thenReturn(2L);
    when(row.get("caller_workload", String.class)).thenReturn("test-workload");
    return row;
  }

  private static Record historyKey(TenantCapacityAdmission.Request request) {
    return historyKey(request, request.admissionAttemptId());
  }

  private static Record historyKey(TenantCapacityAdmission.Request request, UUID attemptId) {
    Record row =
        historyKey(
            request.identity().tenantId(),
            request.identity().requestId(),
            request.identity().admissionId(),
            attemptId);
    when(row.get("owner_service", String.class)).thenReturn(request.context().owner().service());
    when(row.get("capacity_rearm_fence", Long.class)).thenReturn(request.capacityRearmFence());
    when(row.get("mutation_digest", String.class)).thenReturn(request.mutationDigest());
    when(row.get("caller_workload", String.class)).thenReturn(request.evidence().callerWorkload());
    when(row.get("payload_bytes", Long.class))
        .thenReturn((long) CapacityAdmissionDigest.preimage(request).length);
    return row;
  }

  private static Record historyKey(
      UUID tenantId, UUID requestId, UUID admissionId, UUID attemptId) {
    Record row = mock(Record.class);
    when(row.get("tenant_uuid", UUID.class)).thenReturn(tenantId);
    when(row.get("owner_service", String.class)).thenReturn("game-session-service");
    when(row.get("request_id", UUID.class)).thenReturn(requestId);
    when(row.get("admission_id", UUID.class)).thenReturn(admissionId);
    when(row.get("admission_attempt_id", UUID.class)).thenReturn(attemptId);
    when(row.get("caller_workload", String.class)).thenReturn("test-workload");
    when(row.get("capacity_rearm_fence", Long.class)).thenReturn(1L);
    when(row.get("mutation_digest", String.class)).thenReturn("f".repeat(64));
    when(row.get("payload_bytes", Long.class)).thenReturn(1L);
    return row;
  }

  private static Record historyPayload(TenantCapacityAdmission.Request request) {
    Record row = mock(Record.class);
    when(row.get("tenant_uuid", UUID.class)).thenReturn(request.identity().tenantId());
    when(row.get("request_id", UUID.class)).thenReturn(request.identity().requestId());
    when(row.get("admission_id", UUID.class)).thenReturn(request.identity().admissionId());
    when(row.get("admission_attempt_id", UUID.class)).thenReturn(request.admissionAttemptId());
    when(row.get("capacity_rearm_fence", Long.class)).thenReturn(request.capacityRearmFence());
    when(row.get("mutation_digest", String.class)).thenReturn(request.mutationDigest());
    when(row.get("canonical_payload", byte[].class))
        .thenReturn(CapacityAdmissionDigest.preimage(request));
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
