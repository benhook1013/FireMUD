package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.InventoryEntryDto;
import net.firedevops.firemud.entity.Character;
import net.firedevops.firemud.entity.InventoryEntry;
import net.firedevops.firemud.entity.InventoryKey;
import net.firedevops.firemud.entity.Item;
import net.firedevops.firemud.mapper.InventoryEntryMapper;
import net.firedevops.firemud.repository.CharacterRepository;
import net.firedevops.firemud.repository.InventoryEntryRepository;
import net.firedevops.firemud.repository.ItemRepository;
import net.firedevops.firemud.service.InventoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {
  private final InventoryEntryRepository repository;
  private final InventoryEntryMapper mapper;
  private final CharacterRepository characterRepository;
  private final ItemRepository itemRepository;

  @Override
  @Timed(value = "inventory.list")
  public List<InventoryEntryDto> listInventory(Long characterId) {
    return repository.findAll().stream()
        .filter(e -> e.getId().getCharacterId().equals(characterId))
        .map(mapper::toDto)
        .toList();
  }

  @Override
  @Transactional
  @Timed(value = "inventory.add")
  public InventoryEntryDto addItem(Long characterId, Long itemId, int quantity) {
    InventoryKey key = new InventoryKey();
    key.setCharacterId(characterId);
    key.setItemId(itemId);
    InventoryEntry entry = repository.findById(key).orElse(null);
    if (entry == null) {
      Character character = characterRepository.findById(characterId).orElseThrow();
      Item item = itemRepository.findById(itemId).orElseThrow();
      entry = new InventoryEntry();
      entry.setId(key);
      entry.setCharacter(character);
      entry.setItem(item);
      entry.setQuantity(quantity);
    } else {
      entry.setQuantity(entry.getQuantity() + quantity);
    }
    entry = repository.save(entry);
    return mapper.toDto(entry);
  }

  @Override
  @Transactional
  @Timed(value = "inventory.remove")
  public void removeItem(Long characterId, Long itemId) {
    InventoryKey key = new InventoryKey();
    key.setCharacterId(characterId);
    key.setItemId(itemId);
    repository.deleteById(key);
  }
}
