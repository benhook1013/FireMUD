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
    Instant terminalAt,
    String catalogSourceKind,
    String publishedTargetNamespace,
    UUID canonicalTenantId,
    Long gameTemplateId,
    String launchDescriptorId,
    Long releaseBundleId,
    String publishedReleaseBundleRef,
    Long versionStateEpoch) {
  /** Historical V9 fixture attempt shape retained for existing tests and migration readers. */
  public InitialAdmissionBindAttempt(
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
    this(
        attemptId,
        tenantId,
        initialAdmissionRequestId,
        requestDigest,
        realmId,
        playableStateNamespaceId,
        playableStateScope,
        expectedNoPriorPointer,
        catalogRevision,
        gameInstanceId,
        versionId,
        activeLifecycleEpoch,
        holdId,
        holdFence,
        status,
        pointerId,
        auditEventId,
        createdAt,
        updatedAt,
        terminalAt,
        "V9_FIXTURE",
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public enum Status {
    PENDING,
    COMMITTED,
    ABORTED
  }
}
