package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.CanonicalMembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import org.junit.jupiter.api.Test;

class CanonicalMembershipTransitionReceiptDigestTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String PROVENANCE_DIGEST = "sha256:" + "b".repeat(64);
  private static final String REQUEST_ID = "join-雪-🧪";

  @Test
  void canonicalReceiptMatchesUtf8FramedGoldenVectorWithLargeCounters() {
    CanonicalMembershipTransitionReceipt receipt = receipt();

    assertThat(REQUEST_ID.getBytes(StandardCharsets.UTF_8)).hasSize(13);
    assertThat(receipt.receiptStreamKey())
        .isEqualTo(
            "account:membership-transition-receipt:v2:membership/" + ACCOUNT_ID + "/" + TENANT_ID);
    assertThat(receipt.receiptId())
        .isEqualTo(UUID.fromString("bc2dad06-2211-383f-ae1f-22be6605a683"));
    assertThat(MembershipTransitionReceiptDigest.transitionDigestV2(receipt))
        .isEqualTo("sha256:676bfbe8f62834bef3ae12ced5395a5a7784b038ff01681a9287a5e1358f2a59");
  }

  @Test
  void v2ReceiptIdentityAndDigestAreDistinctFromRetainedV1Evidence() {
    CanonicalMembershipTransitionReceipt receipt = receipt();

    assertThat(receipt.receiptId())
        .isEqualTo(MembershipTransitionReceiptDigest.receiptIdForRequestV2(REQUEST_ID));
    assertThat(receipt.receiptId())
        .isNotEqualTo(MembershipTransitionReceiptDigest.receiptIdForRequest(REQUEST_ID));
    assertThat(receipt.receiptDigest())
        .isNotEqualTo(MembershipTransitionReceiptDigest.transitionDigestV2(receipt));
    assertThat(MembershipTransitionReceiptDigest.receiptStreamKeyV2(ACCOUNT_ID, TENANT_ID))
        .contains(ACCOUNT_ID.toString(), TENANT_ID.toString())
        .doesNotContain("/1/", "/2/");
  }

  @Test
  void v2DigestChangesWithDeclaredStateSourceAndCounterEvidence() {
    CanonicalMembershipTransitionReceipt original = receipt();
    String digest = MembershipTransitionReceiptDigest.transitionDigestV2(original);

    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(original, "receiptSequence", 4L)))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(original, "requestId", "other-雪")))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(original, "transitionType", "MEMBERSHIP_REACTIVATED")))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(original, "transitionType", "MEMBERSHIP_LEFT")))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(
                    original,
                    "accountId",
                    UUID.fromString("44444444-4444-4444-8444-444444444444"))))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(
                    original, "tenantId", UUID.fromString("55555555-5555-4555-8555-555555555555"))))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(original, "membershipVersion", "9007199254740994")))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(original, "membershipAuthorityGeneration", 7L)))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(original, "tenantProvenanceKind", "FRESH_GAME_DESIGN")))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(
                    original,
                    "tenantSourceOperationId",
                    UUID.fromString("66666666-6666-4666-8666-666666666666"))))
        .isNotEqualTo(digest);
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(original, "tenantProvenanceDigest", "sha256:" + "c".repeat(64))))
        .isNotEqualTo(digest);

    assertThat(
            MembershipTransitionReceiptDigest.transitionDigestV2(
                copy(original, "receiptDigest", "sha256:" + "c".repeat(64))))
        .isEqualTo(digest);
  }

  @Test
  void acceptsArbitraryPrecisionCanonicalMembershipVersionInDigestPreimage() {
    CanonicalMembershipTransitionReceipt receipt =
        copy(receipt(), "membershipVersion", "9223372036854775808");

    assertThat(receipt.membershipVersion())
        .containsExactly(Map.entry(TENANT_ID.toString(), "9223372036854775808"));
    assertThat(MembershipTransitionReceiptDigest.transitionDigestV2(receipt))
        .matches("sha256:[0-9a-f]{64}");
  }

  @Test
  void rejectsMalformedTextIdentityMapsCountersAndTransitionState() {
    assertThatThrownBy(() -> MembershipTransitionReceiptDigest.receiptIdForRequestV2("bad-\uD800"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> MembershipTransitionReceiptDigest.receiptStreamKeyV2(new UUID(0L, 0L), TENANT_ID))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new CanonicalMembershipTransitionReceipt(
                    receipt().receiptStreamKey(),
                    1L,
                    receipt().receiptId(),
                    receipt().receiptDigest(),
                    MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
                    "MEMBERSHIP_JOINED",
                    REQUEST_ID,
                    ACCOUNT_ID,
                    TENANT_ID,
                    "ACTIVE",
                    true,
                    Map.of(TENANT_ID.toString(), "01"),
                    1L,
                    "EXPLICIT_JOIN",
                    "APPROVED_RETAINED",
                    SOURCE_OPERATION_ID,
                    PROVENANCE_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CanonicalMembershipTransitionReceipt(
                    receipt().receiptStreamKey(),
                    0L,
                    receipt().receiptId(),
                    receipt().receiptDigest(),
                    MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
                    "MEMBERSHIP_JOINED",
                    REQUEST_ID,
                    ACCOUNT_ID,
                    TENANT_ID,
                    "ACTIVE",
                    true,
                    Map.of(TENANT_ID.toString(), "9007199254740993"),
                    Long.MAX_VALUE,
                    "EXPLICIT_JOIN",
                    "APPROVED_RETAINED",
                    SOURCE_OPERATION_ID,
                    PROVENANCE_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CanonicalMembershipTransitionReceipt(
                    receipt().receiptStreamKey(),
                    Long.MAX_VALUE,
                    receipt().receiptId(),
                    receipt().receiptDigest(),
                    MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
                    "MEMBERSHIP_JOINED",
                    REQUEST_ID,
                    ACCOUNT_ID,
                    TENANT_ID,
                    "ACTIVE",
                    true,
                    Map.of(TENANT_ID.toString(), "9007199254740993", ACCOUNT_ID.toString(), "1"),
                    Long.MAX_VALUE,
                    "EXPLICIT_JOIN",
                    "APPROVED_RETAINED",
                    SOURCE_OPERATION_ID,
                    PROVENANCE_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CanonicalMembershipTransitionReceipt(
                    receipt().receiptStreamKey(),
                    Long.MAX_VALUE,
                    receipt().receiptId(),
                    receipt().receiptDigest(),
                    MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
                    "MEMBERSHIP_JOINED",
                    REQUEST_ID,
                    ACCOUNT_ID,
                    TENANT_ID,
                    "INACTIVE",
                    false,
                    Map.of(TENANT_ID.toString(), "9007199254740993"),
                    Long.MAX_VALUE,
                    "EXPLICIT_JOIN",
                    "APPROVED_RETAINED",
                    SOURCE_OPERATION_ID,
                    PROVENANCE_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalMembershipTransitionReceipt receipt() {
    return new CanonicalMembershipTransitionReceipt(
        MembershipTransitionReceiptDigest.receiptStreamKeyV2(ACCOUNT_ID, TENANT_ID),
        Long.MAX_VALUE,
        MembershipTransitionReceiptDigest.receiptIdForRequestV2(REQUEST_ID),
        "sha256:" + "a".repeat(64),
        MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
        "MEMBERSHIP_JOINED",
        REQUEST_ID,
        ACCOUNT_ID,
        TENANT_ID,
        "ACTIVE",
        true,
        Map.of(TENANT_ID.toString(), "9007199254740993"),
        Long.MAX_VALUE,
        "EXPLICIT_JOIN",
        "APPROVED_RETAINED",
        SOURCE_OPERATION_ID,
        PROVENANCE_DIGEST);
  }

  private static CanonicalMembershipTransitionReceipt copy(
      CanonicalMembershipTransitionReceipt receipt, String field, Object replacement) {
    String requestId = "requestId".equals(field) ? (String) replacement : receipt.requestId();
    UUID accountId = "accountId".equals(field) ? (UUID) replacement : receipt.accountId();
    UUID tenantId = "tenantId".equals(field) ? (UUID) replacement : receipt.tenantId();
    String transitionType =
        "transitionType".equals(field) ? (String) replacement : receipt.transitionType();
    String lifecycleState =
        "transitionType".equals(field) && "MEMBERSHIP_LEFT".equals(transitionType)
            ? "INACTIVE"
            : receipt.membershipLifecycleState();
    boolean admissionAllowed =
        "transitionType".equals(field) && "MEMBERSHIP_LEFT".equals(transitionType)
            ? false
            : receipt.gameplayAdmissionAllowed();
    String membershipVersionValue =
        "membershipVersion".equals(field)
            ? (String) replacement
            : receipt.membershipVersion().get(receipt.tenantId().toString());
    Map<String, String> membershipVersion =
        "membershipVersion".equals(field) || "tenantId".equals(field)
            ? Map.of(tenantId.toString(), membershipVersionValue)
            : receipt.membershipVersion();
    return new CanonicalMembershipTransitionReceipt(
        "accountId".equals(field) || "tenantId".equals(field)
            ? MembershipTransitionReceiptDigest.receiptStreamKeyV2(accountId, tenantId)
            : receipt.receiptStreamKey(),
        "receiptSequence".equals(field) ? (long) replacement : receipt.receiptSequence(),
        "requestId".equals(field)
            ? MembershipTransitionReceiptDigest.receiptIdForRequestV2(requestId)
            : receipt.receiptId(),
        "receiptDigest".equals(field) ? (String) replacement : receipt.receiptDigest(),
        receipt.evidenceStatus(),
        transitionType,
        requestId,
        accountId,
        tenantId,
        lifecycleState,
        admissionAllowed,
        membershipVersion,
        "membershipAuthorityGeneration".equals(field)
            ? (long) replacement
            : receipt.membershipAuthorityGeneration(),
        receipt.authorityProvenance(),
        "tenantProvenanceKind".equals(field)
            ? (String) replacement
            : receipt.tenantProvenanceKind(),
        "tenantSourceOperationId".equals(field)
            ? (UUID) replacement
            : receipt.tenantSourceOperationId(),
        "tenantProvenanceDigest".equals(field)
            ? (String) replacement
            : receipt.tenantProvenanceDigest());
  }
}
