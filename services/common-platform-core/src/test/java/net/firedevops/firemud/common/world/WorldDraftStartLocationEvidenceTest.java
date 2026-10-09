package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WorldDraftStartLocationEvidenceTest {
  private static final String ACCOUNT_BINDING_DIGEST = "sha256:" + "0".repeat(64);
  private static final String BINDING_DIGEST = "sha256:" + "1".repeat(64);
  private static final String GRAPH_DIGEST = "sha256:" + "2".repeat(64);
  private static final String RECEIPT_DIGEST =
      "sha256:9f267c68a4915e5c28dd66536663783808210422fef4ae6062a8cd185d90636c";
  private static final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final RoomTemplateRef START_LOCATION =
      new RoomTemplateRef(
          uuid("11111111-1111-4111-8111-111111111111"),
          uuid("22222222-2222-4222-8222-222222222222"),
          uuid("77777777-7777-4777-8777-777777777777"));
  private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();

  @Test
  void createMatchesTheOriginalReceiptPreimageAndCanonicalBytes() {
    var evidence = evidence();

    assertThat(evidence.receiptDigest()).isEqualTo(RECEIPT_DIGEST);
    assertThat(new String(evidence.canonicalBytes(), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"accountBindingDigest\":\"sha256:"
                + "0000000000000000000000000000000000000000000000000000000000000000"
                + "\",\"authorizationFenceId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\""
                + ",\"bindingDigest\":\"sha256:"
                + "1111111111111111111111111111111111111111111111111111111111111111"
                + "\",\"commitId\":\"55555555-5555-4555-8555-555555555555\""
                + ",\"graphDigest\":\"sha256:"
                + "2222222222222222222222222222222222222222222222222222222222222222"
                + "\",\"operationId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\""
                + ",\"receiptDigest\":\"sha256:"
                + "9f267c68a4915e5c28dd66536663783808210422fef4ae6062a8cd185d90636c"
                + "\",\"requestId\":\"44444444-4444-4444-8444-444444444444\""
                + ",\"schema\":\"world-draft-start-location-receipt/v1\""
                + ",\"startLocation\":{\"roomTemplateId\":\"77777777-7777-4777-8777-777777777777\""
                + ",\"tenantId\":\"11111111-1111-4111-8111-111111111111\""
                + ",\"versionId\":\"22222222-2222-4222-8222-222222222222\"}"
                + ",\"targetNamespace\":\"test\"}");
    assertThat(WorldDraftStartLocationEvidence.fromStored(evidence.canonicalBytes()))
        .isEqualTo(evidence);
  }

  @Test
  void fromStoredRejectsUnknownDuplicateTrailingInvalidUtf8AndNoncanonicalBytes() throws Exception {
    byte[] canonical = evidence().canonicalBytes();
    String json = new String(canonical, StandardCharsets.UTF_8);
    assertRejected((" " + json).getBytes(StandardCharsets.UTF_8));
    assertRejected((json + "{}").getBytes(StandardCharsets.UTF_8));
    assertRejected(
        (json.substring(0, json.length() - 1) + ",\"unsupported\":true}")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        json.replace("\"startLocation\":{", "\"startLocation\":{\"unsupported\":true,")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        json.replace(
                "\"targetNamespace\":\"test\"",
                "\"targetNamespace\":\"test\",\"targetNamespace\":\"test\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        json.replace(
                "\"startLocation\":{",
                "\"startLocation\":{\"tenantId\":\"11111111-1111-4111-8111-111111111111\",")
            .getBytes(StandardCharsets.UTF_8));
    byte[] invalidUtf8 = canonical.clone();
    invalidUtf8[1] = (byte) 0xc3;
    assertRejected(invalidUtf8);
  }

  @Test
  void fromStoredRejectsNilNoncanonicalAndDigestSubstitutedFields() throws Exception {
    for (Consumer<ObjectNode> change :
        List.<Consumer<ObjectNode>>of(
            receipt -> receipt.put("schema", "world-draft-start-location-receipt/v2"),
            receipt -> receipt.put("operationId", "00000000-0000-0000-0000-000000000000"),
            receipt -> receipt.put("operationId", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
            receipt -> receipt.put("targetNamespace", "test-two"),
            receipt -> receipt.put("operationId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            receipt -> receipt.put("requestId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            receipt -> receipt.put("commitId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            receipt -> receipt.put("authorizationFenceId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            receipt -> receipt.put("accountBindingDigest", "sha256:" + "f".repeat(64)),
            receipt -> receipt.put("bindingDigest", "sha256:" + "f".repeat(64)),
            receipt -> selector(receipt).put("tenantId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            receipt -> selector(receipt).put("versionId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            receipt ->
                selector(receipt).put("roomTemplateId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            receipt -> receipt.put("graphDigest", "sha256:" + "f".repeat(64)),
            receipt -> receipt.put("receiptDigest", "sha256:" + "f".repeat(64)))) {
      assertRejected(changed(change));
    }

    assertThatThrownBy(
            () ->
                WorldDraftStartLocationEvidence.create(
                    "invalid/namespace",
                    OPERATION_ID,
                    REQUEST_ID,
                    COMMIT_ID,
                    FENCE_ID,
                    ACCOUNT_BINDING_DIGEST,
                    BINDING_DIGEST,
                    START_LOCATION,
                    GRAPH_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldDraftStartLocationEvidence evidence() {
    return WorldDraftStartLocationEvidence.create(
        "test",
        OPERATION_ID,
        REQUEST_ID,
        COMMIT_ID,
        FENCE_ID,
        ACCOUNT_BINDING_DIGEST,
        BINDING_DIGEST,
        START_LOCATION,
        GRAPH_DIGEST);
  }

  private static byte[] changed(Consumer<ObjectNode> change) throws Exception {
    ObjectNode receipt = (ObjectNode) JSON.readTree(evidence().canonicalBytes());
    change.accept(receipt);
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(receipt));
  }

  private static ObjectNode selector(ObjectNode receipt) {
    return (ObjectNode) receipt.get("startLocation");
  }

  private static void assertRejected(byte[] stored) {
    assertThatThrownBy(() -> WorldDraftStartLocationEvidence.fromStored(stored))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
