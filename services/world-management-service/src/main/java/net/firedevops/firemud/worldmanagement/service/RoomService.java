package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.RoomDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;

public interface RoomService {
  RoomDto getRoom(Long tenantId, Long gameInstanceId, Long roomId);

  RoomSnapshotDto getRoomSnapshot(
      Long tenantId, Long gameInstanceId, Long roomId, String preferredLocaleTag);
}
