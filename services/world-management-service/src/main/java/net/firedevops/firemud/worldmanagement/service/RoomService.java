package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.RoomDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;

public interface RoomService {
  RoomDto getRoom(Long tenantId, Long roomId);

  RoomSnapshotDto getRoomSnapshot(Long tenantId, Long roomId, String preferredLocaleTag);
}
