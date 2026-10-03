package net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MembershipTransitionReceiptDigestTest {
  @Test
  void joinsUseTheNamespacedProvisionalReceiptIdentityAndDigestVector() {
    UUID receiptId = MembershipTransitionReceiptDigest.receiptIdForRequest("join-request-1");

    assertThat(MembershipTransitionReceiptDigest.receiptStreamKey(42L, 7L))
        .isEqualTo("account:membership-transition-receipt:v1:membership/42/7");
    assertThat(receiptId).isEqualTo(UUID.fromString("171e5793-c53b-3308-934c-bdd7b7c0e13a"));
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigest(
                "account:membership-transition-receipt:v1:membership/42/7",
                1L,
                receiptId,
                "MEMBERSHIP_JOINED",
                "join-request-1",
                42L,
                7L,
                18L,
                "ACTIVE",
                true,
                3L,
                4L,
                "EXPLICIT_JOIN"))
        .isEqualTo("sha256:2a07588ddf2444d1b4304cf5bd746365640fc30a2474dc3d0dbe0787d7cc494c");
  }

  @Test
  void reactivationKeepsItsActiveStateDigestVector() {
    UUID receiptId =
        MembershipTransitionReceiptDigest.receiptIdForRequest("reactivation-request-1");

    assertThat(receiptId).isEqualTo(UUID.fromString("328628ab-035d-356f-9827-4e450366c2f7"));
    assertThat(
            MembershipTransitionReceiptDigest.transitionDigest(
                MembershipTransitionReceiptDigest.receiptStreamKey(42L, 7L),
                2L,
                receiptId,
                "MEMBERSHIP_REACTIVATED",
                "reactivation-request-1",
                42L,
                7L,
                18L,
                "ACTIVE",
                true,
                3L,
                2L,
                "EXPLICIT_JOIN"))
        .isEqualTo("sha256:f7faf6c9bcd7937386ed3e8443f65fc65ddd4226005145cbd9607acecf231236");
  }

  @Test
  void leaveUsesTheSameNamespacedProvisionalReceiptPreimage() {
    UUID receiptId = MembershipTransitionReceiptDigest.receiptIdForRequest("leave-request-1");

    assertThat(receiptId).isEqualTo(UUID.fromString("2ce0e16e-2d24-387d-9059-72e57faa3e56"));
    assertThat(transitionDigest(receiptId, "MEMBERSHIP_LEFT", "INACTIVE", false, "leave-request-1"))
        .isEqualTo("sha256:cd739f1193f72eb08f543cb74c64dcd39d42247d15e42c6bb58b2672e958d029");
  }

  @Test
  void digestRejectsMismatchedTransitionStateAndUnknownType() {
    UUID receiptId = MembershipTransitionReceiptDigest.receiptIdForRequest("transition-request");

    assertThatThrownBy(
            () ->
                transitionDigest(
                    receiptId, "MEMBERSHIP_LEFT", "ACTIVE", false, "transition-request"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    assertThatThrownBy(
            () ->
                transitionDigest(
                    receiptId, "MEMBERSHIP_LEFT", "INACTIVE", true, "transition-request"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    assertThatThrownBy(
            () ->
                transitionDigest(
                    receiptId, "MEMBERSHIP_JOINED", "INACTIVE", false, "transition-request"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    assertThatThrownBy(
            () ->
                transitionDigest(
                    receiptId, "MEMBERSHIP_REVOKED", "INACTIVE", false, "transition-request"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a supported membership receipt");
  }

  private static String transitionDigest(
      UUID receiptId,
      String transitionType,
      String lifecycleState,
      boolean gameplayAdmissionAllowed,
      String requestId) {
    return MembershipTransitionReceiptDigest.transitionDigest(
        MembershipTransitionReceiptDigest.receiptStreamKey(42L, 7L),
        3L,
        receiptId,
        transitionType,
        requestId,
        42L,
        7L,
        18L,
        lifecycleState,
        gameplayAdmissionAllowed,
        4L,
        5L,
        "EXPLICIT_JOIN");
  }
}
