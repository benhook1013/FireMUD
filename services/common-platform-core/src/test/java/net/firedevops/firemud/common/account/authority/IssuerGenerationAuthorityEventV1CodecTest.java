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
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IssuerGenerationAuthorityEventV1CodecTest {
  private static final String VECTOR_RESOURCE =
      "/net/firedevops/firemud/common/account/authority/account-auth-issuer-generation-event-v1-vectors.json";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static JsonNode vectors;

  @BeforeAll
  static void loadVectors() throws IOException {
    try (InputStream stream =
        IssuerGenerationAuthorityEventV1CodecTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      if (stream == null) {
        throw new IOException("Missing shared event vectors: " + VECTOR_RESOURCE);
      }
      vectors = JSON.readTree(stream);
    }
  }

  @Test
  void fixedVectorsVerifySealAndArbitraryPrecisionCounters() throws Exception {
    assertThat(vectors.path("schemaVersion").asText())
        .isEqualTo("account-auth-issuer-generation-event-vectors/v1");

    for (JsonNode vector : vectors.path("validEvents")) {
      JsonNode wireEvent = vector.path("event");
      IssuerGenerationAuthorityEvent verified =
          IssuerGenerationAuthorityEventV1Codec.verify(wireEvent.toString());
      assertThat(verified.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
      assertThat(verified.eventDigest()).isEqualTo(wireEvent.path("eventDigest").asText());
      assertThat(verified.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
      assertThat(verified.canonicalJsonUtf8())
          .isEqualTo(
              vector.path("expectedCanonicalJson").asText().getBytes(StandardCharsets.UTF_8));
      assertThat(verified.canonicalJsonUtf8())
          .isEqualTo(Rfc8785CanonicalJson.canonicalizeUtf8(wireEvent.toString()));

      Map<String, Object> wireMap = JSON.convertValue(wireEvent, new TypeReference<>() {});
      assertThat(IssuerGenerationAuthorityEventV1Codec.verify(wireMap).canonicalJson())
          .isEqualTo(vector.path("expectedCanonicalJson").asText());

      ObjectNode preimage = (ObjectNode) wireEvent.deepCopy();
      preimage.remove("eventDigest");
      Map<String, Object> preimageMap = JSON.convertValue(preimage, new TypeReference<>() {});
      IssuerGenerationAuthorityEvent sealed =
          IssuerGenerationAuthorityEventV1Codec.seal(preimageMap);
      assertThat(sealed.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
      assertThat(sealed.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
    }

    JsonNode baselineVector = vectors.path("validEvents").get(0);
    IssuerGenerationAuthorityEvent baseline =
        IssuerGenerationAuthorityEventV1Codec.verify(baselineVector.path("event").toString());
    assertThat(baseline.schemaVersion()).isEqualTo("account-auth-issuer-generation-event/v1");
    assertThat(baseline.eventType()).isEqualTo("ISSUER_GENERATION_ADVANCED");
    assertThat(baseline.eventId()).isEqualTo("event-issuer-generation-001");
    assertThat(baseline.requestId()).isEqualTo("request-issuer-generation-001");
    assertThat(baseline.issuerId()).isEqualTo("https://Auth.FireMUD.example/issuer");
    assertThat(baseline.sourceScope()).isEqualTo("issuer/https://Auth.FireMUD.example/issuer");
    assertThat(baseline.outboxStreamKey())
        .isEqualTo("account:auth-authority:v1:issuer/https://Auth.FireMUD.example/issuer");
    assertThat(baseline.outboxSequence()).isEqualTo("7");
    assertThat(baseline.issuerAuthGeneration()).isEqualTo("5");
    assertThat(baseline.sourceVersion()).isEqualTo("6");

    IssuerGenerationAuthorityEvent large =
        IssuerGenerationAuthorityEventV1Codec.verify(
            vectors.path("validEvents").get(1).path("event").toString());
    assertThat(large.outboxSequence()).isEqualTo("18446744073709551616000000000000000001");
    assertThat(large.issuerAuthGeneration()).isEqualTo("92233720368547758081234567890123456789");
    assertThat(large.sourceVersion()).isEqualTo("90071992547409931234567890123456789");
  }

  @Test
  void changingEveryDeclaredPreimageFieldCannotReuseTheOriginalDigest() {
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

      if (mutation.path("sealValid").asBoolean()) {
        IssuerGenerationAuthorityEvent sealed =
            IssuerGenerationAuthorityEventV1Codec.seal(preimageMap);
        assertThat(sealed.eventDigest())
            .as(mutation.path("name").asText())
            .isNotEqualTo(originalDigest);
        assertThatThrownBy(
                () -> IssuerGenerationAuthorityEventV1Codec.verify(changedWire.toString()))
            .as(mutation.path("name").asText())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventDigest");
      } else {
        assertThatThrownBy(() -> IssuerGenerationAuthorityEventV1Codec.seal(preimageMap))
            .as(mutation.path("name").asText())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> IssuerGenerationAuthorityEventV1Codec.verify(changedWire.toString()))
            .as(mutation.path("name").asText())
            .isInstanceOf(IllegalArgumentException.class);
      }
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

    IssuerGenerationAuthorityEvent verified =
        IssuerGenerationAuthorityEventV1Codec.verify(reordered.toString());
    assertThat(verified.eventDigest()).isEqualTo(vector.path("expectedEventDigest").asText());
    assertThat(verified.canonicalJson()).isEqualTo(vector.path("expectedCanonicalJson").asText());
  }

  @Test
  void malformedAndNoncanonicalVectorsAreRejected() {
    ObjectNode baseline = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    for (JsonNode mutation : vectors.path("invalidMutations")) {
      ObjectNode changed = (ObjectNode) baseline.deepCopy();
      applyMutation(changed, mutation);
      assertThatThrownBy(() -> IssuerGenerationAuthorityEventV1Codec.verify(changed.toString()))
          .as(mutation.path("name").asText())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void duplicatePropertiesAreRejectedBeforeShapeValidation() {
    assertThatThrownBy(
            () ->
                IssuerGenerationAuthorityEventV1Codec.verify(
                    "{\"eventId\":\"first\",\"eventId\":\"second\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parsedEvidenceIsImmutableAndCanonicalBytesAreCallerOwned() {
    JsonNode vector = vectors.path("validEvents").get(0);
    IssuerGenerationAuthorityEvent event =
        IssuerGenerationAuthorityEventV1Codec.verify(vector.path("event").toString());
    byte[] bytes = event.canonicalJsonUtf8();
    bytes[0] = (byte) 'x';

    assertThat(event.canonicalJsonUtf8())
        .isEqualTo(vector.path("expectedCanonicalJson").asText().getBytes(StandardCharsets.UTF_8));
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
