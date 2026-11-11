package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.jooq.DSLContext;
import org.jooq.Record;
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

  public enum Blocker {
    LIVE_EVALUATION,
    RETAINED_RESERVATION_SCOPE,
    POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE
  }

  /**
   * Sample counts/bytes are lower bounds, never whole-table cardinality or deletion eligibility.
   */
  public record BlockedWindow(int rows, long evidenceBytes, Optional<Instant> oldestEvaluatedAt) {
    public BlockedWindow {
      Objects.requireNonNull(oldestEvaluatedAt);
    }
  }

  /**
   * Physical relation bytes include indexes and TOAST; the catalog row estimate may be unavailable
   * or stale. Only the oldest bounded sample is inspected, without reading evidence payload bytes
   * or exposing tenant, caller or evaluation identities. There is no approved cleanup-lag horizon.
   */
  public record Observation(
      Instant observedAt,
      long totalRelationBytes,
      OptionalLong estimatedTableRows,
      int sampledRows,
      boolean sampleTruncated,
      Blocker governanceBlocker,
      Map<Blocker, BlockedWindow> blockers) {
    public Observation {
      Objects.requireNonNull(observedAt);
      Objects.requireNonNull(estimatedTableRows);
      Objects.requireNonNull(governanceBlocker);
      blockers = Map.copyOf(blockers);
    }
  }

  private final DSLContext dsl;
  private final TransactionTemplate reads;

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
    requireBound(sampleRows);
    return Objects.requireNonNull(reads.execute(status -> readObservation(sampleRows)));
  }

  private Observation readObservation(int sampleRows) {
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
    // Scope-wide preservation is intentional: rearm replaces the current evaluation FK, while
    // historical canonical_payload and owner_readback can still name the previous evaluation.
    var rows =
        dsl.fetch(
            "WITH sample AS MATERIALIZED ("
                + "SELECT evaluation_identity, tenant_uuid, owner_service, evaluated_at, expires_at, "
                + "octet_length(canonical_evidence)::bigint AS evidence_bytes "
                + "FROM account_tenant_capacity_evaluations "
                + "ORDER BY evaluated_at, evaluation_identity LIMIT ?) "
                + "SELECT sample.evaluated_at, sample.expires_at, sample.evidence_bytes, "
                + "EXISTS (SELECT 1 FROM account_tenant_capacity_reservations reservation "
                + "WHERE reservation.tenant_uuid = sample.tenant_uuid "
                + "AND reservation.owner_service = sample.owner_service) AS scope_referenced "
                + "FROM sample ORDER BY sample.evaluated_at, sample.evaluation_identity",
            sampleRows + 1);
    Map<Blocker, BlockedWindow> windows = new EnumMap<>(Blocker.class);
    for (Blocker blocker : Blocker.values()) {
      windows.put(blocker, new BlockedWindow(0, 0, Optional.empty()));
    }
    int count = Math.min(rows.size(), sampleRows);
    for (int index = 0; index < count; index++) {
      Record row = rows.get(index);
      Instant evaluated =
          Objects.requireNonNull(row.get("evaluated_at", OffsetDateTime.class)).toInstant();
      Instant expires =
          Objects.requireNonNull(row.get("expires_at", OffsetDateTime.class)).toInstant();
      Blocker blocker =
          Boolean.TRUE.equals(row.get("scope_referenced", Boolean.class))
              ? Blocker.RETAINED_RESERVATION_SCOPE
              : observedAt.isBefore(expires)
                  ? Blocker.LIVE_EVALUATION
                  : Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE;
      BlockedWindow before = windows.get(blocker);
      windows.put(
          blocker,
          new BlockedWindow(
              before.rows() + 1,
              Math.addExact(
                  before.evidenceBytes(),
                  Objects.requireNonNull(row.get("evidence_bytes", Long.class))),
              before.oldestEvaluatedAt().or(() -> Optional.of(evaluated))));
    }
    return new Observation(
        observedAt,
        relationBytes,
        estimate < 0 ? OptionalLong.empty() : OptionalLong.of(estimate),
        count,
        rows.size() > sampleRows,
        Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE,
        windows);
  }

  private static void requireBound(int rows) {
    if (rows < 1 || rows > MAX_SAMPLE_ROWS) {
      throw new IllegalArgumentException(
          "Capacity retention sample must be between 1 and 1000 rows");
    }
  }
}
