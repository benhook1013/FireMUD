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
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec.PasswordResetAuthorityEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PasswordResetAuthorityEventV1CodecTest {
  private static final String VECTOR_RESOURCE =
      "/net/firedevops/firemud/common/account/authority/account-auth-password-reset-event-v1-vectors.json";
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
      PasswordResetAuthorityEvent verified =
          PasswordResetAuthorityEventV1Codec.verify(wireEvent.toString());
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
      PasswordResetAuthorityEvent sealed = PasswordResetAuthorityEventV1Codec.seal(preimageMap);
      assertThat(sealed.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
      assertThat(sealed.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
    }

    PasswordResetAuthorityEvent large =
        PasswordResetAuthorityEventV1Codec.verify(
            vectors.path("validEvents").get(1).path("event").toString());
    assertThat(large.outboxSequence()).isEqualTo("18446744073709551616000000000000000001");
    assertThat(large.accountAuthorityGeneration())
        .isEqualTo("92233720368547758081234567890123456789");
    assertThat(large.sourceVersion()).isEqualTo("90071992547409931234567890123456789");
  }

  @Test
  void changingAnyDeclaredSourceEvidenceCannotReuseTheOriginalDigest() {
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

      PasswordResetAuthorityEvent sealed = PasswordResetAuthorityEventV1Codec.seal(preimageMap);
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
  void canonicalPropertyReorderingPreservesTheFixedCanonicalEvent() {
    JsonNode vector = vectors.path("validEvents").get(0);
    JsonNode event = vector.path("event");
    ObjectNode reordered = JSON.createObjectNode();
    for (JsonNode field : vectors.path("canonicalReorderedPropertyOrder")) {
      String fieldName = field.asText();
      reordered.set(fieldName, event.path(fieldName).deepCopy());
    }

    PasswordResetAuthorityEvent verified =
        PasswordResetAuthorityEventV1Codec.verify(reordered.toString());
    assertThat(verified.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
    assertThat(verified.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
  }

  @Test
  void malformedAndNoncanonicalVectorsAreRejected() {
    ObjectNode baseline = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    for (JsonNode mutation : vectors.path("invalidMutations")) {
      ObjectNode changed = (ObjectNode) baseline.deepCopy();
      applyMutation(changed, mutation);
      assertThatThrownBy(() -> PasswordResetAuthorityEventV1Codec.verify(changed.toString()))
          .as(mutation.path("name").asText())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void duplicatePropertiesAreRejectedBeforeShapeValidation() {
    assertThatThrownBy(
            () ->
                PasswordResetAuthorityEventV1Codec.verify(
                    "{\"eventId\":\"first\",\"eventId\":\"second\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parsedEvidenceIsImmutableAndCanonicalBytesAreCallerOwned() {
    JsonNode vector = vectors.path("validEvents").get(0);
    PasswordResetAuthorityEvent event =
        PasswordResetAuthorityEventV1Codec.verify(vector.path("event").toString());
    byte[] bytes = event.canonicalJsonUtf8();
    bytes[0] = (byte) 'x';

    assertThat(event.canonicalJsonUtf8())
        .isEqualTo(vector.path("expectedCanonicalJson").asText().getBytes(StandardCharsets.UTF_8));
    assertThat(event.accountSecurityCutoff().accountAuthorityGeneration())
        .isEqualTo(event.accountAuthorityGeneration());
    assertThat(event.accountSecurityCutoff().outboxStreamKey()).isEqualTo(event.outboxStreamKey());
    assertThat(event.accountSecurityCutoff().outboxSequence()).isEqualTo(event.outboxSequence());
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
