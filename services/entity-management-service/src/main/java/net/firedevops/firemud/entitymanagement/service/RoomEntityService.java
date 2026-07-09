package net.firedevops.firemud.entitymanagement.service;

import java.util.List;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;

public interface RoomEntityService {
  List<RoomEntityDto> listEntities(long tenantId, String gameInstanceId, String roomId);
}
