package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.Map;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;

class AccountLogoutAllAuthorityEventV1CodecTest {
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String STREAM =
      AccountLogoutAllAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + ACCOUNT_ID;
  private static final String LARGE_COUNTER = "9223372036854775807";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void sealsAndVerifiesRequestDerivedLogoutIdentityAndLargeCounters() throws Exception {
    var sealed = AccountLogoutAllAuthorityEventV1Codec.seal(preimage(LARGE_COUNTER));
    var verified = AccountLogoutAllAuthorityEventV1Codec.verify(sealed.canonicalJson());

    assertThat(verified.schemaVersion())
        .isEqualTo(AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION);
    assertThat(verified.eventType()).isEqualTo(AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE);
    assertThat(verified.eventId())
        .isEqualTo(AccountLogoutAllAuthorityEventV1Codec.EVENT_ID_PREFIX + REQUEST_ID);
    assertThat(verified.requestId()).isEqualTo(REQUEST_ID);
    assertThat(verified.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(verified.sourceScope()).isEqualTo("account/" + ACCOUNT_ID);
    assertThat(verified.outboxStreamKey()).isEqualTo(STREAM);
    assertThat(verified.outboxSequence()).isEqualTo(LARGE_COUNTER);
    assertThat(verified.accountAuthorityGeneration()).isEqualTo(LARGE_COUNTER);
    assertThat(verified.sourceVersion()).isEqualTo(LARGE_COUNTER);
    assertThat(verified.accountSecurityCutoff())
        .isEqualTo(
            new AccountLogoutAllAuthorityEventV1Codec.AccountSecurityCutoff(
                LARGE_COUNTER, STREAM, LARGE_COUNTER));
    assertThat(verified.eventDigest()).isEqualTo(preimageDigest(sealed.canonicalJson()));
    assertThat(verified.canonicalJsonUtf8()).isEqualTo(sealed.canonicalJsonUtf8());
  }

  @Test
  void rejectsWrongRequestEventIdentityAccountScopeAndCutoff() {
    var wrongEventId = new java.util.HashMap<>(preimage("1"));
    wrongEventId.put("eventId", "account-logout-all-event-v1:33333333-3333-4333-8333-333333333333");
    var wrongAccount = new java.util.HashMap<>(preimage("1"));
    wrongAccount.put("accountId", "33333333-3333-4333-8333-333333333333");
    var wrongCutoff = new java.util.HashMap<>(preimage("1"));
    wrongCutoff.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration", "2",
            "outboxStreamKey", STREAM,
            "outboxSequence", "1"));

    assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.seal(wrongEventId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventId");
    assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.seal(wrongAccount))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.seal(wrongCutoff))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("accountSecurityCutoff.accountAuthorityGeneration");
  }

  @Test
  void rejectsUnknownDuplicateTrailingAndNoncanonicalCounterWireData() throws Exception {
    String valid = AccountLogoutAllAuthorityEventV1Codec.seal(preimage("1")).canonicalJson();
    String duplicate = valid.replaceFirst("\\{", "{\"eventType\":\"duplicate\",");
    String unknown = valid.replaceFirst("}$", ",\"future\":true}");
    assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.verify(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.verify(unknown))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.verify(valid + " false"))
        .isInstanceOf(IllegalArgumentException.class);

    ObjectNode badSequence = (ObjectNode) JSON.readTree(valid);
    badSequence.put("outboxSequence", "01");
    assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.verify(badSequence.toString()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, Object> preimage(String counter) {
    return Map.ofEntries(
        Map.entry("schemaVersion", AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION),
        Map.entry("eventType", AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE),
        Map.entry("eventId", AccountLogoutAllAuthorityEventV1Codec.EVENT_ID_PREFIX + REQUEST_ID),
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
                "outboxSequence", counter)));
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
