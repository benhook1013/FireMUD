package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered read-only Account-local retention inspection, with no scheduler or endpoint.
 *
 * <p>Evaluation expiry ends admission freshness, not retention. Every retained reservation scope
 * remains protected, including terminal reservations and earlier rearmed evaluations whose only
 * historical reference is inside immutable attempt payloads. Expired evaluations outside those
 * scopes still require the approved environment schedule, replay/restore compatibility and current
 * governance/hold authority. None of those authorities is implemented by this component. There is
 * no cleanup operation or policy-approval API here. Existing database UPDATE/DELETE/TRUNCATE guards
 * remain unchanged and reject physical cleanup.
 */
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Private Account owner collaborator.")
public final class AccountCapacityEvaluationRetentionInspectionOwner {
  public static final int MAX_SAMPLE_ROWS = 1000;
  // At most 8 scopes × 16 reservations × 9 indexed history-key rows are inspected. At most 32
  // payloads are fetched (16 MiB under the V1040 row-size constraint); sentinels fail closed.
  private static final int MAX_REFERENCE_SCOPES = 8;
  private static final int MAX_RESERVATIONS_PER_SCOPE = 16;
  private static final int MAX_HISTORY_ROWS_PER_SCOPE = 8;
  private static final int MAX_HISTORY_ROWS_PER_OBSERVATION = 32;
  private static final int MAX_RETAINED_PAYLOAD_BYTES = 524288;

  public enum Blocker {
    LIVE_EVALUATION,
    RETAINED_RESERVATION_SCOPE,
    POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE,
    HELD,
    ABOVE_SAFE_WATERMARK,
    RETENTION_WINDOW_OPEN
  }

  /**
   * Sample counts/bytes are lower bounds, never whole-table cardinality or deletion eligibility.
   */
  public record SampleWindow(int rows, long evidenceBytes, Optional<Instant> oldestEvaluatedAt) {
    public SampleWindow {
      Objects.requireNonNull(oldestEvaluatedAt);
    }

    private SampleWindow include(Instant evaluated, long bytes) {
      return new SampleWindow(
          rows + 1,
          Math.addExact(evidenceBytes, bytes),
          oldestEvaluatedAt.or(() -> Optional.of(evaluated)));
    }
  }

  /**
   * Physical relation bytes include indexes and TOAST; the catalog row estimate may be unavailable
   * or stale. Only the oldest bounded sample is inspected. The default observation does not read
   * evidence payload bytes; a current policy-qualified assessment decodes only bounded attempt
   * envelopes. Neither identities nor payloads are exposed. Candidate counts authorize no mutation.
   */
  public record Observation(
      Instant observedAt,
      long totalRelationBytes,
      OptionalLong estimatedTableRows,
      int sampledRows,
      boolean sampleTruncated,
      @Nullable Blocker governanceBlocker,
      Map<Blocker, SampleWindow> blockers,
      Optional<String> scheduleVersion,
      SampleWindow candidates) {
    public Observation {
      Objects.requireNonNull(observedAt);
      Objects.requireNonNull(estimatedTableRows);
      blockers = Map.copyOf(blockers);
      Objects.requireNonNull(scheduleVersion);
      Objects.requireNonNull(candidates);
    }
  }

  private final DSLContext dsl;
  private final TransactionTemplate reads;

  private record Scope(UUID tenantId, String ownerService) {}

  private record ReservationKey(
      Scope scope,
      UUID requestId,
      UUID admissionId,
      long currentRearmFence,
      String callerWorkload) {}

  private record HistoryKey(
      ReservationKey reservation, UUID attemptId, long rearmFence, String mutationDigest) {}

  private record SampledEvaluation(
      String evaluationIdentity,
      Scope scope,
      Instant evaluatedAt,
      Instant expiresAt,
      long evidenceBytes,
      boolean directlyReferenced,
      boolean scopeReferenced) {}

  private record HistoryQualification(Set<String> references, Set<Scope> unqualifiedScopes) {}

