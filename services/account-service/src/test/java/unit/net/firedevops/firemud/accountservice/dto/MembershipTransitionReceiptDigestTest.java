package net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;

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
}
