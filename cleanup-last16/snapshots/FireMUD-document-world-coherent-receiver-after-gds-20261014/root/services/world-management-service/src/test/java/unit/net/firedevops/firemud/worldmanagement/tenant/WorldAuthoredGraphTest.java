package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.SnapshotConflictException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorldAuthoredGraphTest {

  @Test
  void encodesTheExistingSixFamilySnapshotShapeAndFieldOrder() {
    WorldAuthoredGraph graph =
        new WorldAuthoredGraph(
            List.of(
                row(
                    "id",
                    9_000_000_042L,
                    "shardId",
                    3,
                    "name",
                    "Northern Reach",
                    "weather",
                    null,
                    "generationSeed",
                    Long.MIN_VALUE,
                    "generatorType",
                    null,
                    "generatorParams",
                    null,
                    "spacingMultiplier",
                    1.0d)),
            List.of(
                row("id", 900_000_000_000_000_042L, "regionId", 9_000_000_042L, "name", "North")),
            List.of(
                row(
                    "id",
                    8_000_000_000_000_000_042L,
                    "zoneId",
                    900_000_000_000_000_042L,
                    "name",
                    "Keep",
                    "description",
                    null,
                    "nameLocalizedVariantsJson",
                    null,
                    "descriptionLocalizedVariantsJson",
                    "{\"en\":\"Keep\"}")),
            List.of(
                row(
                    "id", 7_000_000_000_000_000_042L,
                    "fromRoomId", 8_000_000_000_000_000_042L,
                    "toRoomId", 8_000_000_000_000_000_043L,
                    "direction", "north",
                    "cost", 2)),
            List.of(
                row(
                    "id", 6_000_000_000_000_000_042L,
                    "name", "weather-profile",
                    "scopeType", "REGION_SUBTREE",
                    "scopeId", "9",
                    "value", "{\"rain\":true}")),
            List.of(
                row(
                    "id",
                    5_000_000_000_000_000_042L,
                    "roomId",
                    8_000_000_000_000_000_042L,
                    "entityTemplateType",
                    "NPC",
                    "entityTemplateId",
                    9_000_000_000_000_000_042L,
                    "spawnCount",
                    4,
                    "respawnDelaySeconds",
                    30)));

    assertThat(new String(graph.encode(new ObjectMapper()), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"snapshotSchemaVersion\":1,\"regions\":[{\"id\":9000000042,\"shardId\":3,"
                + "\"name\":\"Northern Reach\",\"weather\":null,\"generationSeed\":-9223372036854775808,"
                + "\"generatorType\":null,\"generatorParams\":null,\"spacingMultiplier\":1.0}],"
                + "\"zones\":[{\"id\":900000000000000042,\"regionId\":9000000042,\"name\":\"North\"}],"
                + "\"rooms\":[{\"id\":8000000000000000042,\"zoneId\":900000000000000042,"
                + "\"name\":\"Keep\",\"description\":null,\"nameLocalizedVariantsJson\":null,"
                + "\"descriptionLocalizedVariantsJson\":\"{\\\"en\\\":\\\"Keep\\\"}\"}],"
                + "\"roomExits\":[{\"id\":7000000000000000042,\"fromRoomId\":8000000000000000042,"
                + "\"toRoomId\":8000000000000000043,\"direction\":\"north\",\"cost\":2}],"
                + "\"generationRules\":[{\"id\":6000000000000000042,\"name\":\"weather-profile\","
                + "\"scopeType\":\"REGION_SUBTREE\",\"scopeId\":\"9\","
                + "\"value\":\"{\\\"rain\\\":true}\"}],"
                + "\"worldEntitySpawnBindings\":[{\"id\":5000000000000000042,"
                + "\"roomId\":8000000000000000042,\"entityTemplateType\":\"NPC\","
                + "\"entityTemplateId\":9000000000000000042,\"spawnCount\":4,"
                + "\"respawnDelaySeconds\":30}]}");
  }

  @Test
  void deeplyCopiesNestedRowsCollectionsAndByteArrays() {
    byte[] suppliedBytes = {1, 2, 3};
    List<Object> suppliedItems = new ArrayList<>(List.of("before", suppliedBytes));
    Map<String, Object> suppliedNested = new LinkedHashMap<>();
    suppliedNested.put("items", suppliedItems);
    Map<String, Object> suppliedRow = new LinkedHashMap<>();
    suppliedRow.put("id", 7L);
    suppliedRow.put("extension", suppliedNested);
    WorldAuthoredGraph graph =
        new WorldAuthoredGraph(
            List.of(), List.of(suppliedRow), List.of(), List.of(), List.of(), List.of());

    suppliedItems.set(0, "after");
    suppliedBytes[0] = 9;
    Map<?, ?> nested = (Map<?, ?>) graph.zones().getFirst().get("extension");
    List<?> items = (List<?>) nested.get("items");
    byte[] exposedBytes = (byte[]) items.get(1);
    exposedBytes[0] = 8;

    assertThat(items.getFirst()).isEqualTo("before");
    Map<?, ?> freshNested = (Map<?, ?>) graph.zones().getFirst().get("extension");
    List<?> freshItems = (List<?>) freshNested.get("items");
    assertThat((byte[]) freshItems.get(1)).containsExactly((byte) 1, (byte) 2, (byte) 3);
    assertThatThrownBy(() -> graph.zones().getFirst().put("newField", true))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(items::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void decodesAndReencodesTheCanonicalSixFamilyGraphWithoutLosingNumericValues() {
    ObjectMapper objectMapper = new ObjectMapper();
    byte[] encoded = completeGraph().encode(objectMapper);

    WorldAuthoredGraph decoded = WorldAuthoredGraph.decode(encoded, objectMapper);

    assertThat(decoded.encode(objectMapper)).containsExactly(encoded);
    assertThat(decoded.regions())
        .extracting(row -> row.get("generationSeed"))
        .containsExactly(Long.MIN_VALUE, Long.MAX_VALUE);
    assertThat(decoded.regions().getFirst().get("id")).isEqualTo(Long.MAX_VALUE);
    assertThat(decoded.regions().getFirst().get("spacingMultiplier"))
        .isInstanceOf(Double.class)
        .isEqualTo(0.125d);
    assertThat(decoded.zones().getFirst().get("id")).isEqualTo(9_007_199_254_740_993L);
    assertThat(decoded.rooms().getFirst().get("description")).isNull();
    assertThat(decoded.generationRules().getFirst().get("scopeId")).isNull();
    assertThat(decoded.spawnBindings().getFirst().get("entityTemplateId"))
        .isEqualTo(9_007_199_254_740_999L);
  }

  @Test
  void rejectsMalformedRootFamiliesAndUnknownOrNoncanonicalSnapshots() {
    ObjectMapper objectMapper = new ObjectMapper();
    String emptyGraph =
        new String(
            new WorldAuthoredGraph(List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
                .encode(objectMapper),
            StandardCharsets.UTF_8);
    List<String> invalidSnapshots =
        List.of(
            emptyGraph.replace("\"snapshotSchemaVersion\":1", "\"snapshotSchemaVersion\":2"),
            emptyGraph.replace(",\"regions\":[]", ""),
            emptyGraph.replace("\"zones\":[]", "\"unknown\":true,\"zones\":[]"),
            emptyGraph.replace("\"regions\":[]", "\"regions\":{}"),
            emptyGraph.replace("\"zones\":[]", "\"regions\":[],\"zones\":[]"),
            " " + emptyGraph,
            emptyGraph + " {}",
            emptyGraph.replace(",\"zones\":[]", ",\"regions\":[],\"zones\":[]"));

    for (String invalidSnapshot : invalidSnapshots) {
      assertThatThrownBy(
              () ->
                  WorldAuthoredGraph.decode(
                      invalidSnapshot.getBytes(StandardCharsets.UTF_8), objectMapper))
          .isInstanceOf(SnapshotConflictException.class);
    }
    assertThatThrownBy(
            () -> WorldAuthoredGraph.decode(new byte[] {'{', '}', (byte) 0xc3, 0x28}, objectMapper))
        .isInstanceOf(SnapshotConflictException.class);
  }

  @Test
  void rejectsMalformedRowsDuplicateFieldsAndChangedJsonTypes() {
    ObjectMapper objectMapper = new ObjectMapper();
    String graph = new String(completeGraph().encode(objectMapper), StandardCharsets.UTF_8);
    List<String> invalidSnapshots =
        List.of(
            graph.replace("\"shardId\":4", "\"shardId\":\"4\""),
            graph.replace("\"id\":9223372036854775807,", ""),
            graph.replace(
                "\"id\":9223372036854775807,",
                "\"id\":9223372036854775807,\"id\":9223372036854775807,"),
            graph.replace(
                "\"id\":9223372036854775807,", "\"id\":9223372036854775807,\"unexpected\":0,"),
            graph.replace("\"spacingMultiplier\":0.125", "\"spacingMultiplier\":\"0.125\""),
            graph.replace("\"spacingMultiplier\":0.125", "\"spacingMultiplier\":0.1250"),
            graph.replace("\"spacingMultiplier\":0.125", "\"spacingMultiplier\":1e9999"),
            graph.replace(
                "\"scopeType\":null,\"scopeId\":null",
                "\"scopeType\":\"REGION_SUBTREE\",\"scopeId\":\"009\""),
            graph.replace("\"shardId\":4,\"name\":\"edge\"", "\"name\":\"edge\",\"shardId\":4"));

    for (String invalidSnapshot : invalidSnapshots) {
      assertThatThrownBy(
              () ->
                  WorldAuthoredGraph.decode(
                      invalidSnapshot.getBytes(StandardCharsets.UTF_8), objectMapper))
          .isInstanceOf(SnapshotConflictException.class);
    }
  }

  @Test
  void decodedGraphDoesNotRetainMutableInputBytesAndExposesImmutableRows() {
    ObjectMapper objectMapper = new ObjectMapper();
    byte[] input = completeGraph().encode(objectMapper);
    byte[] canonical = input.clone();

    WorldAuthoredGraph decoded = WorldAuthoredGraph.decode(input, objectMapper);
    input[0] = 'x';

    assertThat(decoded.encode(objectMapper)).containsExactly(canonical);
    assertThatThrownBy(() -> decoded.regions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> decoded.regions().getFirst().put("name", "changed"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static WorldAuthoredGraph completeGraph() {
    return new WorldAuthoredGraph(
        List.of(
            row(
                "id",
                Long.MAX_VALUE,
                "shardId",
                4,
                "name",
                "edge",
                "weather",
                null,
                "generationSeed",
                Long.MIN_VALUE,
                "generatorType",
                null,
                "generatorParams",
                null,
                "spacingMultiplier",
                0.125d),
            row(
                "id",
                Long.MAX_VALUE - 1,
                "shardId",
                0,
                "name",
                "edge-two",
                "weather",
                "clear",
                "generationSeed",
                Long.MAX_VALUE,
                "generatorType",
                "grid",
                "generatorParams",
                "{}",
                "spacingMultiplier",
                3.75d)),
        List.of(
            row("id", 9_007_199_254_740_993L, "regionId", Long.MAX_VALUE, "name", "large-zone")),
        List.of(
            row(
                "id",
                9_007_199_254_740_995L,
                "zoneId",
                9_007_199_254_740_993L,
                "name",
                "large-room",
                "description",
                null,
                "nameLocalizedVariantsJson",
                "{}",
                "descriptionLocalizedVariantsJson",
                null)),
        List.of(
            row(
                "id",
                Long.MAX_VALUE - 2,
                "fromRoomId",
                9_007_199_254_740_995L,
                "toRoomId",
                9_007_199_254_740_995L,
                "direction",
                "east",
                "cost",
                1)),
        List.of(
            row(
                "id", Long.MAX_VALUE - 3,
                "name", "unscoped-rule",
                "scopeType", null,
                "scopeId", null,
                "value", null)),
        List.of(
            row(
                "id",
                Long.MAX_VALUE - 4,
                "roomId",
                9_007_199_254_740_995L,
                "entityTemplateType",
                "ITEM",
                "entityTemplateId",
                9_007_199_254_740_999L,
                "spawnCount",
                1,
                "respawnDelaySeconds",
                0)));
  }

  private static LinkedHashMap<String, Object> row(Object... values) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      row.put((String) values[index], values[index + 1]);
    }
    return row;
  }
}
