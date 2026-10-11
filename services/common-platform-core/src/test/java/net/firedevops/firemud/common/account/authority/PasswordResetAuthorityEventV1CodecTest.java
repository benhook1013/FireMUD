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

class PasswordResetAuthorityEventV1CodecTest {
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String STREAM =
      PasswordResetAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + ACCOUNT_ID;
  private static final String LARGE_COUNTER = "9223372036854775807";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void sealsAndVerifiesBoundPasswordResetEvidenceWithLargeStringCounters() throws Exception {
    var sealed = PasswordResetAuthorityEventV1Codec.seal(preimage(LARGE_COUNTER, ACCOUNT_ID));
    var verified = PasswordResetAuthorityEventV1Codec.verify(sealed.canonicalJson());

    assertThat(verified.schemaVersion())
        .isEqualTo(PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION);
    assertThat(verified.eventType()).isEqualTo(PasswordResetAuthorityEventV1Codec.EVENT_TYPE);
    assertThat(verified.eventId()).isEqualTo("password-reset-event-1");
    assertThat(verified.requestId()).isEqualTo("request-1");
    assertThat(verified.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(verified.sourceScope()).isEqualTo("account/" + ACCOUNT_ID);
    assertThat(verified.outboxStreamKey()).isEqualTo(STREAM);
    assertThat(verified.outboxSequence()).isEqualTo(LARGE_COUNTER);
    assertThat(verified.accountAuthorityGeneration()).isEqualTo(LARGE_COUNTER);
    assertThat(verified.sourceVersion()).isEqualTo(LARGE_COUNTER);
    assertThat(verified.accountSecurityCutoff())
        .isEqualTo(
            new PasswordResetAuthorityEventV1Codec.AccountSecurityCutoff(
                LARGE_COUNTER, STREAM, LARGE_COUNTER));
    assertThat(verified.eventDigest()).isEqualTo(preimageDigest(sealed.canonicalJson()));
    assertThat(verified.canonicalJson()).isEqualTo(sealed.canonicalJson());
    assertThat(verified.canonicalJson()).contains("\"outboxSequence\":\"" + LARGE_COUNTER + "\"");
  }

  @Test
  void rejectsMismatchedScopeAndCutoffBindings() {
    var wrongScope = new java.util.HashMap<>(preimage("1", ACCOUNT_ID));
    wrongScope.put("sourceScope", "account/22222222-2222-4222-8222-222222222222");
    var wrongCutoffSequence = new java.util.HashMap<>(preimage("1", ACCOUNT_ID));
    wrongCutoffSequence.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration", "1",
            "outboxStreamKey", STREAM,
            "outboxSequence", "2"));

    assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.seal(wrongScope))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.seal(wrongCutoffSequence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("accountSecurityCutoff.outboxSequence");
  }

  @Test
  void rejectsNoncanonicalCountersAndMalformedOrTamperedWireJson() throws Exception {
    for (String invalidCounter : new String[] {"0", "01", "-1"}) {
      var invalid = new java.util.HashMap<>(preimage("1", ACCOUNT_ID));
      invalid.put("outboxSequence", invalidCounter);
      assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.seal(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }

    String valid =
        PasswordResetAuthorityEventV1Codec.seal(preimage("1", ACCOUNT_ID)).canonicalJson();
    ObjectNode changed = (ObjectNode) JSON.readTree(valid);
    changed.put("sourceVersion", "2");
    assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.verify(changed.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventDigest");

    String duplicate = valid.replaceFirst("\\{", "{\"eventType\":\"duplicate\",");
    String unknown = valid.replaceFirst("}$", ",\"future\":true}");
    assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.verify(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.verify(unknown))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.verify(valid + " {}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, Object> preimage(String counter, String accountId) {
    String stream = PasswordResetAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + accountId;
    return Map.ofEntries(
        Map.entry("schemaVersion", PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION),
        Map.entry("eventType", PasswordResetAuthorityEventV1Codec.EVENT_TYPE),
        Map.entry("eventId", "password-reset-event-1"),
        Map.entry("requestId", "request-1"),
        Map.entry("accountId", accountId),
        Map.entry("sourceScope", "account/" + accountId),
        Map.entry("outboxStreamKey", stream),
        Map.entry("outboxSequence", counter),
        Map.entry("accountAuthorityGeneration", counter),
        Map.entry("sourceVersion", counter),
        Map.entry(
            "accountSecurityCutoff",
            Map.of(
                "accountAuthorityGeneration", counter,
                "outboxStreamKey", stream,
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
