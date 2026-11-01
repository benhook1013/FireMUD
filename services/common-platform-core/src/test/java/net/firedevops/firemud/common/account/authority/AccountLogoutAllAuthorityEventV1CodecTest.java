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

class AccountLogoutAllAuthorityEventV1CodecTest {
  private static final String VECTOR_RESOURCE =
      "/net/firedevops/firemud/common/account/authority/"
          + "account-auth-logout-all-event-v1-vectors.json";
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String STREAM =
      AccountLogoutAllAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + ACCOUNT_ID;
  private static final String LARGE_COUNTER = "9223372036854775807";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static JsonNode vectors;

  @BeforeAll
  static void loadVectors() throws IOException {
    try (InputStream stream =
        AccountLogoutAllAuthorityEventV1CodecTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      if (stream == null) {
        throw new IOException("Missing shared event vectors: " + VECTOR_RESOURCE);
      }
      vectors = JSON.readTree(stream);
    }
  }

  @Test
  void fixedVectorsVerifySealAndArbitraryPrecisionCounters() throws Exception {
    assertThat(vectors.path("schemaVersion").asText())
        .isEqualTo("account-auth-logout-all-event-vectors/v1");

    JsonNode validEvents = vectors.path("validEvents");
    assertThat(validEvents.isArray()).as("validEvents is an array").isTrue();
    assertThat(validEvents.size()).as("validEvents is nonempty").isPositive();
    for (JsonNode vector : validEvents) {
      JsonNode wireEvent = vector.path("event");
      var verified = AccountLogoutAllAuthorityEventV1Codec.verify(wireEvent.toString());
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
      var sealed = AccountLogoutAllAuthorityEventV1Codec.seal(preimageMap);
      assertThat(sealed.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
      assertThat(sealed.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
    }

    var large =
        AccountLogoutAllAuthorityEventV1Codec.verify(
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

    JsonNode mutations = vectors.path("changedDeclaredFieldMutations");
    assertThat(mutations.isArray()).as("changedDeclaredFieldMutations is an array").isTrue();
    assertThat(mutations.size()).as("changedDeclaredFieldMutations is nonempty").isPositive();
    for (JsonNode mutation : mutations) {
      ObjectNode changedWire = (ObjectNode) baseline.deepCopy();
      JsonNode changes = mutation.path("changes");
      changes
          .fieldNames()
          .forEachRemaining(field -> changedWire.set(field, changes.get(field).deepCopy()));
      ObjectNode changedPreimage = (ObjectNode) changedWire.deepCopy();
      changedPreimage.remove("eventDigest");
      Map<String, Object> preimageMap =
          JSON.convertValue(changedPreimage, new TypeReference<>() {});

      var sealed = AccountLogoutAllAuthorityEventV1Codec.seal(preimageMap);
      assertThat(sealed.eventDigest())
          .as(mutation.path("name").asText())
          .isNotEqualTo(originalDigest);
      assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.verify(changedWire.toString()))
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
    JsonNode propertyOrder = vectors.path("canonicalReorderedPropertyOrder");
    assertThat(propertyOrder.isArray()).as("canonicalReorderedPropertyOrder is an array").isTrue();
    assertThat(propertyOrder.size()).as("canonicalReorderedPropertyOrder is nonempty").isPositive();
    for (JsonNode field : propertyOrder) {
      String fieldName = field.asText();
      reordered.set(fieldName, event.path(fieldName).deepCopy());
    }

    var verified = AccountLogoutAllAuthorityEventV1Codec.verify(reordered.toString());
    assertThat(verified.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
    assertThat(verified.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
  }

  @Test
  void invalidMutationVectorsAreRejected() {
    ObjectNode baseline = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    JsonNode mutations = vectors.path("invalidMutations");
    assertThat(mutations.isArray()).as("invalidMutations is an array").isTrue();
    assertThat(mutations.size()).as("invalidMutations is nonempty").isPositive();
    for (JsonNode mutation : mutations) {
      ObjectNode changed = (ObjectNode) baseline.deepCopy();
      applyMutation(changed, mutation);
      assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.verify(changed.toString()))
          .as(mutation.path("name").asText())
          .isInstanceOf(IllegalArgumentException.class);

      if (!"/eventDigest".equals(mutation.path("path").asText())) {
        ObjectNode changedPreimage = (ObjectNode) changed.deepCopy();
        changedPreimage.remove("eventDigest");
        Map<String, Object> preimageMap =
            JSON.convertValue(changedPreimage, new TypeReference<>() {});
        assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.seal(preimageMap))
            .as(mutation.path("name").asText() + " invalid preimage")
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void rejectsUppercaseRequestIdWhenEventIdentityBindingMatches() {
    String uppercaseRequestId = "22222222-2222-4222-8222-22222222222A";
    ObjectNode changed = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    changed.put("requestId", uppercaseRequestId);
    changed.put(
        "eventId", AccountLogoutAllAuthorityEventV1Codec.EVENT_ID_PREFIX + uppercaseRequestId);
    changed.remove("eventDigest");
    Map<String, Object> preimageMap = JSON.convertValue(changed, new TypeReference<>() {});

    assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.seal(preimageMap))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestId")
        .hasMessageContaining("canonical lowercase non-nil UUID");
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
