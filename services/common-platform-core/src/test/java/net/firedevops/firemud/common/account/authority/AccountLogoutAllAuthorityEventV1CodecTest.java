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
import java.util.Map;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec.AccountLogoutAllAuthorityEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AccountLogoutAllAuthorityEventV1CodecTest {
  private static final String VECTOR_RESOURCE =
      "/net/firedevops/firemud/common/account/authority/"
          + "account-auth-logout-all-event-v1-vectors.json";
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
  void fixedVectorsVerifySealAndPreserveArbitraryPrecisionCounters() throws Exception {
    assertThat(vectors.path("schemaVersion").asText())
        .isEqualTo("account-auth-logout-all-event-vectors/v1");

    for (JsonNode vector : vectors.path("validEvents")) {
      JsonNode wireEvent = vector.path("event");
      AccountLogoutAllAuthorityEvent verified =
          AccountLogoutAllAuthorityEventV1Codec.verify(wireEvent.toString());
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
      AccountLogoutAllAuthorityEvent sealed =
          AccountLogoutAllAuthorityEventV1Codec.seal(preimageMap);
      assertThat(sealed.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
      assertThat(sealed.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
    }

    AccountLogoutAllAuthorityEvent ordinary =
        AccountLogoutAllAuthorityEventV1Codec.verify(
            vectors.path("validEvents").get(0).path("event").toString());
    assertThat(ordinary.accountId()).isEqualTo("11111111-1111-4111-8111-111111111111");
    assertThat(ordinary.requestId()).isEqualTo("22222222-2222-4222-8222-222222222222");
    assertThat(ordinary.eventId())
        .isEqualTo("account-logout-all-event-v1:22222222-2222-4222-8222-222222222222");
    assertThat(ordinary.accountSecurityCutoff().accountAuthorityGeneration())
        .isEqualTo(ordinary.accountAuthorityGeneration());
    assertThat(ordinary.accountSecurityCutoff().outboxStreamKey())
        .isEqualTo(ordinary.outboxStreamKey());
    assertThat(ordinary.accountSecurityCutoff().outboxSequence())
        .isEqualTo(ordinary.outboxSequence());

    AccountLogoutAllAuthorityEvent large =
        AccountLogoutAllAuthorityEventV1Codec.verify(
            vectors.path("validEvents").get(1).path("event").toString());
    assertThat(large.outboxSequence()).isEqualTo("18446744073709551616000000000000000001");
    assertThat(large.accountAuthorityGeneration())
        .isEqualTo("92233720368547758081234567890123456789");
    assertThat(large.sourceVersion()).isEqualTo("90071992547409931234567890123456789");
  }

  @Test
  void everyMutableDeclaredSourceFieldChangesTheDigest() {
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

      AccountLogoutAllAuthorityEvent sealed =
          AccountLogoutAllAuthorityEventV1Codec.seal(preimageMap);
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
    for (JsonNode field : vectors.path("canonicalReorderedPropertyOrder")) {
      String fieldName = field.asText();
      reordered.set(fieldName, event.path(fieldName).deepCopy());
    }

    AccountLogoutAllAuthorityEvent verified =
        AccountLogoutAllAuthorityEventV1Codec.verify(reordered.toString());
    assertThat(verified.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
    assertThat(verified.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
  }

  @Test
  void malformedAndNoncanonicalVectorsAreRejected() {
    ObjectNode baseline = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    for (JsonNode mutation : vectors.path("invalidMutations")) {
      ObjectNode changed = (ObjectNode) baseline.deepCopy();
      applyMutation(changed, mutation);
      assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.verify(changed.toString()))
          .as(mutation.path("name").asText())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void duplicatePropertiesAndLegacySchemasAreRejected() {
    assertThatThrownBy(
            () ->
                AccountLogoutAllAuthorityEventV1Codec.verify(
                    "{\"eventId\":\"first\",\"eventId\":\"second\"}"))
        .isInstanceOf(IllegalArgumentException.class);

    ObjectNode passwordReset =
        (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    passwordReset.put("schemaVersion", "account-auth-password-reset-event/v1");
    passwordReset.put("eventType", "PASSWORD_RESET_COMMITTED");
    assertThatThrownBy(() -> AccountLogoutAllAuthorityEventV1Codec.verify(passwordReset.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemaVersion");

    assertThatThrownBy(
            () ->
                AccountLogoutAllAuthorityEventV1Codec.verify(
                    "{\"schemaVersion\":\"account-auth-issuer-generation-event/v1\","
                        + "\"eventType\":\"ISSUER_GENERATION_ADVANCED\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parsedEvidenceAndReturnedBytesAreImmutable() {
    JsonNode vector = vectors.path("validEvents").get(0);
    AccountLogoutAllAuthorityEvent event =
        AccountLogoutAllAuthorityEventV1Codec.verify(vector.path("event").toString());
    byte[] bytes = event.canonicalJsonUtf8();
    bytes[0] = (byte) 'x';

    assertThat(event.canonicalJsonUtf8())
        .isEqualTo(vector.path("expectedCanonicalJson").asText().getBytes(StandardCharsets.UTF_8));
    assertThat(event.accountSecurityCutoff().accountAuthorityGeneration())
        .isEqualTo(event.accountAuthorityGeneration());
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
}
