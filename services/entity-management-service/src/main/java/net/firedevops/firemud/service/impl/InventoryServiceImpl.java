package net.firedevops.firemud.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.InventoryEntryDto;
import net.firedevops.firemud.mapper.InventoryEntryMapper;
import net.firedevops.firemud.repository.InventoryEntryRepository;
import net.firedevops.firemud.service.InventoryService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {
  private final InventoryEntryRepository repository;
  private final InventoryEntryMapper mapper;

  @Override
  public List<InventoryEntryDto> listInventory(Long characterId) {
    return repository.findAll().stream()
        .filter(e -> e.getId().getCharacterId().equals(characterId))
        .map(mapper::toDto)
        .toList();
  }
}
