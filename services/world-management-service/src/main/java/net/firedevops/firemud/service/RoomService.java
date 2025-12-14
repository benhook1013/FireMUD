package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.RoomDto;
import net.firedevops.firemud.dto.RoomSnapshotDto;

public interface RoomService {
  RoomDto getRoom(Long tenantId, Long roomId);

  RoomSnapshotDto getRoomSnapshot(Long tenantId, Long roomId);
}
