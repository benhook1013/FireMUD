package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountFreshEnrollmentAuthorityEventV1CodecTest {
  @Test
  void freshEnrollmentIsAClosedNonrestrictedSourceForEachCategory() {
    for (String category : new String[] {"account_security_lock", "platform_access_ban"}) {
      var event =
          AccountSecurityStateAuthorityEventV1Codec.sealRestriction(
              preimage(category, "NONRESTRICTED"));
      var verified = AccountSecurityStateAuthorityEventV1Codec.verifyFamily(event.canonicalJson());
      assertThat(verified.restriction().orElseThrow().sourceKind())
          .isEqualTo("ACCOUNT_FRESH_ENROLLMENT");
      assertThat(verified.canonicalJson()).isEqualTo(event.canonicalJson());
      assertThatThrownBy(
              () ->
                  AccountSecurityStateAuthorityEventV1Codec.sealRestriction(
                      preimage(category, "RESTRICTED")))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                AccountSecurityStateAuthorityEventV1Codec.sealRestriction(
                    preimage("chat_ban", "NONRESTRICTED")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, Object> preimage(String category, String state) {
    String account = "11111111-1111-4111-8111-111111111111";
    String request = "22222222-2222-4222-8222-222222222222";
    String stream =
        AccountSecurityStateAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + account;
    return Map.ofEntries(
        Map.entry("schemaVersion", AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
        Map.entry("eventType", AccountSecurityStateAuthorityEventV1Codec.RESTRICTION_EVENT_TYPE),
        Map.entry(
            "eventId",
            AccountSecurityStateAuthorityEventV1Codec.RESTRICTION_EVENT_ID_PREFIX + request),
        Map.entry("requestId", request),
        Map.entry("accountId", account),
        Map.entry("sourceScope", "account/" + account),
        Map.entry("outboxStreamKey", stream),
        Map.entry("outboxSequence", "1"),
        Map.entry("accountAuthorityGeneration", "2"),
        Map.entry("sourceVersion", "2"),
        Map.entry(
            "accountSecurityCutoff",
            Map.of(
                "accountAuthorityGeneration",
                "2",
                "outboxStreamKey",
                stream,
                "outboxSequence",
                "1")),
        Map.entry("restrictionCategory", category),
        Map.entry("restrictionRevision", "2"),
        Map.entry("restrictionEnforcementEpoch", "2"),
        Map.entry("restrictionState", state),
        Map.entry("restrictionResultId", "33333333-3333-4333-8333-333333333333"),
        Map.entry("restrictionRequestDigest", "sha256:" + "a".repeat(64)),
        Map.entry("restrictionSourceKind", "ACCOUNT_FRESH_ENROLLMENT"),
        Map.entry("restrictionSourceRequestId", "44444444-4444-4444-8444-444444444444"),
        Map.entry("restrictionSourceDigest", "sha256:" + "b".repeat(64)));
  }
}
