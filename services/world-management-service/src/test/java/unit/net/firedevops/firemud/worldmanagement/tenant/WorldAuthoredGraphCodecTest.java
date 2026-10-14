package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class WorldAuthoredGraphCodecTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String VERSION = "22222222-2222-2222-2222-222222222222";
  private static final String TEMPLATE = "33333333-3333-3333-3333-333333333333";
  private static final String CANONICAL_REFERENCE =
      "{\"kind\":\"CANONICAL_UUID\",\"tenantId\":\""
          + TENANT
          + "\",\"versionId\":\""
          + VERSION
          + "\",\"templateId\":\""
          + TEMPLATE
          + "\"}";

  @Test
  void freshSnapshotsUseSchemaTwoAndRetainTheCompleteCanonicalEntityTuple() {
    LinkedHashMap<String, Object> reference = new LinkedHashMap<>();
    reference.put("kind", "CANONICAL_UUID");
    reference.put("tenantId", TENANT);
    reference.put("versionId", VERSION);
    reference.put("templateId", TEMPLATE);
    byte[] encoded = freshGraph(reference).encode(MAPPER);

    assertThat(encoded).isEqualTo(snapshot(2, "\"entityReference\":" + CANONICAL_REFERENCE));
    WorldAuthoredGraph decoded = WorldAuthoredGraph.decode(encoded, MAPPER);
    assertThat(decoded.spawnBindings().getFirst().get("entityReference")).isEqualTo(reference);
    assertThat(decoded.encode(MAPPER)).isEqualTo(encoded);
    assertThat(decoded.withRegions(List.of()).encode(MAPPER)).isEqualTo(encoded);
  }

  @Test
  void freshSnapshotsTagRetainedPrivateKeysWithoutLosingLongPrecision() {
    LinkedHashMap<String, Object> reference = new LinkedHashMap<>();
    reference.put("kind", "RETAINED_PRIVATE_KEY");
    reference.put("templateId", Long.toString(Long.MAX_VALUE));
    byte[] encoded = freshGraph(reference).encode(MAPPER);

    assertThat(encoded)
        .isEqualTo(
            snapshot(
                2,
                "\"entityReference\":{\"kind\":\"RETAINED_PRIVATE_KEY\","
                    + "\"templateId\":\"9223372036854775807\"}"));
    assertThat(WorldAuthoredGraph.decode(encoded, MAPPER).encode(MAPPER)).isEqualTo(encoded);
  }

  @Test
  void retainedSchemaOneNumericSnapshotsReencodeWithExactlyTheirOriginalBytes() {
    byte[] retained = snapshot(1, "\"entityTemplateId\":9223372036854775807");
    WorldAuthoredGraph decoded = WorldAuthoredGraph.decode(retained, MAPPER);

    assertThat(decoded.spawnBindings().getFirst().get("entityTemplateId"))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(decoded.encode(MAPPER)).isEqualTo(retained);
    assertThat(decoded.withRegions(List.of()).encode(MAPPER)).isEqualTo(retained);
  }

  @ParameterizedTest
  @MethodSource("invalidReferences")
  void schemaTwoRejectsPartialMixedUnknownAndNoncanonicalReferences(String reference) {
    assertThatThrownBy(
            () ->
                WorldAuthoredGraph.decode(snapshot(2, "\"entityReference\":" + reference), MAPPER))
        .isInstanceOf(WorldAuthoredGraphSnapshotRepository.SnapshotConflictException.class);
  }

  private static Stream<String> invalidReferences() {
    return Stream.of(
        "null",
        "{}",
        "{\"kind\":\"UNKNOWN\",\"templateId\":\"1\"}",
        "{\"kind\":\"RETAINED_PRIVATE_KEY\",\"templateId\":1}",
        "{\"kind\":\"RETAINED_PRIVATE_KEY\",\"templateId\":\"0\"}",
        "{\"kind\":\"RETAINED_PRIVATE_KEY\",\"templateId\":\"-1\"}",
        "{\"kind\":\"RETAINED_PRIVATE_KEY\",\"templateId\":\"01\"}",
        "{\"kind\":\"RETAINED_PRIVATE_KEY\",\"templateId\":\"9223372036854775808\"}",
        "{\"kind\":\"RETAINED_PRIVATE_KEY\",\"templateId\":\"1\",\"tenantId\":\"" + TENANT + "\"}",
        "{\"kind\":\"CANONICAL_UUID\",\"templateId\":\"" + TEMPLATE + "\"}",
        CANONICAL_REFERENCE.replace("\"tenantId\":\"" + TENANT + "\"", "\"tenantId\":null"),
        CANONICAL_REFERENCE.replace(VERSION, "00000000-0000-0000-0000-000000000000"),
        CANONICAL_REFERENCE.replace(TEMPLATE, "3-3-3-3-3"),
        CANONICAL_REFERENCE.replace(TENANT, "not-a-uuid"),
        CANONICAL_REFERENCE.replace(TENANT, "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
        CANONICAL_REFERENCE.replace("}", ",\"entityTemplateId\":1}"));
  }

  @Test
  void snapshotVersionsDoNotAcceptEachOthersSpawnRepresentationOrDigestSchemaVersion() {
    assertThatThrownBy(
            () -> WorldAuthoredGraph.decode(snapshot(2, "\"entityTemplateId\":1"), MAPPER))
        .isInstanceOf(WorldAuthoredGraphSnapshotRepository.SnapshotConflictException.class);
    assertThatThrownBy(
            () ->
                WorldAuthoredGraph.decode(
                    snapshot(1, "\"entityReference\":" + CANONICAL_REFERENCE), MAPPER))
        .isInstanceOf(WorldAuthoredGraphSnapshotRepository.SnapshotConflictException.class);
    assertThatThrownBy(
            () ->
                WorldAuthoredGraph.decode(
                    snapshot(3, "\"entityReference\":" + CANONICAL_REFERENCE), MAPPER))
        .isInstanceOf(WorldAuthoredGraphSnapshotRepository.SnapshotConflictException.class);
  }

  private static WorldAuthoredGraph freshGraph(Map<String, Object> reference) {
    LinkedHashMap<String, Object> binding = new LinkedHashMap<>();
    binding.put("id", 7L);
    binding.put("roomId", 8L);
    binding.put("entityTemplateType", "NPC");
    binding.put("entityReference", reference);
    binding.put("spawnCount", 2);
    binding.put("respawnDelaySeconds", 0);
    return new WorldAuthoredGraph(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(binding));
  }

  private static byte[] snapshot(int version, String referenceField) {
    return ("{\"snapshotSchemaVersion\":"
            + version
            + ",\"regions\":[],\"zones\":[],\"rooms\":[],\"roomExits\":[],\"generationRules\":[],"
            + "\"worldEntitySpawnBindings\":[{\"id\":7,\"roomId\":8,\"entityTemplateType\":\"NPC\","
            + referenceField
            + ",\"spawnCount\":2,\"respawnDelaySeconds\":0}]}")
        .getBytes(StandardCharsets.UTF_8);
  }
}
