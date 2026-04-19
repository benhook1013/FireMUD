package net.firedevops.firemud.worldmanagement.dto;

import java.util.List;
import java.util.Map;

public record RoomSnapshotDto(
    Long roomId,
    Long tenantId,
    Long gameInstanceId,
    String roomName,
    String shortDescription,
    String longDescription,
    List<RoomExitSnapshotDto> exits,
    Map<String, String> ambientState,
    List<String> roomFlags) {

  public RoomSnapshotDto {
    exits = exits == null ? List.of() : List.copyOf(exits);
    ambientState = ambientState == null ? Map.of() : Map.copyOf(ambientState);
    roomFlags = roomFlags == null ? List.of() : List.copyOf(roomFlags);
  }

  public record RoomExitSnapshotDto(
      Long exitId,
      Long targetRoomId,
      String targetRoomName,
      String direction,
      String label,
      String description,
      Integer cost) {}
}
