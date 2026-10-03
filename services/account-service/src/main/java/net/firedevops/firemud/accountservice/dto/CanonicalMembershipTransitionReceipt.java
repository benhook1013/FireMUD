package net.firedevops.firemud.accountservice.dto;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Canonical UUID evidence for a provisional membership transition, not an authority checkpoint. */
public record CanonicalMembershipTransitionReceipt(
    String receiptStreamKey,
    long receiptSequence,
    UUID receiptId,
    String receiptDigest,
    String evidenceStatus,
    String transitionType,
    String requestId,
    UUID accountId,
    UUID tenantId,
    String membershipLifecycleState,
    boolean gameplayAdmissionAllowed,
    Map<String, String> membershipVersion,
    long membershipAuthorityGeneration,
    String authorityProvenance,
    String tenantProvenanceKind,
    UUID tenantSourceOperationId,
    String tenantProvenanceDigest) {

  public CanonicalMembershipTransitionReceipt {
    requestId = MembershipTransitionReceiptDigest.requireRequestIdV2(requestId);
    accountId = MembershipTransitionReceiptDigest.requireCanonicalUuid(accountId, "accountId");
    tenantId = MembershipTransitionReceiptDigest.requireCanonicalUuid(tenantId, "tenantId");
    receiptStreamKey = Objects.requireNonNull(receiptStreamKey, "receiptStreamKey");
    receiptId = Objects.requireNonNull(receiptId, "receiptId");
    receiptDigest =
        MembershipTransitionReceiptDigest.requireSha256Digest(receiptDigest, "receiptDigest");
    evidenceStatus = Objects.requireNonNull(evidenceStatus, "evidenceStatus");
    transitionType = Objects.requireNonNull(transitionType, "transitionType");
    membershipLifecycleState =
        Objects.requireNonNull(membershipLifecycleState, "membershipLifecycleState");
    authorityProvenance = Objects.requireNonNull(authorityProvenance, "authorityProvenance");
    tenantProvenanceKind = Objects.requireNonNull(tenantProvenanceKind, "tenantProvenanceKind");
    tenantSourceOperationId =
        MembershipTransitionReceiptDigest.requireCanonicalUuid(
            tenantSourceOperationId, "tenantSourceOperationId");
    tenantProvenanceDigest =
        MembershipTransitionReceiptDigest.requireSha256Digest(
            tenantProvenanceDigest, "tenantProvenanceDigest");
    if (receiptSequence <= 0L || membershipAuthorityGeneration <= 0L) {
      throw new IllegalArgumentException("Canonical membership receipt counters must be positive");
    }
    if (!MembershipTransitionReceiptDigest.EVIDENCE_STATUS.equals(evidenceStatus)) {
      throw new IllegalArgumentException("Canonical membership receipt evidence status is invalid");
    }
    if (!MembershipTransitionReceiptDigest.receiptIdForRequestV2(requestId).equals(receiptId)) {
      throw new IllegalArgumentException(
          "Canonical membership receipt ID does not match request ID");
    }
    if (membershipVersion == null
        || membershipVersion.size() != 1
        || !membershipVersion.containsKey(tenantId.toString())) {
      throw new IllegalArgumentException(
          "Canonical membership version must contain exactly its tenant UUID");
    }
    String version = membershipVersion.get(tenantId.toString());
    MembershipTransitionReceiptDigest.requirePositiveDecimal(version, "membershipVersion");
    membershipVersion = Map.of(tenantId.toString(), version);

    if (!"APPROVED_RETAINED".equals(tenantProvenanceKind)
        && !"FRESH_GAME_DESIGN".equals(tenantProvenanceKind)) {
      throw new IllegalArgumentException("Canonical membership tenant provenance is unsupported");
    }
    boolean activeTransition =
        "MEMBERSHIP_JOINED".equals(transitionType)
            || "MEMBERSHIP_REACTIVATED".equals(transitionType);
    boolean leaveTransition = "MEMBERSHIP_LEFT".equals(transitionType);
    if ((!activeTransition && !leaveTransition)
        || (activeTransition
            && (!"ACTIVE".equals(membershipLifecycleState) || !gameplayAdmissionAllowed))
        || (leaveTransition
            && (!"INACTIVE".equals(membershipLifecycleState) || gameplayAdmissionAllowed))
        || !"EXPLICIT_JOIN".equals(authorityProvenance)) {
      throw new IllegalArgumentException(
          "Canonical membership receipt transition does not match its state and provenance");
    }
    if (!MembershipTransitionReceiptDigest.receiptStreamKeyV2(accountId, tenantId)
        .equals(receiptStreamKey)) {
      throw new IllegalArgumentException("Canonical membership receipt stream key is inconsistent");
    }
  }
}
