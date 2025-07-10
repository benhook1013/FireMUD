package net.firedevops.firemud.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.RoomDto;
import net.firedevops.firemud.mapper.RoomMapper;
import net.firedevops.firemud.repository.RoomRepository;
import net.firedevops.firemud.service.RoomService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {
  private final RoomRepository roomRepository;
  private final RoomMapper roomMapper;

  @Override
  public RoomDto getRoom(Long tenantId, Long roomId) {
    return roomRepository
        .findById(roomId)
        .filter(r -> r.getTenantId().equals(tenantId))
        .map(roomMapper::toDto)
        .orElseThrow(() -> new IllegalArgumentException("Room not found"));
  }
}
