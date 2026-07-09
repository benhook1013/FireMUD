package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.RuntimeRoomDto;

public interface RoomService {
  RuntimeRoomDto getRoom(Long tenantId, Long gameInstanceId, Long roomInstanceRowId);

  RoomSnapshotDto getRoomSnapshot(
      Long tenantId, Long gameInstanceId, Long roomInstanceRowId, String preferredLocaleTag);
}
