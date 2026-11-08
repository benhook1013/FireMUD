package unit.net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalogCodec;
import org.junit.jupiter.api.Test;

/** Controlled catalog bytes exercise the value/codec, not an installed schema or enrollment. */
class WorldRuntimeFamilyCatalogTest {
  private static final String CATALOG =
      """
      {"schema":"world-runtime-family-catalog/v1","owner":"world-management-service",
       "catalogEpoch":"1","families":[
        {"familyId":"world_instance","relation":"world_instance","component":"",
         "role":"LIFECYCLE","stateClass":"S3","freshDisposition":"REQUIRED_LIFECYCLE"}],
       "schemaFacts":[{"kind":"TABLE","identity":"world_instance",
         "definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
      """;

  @Test
  void fixedRequestFramingAndStandardDigestVectorAreStable() {
    var request = new Request(UUID.fromString("11111111-1111-4111-8111-111111111111"), "test");
    assertThat(
            java.util.HexFormat.of()
                .formatHex(WorldRuntimeFamilyCatalogCodec.requestBytes(request)))
        .isEqualTo("57435231111111111111411181111111111111110000000474657374");
    assertThat(WorldRuntimeFamilyCatalog.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void familyAndFactArrayBoundsAreIndependentOfPayloadRowAbsence() {
    String family =
        """
        {"familyId":"world_instance","relation":"world_instance","component":"",
         "role":"LIFECYCLE","stateClass":"S3","freshDisposition":"REQUIRED_LIFECYCLE"}
        """;
    String fact =
        """
        {"kind":"TABLE","identity":"world_instance",
         "definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
        """;
    String prefix =
        "{\"schema\":\"world-runtime-family-catalog/v1\",\"owner\":\"world-management-service\",\"catalogEpoch\":\"1\",";
    String manyFamilies =
        prefix
            + "\"families\":["
            + String.join(",", java.util.Collections.nCopies(513, family))
            + "],\"schemaFacts\":["
            + fact
            + "]}";
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalog.fromStored(manyFamilies.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    String compactFact =
        "{\"kind\":\"TYPE\",\"identity\":\"x\",\"definitionDigest\":\"sha256:"
            + "a".repeat(64)
            + "\"}";
    String manyFacts =
        prefix
            + "\"families\":["
            + family
            + "],\"schemaFacts\":["
            + String.join(",", java.util.Collections.nCopies(16385, compactFact))
            + "]}";
    assertThatThrownBy(
            () -> WorldRuntimeFamilyCatalog.fromStored(manyFacts.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactTypedRoundtripPreservesOriginalBytesIdentityAndDigests() {
    byte[] source = CATALOG.getBytes(StandardCharsets.UTF_8);
    var catalog = WorldRuntimeFamilyCatalog.fromStored(source);
    var read = new Request(UUID.randomUUID(), "test");
    var evidence = new Evidence(read, UUID.randomUUID(), catalog);
    assertThat(catalog.catalogEpoch()).isEqualTo(1);
    assertThat(catalog.families()).hasSize(1);
    assertThat(catalog.digest()).isEqualTo(WorldRuntimeFamilyCatalog.sha256(source));
    var response = WorldRuntimeFamilyCatalogCodec.toResponse(read, evidence);
    assertThat(WorldRuntimeFamilyCatalogCodec.fromResponse(read, response)).isEqualTo(evidence);
    assertThat(
            WorldRuntimeFamilyCatalogCodec.fromRequest(
                WorldRuntimeFamilyCatalogCodec.toRequest(read)))
        .isEqualTo(read);
    assertThat(
            WorldRuntimeFamilyCatalogCodec.evidenceFromStored(
                    WorldRuntimeFamilyCatalogCodec.evidenceBytes(evidence))
                .digest())
        .isEqualTo(evidence.digest());
    source[0] = 0;
    byte[] returned = catalog.exactBytes();
    returned[0] = 0;
    assertThat(catalog.exactBytes()).isEqualTo(CATALOG.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> catalog.families().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void refusesUnknownDuplicateMissingUnsupportedOrInconsistentCatalogFacts() {
    for (String invalid :
        new String[] {
          CATALOG.replace("\"owner\":", "\"extra\":true,\"owner\":"),
          CATALOG.replace(
              "\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"1\",\"catalogEpoch\":\"1\""),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"0\""),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":1"),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"9223372036854775808\""),
          CATALOG.replace("world-runtime-family-catalog/v1", "world-runtime-family-catalog/v2"),
          CATALOG.replace("world-management-service", "game-session-service"),
          CATALOG.replace("\"S3\"", "\"NOT_PAYLOAD\""),
          CATALOG.replace("\"LIFECYCLE\"", "\"UNKNOWN\""),
          CATALOG.replace("\"REQUIRED_LIFECYCLE\"", "\"REQUIRED_TOPOLOGY\""),
          CATALOG.replace("\"identity\":\"world_instance\"", "\"identity\":\"future_family\""),
          CATALOG.replace("\"component\":\"\"", "\"component\":\"weather\""),
          CATALOG + "{}"
        }) {
      assertThatThrownBy(
              () -> WorldRuntimeFamilyCatalog.fromStored(invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsUnknownDuplicateOversizedAndChangedCorrelationWireValues() {
    var read = new Request(UUID.randomUUID(), "test");
    var request = WorldRuntimeFamilyCatalogCodec.toRequest(read);
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalogCodec.fromRequest(
                    request.toBuilder()
                        .addCanonicalRequestBytes(request.getCanonicalRequestBytes(0))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalogCodec.fromRequest(
                    request.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    9, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalogCodec.fromRequest(
                    request.toBuilder()
                        .setCanonicalRequestBytes(0, ByteString.copyFrom(new byte[1025]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var evidence = new Evidence(read, UUID.randomUUID(), catalog());
    var response = WorldRuntimeFamilyCatalogCodec.toResponse(read, evidence);
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalogCodec.fromResponse(
                    new Request(UUID.randomUUID(), "test"), response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalogCodec.fromResponse(
                    new Request(read.readRequestId(), "other"), response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalogCodec.fromResponse(
                    read,
                    response.toBuilder()
                        .addCanonicalResponseBytes(response.getCanonicalResponseBytes(0))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesTruncationTrailingDataDigestTamperingInvalidUtf8AndSourceBounds() {
    var evidence =
        new Evidence(new Request(UUID.randomUUID(), "test"), UUID.randomUUID(), catalog());
    byte[] bytes = WorldRuntimeFamilyCatalogCodec.evidenceBytes(evidence);
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalogCodec.evidenceFromStored(
                    Arrays.copyOf(bytes, bytes.length - 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalogCodec.evidenceFromStored(
                    Arrays.copyOf(bytes, bytes.length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    // The digest string is outside the catalog bytes, so a complete parse must still refuse it.
    int digestOffset =
        4 + 4 + WorldRuntimeFamilyCatalogCodec.requestBytes(evidence.request()).length + 16 + 4;
    bytes[digestOffset + 7] = bytes[digestOffset + 7] == 'a' ? (byte) 'b' : (byte) 'a';
    assertThatThrownBy(() -> WorldRuntimeFamilyCatalogCodec.evidenceFromStored(bytes))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WorldRuntimeFamilyCatalog.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldRuntimeFamilyCatalog.fromStored(
                    new byte[WorldRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(new UUID(0, 0), "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(UUID.randomUUID(), "1/sa/world-management-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldRuntimeFamilyCatalog catalog() {
    return WorldRuntimeFamilyCatalog.fromStored(CATALOG.getBytes(StandardCharsets.UTF_8));
  }
}
