package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.RoomDto;

public interface RoomService {
  RoomDto getRoom(Long tenantId, Long roomId);
}
