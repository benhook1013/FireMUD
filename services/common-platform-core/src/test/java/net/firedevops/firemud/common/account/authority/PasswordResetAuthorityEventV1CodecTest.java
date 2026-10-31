package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PasswordResetAuthorityEventV1CodecTest {
  private static final String VECTOR_RESOURCE =
      "/net/firedevops/firemud/common/account/authority/"
          + "account-auth-password-reset-event-v1-vectors.json";
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String STREAM =
      PasswordResetAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + ACCOUNT_ID;
  private static final String LARGE_COUNTER = "9223372036854775807";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static JsonNode vectors;

  @BeforeAll
  static void loadVectors() throws IOException {
    try (InputStream stream =
        PasswordResetAuthorityEventV1CodecTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      if (stream == null) {
        throw new IOException("Missing shared event vectors: " + VECTOR_RESOURCE);
      }
      vectors = JSON.readTree(stream);
    }
  }

  @Test
  void fixedVectorsVerifySealAndArbitraryPrecisionCounters() throws Exception {
    assertThat(vectors.path("schemaVersion").asText())
        .isEqualTo("account-auth-password-reset-event-vectors/v1");

    for (JsonNode vector : vectors.path("validEvents")) {
      JsonNode wireEvent = vector.path("event");
      var verified = PasswordResetAuthorityEventV1Codec.verify(wireEvent.toString());
      assertThat(verified.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
      assertThat(verified.eventDigest()).isEqualTo(wireEvent.path("eventDigest").asText());
      assertThat(verified.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
      assertThat(verified.canonicalJsonUtf8())
          .isEqualTo(
              vector.path("expectedCanonicalJson").asText().getBytes(StandardCharsets.UTF_8));
      assertThat(verified.canonicalJsonUtf8())
          .isEqualTo(Rfc8785CanonicalJson.canonicalizeUtf8(wireEvent.toString()));

      ObjectNode preimage = (ObjectNode) wireEvent.deepCopy();
      preimage.remove("eventDigest");
      Map<String, Object> preimageMap = JSON.convertValue(preimage, new TypeReference<>() {});
      var sealed = PasswordResetAuthorityEventV1Codec.seal(preimageMap);
      assertThat(sealed.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
      assertThat(sealed.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
    }

    var large =
        PasswordResetAuthorityEventV1Codec.verify(
            vectors.path("validEvents").get(1).path("event").toString());
    assertThat(large.outboxSequence()).isEqualTo("18446744073709551616000000000000000001");
    assertThat(large.accountAuthorityGeneration())
        .isEqualTo("92233720368547758081234567890123456789");
    assertThat(large.sourceVersion()).isEqualTo("90071992547409931234567890123456789");
  }

  @Test
  void changedDeclaredFieldsCannotReuseTheOriginalDigest() {
    ObjectNode baseline = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    String originalDigest = baseline.path("eventDigest").asText();

    for (JsonNode mutation : vectors.path("changedDeclaredFieldMutations")) {
      ObjectNode changedWire = (ObjectNode) baseline.deepCopy();
      JsonNode changes = mutation.path("changes");
      changes
          .fieldNames()
          .forEachRemaining(field -> changedWire.set(field, changes.get(field).deepCopy()));
      ObjectNode changedPreimage = (ObjectNode) changedWire.deepCopy();
      changedPreimage.remove("eventDigest");
      Map<String, Object> preimageMap =
          JSON.convertValue(changedPreimage, new TypeReference<>() {});

      var sealed = PasswordResetAuthorityEventV1Codec.seal(preimageMap);
      assertThat(sealed.eventDigest())
          .as(mutation.path("name").asText())
          .isNotEqualTo(originalDigest);
      assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.verify(changedWire.toString()))
          .as(mutation.path("name").asText())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("eventDigest");
    }
  }

  @Test
  void canonicalPropertyReorderingPreservesTheFixedEvent() {
    JsonNode vector = vectors.path("validEvents").get(0);
    JsonNode event = vector.path("event");
    ObjectNode reordered = JSON.createObjectNode();
    for (JsonNode field : vectors.path("canonicalReorderedPropertyOrder")) {
      String fieldName = field.asText();
      reordered.set(fieldName, event.path(fieldName).deepCopy());
    }

    var verified = PasswordResetAuthorityEventV1Codec.verify(reordered.toString());
    assertThat(verified.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
    assertThat(verified.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
  }

  @Test
  void invalidMutationVectorsAreRejected() {
    ObjectNode baseline = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    for (JsonNode mutation : vectors.path("invalidMutations")) {
      ObjectNode changed = (ObjectNode) baseline.deepCopy();
      applyMutation(changed, mutation);
      assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.verify(changed.toString()))
          .as(mutation.path("name").asText())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static void applyMutation(ObjectNode root, JsonNode mutation) {
    String[] path = mutation.path("path").asText().substring(1).split("/");
    JsonNode parent = root;
    for (int index = 0; index < path.length - 1; index++) {
      parent = parent.path(path[index]);
    }
    if (!(parent instanceof ObjectNode object)) {
      throw new IllegalArgumentException("Mutation parent must be an object: " + mutation);
    }
    String field = path[path.length - 1];
    switch (mutation.path("op").asText()) {
      case "add", "replace" -> object.set(field, mutation.path("value").deepCopy());
      case "remove" -> object.remove(field);
      default -> throw new IllegalArgumentException("Unsupported mutation operation: " + mutation);
    }
  }

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
