package net.firedevops.firemud.accountservice.dto;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Deterministic SHA-256 digest for a provisional Account membership transition receipt only. */
public final class MembershipTransitionReceiptDigest {
  public static final String EVIDENCE_STATUS = "PROVISIONAL_TRANSITION_RECEIPT";

  private static final String VERSIONED_RECEIPT_ID_PREFIX =
      "account-membership-transition-receipt/v1:";
  private static final String VERSIONED_RECEIPT_ID_V2_PREFIX =
      "account-membership-transition-receipt/v2:";
  private static final String RECEIPT_STREAM_KEY_V2_PREFIX =
      "account:membership-transition-receipt:v2:membership/";
  private static final String RECEIPT_DIGEST_V2_DOMAIN = "account-membership-transition-receipt/v2";

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
    boolean activeTransition =
        "MEMBERSHIP_JOINED".equals(transition) || "MEMBERSHIP_REACTIVATED".equals(transition);
    boolean leaveTransition = "MEMBERSHIP_LEFT".equals(transition);
    if (!activeTransition && !leaveTransition) {
      throw new IllegalArgumentException("transitionType is not a supported membership receipt");
    }
    if ((activeTransition && (!"ACTIVE".equals(lifecycleState) || !gameplayAdmissionAllowed))
        || (leaveTransition && (!"INACTIVE".equals(lifecycleState) || gameplayAdmissionAllowed))
        || !"EXPLICIT_JOIN".equals(provenance)) {
      throw new IllegalArgumentException(
          "transitionType does not match the membership state and provenance");
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

  public static String receiptStreamKeyV2(UUID accountUuid, UUID tenantUuid) {
    return RECEIPT_STREAM_KEY_V2_PREFIX
        + requireCanonicalUuid(accountUuid, "accountUuid")
        + "/"
        + requireCanonicalUuid(tenantUuid, "tenantUuid");
  }

  public static UUID receiptIdForRequestV2(String requestId) {
    String request = requireRequestIdV2(requestId);
    return UUID.nameUUIDFromBytes(
        strictUtf8(VERSIONED_RECEIPT_ID_V2_PREFIX + request, "requestId"));
  }

  /** Computes the canonical V2 provisional receipt digest, excluding the digest field itself. */
  public static String transitionDigestV2(CanonicalMembershipTransitionReceipt receipt) {
    if (receipt == null) {
      throw new IllegalArgumentException("canonical membership receipt is required");
    }
    String request = requireRequestIdV2(receipt.requestId());
    UUID accountUuid = requireCanonicalUuid(receipt.accountId(), "accountId");
    UUID tenantUuid = requireCanonicalUuid(receipt.tenantId(), "tenantId");
    UUID receiptId = receipt.receiptId();
    if (receiptId == null || !receiptId.equals(receiptIdForRequestV2(request))) {
      throw new IllegalArgumentException("receiptId does not match the canonical request ID");
    }
    requirePositive(receipt.receiptSequence(), "receiptSequence");
    requirePositive(receipt.membershipAuthorityGeneration(), "membershipAuthorityGeneration");
    String streamKey = requiredV2Field("receiptStreamKey", receipt.receiptStreamKey());
    if (!receiptStreamKeyV2(accountUuid, tenantUuid).equals(streamKey)) {
      throw new IllegalArgumentException("receiptStreamKey does not match the canonical scope");
    }
    String transition = requiredV2Field("transitionType", receipt.transitionType());
    String lifecycle =
        requiredV2Field("membershipLifecycleState", receipt.membershipLifecycleState());
    String authority = requiredV2Field("authorityProvenance", receipt.authorityProvenance());
    String tenantKind = requiredV2Field("tenantProvenanceKind", receipt.tenantProvenanceKind());
    if (!EVIDENCE_STATUS.equals(receipt.evidenceStatus())) {
      throw new IllegalArgumentException("evidenceStatus is not provisional transition evidence");
    }
    boolean activeTransition =
        "MEMBERSHIP_JOINED".equals(transition) || "MEMBERSHIP_REACTIVATED".equals(transition);
    boolean leaveTransition = "MEMBERSHIP_LEFT".equals(transition);
    if ((!activeTransition && !leaveTransition)
        || (activeTransition
            && (!"ACTIVE".equals(lifecycle) || !receipt.gameplayAdmissionAllowed()))
        || (leaveTransition
            && (!"INACTIVE".equals(lifecycle) || receipt.gameplayAdmissionAllowed()))
        || !"EXPLICIT_JOIN".equals(authority)) {
      throw new IllegalArgumentException(
          "transitionType does not match canonical membership state");
    }
    if (!"APPROVED_RETAINED".equals(tenantKind) && !"FRESH_GAME_DESIGN".equals(tenantKind)) {
      throw new IllegalArgumentException("tenantProvenanceKind is unsupported");
    }
    UUID sourceOperation =
        requireCanonicalUuid(receipt.tenantSourceOperationId(), "tenantSourceOperationId");
    String sourceDigest =
        requireSha256Digest(receipt.tenantProvenanceDigest(), "tenantProvenanceDigest");
    Map<String, String> membershipVersion = receipt.membershipVersion();
    if (membershipVersion == null
        || membershipVersion.size() != 1
        || !membershipVersion.containsKey(tenantUuid.toString())) {
      throw new IllegalArgumentException("membershipVersion must contain exactly the tenant UUID");
    }
    String version =
        requirePositiveDecimal(membershipVersion.get(tenantUuid.toString()), "membershipVersion");

    MessageDigest digest = sha256();
    frame(digest, RECEIPT_DIGEST_V2_DOMAIN);
    frame(digest, "2");
    frame(digest, EVIDENCE_STATUS);
    frame(digest, streamKey);
    frame(digest, Long.toString(receipt.receiptSequence()));
    frame(digest, receiptId.toString());
    frame(digest, transition);
    frame(digest, request);
    frame(digest, accountUuid.toString());
    frame(digest, tenantUuid.toString());
    frame(digest, lifecycle);
    frame(digest, receipt.gameplayAdmissionAllowed() ? "true" : "false");
    frame(digest, tenantUuid.toString());
    frame(digest, version);
    frame(digest, Long.toString(receipt.membershipAuthorityGeneration()));
    frame(digest, authority);
    frame(digest, tenantKind);
    frame(digest, sourceOperation.toString());
    frame(digest, sourceDigest);
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }

  public static String requireRequestIdV2(String requestId) {
    if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
      throw new IllegalArgumentException("requestId must contain 1 to 128 characters");
    }
    strictUtf8(requestId, "requestId");
    return requestId;
  }

  public static UUID requireCanonicalUuid(UUID value, String name) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
    return value;
  }

  public static String requirePositiveDecimal(String value, String name) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(name + " must be a canonical positive decimal");
    }
    BigInteger parsed = new BigInteger(value);
    if (parsed.signum() <= 0 || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException(name + " must be a canonical positive decimal");
    }
    return value;
  }

  public static String requireSha256Digest(String value, String name) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
    }
    return value;
  }

  private static String requiredV2Field(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    strictUtf8(value, name);
    return value;
  }

  private static void frame(MessageDigest digest, String value) {
    byte[] bytes = strictUtf8(value, "receipt preimage segment");
    digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    digest.update((byte) ':');
    digest.update(bytes);
  }

  private static byte[] strictUtf8(String value, String name) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      return result;
    } catch (CharacterCodingException ex) {
      throw new IllegalArgumentException(name + " is not strict UTF-8", ex);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
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
