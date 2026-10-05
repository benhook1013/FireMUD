package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraph;
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

  private static LinkedHashMap<String, Object> row(Object... values) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      row.put((String) values[index], values[index + 1]);
    }
    return row;
  }
}
