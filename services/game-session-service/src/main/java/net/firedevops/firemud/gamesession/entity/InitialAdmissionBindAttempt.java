package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import java.util.UUID;

/** Durable GS owner ledger row; the attempt UUID is the stable owner-proof identity. */
public record InitialAdmissionBindAttempt(
    UUID attemptId,
    long tenantId,
    String initialAdmissionRequestId,
    String requestDigest,
    UUID realmId,
    UUID playableStateNamespaceId,
    String playableStateScope,
    boolean expectedNoPriorPointer,
    long catalogRevision,
    long gameInstanceId,
    long versionId,
    long activeLifecycleEpoch,
    UUID holdId,
    UUID holdFence,
    Status status,
    Long pointerId,
    Long auditEventId,
    Instant createdAt,
    Instant updatedAt,
    Instant terminalAt) {
  public enum Status {
    PENDING,
    COMMITTED,
    ABORTED
  }
}
