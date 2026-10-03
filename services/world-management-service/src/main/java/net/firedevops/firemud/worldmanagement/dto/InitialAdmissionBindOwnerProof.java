package net.firedevops.firemud.worldmanagement.dto;

/** Typed Game Session owner readback; only an authenticated owner adapter may supply this proof. */
public record InitialAdmissionBindOwnerProof(
    Outcome outcome,
    String holdId,
    String holdFence,
    long tenantId,
    String realmUuid,
    String playableStateNamespaceUuid,
    String playableStateScope,
    long gameInstanceId,
    long versionId,
    long activeLifecycleEpoch,
    String initialAdmissionRequestId,
    String requestDigest,
    boolean expectedNoPriorPointer,
    long expectedCatalogRevision,
    String ownerProofId,
    String pointerAuditId,
    long pointerVersion,
    String pointerAuditRequestDigest,
    boolean futureCommitPrevented) {
  public enum Outcome {
    COMMITTED,
    ABORTED,
    NOT_FOUND,
    PENDING,
    UNAVAILABLE,
    ERROR
  }
}
