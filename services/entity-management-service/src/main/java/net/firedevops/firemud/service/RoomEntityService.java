package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.RoomEntityDto;

public interface RoomEntityService {
  List<RoomEntityDto> listEntities(String tenantId, String roomId);
}
