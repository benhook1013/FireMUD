package unit.net.firedevops.firemud.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Family;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.FreshDisposition;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Role;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.StateClass;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalogCodec;
import org.junit.jupiter.api.Test;

/** Controlled bytes prove closed value behavior, never actual schema or runtime completeness. */
class EntityRuntimeFamilyCatalogTest {
  private static final String CATALOG =
      """
      {"schema":"entity-runtime-family-catalog/v1","owner":"entity-management-service",
       "catalogEpoch":"1","families":[
        {"familyId":"characters","relation":"characters","component":"",
         "role":"RUNTIME_PAYLOAD","stateClass":"S1","freshDisposition":"PROVISIONAL_NAMESPACE"}],
       "schemaFacts":[{"kind":"TABLE","identity":"characters",
         "definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
      """;

  @Test
  void stableFramingAndDigestVector() {
    var request = new Request(UUID.fromString("11111111-1111-4111-8111-111111111111"), "test");
    assertThat(
            java.util.HexFormat.of()
                .formatHex(EntityRuntimeFamilyCatalogCodec.requestBytes(request)))
        .isEqualTo("45435231111111111111411181111111111111110000000474657374");
    assertThat(EntityRuntimeFamilyCatalog.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void exactRoundtripAndImmutableOriginalBytes() {
    byte[] source = CATALOG.getBytes(StandardCharsets.UTF_8);
    var catalog = EntityRuntimeFamilyCatalog.fromStored(source);
    var read = request();
    var evidence = new Evidence(read, UUID.randomUUID(), catalog);
    assertThat(catalog.catalogEpoch()).isEqualTo(1);
    assertThat(catalog.families().getFirst().logicalFamily()).isEqualTo("character");
    assertThat(
            EntityRuntimeFamilyCatalogCodec.fromRequest(
                EntityRuntimeFamilyCatalogCodec.toRequest(read)))
        .isEqualTo(read);
    assertThat(
            EntityRuntimeFamilyCatalogCodec.fromResponse(
                read, EntityRuntimeFamilyCatalogCodec.toResponse(read, evidence)))
        .isEqualTo(evidence);
    assertThat(
            EntityRuntimeFamilyCatalogCodec.evidenceFromStored(
                    EntityRuntimeFamilyCatalogCodec.evidenceBytes(evidence))
                .digest())
        .isEqualTo(evidence.digest());
    assertThat(catalog.digest()).isEqualTo(EntityRuntimeFamilyCatalog.sha256(source));
    source[0] = 0;
    catalog.exactBytes()[0] = 0;
    assertThat(catalog.exactBytes()).isEqualTo(CATALOG.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> catalog.families().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> catalog.schemaFacts().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void conditionalAndUnresolvedAreExplicitFactsNotSuccessfulRowClassifications() {
    assertThat(
            new Family(
                    "actor_resource_states",
                    "actor_resource_states",
                    "",
                    Role.RUNTIME_PAYLOAD,
                    StateClass.CONDITIONAL_S1_S2,
                    FreshDisposition.BLOCKING_NAMESPACE_REMAP)
                .stateClass())
        .isEqualTo(StateClass.CONDITIONAL_S1_S2);
    assertThat(
            new Family(
                    "npcs.last_defeated_at",
                    "npcs",
                    "last_defeated_at",
                    Role.MIXED,
                    StateClass.UNRESOLVED,
                    FreshDisposition.BLOCKING_MIXED_NPC)
                .stateClass())
        .isEqualTo(StateClass.UNRESOLVED);
    assertThat(
            new Family(
                    "item_instances",
                    "item_instances",
                    "",
                    Role.RUNTIME_PAYLOAD,
                    StateClass.CONDITIONAL_HOLDER,
                    FreshDisposition.BLOCKING_HOLDER_CLOSURE)
                .stateClass())
        .isEqualTo(StateClass.CONDITIONAL_HOLDER);
    assertThatThrownBy(
            () ->
                new Family(
                    "item_instances",
                    "item_instances",
                    "",
                    Role.RUNTIME_PAYLOAD,
                    StateClass.S3,
                    FreshDisposition.BLOCKING_HOLDER_CLOSURE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Family(
                    "item_visible_ref_counters",
                    "item_visible_ref_counters",
                    "",
                    Role.ALLOCATOR,
                    StateClass.S3,
                    FreshDisposition.BLOCKING_ALLOCATOR_CLASS))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownMissingDuplicateNumericAndContradictoryCatalogsRefuse() {
    for (String invalid :
        List.of(
            CATALOG.replace("\"owner\":", "\"extra\":true,\"owner\":"),
            CATALOG.replace(
                "\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"1\",\"catalogEpoch\":\"1\""),
            CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":1"),
            CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"0\""),
            CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"9223372036854775808\""),
            CATALOG.replace("catalog/v1", "catalog/v2"),
            CATALOG.replace("entity-management-service", "world-management-service"),
            CATALOG.replace("\"S1\"", "\"S3\""),
            CATALOG.replace("\"RUNTIME_PAYLOAD\"", "\"UNKNOWN\""),
            CATALOG.replace("\"identity\":\"characters\"", "\"identity\":\"other_table\""),
            CATALOG.replace("\"component\":\"\"", "\"component\":\"body_layout_key\""),
            CATALOG + "{}"))
      assertThatThrownBy(
              () -> EntityRuntimeFamilyCatalog.fromStored(invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void arrayAndByteBoundsDoNotDependOnPayloadAbsence() {
    String family =
        "{\"familyId\":\"characters\",\"relation\":\"characters\",\"component\":\"\","
            + "\"role\":\"RUNTIME_PAYLOAD\",\"stateClass\":\"S1\",\"freshDisposition\":\"PROVISIONAL_NAMESPACE\"}";
    String fact =
        "{\"kind\":\"TYPE\",\"identity\":\"x\",\"definitionDigest\":\"sha256:"
            + "a".repeat(64)
            + "\"}";
    String prefix =
        "{\"schema\":\"entity-runtime-family-catalog/v1\",\"owner\":\"entity-management-service\",\"catalogEpoch\":\"1\",";
    for (String invalid :
        List.of(
            prefix
                + "\"families\":["
                + String.join(",", java.util.Collections.nCopies(513, family))
                + "],\"schemaFacts\":["
                + fact
                + "]}",
            prefix
                + "\"families\":["
                + family
                + "],\"schemaFacts\":["
                + String.join(",", java.util.Collections.nCopies(16385, fact))
                + "]}",
            prefix + "\"families\":[],\"schemaFacts\":[]}"))
      assertThatThrownBy(
              () -> EntityRuntimeFamilyCatalog.fromStored(invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntityRuntimeFamilyCatalog.fromStored(
                    new byte[EntityRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EntityRuntimeFamilyCatalog.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicateUnknownOversizedAndChangedCorrelationFramesRefuse() {
    var read = request();
    var wire = EntityRuntimeFamilyCatalogCodec.toRequest(read);
    assertThatThrownBy(
            () ->
                EntityRuntimeFamilyCatalogCodec.fromRequest(
                    wire.toBuilder()
                        .addCanonicalRequestBytes(wire.getCanonicalRequestBytes(0))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(9, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                EntityRuntimeFamilyCatalogCodec.fromRequest(
                    wire.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntityRuntimeFamilyCatalogCodec.fromRequest(
                    wire.toBuilder()
                        .setCanonicalRequestBytes(0, ByteString.copyFrom(new byte[1025]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var evidence = new Evidence(read, UUID.randomUUID(), catalog());
    var response = EntityRuntimeFamilyCatalogCodec.toResponse(read, evidence);
    for (var invalid :
        List.of(
            response.toBuilder().setUnknownFields(unknown).build(),
            response.toBuilder()
                .addCanonicalResponseBytes(response.getCanonicalResponseBytes(0))
                .build(),
            response.toBuilder()
                .setCanonicalResponseBytes(
                    0,
                    ByteString.copyFrom(
                        new byte[EntityRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES + 1]))
                .build()))
      assertThatThrownBy(() -> EntityRuntimeFamilyCatalogCodec.fromResponse(read, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EntityRuntimeFamilyCatalogCodec.fromResponse(request(), response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntityRuntimeFamilyCatalogCodec.fromResponse(
                    new Request(read.readRequestId(), "other"), response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(new UUID(0, 0), "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(UUID.randomUUID(), "ns/sa/entity-management-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void truncationTrailingDataAndDigestTamperingRefuse() {
    var evidence = new Evidence(request(), UUID.randomUUID(), catalog());
    byte[] bytes = EntityRuntimeFamilyCatalogCodec.evidenceBytes(evidence);
    for (byte[] invalid :
        List.of(Arrays.copyOf(bytes, bytes.length - 1), Arrays.copyOf(bytes, bytes.length + 1)))
      assertThatThrownBy(() -> EntityRuntimeFamilyCatalogCodec.evidenceFromStored(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    int digestOffset =
        4 + 4 + EntityRuntimeFamilyCatalogCodec.requestBytes(evidence.request()).length + 16 + 4;
    bytes[digestOffset + 7] = bytes[digestOffset + 7] == 'a' ? (byte) 'b' : (byte) 'a';
    assertThatThrownBy(() -> EntityRuntimeFamilyCatalogCodec.evidenceFromStored(bytes))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Request request() {
    return new Request(UUID.randomUUID(), "test");
  }

  private static EntityRuntimeFamilyCatalog catalog() {
    return EntityRuntimeFamilyCatalog.fromStored(CATALOG.getBytes(StandardCharsets.UTF_8));
  }
}
