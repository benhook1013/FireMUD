package net.firedevops.firemud.accountservice.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Deterministic SHA-256 digest for a provisional Account membership transition receipt only. */
public final class MembershipTransitionReceiptDigest {
  public static final String EVIDENCE_STATUS = "PROVISIONAL_TRANSITION_RECEIPT";

  private static final String VERSIONED_RECEIPT_ID_PREFIX =
      "account-membership-transition-receipt/v1:";

  private MembershipTransitionReceiptDigest() {}

  public static String receiptStreamKey(long accountId, long tenantId) {
    requirePositive(accountId, "accountId");
    requirePositive(tenantId, "tenantId");
    return "account:membership-transition-receipt:v1:membership/" + accountId + "/" + tenantId;
  }

  public static UUID receiptIdForRequest(String requestId) {
    String request = requiredField("requestId", requestId);
    return UUID.nameUUIDFromBytes(
        (VERSIONED_RECEIPT_ID_PREFIX + request).getBytes(StandardCharsets.UTF_8));
  }

  public static String transitionDigest(
      String receiptStreamKey,
      long receiptSequence,
      UUID receiptId,
      String transitionType,
      String requestId,
      long accountId,
      long tenantId,
      long membershipId,
      String membershipLifecycleState,
      boolean gameplayAdmissionAllowed,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String authorityProvenance) {
    requirePositive(receiptSequence, "receiptSequence");
    requirePositive(accountId, "accountId");
    requirePositive(tenantId, "tenantId");
    requirePositive(membershipId, "membershipId");
    requirePositive(membershipVersion, "membershipVersion");
    requirePositive(membershipAuthorityGeneration, "membershipAuthorityGeneration");
    String streamKey = requiredField("receiptStreamKey", receiptStreamKey);
    String transition = requiredField("transitionType", transitionType);
    String request = requiredField("requestId", requestId);
    String lifecycleState = requiredField("membershipLifecycleState", membershipLifecycleState);
    String provenance = requiredField("authorityProvenance", authorityProvenance);
    if (receiptId == null) {
      throw new IllegalArgumentException("receiptId is required");
    }
    if (!receiptStreamKey(accountId, tenantId).equals(streamKey)) {
      throw new IllegalArgumentException("receiptStreamKey does not match the membership scope");
    }
    if (!("MEMBERSHIP_JOINED".equals(transition) || "MEMBERSHIP_REACTIVATED".equals(transition))) {
      throw new IllegalArgumentException("transitionType is not a supported membership receipt");
    }

    String preimage =
        "accountMembershipTransitionReceiptVersion=v1\n"
            + field("evidenceStatus", EVIDENCE_STATUS)
            + field("receiptStreamKey", streamKey)
            + field("receiptSequence", receiptSequence)
            + field("receiptId", receiptId)
            + field("transitionType", transition)
            + field("requestId", request)
            + field("accountId", accountId)
            + field("tenantId", tenantId)
            + field("membershipId", membershipId)
            + field("membershipLifecycleState", lifecycleState)
            + field("gameplayAdmissionAllowed", gameplayAdmissionAllowed)
            + field("membershipVersion", membershipVersion)
            + field("membershipAuthorityGeneration", membershipAuthorityGeneration)
            + field("authorityProvenance", provenance);
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(preimage.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static String field(String name, Object value) {
    String wireValue = value.toString();
    requiredField(name, wireValue);
    return name + "=" + wireValue + "\n";
  }

  private static String requiredField(String name, String value) {
    if (value == null
        || value.isBlank()
        || value.indexOf('=') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(name + " has an invalid canonical wire value");
    }
    return value;
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
