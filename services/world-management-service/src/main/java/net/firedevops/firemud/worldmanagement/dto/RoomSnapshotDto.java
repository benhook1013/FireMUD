package net.firedevops.firemud.worldmanagement.dto;

import java.util.List;
import java.util.Map;

public record RoomSnapshotDto(
    Long roomId,
    Long tenantId,
    String roomName,
    String shortDescription,
    String longDescription,
    List<RoomExitSnapshotDto> exits,
    Map<String, String> ambientState,
    List<String> roomFlags) {

  public record RoomExitSnapshotDto(
      Long exitId,
      Long targetRoomId,
      String targetRoomName,
      String label,
      String description,
      Integer cost) {}
}