  public AccountCapacityEvaluationRetentionInspectionOwner(
      DSLContext dsl, PlatformTransactionManager transactions) {
    this.dsl = Objects.requireNonNull(dsl);
    reads = new TransactionTemplate(Objects.requireNonNull(transactions));
    reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    reads.setReadOnly(true);
    reads.setTimeout(5);
  }

  /** One bounded consistent observation, not a sweep or a claim that the sample is erasable. */
  public Observation observe(int sampleRows) {
    return observe(sampleRows, null, null);
  }

  Observation observe(
      int sampleRows,
      AccountCapacityEvaluationRetentionPolicy.@Nullable Schedule schedule,
      AccountCapacityEvaluationRetentionPolicy.@Nullable Safety safety) {
    requireBound(sampleRows);
    return Objects.requireNonNull(
        reads.execute(status -> readObservation(sampleRows, schedule, safety)));
  }

  private Observation readObservation(
      int sampleRows,
      AccountCapacityEvaluationRetentionPolicy.@Nullable Schedule schedule,
      AccountCapacityEvaluationRetentionPolicy.@Nullable Safety safety) {
    Record table =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT transaction_timestamp() AS observed_at, "
                    + "pg_total_relation_size(relation.oid) AS relation_bytes, "
                    + "relation.reltuples::bigint AS estimated_rows "
                    + "FROM pg_class relation WHERE relation.oid = "
                    + "'account_tenant_capacity_evaluations'::regclass"));
    Instant observedAt =
        Objects.requireNonNull(table.get("observed_at", OffsetDateTime.class)).toInstant();
    long relationBytes = Objects.requireNonNull(table.get("relation_bytes", Long.class));
    long estimate = Objects.requireNonNull(table.get("estimated_rows", Long.class));
    boolean policyCurrent =
        AccountCapacityEvaluationRetentionPolicy.current(schedule, safety, observedAt);
    // The initial sample is one bounded query; identity fields remain private to this inspection.
    var rows =
        dsl.fetch(
            "WITH sample AS MATERIALIZED ("
                + "SELECT evaluation_identity, tenant_uuid, owner_service, evaluated_at, expires_at, "
                + "octet_length(canonical_evidence)::bigint AS evidence_bytes "
                + "FROM account_tenant_capacity_evaluations "
                + "ORDER BY evaluated_at, evaluation_identity LIMIT ?) "
                + "SELECT sample.evaluation_identity, sample.tenant_uuid, sample.owner_service, "
                + "sample.evaluated_at, sample.expires_at, sample.evidence_bytes, "
                + "EXISTS (SELECT 1 FROM account_tenant_capacity_reservations reservation "
                + "WHERE reservation.tenant_uuid = sample.tenant_uuid "
                + "AND reservation.owner_service = sample.owner_service) AS scope_referenced, "
                + (policyCurrent
                    ? "EXISTS (SELECT 1 FROM account_tenant_capacity_reservations reservation "
                        + "WHERE reservation.tenant_uuid = sample.tenant_uuid "
                        + "AND reservation.owner_service = sample.owner_service "
                        + "AND reservation.evaluation_identity = sample.evaluation_identity) AS directly_referenced "
                    : "FALSE AS directly_referenced ")
                + "FROM sample ORDER BY sample.evaluated_at, sample.evaluation_identity",
            sampleRows + 1);

    int count = Math.min(rows.size(), sampleRows);
    List<SampledEvaluation> sampled = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      Record row = rows.get(index);
      sampled.add(
          new SampledEvaluation(
              row.get("evaluation_identity", String.class),
              new Scope(row.get("tenant_uuid", UUID.class), row.get("owner_service", String.class)),
              Objects.requireNonNull(row.get("evaluated_at", OffsetDateTime.class)).toInstant(),
              Objects.requireNonNull(row.get("expires_at", OffsetDateTime.class)).toInstant(),
              Objects.requireNonNull(row.get("evidence_bytes", Long.class)),
              Boolean.TRUE.equals(row.get("directly_referenced", Boolean.class)),
              Boolean.TRUE.equals(row.get("scope_referenced", Boolean.class))));
    }
    HistoryQualification history =
        policyCurrent ? qualifyHistory(sampled) : new HistoryQualification(Set.of(), Set.of());

    var empty = new SampleWindow(0, 0, Optional.empty());
    Map<Blocker, SampleWindow> windows = new EnumMap<>(Blocker.class);
    for (Blocker blocker : Blocker.values()) {
      windows.put(blocker, empty);
    }
    SampleWindow candidates = empty;
    for (int index = 0; index < count; index++) {
      SampledEvaluation row = sampled.get(index);
      Boolean referenced;
      if (!policyCurrent) {
        // Without current policy and hold inputs, retain the original cheap scope-wide block.
        referenced = row.scopeReferenced();
      } else if (row.directlyReferenced()
          || history.references().contains(row.evaluationIdentity())) {
        referenced = true;
      } else if (history.unqualifiedScopes().contains(row.scope())) {
        // An incomplete or over-limit history scan cannot prove the scope is unreferenced.
        referenced = true;
      } else {
        referenced = false;
      }
      var classification =
          AccountCapacityEvaluationRetentionPolicy.classify(
              schedule,
              safety,
              observedAt,
              row.evaluationIdentity(),
              row.evaluatedAt(),
              row.expiresAt(),
              referenced);
      if (classification == AccountCapacityEvaluationRetentionPolicy.Classification.CANDIDATE) {
        candidates = candidates.include(row.evaluatedAt(), row.evidenceBytes());
      } else {
        Blocker blocker = Blocker.valueOf(classification.name());
        windows.put(
            blocker,
            Objects.requireNonNull(windows.get(blocker))
                .include(row.evaluatedAt(), row.evidenceBytes()));
      }
    }
    Optional<String> version =
        schedule != null
                && AccountCapacityEvaluationRetentionPolicy.current(schedule, safety, observedAt)
            ? Optional.of(schedule.version())
            : Optional.empty();
    return new Observation(
        observedAt,
        relationBytes,
        estimate < 0 ? OptionalLong.empty() : OptionalLong.of(estimate),
        count,
        rows.size() > sampleRows,
        version.isPresent() ? null : Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE,
        windows,
        version,
        candidates);
  }

  private HistoryQualification qualifyHistory(List<SampledEvaluation> sampled) {
    Set<Scope> requiredScopes = new HashSet<>();
    for (SampledEvaluation row : sampled) {
      if (row.scopeReferenced() && !row.directlyReferenced()) requiredScopes.add(row.scope());
    }
    if (requiredScopes.isEmpty()) return new HistoryQualification(Set.of(), Set.of());

    List<Scope> orderedScopes =
        requiredScopes.stream()
            .sorted(
                (left, right) -> {
                  int tenantOrder = left.tenantId().compareTo(right.tenantId());
                  return tenantOrder != 0
                      ? tenantOrder
                      : left.ownerService().compareTo(right.ownerService());
                })
            .toList();
    Set<Scope> unqualified = new HashSet<>();
    if (orderedScopes.size() > MAX_REFERENCE_SCOPES) {
      unqualified.addAll(orderedScopes.subList(MAX_REFERENCE_SCOPES, orderedScopes.size()));
      orderedScopes = orderedScopes.subList(0, MAX_REFERENCE_SCOPES);
    }

    // Cap scope enumeration before looking at attempt payloads. The extra row detects overflow.
    List<Object> scopeBindings = new ArrayList<>();
    for (Scope scope : orderedScopes) {
      scopeBindings.add(scope.tenantId());
      scopeBindings.add(scope.ownerService());
    }
    String scopeValues = valuesClause(orderedScopes.size(), 2, "uuid", "text");
    var reservationRows =
        dsl.fetch(
            "WITH scopes(tenant_uuid, owner_service) AS (VALUES "
                + scopeValues
                + ") SELECT scopes.tenant_uuid, scopes.owner_service, reservation.request_id, "
                + "reservation.admission_id, reservation.capacity_rearm_fence, reservation.caller_workload "
                + "FROM scopes CROSS JOIN LATERAL ("
                + "SELECT request_id, admission_id, capacity_rearm_fence, caller_workload "
                + "FROM account_tenant_capacity_reservations "
                + "WHERE tenant_uuid = scopes.tenant_uuid AND owner_service = scopes.owner_service "
                + "LIMIT "
                + (MAX_RESERVATIONS_PER_SCOPE + 1)
                + ") reservation",
            scopeBindings.toArray());
    Map<Scope, List<ReservationKey>> reservations = new HashMap<>();
    for (Record row : reservationRows) {
      Scope scope =
          new Scope(row.get("tenant_uuid", UUID.class), row.get("owner_service", String.class));
      reservations
          .computeIfAbsent(scope, ignored -> new ArrayList<>())
          .add(
              new ReservationKey(
                  scope,
                  row.get("request_id", UUID.class),
                  row.get("admission_id", UUID.class),
                  Objects.requireNonNull(row.get("capacity_rearm_fence", Long.class)),
                  row.get("caller_workload", String.class)));
    }
    for (Scope scope : orderedScopes) {
      List<ReservationKey> scopeReservations = reservations.getOrDefault(scope, List.of());
      if (scopeReservations.size() > MAX_RESERVATIONS_PER_SCOPE) {
        unqualified.add(scope);
        reservations.remove(scope);
      }
    }
    List<ReservationKey> qualifiedReservations =
        reservations.values().stream().flatMap(List::stream).toList();
    if (qualifiedReservations.isEmpty())
      return new HistoryQualification(Set.of(), Set.copyOf(unqualified));

    List<Object> reservationBindings = new ArrayList<>();
    for (ReservationKey reservation : qualifiedReservations) {
      reservationBindings.add(reservation.scope().tenantId());
      reservationBindings.add(reservation.scope().ownerService());
      reservationBindings.add(reservation.requestId());
      reservationBindings.add(reservation.admissionId());
      reservationBindings.add(reservation.callerWorkload());
    }
    String reservationValues =
        valuesClause(qualifiedReservations.size(), 5, "uuid", "text", "uuid", "uuid", "text");
    var historyKeys =
        dsl.fetch(
            "WITH reservations(tenant_uuid, owner_service, request_id, admission_id, caller_workload) AS (VALUES "
                + reservationValues
                + "), scopes AS (SELECT DISTINCT tenant_uuid, owner_service FROM reservations) "
                + "SELECT histories.tenant_uuid, histories.owner_service, histories.request_id, "
                + "histories.admission_id, histories.caller_workload, histories.admission_attempt_id, histories.capacity_rearm_fence, "
                + "histories.mutation_digest, histories.payload_bytes FROM scopes CROSS JOIN LATERAL ("
                + "SELECT bounded_history.tenant_uuid, bounded_history.owner_service, bounded_history.request_id, "
                + "bounded_history.admission_id, bounded_history.caller_workload, bounded_history.admission_attempt_id, "
                + "bounded_history.capacity_rearm_fence, bounded_history.mutation_digest, "
                + "bounded_history.payload_bytes FROM (SELECT * FROM reservations "
                + "WHERE tenant_uuid = scopes.tenant_uuid AND owner_service = scopes.owner_service) reservations "
                + "CROSS JOIN LATERAL ("
                + "SELECT history.tenant_uuid, reservations.owner_service, history.request_id, "
                + "history.admission_id, reservations.caller_workload, history.admission_attempt_id, history.capacity_rearm_fence, "
                + "history.mutation_digest, octet_length(history.canonical_payload)::bigint AS payload_bytes "
                + "FROM account_tenant_capacity_attempt_history history "
                + "WHERE history.tenant_uuid = reservations.tenant_uuid "
                + "AND history.request_id = reservations.request_id "
                + "AND history.admission_id = reservations.admission_id "
                + "ORDER BY history.admission_attempt_id LIMIT "
                + (MAX_HISTORY_ROWS_PER_SCOPE + 1)
                + ") bounded_history "
                + "ORDER BY bounded_history.request_id, bounded_history.admission_id, "
                + "bounded_history.admission_attempt_id LIMIT "
                + (MAX_HISTORY_ROWS_PER_SCOPE + 1)
                + ") histories ORDER BY histories.tenant_uuid, histories.owner_service, histories.request_id, "
                + "histories.admission_id, histories.admission_attempt_id LIMIT "
                + (MAX_HISTORY_ROWS_PER_OBSERVATION + 1),
            reservationBindings.toArray());
    if (historyKeys.size() > MAX_HISTORY_ROWS_PER_OBSERVATION) {
      // The observation-wide byte/row budget was exceeded; none of the unscanned history is
      // inferred.
      unqualified.addAll(reservations.keySet());
      return new HistoryQualification(Set.of(), Set.copyOf(unqualified));
    }

    Map<Scope, List<HistoryKey>> keysByScope = new HashMap<>();
    for (Record row : historyKeys) {
      Scope scope =
          new Scope(row.get("tenant_uuid", UUID.class), row.get("owner_service", String.class));
      if (Objects.requireNonNull(row.get("payload_bytes", Long.class))
          > MAX_RETAINED_PAYLOAD_BYTES) {
        unqualified.add(scope);
        continue;
      }
      UUID requestId = row.get("request_id", UUID.class);
      UUID admissionId = row.get("admission_id", UUID.class);
      String callerWorkload = row.get("caller_workload", String.class);
      ReservationKey reservation =
          qualifiedReservations.stream()
              .filter(
                  candidate ->
                      candidate.scope().equals(scope)
                          && candidate.requestId().equals(requestId)
                          && candidate.admissionId().equals(admissionId)
                          && candidate.callerWorkload().equals(callerWorkload))
              .findFirst()
              .orElse(null);
      if (reservation == null) {
        unqualified.add(scope);
        continue;
      }
      keysByScope
          .computeIfAbsent(scope, ignored -> new ArrayList<>())
          .add(
              new HistoryKey(
                  reservation,
                  row.get("admission_attempt_id", UUID.class),
                  Objects.requireNonNull(row.get("capacity_rearm_fence", Long.class)),
                  Objects.requireNonNull(row.get("mutation_digest", String.class)).trim()));
    }
    for (Map.Entry<Scope, List<HistoryKey>> entry : keysByScope.entrySet()) {
      if (entry.getValue().size() > MAX_HISTORY_ROWS_PER_SCOPE) unqualified.add(entry.getKey());
    }
    for (ReservationKey reservation : qualifiedReservations) {
      if (unqualified.contains(reservation.scope())) continue;
      List<HistoryKey> attemptKeys =
          keysByScope.getOrDefault(reservation.scope(), List.of()).stream()
              .filter(key -> key.reservation().equals(reservation))
              .toList();
      long expectedHistoryRows = reservation.currentRearmFence() - 1;
      if (expectedHistoryRows < 0
          || expectedHistoryRows != attemptKeys.size()
          || expectedHistoryRows > MAX_HISTORY_ROWS_PER_SCOPE) {
        unqualified.add(reservation.scope());
        continue;
      }
      Set<Long> fences = new HashSet<>();
      for (HistoryKey key : attemptKeys) fences.add(key.rearmFence());
      for (long expectedFence = 1; expectedFence <= expectedHistoryRows; expectedFence++) {
        if (!fences.contains(expectedFence)) {
          unqualified.add(reservation.scope());
          break;
        }
      }
    }

    List<HistoryKey> keysToRead =
        keysByScope.entrySet().stream()
            .filter(entry -> !unqualified.contains(entry.getKey()))
            .flatMap(entry -> entry.getValue().stream())
            .toList();
    if (keysToRead.isEmpty()) return new HistoryQualification(Set.of(), Set.copyOf(unqualified));
    List<Object> historyBindings = new ArrayList<>();
    for (HistoryKey key : keysToRead) {
      historyBindings.add(key.reservation().scope().tenantId());
      historyBindings.add(key.reservation().requestId());
      historyBindings.add(key.reservation().admissionId());
      historyBindings.add(key.attemptId());
    }
    String historyValues = valuesClause(keysToRead.size(), 4, "uuid", "uuid", "uuid", "uuid");
    var payloadRows =
        dsl.fetch(
            "WITH selected(tenant_uuid, request_id, admission_id, admission_attempt_id) AS (VALUES "
                + historyValues
                + ") SELECT history.tenant_uuid, history.request_id, history.admission_id, "
                + "history.admission_attempt_id, history.capacity_rearm_fence, history.mutation_digest, "
                + "history.canonical_payload FROM selected JOIN account_tenant_capacity_attempt_history history "
                + "USING (tenant_uuid, request_id, admission_id, admission_attempt_id)",
            historyBindings.toArray());
    if (payloadRows.size() != keysToRead.size()) {
      unqualified.addAll(keysByScope.keySet());
      return new HistoryQualification(Set.of(), Set.copyOf(unqualified));
    }

    Map<Scope, Set<String>> referencesByScope = new HashMap<>();
    Set<Scope> malformed = new HashSet<>();
    for (Record row : payloadRows) {
      UUID tenantId = row.get("tenant_uuid", UUID.class);
      UUID requestId = row.get("request_id", UUID.class);
      UUID admissionId = row.get("admission_id", UUID.class);
      UUID attemptId = row.get("admission_attempt_id", UUID.class);
      HistoryKey expected =
          keysToRead.stream()
              .filter(
                  key ->
                      key.reservation().scope().tenantId().equals(tenantId)
                          && key.reservation().requestId().equals(requestId)
                          && key.reservation().admissionId().equals(admissionId)
                          && key.attemptId().equals(attemptId))
              .findFirst()
              .orElse(null);
      Scope scope = expected == null ? null : expected.reservation().scope();
      if (scope == null || requestId == null || admissionId == null || attemptId == null) {
        if (scope != null) malformed.add(scope);
        else unqualified.addAll(keysByScope.keySet());
        continue;
      }
      ReservationKey reservation = expected.reservation();
      try {
        long fence = Objects.requireNonNull(row.get("capacity_rearm_fence", Long.class));
        String digest = Objects.requireNonNull(row.get("mutation_digest", String.class)).trim();
        byte[] payload = Objects.requireNonNull(row.get("canonical_payload", byte[].class));
        if (fence != expected.rearmFence()
            || !digest.equals(expected.mutationDigest())
            || payload.length > MAX_RETAINED_PAYLOAD_BYTES) throw new IllegalArgumentException();
        var request = CapacityAdmissionDigest.decodeCommittable(payload, digest);
        if (!request.identity().tenantId().equals(scope.tenantId())
            || !request.identity().requestId().equals(requestId)
            || !request.identity().admissionId().equals(admissionId)
            || !request.admissionAttemptId().equals(attemptId)
            || request.capacityRearmFence() != fence
            || !request.context().owner().service().equals(reservation.scope().ownerService())
            || !request.evidence().callerWorkload().equals(reservation.callerWorkload()))
          throw new IllegalArgumentException();
        referencesByScope
            .computeIfAbsent(scope, ignored -> new HashSet<>())
            .add(request.evidence().evaluationIdentity());
      } catch (RuntimeException invalid) {
        malformed.add(scope);
      }
    }
    unqualified.addAll(malformed);
    Set<String> references = new HashSet<>();
    for (Map.Entry<Scope, Set<String>> entry : referencesByScope.entrySet()) {
      if (!unqualified.contains(entry.getKey())) references.addAll(entry.getValue());
    }
    return new HistoryQualification(Set.copyOf(references), Set.copyOf(unqualified));
  }

  private static String valuesClause(int rows, int columns, String... casts) {
    if (rows < 1 || columns != casts.length)
      throw new IllegalArgumentException("Invalid SQL values shape");
    String row =
        "("
            + String.join(", ", java.util.Arrays.stream(casts).map(cast -> "?::" + cast).toList())
            + ")";
    return String.join(", ", java.util.Collections.nCopies(rows, row));
  }

  private static void requireBound(int rows) {
    if (rows < 1 || rows > MAX_SAMPLE_ROWS) {
      throw new IllegalArgumentException(
          "Capacity retention sample must be between 1 and 1000 rows");
    }
  }
}
