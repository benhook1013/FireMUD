package net.firedevops.firemud.worldmanagement.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoomSnapshotDtoTest {

  @Test
  void constructorCopiesMutableCollections() {
    List<RoomSnapshotDto.RoomExitSnapshotDto> exits = new ArrayList<>();
    RoomSnapshotDto.RoomExitSnapshotDto exit =
        new RoomSnapshotDto.RoomExitSnapshotDto(1L, 2L, "Hall", "NORTH", "north", "Leads north", 1);
    exits.add(exit);
    Map<String, String> ambientState = new HashMap<>();
    ambientState.put("lighting", "dim");
    List<String> roomFlags = new ArrayList<>();
    roomFlags.add("indoors");

    RoomSnapshotDto snapshot =
        new RoomSnapshotDto(
            10L,
            20L,
            30L,
            "Antechamber",
            "Short desc",
            "Long desc",
            exits,
            ambientState,
            roomFlags);

    exits.clear();
    ambientState.put("weather", "dry");
    roomFlags.add("quiet");

    assertEquals(List.of(exit), snapshot.exits());
    assertEquals(Map.of("lighting", "dim"), snapshot.ambientState());
    assertEquals(List.of("indoors"), snapshot.roomFlags());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.exits().add(exit));
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.ambientState().put("weather", "dry"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.roomFlags().add("quiet"));
  }
}
