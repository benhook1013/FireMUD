package net.firedevops.firemud.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.config.LookProperties;
import net.firedevops.firemud.dto.RoomEntityDto;
import net.firedevops.firemud.service.RoomEntityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
public class RoomEntityServiceImpl implements RoomEntityService {
  private final LookProperties lookProperties;

  public RoomEntityServiceImpl(LookProperties lookProperties) {
    this.lookProperties = lookProperties;
  }

  @Override
  public List<RoomEntityDto> listEntities(String tenantId, String roomId) {
    Map<String, LookProperties.LookRoom> rooms = lookProperties.getRooms();
    LookProperties.LookRoom room = rooms.getOrDefault(regionKey(tenantId, roomId), null);
    if (room == null) {
      return Collections.emptyList();
    }
    return room.getEntities().stream()
        .map(
            entity ->
                new RoomEntityDto(
                    entity.getEntityId(),
                    entity.getDisplayName(),
                    entity.getEntityType(),
                    entity.getRole(),
                    entity.getStateFlags(),
                    entity.getVisionPriority(),
                    entity.getReloadHint(),
                    entity.isVisible()))
        .toList();
  }

  private String regionKey(String tenantId, String roomId) {
    return tenantId + ":" + roomId;
  }
}
