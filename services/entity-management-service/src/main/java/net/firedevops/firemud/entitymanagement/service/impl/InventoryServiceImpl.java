package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.mapper.InventoryEntryMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
  public Page<InventoryEntryDto> listInventory(Long characterId, Pageable pageable) {
    return repository.findByIdCharacterId(characterId, pageable).map(mapper::toDto);
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
