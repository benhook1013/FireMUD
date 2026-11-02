package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountSubscriptionCreateDigestTest {
  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OWNER = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID INSTRUMENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OTHER = UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Test
  void canonicalVersionOneGoldenVectorBindsTheResolvedCreateTuple() {
    var encoded =
        AccountSubscriptionCreateDigest.encode(TENANT, OWNER, "basic-hosting", INSTRUMENT);

    assertThat(new String(encoded.payload(), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"instrumentSelection\":{\"kind\":\"EXACT_SAVED_INSTRUMENT\","
                + "\"paymentInstrumentId\":\"33333333-3333-4333-8333-333333333333\"},"
                + "\"ownerAccountId\":\"22222222-2222-4222-8222-222222222222\","
                + "\"planCode\":\"basic-hosting\","
                + "\"schemaVersion\":\"account-subscription-create-request/v1\","
                + "\"tenantId\":\"11111111-1111-4111-8111-111111111111\"}");
    assertThat(encoded.digest())
        .isEqualTo("sha256:1b9e07bcbec705fc21fad3395ef9239ec5e5fb5d08899db9f9dbc07190053b70");
    assertThat(
            AccountSubscriptionCreateDigest.encode(TENANT, OWNER, "basic-hosting", INSTRUMENT)
                .digest())
        .isEqualTo(encoded.digest());
    assertThat(
            AccountSubscriptionCreateDigest.encode(OTHER, OWNER, "basic-hosting", INSTRUMENT)
                .digest())
        .isNotEqualTo(encoded.digest());
    assertThat(
            AccountSubscriptionCreateDigest.encode(TENANT, OTHER, "basic-hosting", INSTRUMENT)
                .digest())
        .isNotEqualTo(encoded.digest());
    assertThat(
            AccountSubscriptionCreateDigest.encode(TENANT, OWNER, "pro-hosting", INSTRUMENT)
                .digest())
        .isNotEqualTo(encoded.digest());
    assertThat(
            AccountSubscriptionCreateDigest.encode(TENANT, OWNER, "basic-hosting", OTHER).digest())
        .isNotEqualTo(encoded.digest());
  }

  @Test
  void malformedOrUnnormalizedPlanCannotCollideThroughLossyEncoding() {
    for (String plan :
        new String[] {null, "", " ", " basic-hosting", "basic-hosting ", "bad\uD800"}) {
      assertThatThrownBy(
              () -> AccountSubscriptionCreateDigest.encode(TENANT, OWNER, plan, INSTRUMENT))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void missingCanonicalIdentityCannotBecomeRequestEvidence() {
    for (UUID identity : new UUID[] {null, new UUID(0, 0)}) {
      assertThatThrownBy(
              () ->
                  AccountSubscriptionCreateDigest.encode(
                      identity, OWNER, "basic-hosting", INSTRUMENT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  AccountSubscriptionCreateDigest.encode(
                      TENANT, identity, "basic-hosting", INSTRUMENT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  AccountSubscriptionCreateDigest.encode(TENANT, OWNER, "basic-hosting", identity))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void returnedBytesCannotChangeRetainedRequestEvidence() {
    var encoded =
        AccountSubscriptionCreateDigest.encode(TENANT, OWNER, "basic-hosting", INSTRUMENT);
    byte[] original = encoded.payload();
    byte[] supplied = encoded.payload();
    var retained = new AccountSubscriptionCreateDigest.EncodedRequest(supplied, encoded.digest());
    supplied[0] = '!';
    encoded.payload()[0] = '!';
    retained.payload()[0] = '!';

    assertThat(encoded.payload()).isEqualTo(original);
    assertThat(retained.payload()).isEqualTo(original);
  }
}
