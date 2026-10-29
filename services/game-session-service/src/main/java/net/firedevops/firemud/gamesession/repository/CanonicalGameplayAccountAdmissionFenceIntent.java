package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationDisposition;

/**
 * Immutable Game Session SQL read model for an uninstalled account-wide fence acquisition intent.
 */
public final class CanonicalGameplayAccountAdmissionFenceIntent {
  public enum State {
    ACQUISITION_INTENT
  }

  public enum CohortKind {
    BINDING,
    TRANSITION,
    RESERVATION,
    ACCOUNT_OBLIGATION,
    ISSUER_OBLIGATION,
    REGION_OBLIGATION,
    MEMBER_REPAIR_CONTROL,
    MEMBER_REPAIR_OPERATION,
    INITIALIZER,
    INITIAL_TARGET
  }

  private final UUID operationId;
  private final UUID requestId;
  private final UUID accountId;
  private final BigInteger operationFence;
  private final UUID plannedAccountAdmissionFence;
  private final BigInteger inventorySnapshotRevision;
  private final CanonicalGameplayLegacyMigrationDisposition legacyMigrationDisposition;
  private final State state;
  private final List<CohortRow> cohortRows;
  private final List<BarrierTarget> barrierTargets;
  private final String cohortDigest;
  private final String barrierTargetDigest;

  CanonicalGameplayAccountAdmissionFenceIntent(
      UUID operationId,
      UUID requestId,
      UUID accountId,
      BigInteger operationFence,
      UUID plannedAccountAdmissionFence,
      BigInteger inventorySnapshotRevision,
      CanonicalGameplayLegacyMigrationDisposition legacyMigrationDisposition,
      State state,
      List<CohortRow> cohortRows,
      List<BarrierTarget> barrierTargets,
      String cohortDigest,
      String barrierTargetDigest) {
    this.operationId = Objects.requireNonNull(operationId, "operationId");
    this.requestId = Objects.requireNonNull(requestId, "requestId");
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    this.operationFence = Objects.requireNonNull(operationFence, "operationFence");
    this.plannedAccountAdmissionFence =
        Objects.requireNonNull(plannedAccountAdmissionFence, "plannedAccountAdmissionFence");
    this.inventorySnapshotRevision =
        Objects.requireNonNull(inventorySnapshotRevision, "inventorySnapshotRevision");
    this.legacyMigrationDisposition =
        Objects.requireNonNull(legacyMigrationDisposition, "legacyMigrationDisposition");
    this.state = Objects.requireNonNull(state, "state");
    this.cohortRows = List.copyOf(Objects.requireNonNull(cohortRows, "cohortRows"));
    this.barrierTargets = List.copyOf(Objects.requireNonNull(barrierTargets, "barrierTargets"));
    this.cohortDigest = requireDigest(cohortDigest, "cohortDigest");
    this.barrierTargetDigest = requireDigest(barrierTargetDigest, "barrierTargetDigest");
    if (this.cohortRows.stream().anyMatch(Objects::isNull)
        || this.barrierTargets.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Persisted intent snapshots must not contain null rows");
    }
    if (operationFence.signum() <= 0 || inventorySnapshotRevision.signum() <= 0) {
      throw new IllegalArgumentException("Intent fence and snapshot revision must be positive");
    }
  }

  public UUID operationId() {
    return operationId;
  }

  public UUID requestId() {
    return requestId;
  }

  public UUID accountId() {
    return accountId;
  }

  public BigInteger operationFence() {
    return operationFence;
  }

  /** Identity reserved for the future CLOSED barrier; this does not prove that it is installed. */
  public UUID plannedAccountAdmissionFence() {
    return plannedAccountAdmissionFence;
  }

  public BigInteger inventorySnapshotRevision() {
    return inventorySnapshotRevision;
  }

  public CanonicalGameplayLegacyMigrationDisposition legacyMigrationDisposition() {
    return legacyMigrationDisposition;
  }

  public State state() {
    return state;
  }

  public List<CohortRow> cohortRows() {
    return cohortRows;
  }

  public List<BarrierTarget> barrierTargets() {
    return barrierTargets;
  }

  public String cohortDigest() {
    return cohortDigest;
  }

  public String barrierTargetDigest() {
    return barrierTargetDigest;
  }

  public static final class CohortRow {
    private final long ordinal;
    private final CohortKind kind;
    private final String sourceKey;
    private final UUID tenantId;
    private final UUID playableStateNamespaceId;
    private final String sourceRecordJson;

    private CohortRow(
        long ordinal,
        CohortKind kind,
        String sourceKey,
        UUID tenantId,
        UUID playableStateNamespaceId,
        String sourceRecordJson) {
      this.ordinal = ordinal;
      this.kind = Objects.requireNonNull(kind, "kind");
      this.sourceKey = Objects.requireNonNull(sourceKey, "sourceKey");
      this.tenantId = tenantId;
      this.playableStateNamespaceId = playableStateNamespaceId;
      this.sourceRecordJson = Objects.requireNonNull(sourceRecordJson, "sourceRecordJson");
    }

    public long ordinal() {
      return ordinal;
    }

    public CohortKind kind() {
      return kind;
    }

    public String sourceKey() {
      return sourceKey;
    }

    public UUID tenantId() {
      return tenantId;
    }

    public UUID playableStateNamespaceId() {
      return playableStateNamespaceId;
    }

    /** Exact owner-captured source row serialization, retained for bounded later reconciliation. */
    public String sourceRecordJson() {
      return sourceRecordJson;
    }
  }

  public static final class BarrierTarget {
    private final long ordinal;
    private final UUID tenantId;
    private final UUID playableStateNamespaceId;

    private BarrierTarget(long ordinal, UUID tenantId, UUID playableStateNamespaceId) {
      this.ordinal = ordinal;
      this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
      this.playableStateNamespaceId =
          Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
    }

    public long ordinal() {
      return ordinal;
    }

    public UUID tenantId() {
      return tenantId;
    }

    public UUID playableStateNamespaceId() {
      return playableStateNamespaceId;
    }
  }

  static CohortRow cohortRow(
      long ordinal,
      CohortKind kind,
      String sourceKey,
      UUID tenantId,
      UUID playableStateNamespaceId,
      String sourceRecordJson) {
    return new CohortRow(
        ordinal, kind, sourceKey, tenantId, playableStateNamespaceId, sourceRecordJson);
  }

  static BarrierTarget barrierTarget(long ordinal, UUID tenantId, UUID playableStateNamespaceId) {
    return new BarrierTarget(ordinal, tenantId, playableStateNamespaceId);
  }

  private static String requireDigest(String value, String name) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
    return value;
  }
}
