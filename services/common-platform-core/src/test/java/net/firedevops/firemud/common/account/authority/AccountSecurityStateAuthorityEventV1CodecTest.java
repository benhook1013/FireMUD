package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;

class AccountSecurityStateAuthorityEventV1CodecTest {
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String STREAM =
      AccountSecurityStateAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + ACCOUNT_ID;
  private static final String LARGE_COUNTER = "9223372036854775807";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void sealsCompleteSecurityStateAndAllowsEmptyGlobalRoles() throws Exception {
    var sealed = AccountSecurityStateAuthorityEventV1Codec.seal(preimage(LARGE_COUNTER));
    var verified = AccountSecurityStateAuthorityEventV1Codec.verify(sealed.canonicalJson());

    assertThat(verified.schemaVersion())
        .isEqualTo(AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION);
    assertThat(verified.eventType())
        .isEqualTo(AccountSecurityStateAuthorityEventV1Codec.EVENT_TYPE);
    assertThat(verified.eventId())
        .isEqualTo(AccountSecurityStateAuthorityEventV1Codec.EVENT_ID_PREFIX + REQUEST_ID);
    assertThat(verified.requestId()).isEqualTo(REQUEST_ID);
    assertThat(verified.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(verified.sourceScope()).isEqualTo("account/" + ACCOUNT_ID);
    assertThat(verified.outboxStreamKey()).isEqualTo(STREAM);
    assertThat(verified.outboxSequence()).isEqualTo(LARGE_COUNTER);
    assertThat(verified.accountAuthorityGeneration()).isEqualTo(LARGE_COUNTER);
    assertThat(verified.sourceVersion()).isEqualTo(LARGE_COUNTER);
    assertThat(verified.accountSecurityCutoff())
        .isEqualTo(
            new AccountSecurityStateAuthorityEventV1Codec.AccountSecurityCutoff(
                LARGE_COUNTER, STREAM, LARGE_COUNTER));
    assertThat(verified.mutationKinds())
        .containsExactly("EMAIL_LOGIN_ELIGIBILITY_CHANGED", "GLOBAL_ROLE_CHANGED");
    assertThat(verified.accountState().emailVerified()).isTrue();
    assertThat(verified.accountState().loginAuthModes()).containsExactly("EMAIL_OTP", "PASSWORD");
    assertThat(verified.accountState().globalRoles()).isEmpty();
    assertThat(verified.accountState().lifecycleState()).isEqualTo("ACTIVE");
    assertThat(verified.eventDigest()).isEqualTo(preimageDigest(sealed.canonicalJson()));
  }

  @Test
  void requiresSortedUniqueArraysAndExactScopeAndCutoffBindings() {
    var unsortedKinds = new java.util.HashMap<>(preimage("1"));
    unsortedKinds.put(
        "mutationKinds", List.of("GLOBAL_ROLE_CHANGED", "EMAIL_LOGIN_ELIGIBILITY_CHANGED"));
    var unsortedRoles = new java.util.HashMap<>(preimage("1"));
    unsortedRoles.put(
        "accountState",
        Map.of(
            "emailVerified",
            true,
            "loginAuthModes",
            List.of("EMAIL_OTP", "PASSWORD"),
            "globalRoles",
            List.of("support", "platformAdmin"),
            "lifecycleState",
            "ACTIVE"));
    var wrongEventId = new java.util.HashMap<>(preimage("1"));
    wrongEventId.put(
        "eventId", "account-security-state-event-v1:33333333-3333-4333-8333-333333333333");
    var wrongCutoffStream = new java.util.HashMap<>(preimage("1"));
    wrongCutoffStream.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration", "1",
            "outboxStreamKey",
                "account:auth-authority:v1:account/33333333-3333-4333-8333-333333333333",
            "outboxSequence", "1"));

    assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.seal(unsortedKinds))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sorted and unique");
    assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.seal(unsortedRoles))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sorted and unique");
    assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.seal(wrongEventId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.seal(wrongCutoffStream))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateUnknownTrailingAndDigestTampering() throws Exception {
    String valid = AccountSecurityStateAuthorityEventV1Codec.seal(preimage("1")).canonicalJson();
    String duplicate = valid.replaceFirst("\\{", "{\"eventType\":\"duplicate\",");
    String unknown = valid.replaceFirst("}$", ",\"future\":true}");
    assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.verify(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.verify(unknown))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.verify(valid + " null"))
        .isInstanceOf(IllegalArgumentException.class);

    ObjectNode changed = (ObjectNode) JSON.readTree(valid);
    changed.put("sourceVersion", "2");
    assertThatThrownBy(() -> AccountSecurityStateAuthorityEventV1Codec.verify(changed.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventDigest");
  }

  private static Map<String, Object> preimage(String counter) {
    return Map.ofEntries(
        Map.entry("schemaVersion", AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
        Map.entry("eventType", AccountSecurityStateAuthorityEventV1Codec.EVENT_TYPE),
        Map.entry(
            "eventId", AccountSecurityStateAuthorityEventV1Codec.EVENT_ID_PREFIX + REQUEST_ID),
        Map.entry("requestId", REQUEST_ID),
        Map.entry("accountId", ACCOUNT_ID),
        Map.entry("sourceScope", "account/" + ACCOUNT_ID),
        Map.entry("outboxStreamKey", STREAM),
        Map.entry("outboxSequence", counter),
        Map.entry("accountAuthorityGeneration", counter),
        Map.entry("sourceVersion", counter),
        Map.entry(
            "accountSecurityCutoff",
            Map.of(
                "accountAuthorityGeneration", counter,
                "outboxStreamKey", STREAM,
                "outboxSequence", counter)),
        Map.entry(
            "mutationKinds", List.of("EMAIL_LOGIN_ELIGIBILITY_CHANGED", "GLOBAL_ROLE_CHANGED")),
        Map.entry(
            "accountState",
            Map.of(
                "emailVerified",
                true,
                "loginAuthModes",
                List.of("EMAIL_OTP", "PASSWORD"),
                "globalRoles",
                List.of(),
                "lifecycleState",
                "ACTIVE")));
  }

  private static String preimageDigest(String wireJson) throws Exception {
    JsonNode parsed = JSON.readTree(wireJson);
    ObjectNode preimage = ((ObjectNode) parsed).deepCopy();
    preimage.remove("eventDigest");
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(preimage));
    return "sha256:"
        + java.util.HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
  }
}
