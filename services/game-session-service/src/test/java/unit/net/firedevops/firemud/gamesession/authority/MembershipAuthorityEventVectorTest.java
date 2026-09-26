package net.firedevops.firemud.gamesession.authority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class MembershipAuthorityEventVectorTest {
  private static final String VECTOR_RESOURCE =
      "/net/firedevops/firemud/common/account/authority/account-auth-authority-event-v1-vectors.json";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void verifiesEverySharedWireEventAndItsCanonicalDigest() throws IOException {
    JsonNode vectors = readVectors();

    for (JsonNode vector : vectors.path("validEvents")) {
      JsonNode wireEvent = vector.path("event");
      var verified = MembershipAuthorityEventV1Codec.verify(JSON.writeValueAsString(wireEvent));

      assertEquals(wireEvent.path("eventDigest").asText(), verified.eventDigest());
      assertEquals(wireEvent.path("outboxSequence").asText(), verified.outboxSequence());
      assertEquals(wireEvent.path("membershipVersion").asText(), verified.membershipVersion());
      assertEquals(
          wireEvent.path("membershipAuthorityGeneration").asText(),
          verified.membershipAuthorityGeneration());
      assertEquals(wireEvent.path("roles").size(), verified.roles().size());
      assertEquals(wireEvent, JSON.readTree(verified.canonicalJson()));
    }
  }

  @Test
  void acceptsLargeCountersOnlyInTheirCanonicalDecimalStringForm() throws IOException {
    JsonNode vectors = readVectors();
    boolean sawLargeCounter = false;
    BigInteger maxSafeInteger = new BigInteger("9007199254740991");

    for (JsonNode vector : vectors.path("validEvents")) {
      JsonNode event = vector.path("event");
      JsonNode sequence = event.path("outboxSequence");
      assertTrue(sequence.isTextual(), "outboxSequence must remain a decimal string");
      if (new BigInteger(sequence.asText()).compareTo(maxSafeInteger) > 0) {
        sawLargeCounter = true;
      }
      var verified = MembershipAuthorityEventV1Codec.verify(JSON.writeValueAsString(event));
      assertEquals(sequence.asText(), verified.outboxSequence());
    }

    assertTrue(sawLargeCounter, "shared vectors must include a counter above 2^53");
  }

  @Test
  void rejectsEveryChangedFieldWhileKeepingTheOriginalDigest() throws IOException {
    JsonNode vectors = readVectors();
    JsonNode sourceEvent = vectors.path("validEvents").get(0).path("event");

    for (JsonNode mutation : vectors.path("changedFieldMutations")) {
      ObjectNode changedEvent = (ObjectNode) sourceEvent.deepCopy();
      applyMutation(changedEvent, mutation, "replacement", "replace");

      assertThrows(
          IllegalArgumentException.class,
          () -> MembershipAuthorityEventV1Codec.verify(JSON.writeValueAsString(changedEvent)),
          mutation.path("name").asText());
    }
  }

  @Test
  void rejectsSharedInvalidMutationsIncludingOptionalAndArrayOrderViolations() throws IOException {
    JsonNode vectors = readVectors();
    JsonNode sourceEvent = vectors.path("validEvents").get(0).path("event");

    for (JsonNode mutation : vectors.path("invalidMutations")) {
      ObjectNode changedEvent = (ObjectNode) sourceEvent.deepCopy();
      applyMutation(changedEvent, mutation, "value", null);

      assertThrows(
          IllegalArgumentException.class,
          () -> MembershipAuthorityEventV1Codec.verify(JSON.writeValueAsString(changedEvent)),
          mutation.path("name").asText());
    }
  }

  @Test
  void rejectsDuplicatePropertiesInRawEventJson() throws IOException {
    JsonNode event = readVectors().path("validEvents").get(0).path("event");
    String validJson = JSON.writeValueAsString(event);
    String duplicatePropertyJson =
        validJson.replaceFirst("^\\{", "{\"eventId\":\"duplicate-event\",");

    assertThrows(
        IllegalArgumentException.class,
        () -> MembershipAuthorityEventV1Codec.verify(duplicatePropertyJson));
  }

  private static JsonNode readVectors() throws IOException {
    try (InputStream stream =
        MembershipAuthorityEventVectorTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      assertTrue(stream != null, "shared authority-event vectors must be on the test classpath");
      return JSON.readTree(stream);
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
}
