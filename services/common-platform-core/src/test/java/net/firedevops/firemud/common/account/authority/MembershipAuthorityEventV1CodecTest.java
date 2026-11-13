package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MembershipAuthorityEventV1CodecTest {
  private static final String VECTOR_RESOURCE =
      "/net/firedevops/firemud/common/account/authority/"
          + "account-auth-authority-event-v1-vectors.json";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static JsonNode vectors;

  @BeforeAll
  static void loadVectors() throws IOException {
    try (var stream =
        MembershipAuthorityEventV1CodecTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      if (stream == null) {
        throw new IOException("Missing shared event vectors: " + VECTOR_RESOURCE);
      }
      vectors = JSON.readTree(stream);
    }
  }

  @Test
  void sharedVectorsVerifyAndSealCanonicalEvents() throws Exception {
    assertThat(vectors.path("schemaVersion").asText())
        .isEqualTo("account-auth-authority-event-vectors/v1");
    var digestMismatches = new ArrayList<String>();
    for (JsonNode vector : vectors.path("validEvents")) {
      ObjectNode wireEvent = (ObjectNode) vector.path("event").deepCopy();
      Map<String, Object> preimage =
          JSON.convertValue(wireEvent, new TypeReference<LinkedHashMap<String, Object>>() {});
      preimage.remove("eventDigest");
      MembershipEvent sealed = MembershipAuthorityEventV1Codec.seal(preimage);
      if (!sealed.eventDigest().equals(wireEvent.path("eventDigest").asText())) {
        digestMismatches.add(vector.path("name").asText() + "=" + sealed.eventDigest());
      }
      wireEvent.put("eventDigest", sealed.eventDigest());
      MembershipEvent verified = MembershipAuthorityEventV1Codec.verify(wireEvent.toString());
      assertThat(verified.membershipVersion())
          .isEqualTo(
              Map.of(
                  verified.tenantId(),
                  wireEvent.path("membershipVersion").path(verified.tenantId()).asText()));
      assertThat(verified.canonicalJsonUtf8())
          .isEqualTo(Rfc8785CanonicalJson.canonicalizeUtf8(wireEvent.toString()));
      assertThat(sealed.eventDigest()).isEqualTo(verified.eventDigest());
      assertThat(sealed.canonicalJson()).isEqualTo(verified.canonicalJson());
    }
    assertThat(digestMismatches).isEmpty();
  }

  @Test
  void authorityCheckpointRetainsV1ShapeAndMayRefreshOnlyCurrentAuthority() {
    MembershipEvent retainedVector =
        MembershipAuthorityEventV1Codec.verify(
            vectors.path("validEvents").get(0).path("event").toString());
    MembershipEvent original = seal(activeMembershipPreimage());
    ObjectNode checkpointPreimage = authorityCheckpointPreimage();
    String tenantId = checkpointPreimage.path("tenantId").asText();
    MembershipEvent checkpoint = seal(checkpointPreimage);

    assertThat(checkpoint.schemaVersion())
        .isEqualTo(MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    assertThat(checkpoint.eventType())
        .isEqualTo(MembershipAuthorityEventV1Codec.AUTHORITY_CHECKPOINT_EVENT_TYPE);
    assertThat(checkpoint.membershipLifecycleState())
        .isEqualTo(original.membershipLifecycleState());
    assertThat(checkpoint.roles()).isEqualTo(original.roles());
    assertThat(checkpoint.membershipVersion()).isEqualTo(original.membershipVersion());
    assertThat(checkpoint.membershipAuthorityGeneration())
        .isEqualTo(original.membershipAuthorityGeneration());
    assertThat(checkpoint.authorityTuple().tenantAuthorityGeneration())
        .containsEntry(
            tenantId,
            checkpointPreimage
                .path("authorityTuple")
                .path("tenantAuthorityGeneration")
                .path(tenantId)
                .asText())
        .isNotEqualTo(original.authorityTuple().tenantAuthorityGeneration());
    assertThat(checkpoint.authorityTuple().tenantBillingCutoff().orElseThrow())
        .containsKey(tenantId);
    assertThat(MembershipAuthorityEventV1Codec.verify(original.canonicalJson()).canonicalJson())
        .isEqualTo(original.canonicalJson());
    assertThat(MembershipAuthorityEventV1Codec.verify(checkpoint.canonicalJson()).canonicalJson())
        .isEqualTo(checkpoint.canonicalJson());
    assertThat(
            MembershipAuthorityEventV1Codec.verify(
                    vectors.path("validEvents").get(0).path("event").toString())
                .canonicalJson())
        .isEqualTo(retainedVector.canonicalJson());
  }

  @Test
  void authorityCheckpointRequiresActiveAdmittingNonInvalidatedMembership() {
    ObjectNode inactive = authorityCheckpointPreimage();
    inactive.put("membershipLifecycleState", "INACTIVE");
    inactive.put("gameplayAdmissionAllowed", false);
    assertThatThrownBy(() -> seal(inactive)).isInstanceOf(IllegalArgumentException.class);

    ObjectNode nonAdmitting = authorityCheckpointPreimage();
    nonAdmitting.put("gameplayAdmissionAllowed", false);
    assertThatThrownBy(() -> seal(nonAdmitting)).isInstanceOf(IllegalArgumentException.class);

    ObjectNode invalidated = authorityCheckpointPreimage();
    invalidated.put("callerBoundAuthorityInvalidated", true);
    assertThatThrownBy(() -> seal(invalidated)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void changedDeclaredFieldsChangeTheDigestAndCannotReuseTheOriginalDigest() throws Exception {
    ObjectNode baseline = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    String originalDigest = baseline.path("eventDigest").asText();
    String baselinePreimageDigest = preimageDigest(baseline);

    for (JsonNode mutation : vectors.path("changedFieldMutations")) {
      ObjectNode changed = baseline.deepCopy();
      set(changed, mutation.path("path").asText(), mutation.get("replacement"));
      assertThat(preimageDigest(changed))
          .as(mutation.path("name").asText())
          .isNotEqualTo(baselinePreimageDigest);
      assertThat(changed.path("eventDigest").asText()).isEqualTo(originalDigest);
      assertThatThrownBy(() -> MembershipAuthorityEventV1Codec.verify(changed.toString()))
          .as(mutation.path("name").asText())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void malformedAndNoncanonicalVectorsAreRejected() {
    ObjectNode baseline = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    for (JsonNode mutation : vectors.path("invalidMutations")) {
      ObjectNode changed = baseline.deepCopy();
      String operation = mutation.path("op").asText();
      String path = mutation.path("path").asText();
      if (operation.equals("remove")) {
        remove(changed, path);
      } else if (operation.equals("replace") || operation.equals("add")) {
        set(changed, path, mutation.get("value"));
      } else {
        throw new IllegalArgumentException("Unknown vector operation: " + operation);
      }
      assertThatThrownBy(() -> MembershipAuthorityEventV1Codec.verify(changed.toString()))
          .as(mutation.path("name").asText())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void countersRespectSignedLongOwnerRangeAndRemainStrings() {
    ObjectNode maximum = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    maximum.remove("eventDigest");
    maximum.put("outboxSequence", "9223372036854775807");
    Map<String, Object> preimage =
        JSON.convertValue(maximum, new TypeReference<LinkedHashMap<String, Object>>() {});

    MembershipEvent sealed = MembershipAuthorityEventV1Codec.seal(preimage);

    assertThat(sealed.outboxSequence()).isEqualTo("9223372036854775807");
    assertThat(sealed.canonicalJson()).contains("\"outboxSequence\":\"9223372036854775807\"");
    assertThat(MembershipAuthorityEventV1Codec.verify(sealed.canonicalJson()).outboxSequence())
        .isEqualTo("9223372036854775807");

    maximum.put("outboxSequence", "9223372036854775808");
    Map<String, Object> overflowPreimage =
        JSON.convertValue(maximum, new TypeReference<LinkedHashMap<String, Object>>() {});
    assertThatThrownBy(() -> MembershipAuthorityEventV1Codec.seal(overflowPreimage))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive canonical decimal string in the owner range");
  }

  @Test
  void nilUuidValuesAndGenerationMapKeysAreRejected() {
    ObjectNode nilValue = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    nilValue.put("accountId", "00000000-0000-0000-0000-000000000000");
    assertThatThrownBy(() -> MembershipAuthorityEventV1Codec.verify(nilValue.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil canonical lowercase UUID");

    ObjectNode nilMapKey = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    ObjectNode generationMap = JSON.createObjectNode();
    generationMap.put("00000000-0000-0000-0000-000000000000", "1");
    set(nilMapKey, "/authorityTuple/tenantAuthorityGeneration", generationMap);
    assertThatThrownBy(() -> MembershipAuthorityEventV1Codec.verify(nilMapKey.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil canonical lowercase UUID");
  }

  @Test
  void duplicatePropertiesAreRejectedBeforeJacksonTreeValidation() {
    String duplicatePropertyJson =
        "{\"schemaVersion\":\"wrong\",\"schemaVersion\":\"wrong-again\"}";
    assertThatThrownBy(() -> MembershipAuthorityEventV1Codec.verify(duplicatePropertyJson))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("event JSON is malformed");
  }

  @Test
  void eventAndRequestIdsMustBeNonblankAndBounded() {
    ObjectNode blankId = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    blankId.put("eventId", " \t");
    assertThatThrownBy(() -> MembershipAuthorityEventV1Codec.verify(blankId.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonblank");

    ObjectNode longRequestId =
        (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    longRequestId.put("requestId", "x".repeat(513));
    assertThatThrownBy(() -> MembershipAuthorityEventV1Codec.verify(longRequestId.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("512 characters");
  }

  @Test
  void returnedEvidenceIsImmutableAndByteArraysAreCallerOwned() {
    JsonNode wireEvent = vectors.path("validEvents").get(0).path("event");
    MembershipEvent event = MembershipAuthorityEventV1Codec.verify(wireEvent.toString());
    byte[] bytes = event.canonicalJsonUtf8();
    bytes[0] = (byte) 'x';

    assertThat(event.canonicalJsonUtf8()[0]).isNotEqualTo((byte) 'x');
    assertThatThrownBy(() -> event.roles().add("moderator"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> event.authorityTuple().membershipAuthorityGeneration().put("x", "1"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(event.membershipVersion())
        .isEqualTo(
            Map.of(
                event.tenantId(),
                wireEvent.path("membershipVersion").path(event.tenantId()).asText()));
    assertThatThrownBy(() -> event.membershipVersion().put(event.tenantId(), "2"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(event.membershipAuthorityGeneration())
        .isEqualTo(event.authorityTuple().membershipAuthorityGeneration().get(event.tenantId()));
  }

  private static String preimageDigest(ObjectNode wireEvent) throws Exception {
    ObjectNode preimage = wireEvent.deepCopy();
    preimage.remove("eventDigest");
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(preimage));
    return "sha256:"
        + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
  }

  private static ObjectNode activeMembershipPreimage() {
    ObjectNode event = (ObjectNode) vectors.path("validEvents").get(0).path("event").deepCopy();
    event.remove("eventDigest");
    event.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    event.put("eventId", "membership-event-1");
    event.put("requestId", "membership-request-1");
    event.put("outboxSequence", "1");
    event.put("callerBoundAuthorityInvalidated", false);
    ((com.fasterxml.jackson.databind.node.ArrayNode)
            event.path("authorityTuple").path("privateRealmGrantVersions"))
        .removeAll();
    return event;
  }

  private static ObjectNode authorityCheckpointPreimage() {
    ObjectNode event = activeMembershipPreimage();
    event.put("eventType", MembershipAuthorityEventV1Codec.AUTHORITY_CHECKPOINT_EVENT_TYPE);
    event.put("eventId", "authority-checkpoint-event-2");
    event.put("requestId", "authority-checkpoint-request-2");
    event.put("outboxSequence", "2");
    String tenantId = event.path("tenantId").asText();
    ObjectNode tuple = (ObjectNode) event.get("authorityTuple");
    ObjectNode tenantGenerations = JSON.createObjectNode();
    tenantGenerations.put(tenantId, "14");
    tuple.set("tenantAuthorityGeneration", tenantGenerations);
    ObjectNode tenantCutoff = JSON.createObjectNode();
    tenantCutoff.put("tenantAuthorityGeneration", "14");
    tenantCutoff.put("tenantBillingSequence", "6");
    tenantCutoff.put(
        "outboxStreamKey",
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + tenantId);
    tenantCutoff.put("outboxSequence", "10");
    ObjectNode tenantCutoffs = JSON.createObjectNode();
    tenantCutoffs.set(tenantId, tenantCutoff);
    tuple.set("tenantBillingCutoff", tenantCutoffs);
    return event;
  }

  private static MembershipEvent seal(ObjectNode preimage) {
    Map<String, Object> fields =
        JSON.convertValue(preimage, new TypeReference<LinkedHashMap<String, Object>>() {});
    return MembershipAuthorityEventV1Codec.seal(fields);
  }

  private static void set(ObjectNode root, String pointer, JsonNode value) {
    String[] parts = pointer.substring(1).split("/");
    JsonNode parent = root;
    for (int index = 0; index < parts.length - 1; index++) {
      parent = child(parent, parts[index]);
    }
    String leaf = parts[parts.length - 1];
    if (parent instanceof ObjectNode object) {
      object.set(leaf, value.deepCopy());
    } else if (parent.isArray()) {
      ((com.fasterxml.jackson.databind.node.ArrayNode) parent)
          .set(Integer.parseInt(leaf), value.deepCopy());
    } else {
      throw new IllegalArgumentException("Mutation parent is not a JSON container: " + pointer);
    }
  }

  private static void remove(ObjectNode root, String pointer) {
    String[] parts = pointer.substring(1).split("/");
    JsonNode parent = root;
    for (int index = 0; index < parts.length - 1; index++) {
      parent = child(parent, parts[index]);
    }
    String leaf = parts[parts.length - 1];
    if (parent instanceof ObjectNode object) {
      object.remove(leaf);
    } else if (parent.isArray()) {
      ((com.fasterxml.jackson.databind.node.ArrayNode) parent).remove(Integer.parseInt(leaf));
    } else {
      throw new IllegalArgumentException("Mutation parent is not a JSON container: " + pointer);
    }
  }

  private static JsonNode child(JsonNode parent, String part) {
    if (parent.isArray()) {
      return parent.get(Integer.parseInt(part));
    }
    return parent.path(part);
  }
}
