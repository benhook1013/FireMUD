package net.firedevops.firemud.accountservice.authority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class MembershipAuthorityEventVectorTest {
  private static final String VECTOR_RESOURCE =
      "/net/firedevops/firemud/common/account/authority/account-auth-authority-event-v1-vectors.json";
  private static final BigInteger MAX_SAFE_INTEGER = new BigInteger("9007199254740991");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void sealsEverySharedPreimageToItsExpectedDigest() throws IOException {
    JsonNode vectors = readVectors();

    for (JsonNode vector : vectors.path("validEvents")) {
      Map<String, Object> preimage = toMap(vector.path("event"));
      String expectedDigest = (String) preimage.remove("eventDigest");

      var sealed = MembershipAuthorityEventV1Codec.seal(preimage);

      assertEquals(expectedDigest, sealed.eventDigest(), vector.path("name").asText());
      assertEquals(
          expectedDigest,
          MembershipAuthorityEventV1Codec.verify(toMap(JSON.readTree(sealed.canonicalJson())))
              .eventDigest());
      assertEquals(JSON.readTree(sealed.canonicalJson()), vector.path("event"));
    }
  }

  @Test
  void preservesLargeCountersAsCanonicalDecimalStrings() throws IOException {
    JsonNode vectors = readVectors();
    List<BigInteger> largeCounters = new ArrayList<>();

    for (JsonNode vector : vectors.path("validEvents")) {
      JsonNode event = vector.path("event");
      collectCounterEvidence(toMap(event), "event", largeCounters);
      Map<String, Object> preimage = toMap(event);
      String expectedDigest = (String) preimage.remove("eventDigest");
      var sealed = MembershipAuthorityEventV1Codec.seal(preimage);

      assertEquals(expectedDigest, sealed.eventDigest());
    }

    assertFalse(largeCounters.isEmpty(), "shared vectors must include a counter above 2^53");
    assertTrue(largeCounters.stream().anyMatch(value -> value.compareTo(MAX_SAFE_INTEGER) > 0));
  }

  @Test
  void changedPreimageFieldProducesDifferentDigest() throws IOException {
    JsonNode vectors = readVectors();
    JsonNode sourceEvent = vectors.path("validEvents").get(0).path("event");
    Map<String, Object> originalPreimage = toMap(sourceEvent);
    String originalDigest = (String) originalPreimage.remove("eventDigest");
    JsonNode mutation = null;
    for (JsonNode candidate : vectors.path("changedFieldMutations")) {
      if (candidate.path("name").asText().equals("event-id")) {
        mutation = candidate;
        break;
      }
    }
    assertTrue(mutation != null, "shared vectors must include a valid event-id mutation");
    ObjectNode changedEvent = (ObjectNode) sourceEvent.deepCopy();
    applyMutation(changedEvent, mutation, "replacement", "replace");
    Map<String, Object> changedPreimage = toMap(changedEvent);
    changedPreimage.remove("eventDigest");

    var sealed = MembershipAuthorityEventV1Codec.seal(changedPreimage);

    assertNotEquals(originalDigest, sealed.eventDigest());
    assertEquals(
        sealed.eventDigest(),
        MembershipAuthorityEventV1Codec.verify(toMap(JSON.readTree(sealed.canonicalJson())))
            .eventDigest());
  }

  @Test
  void rejectsSharedInvalidPreimagesIncludingOptionalAndArrayOrderViolations() throws IOException {
    JsonNode vectors = readVectors();
    JsonNode sourceEvent = vectors.path("validEvents").get(0).path("event");

    for (JsonNode mutation : vectors.path("invalidMutations")) {
      String path = mutation.path("path").asText();
      if (path.equals("/eventDigest")) {
        continue;
      }
      ObjectNode changedEvent = (ObjectNode) sourceEvent.deepCopy();
      applyMutation(changedEvent, mutation, "value", null);
      Map<String, Object> preimage = toMap(changedEvent);
      preimage.remove("eventDigest");

      assertThrows(
          IllegalArgumentException.class,
          () -> MembershipAuthorityEventV1Codec.seal(preimage),
          mutation.path("name").asText());
    }
  }

  private static JsonNode readVectors() throws IOException {
    try (InputStream stream =
        MembershipAuthorityEventVectorTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      assertTrue(stream != null, "shared authority-event vectors must be on the test classpath");
      return JSON.readTree(stream);
    }
  }

  private static Map<String, Object> toMap(JsonNode node) {
    return JSON.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private static void collectCounterEvidence(
      Object value, String fieldName, List<BigInteger> largeCounters) {
    if (value instanceof Map<?, ?> object) {
      object.forEach((key, child) -> collectCounterEvidence(child, key.toString(), largeCounters));
      return;
    }
    if (value instanceof List<?> array) {
      array.forEach(child -> collectCounterEvidence(child, fieldName, largeCounters));
      return;
    }
    if (isCounterField(fieldName)) {
      assertTrue(
          value instanceof String, () -> fieldName + " must be a decimal string in shared vectors");
      String text = (String) value;
      assertTrue(
          text.matches("0|[1-9][0-9]*"), () -> fieldName + " must be canonical decimal text");
      BigInteger numericValue = new BigInteger(text);
      if (numericValue.compareTo(MAX_SAFE_INTEGER) > 0) {
        largeCounters.add(numericValue);
      }
    }
  }

  private static void applyMutation(
      ObjectNode root, JsonNode mutation, String valueField, String defaultOperation) {
    String[] segments = mutation.path("path").asText().substring(1).split("/");
    JsonNode parent = root;
    for (int i = 0; i < segments.length - 1; i++) {
      String segment = unescapePointerSegment(segments[i]);
      parent =
          parent instanceof ArrayNode array
              ? array.get(Integer.parseInt(segment))
              : parent.path(segment);
    }
    String finalSegment = unescapePointerSegment(segments[segments.length - 1]);
    String operation = mutation.path("op").asText(defaultOperation);
    if (operation.equals("remove")) {
      if (parent instanceof ObjectNode object) {
        object.remove(finalSegment);
      } else if (parent instanceof ArrayNode array) {
        array.remove(Integer.parseInt(finalSegment));
      } else {
        throw new AssertionError("mutation parent is not a JSON container");
      }
      return;
    }

    JsonNode value = mutation.get(valueField).deepCopy();
    if (parent instanceof ObjectNode object) {
      if (operation.equals("add") || operation.equals("replace")) {
        object.set(finalSegment, value);
        return;
      }
    } else if (parent instanceof ArrayNode array) {
      int index = Integer.parseInt(finalSegment);
      if (operation.equals("add")) {
        array.insert(index, value);
        return;
      }
      if (operation.equals("replace")) {
        array.set(index, value);
        return;
      }
    }
    throw new AssertionError("unsupported vector mutation operation: " + operation);
  }

  private static String unescapePointerSegment(String segment) {
    return segment.replace("~1", "/").replace("~0", "~");
  }

  private static boolean isCounterField(String fieldName) {
    return fieldName.equals("outboxSequence")
        || fieldName.equals("membershipVersion")
        || fieldName.equals("membershipAuthorityGeneration")
        || fieldName.equals("issuanceFence")
        || fieldName.equals("issuerAuthGeneration")
        || fieldName.equals("accountAuthorityGeneration")
        || fieldName.equals("tenantAuthorityGeneration")
        || fieldName.equals("grantVersion")
        || fieldName.equals("tenantBillingSequence");
  }
}
