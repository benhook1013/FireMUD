package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.EquipmentSlotDefinition;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.BodyLayoutSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.EquipmentSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.service.EquipmentSlotIncompatibleException;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class EquipmentServiceImplTest {
  private static final PlayableStateScope PLAYABLE_STATE_SCOPE =
      PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;

  @Test
  void listEquipmentReturnsInstanceBackedDtos() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentSlotDefinitionRepository slotRepo =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotRepo =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            charRepo,
            itemRepo,
            new ItemTransferSupport(),
            Mockito.mock(ItemTransferAuditWriter.class),
            new ContainerHolderSyncSupport(containerInstanceRepo),
            slotRepo,
            bodyLayoutSlotRepo);

    Character character = character(1L, 11L);
    Item item = item(2L, 11L, "Leather Cap", true, "HEAD");
    ItemInstance equipped = itemInstance(601L, 11L, character, item, "HEAD");
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(88L);
    containerInstance.setItemInstance(equipped);
    containerInstance.setItem(item);

    when(charRepo.findByIdAndTenantId(1L, 11L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(equipped)));
    when(containerInstanceRepo.findByItemInstance_Id(601L))
        .thenReturn(Optional.of(containerInstance));

    var result = service.listEquipment(11L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals("HEAD", result.getContent().get(0).slot());
    assertEquals(601L, result.getContent().get(0).itemInstanceId());
    assertEquals(88L, result.getContent().get(0).containerInstanceId());
  }

  @Test
  void wearItemMovesCarriedInstanceIntoSlot() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemTransferAuditWriter itemTransferAuditWriter = Mockito.mock(ItemTransferAuditWriter.class);
    EquipmentSlotDefinitionRepository slotRepo =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotRepo =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            charRepo,
            itemRepo,
            new ItemTransferSupport(),
            itemTransferAuditWriter,
            new ContainerHolderSyncSupport(containerInstanceRepo),
            slotRepo,
            bodyLayoutSlotRepo);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Leather Cap", true, "head");
    ItemInstance carried = itemInstance(701L, 1L, character, item, null);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(77L);
    containerInstance.setItemInstance(carried);
    containerInstance.setItem(item);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                1L, 1L, "HEAD"))
        .thenReturn(false);
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(carried));
    when(itemInstanceRepo.save(any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(containerInstanceRepo.findByItemInstance_Id(701L))
        .thenReturn(Optional.of(containerInstance));

    var equipped = service.wearItem(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, 2L, null);

    assertEquals("HEAD", carried.getEquipmentSlot());
    assertEquals("HEAD", equipped.slot());
    assertEquals(77L, equipped.containerInstanceId());
    ItemTransferSupport transferSupport = new ItemTransferSupport();
    verify(itemTransferAuditWriter)
        .recordInstanceTransfer(
            carried,
            transferSupport.inventory(1L, 1L),
            transferSupport.equipment(character, "HEAD"),
            transferSupport.audit("WEAR", 1L));
  }

  @Test
  void wearItemRejectsStaleSourceMismatch() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemTransferAuditWriter itemTransferAuditWriter = Mockito.mock(ItemTransferAuditWriter.class);
    EquipmentSlotDefinitionRepository slotRepo =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotRepo =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            charRepo,
            itemRepo,
            new ItemTransferSupport(),
            itemTransferAuditWriter,
            new ContainerHolderSyncSupport(containerInstanceRepo),
            slotRepo,
            bodyLayoutSlotRepo);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Leather Cap", true, "head");
    ItemInstance stale = itemInstance(701L, 1L, character, item, null);
    stale.setGameInstanceId("GI-1");
    stale.setRoomInstanceId("R-1");

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                1L, 1L, "HEAD"))
        .thenReturn(false);
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(stale));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.wearItem(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, 2L, null));
    verify(itemTransferAuditWriter, never()).recordInstanceTransfer(any(), any(), any(), any());
  }

  @Test
  void removeWornItemReturnsRemovedSlotWhileMovingItemIntoInventory() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemTransferAuditWriter itemTransferAuditWriter = Mockito.mock(ItemTransferAuditWriter.class);
    EquipmentSlotDefinitionRepository slotRepo =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotRepo =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            charRepo,
            itemRepo,
            new ItemTransferSupport(),
            itemTransferAuditWriter,
            new ContainerHolderSyncSupport(containerInstanceRepo),
            slotRepo,
            bodyLayoutSlotRepo);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Leather Cap", true, "HEAD");
    ItemInstance equipped = itemInstance(701L, 1L, character, item, "HEAD");
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(77L);
    containerInstance.setItemInstance(equipped);
    containerInstance.setItem(item);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                1L, 1L, "HEAD"))
        .thenReturn(Optional.of(equipped));
    when(itemInstanceRepo.save(any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(containerInstanceRepo.findByItemInstance_Id(701L))
        .thenReturn(Optional.of(containerInstance));

    var removed = service.removeWornItem(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "HEAD");

    assertEquals(77L, removed.containerInstanceId());
    assertEquals("HEAD", removed.slot());
    assertEquals("Leather Cap", removed.itemName());
    assertNull(equipped.getEquipmentSlot());
    ItemTransferSupport transferSupport = new ItemTransferSupport();
    verify(itemTransferAuditWriter)
        .recordInstanceTransfer(
            equipped,
            transferSupport.equipment(1L, 1L, "HEAD"),
            transferSupport.inventory(character),
            transferSupport.audit("REMOVE", 1L));
  }

  @Test
  void removeWornItemRejectsStaleEquippedSourceMismatch() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentSlotDefinitionRepository slotRepo =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotRepo =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            charRepo,
            itemRepo,
            new ItemTransferSupport(),
            Mockito.mock(ItemTransferAuditWriter.class),
            new ContainerHolderSyncSupport(containerInstanceRepo),
            slotRepo,
            bodyLayoutSlotRepo);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Leather Cap", true, "HEAD");
    ItemInstance stale = itemInstance(701L, 1L, character, item, "HEAD");
    stale.setContainerInstance(new ContainerInstance());

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                1L, 1L, "HEAD"))
        .thenReturn(Optional.of(stale));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.removeWornItem(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "HEAD"));
  }

  @Test
  void wearItemRequiresAuthoredSlotWhenEquipmentSchemaExists() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentSlotDefinitionRepository slotRepo =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotRepo =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            charRepo,
            itemRepo,
            new ItemTransferSupport(),
            Mockito.mock(ItemTransferAuditWriter.class),
            new ContainerHolderSyncSupport(containerInstanceRepo),
            slotRepo,
            bodyLayoutSlotRepo);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Strange Helm", false, "horn");
    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(slotRepo.existsByTenantIdAndVersionId(1L, 1L)).thenReturn(true);
    when(slotRepo.findByTenantIdAndVersionIdAndSlotKey(1L, 1L, "HORN"))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.wearItem(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, 2L, null));
    assertEquals("Equipment slot is not defined", ex.getMessage());
    verify(itemInstanceRepo, never())
        .existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
            any(), any(), any());
  }

  @Test
  void wearItemRequiresBodyLayoutToSupportAuthoredSlot() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentSlotDefinitionRepository slotRepo =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotRepo =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            charRepo,
            itemRepo,
            new ItemTransferSupport(),
            Mockito.mock(ItemTransferAuditWriter.class),
            new ContainerHolderSyncSupport(containerInstanceRepo),
            slotRepo,
            bodyLayoutSlotRepo);

    Character character = character(1L, 1L);
    character.setBodyLayoutKey("SERPENT");
    Item item = item(2L, 1L, "Leather Boots", false, "feet");
    EquipmentSlotDefinition slot = slot("FEET", "FEET");
    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(slotRepo.existsByTenantIdAndVersionId(1L, 1L)).thenReturn(true);
    when(slotRepo.findByTenantIdAndVersionIdAndSlotKey(1L, 1L, "FEET"))
        .thenReturn(Optional.of(slot));
    when(bodyLayoutSlotRepo.existsByTenantIdAndVersionIdAndBodyLayoutKey(1L, 1L, "SERPENT"))
        .thenReturn(true);
    when(bodyLayoutSlotRepo.existsByTenantIdAndVersionIdAndBodyLayoutKeyAndSlotKey(
            1L, 1L, "SERPENT", "FEET"))
        .thenReturn(false);

    EquipmentSlotIncompatibleException ex =
        assertThrows(
            EquipmentSlotIncompatibleException.class,
            () -> service.wearItem(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, 2L, null));
    assertEquals("Leather Boots cannot be worn by this body layout.", ex.getMessage());
    verify(itemInstanceRepo, never())
        .existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
            any(), any(), any());
  }

  @Test
  void wearItemRequiresItemSlotGroupToMatchAuthoredSlotGroup() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentSlotDefinitionRepository slotRepo =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotRepo =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            charRepo,
            itemRepo,
            new ItemTransferSupport(),
            Mockito.mock(ItemTransferAuditWriter.class),
            new ContainerHolderSyncSupport(containerInstanceRepo),
            slotRepo,
            bodyLayoutSlotRepo);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Wing Harness", false, "back");
    item.setEquipmentSlotGroupKey("WINGS");
    EquipmentSlotDefinition slot = slot("BACK", "TORSO");
    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(slotRepo.existsByTenantIdAndVersionId(1L, 1L)).thenReturn(true);
    when(slotRepo.findByTenantIdAndVersionIdAndSlotKey(1L, 1L, "BACK"))
        .thenReturn(Optional.of(slot));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.wearItem(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, 2L, null));
    verify(itemInstanceRepo, never())
        .existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
            any(), any(), any());
  }

  private static Character character(Long id, Long tenantId) {
    Character character = new Character();
    character.setId(id);
    character.setTenantId(tenantId);
    character.setPlayableStateKey("shared-live");
    return character;
  }

  private static Item item(
      Long id, Long tenantId, String name, boolean container, String equipmentSlot) {
    Item item = new Item();
    item.setId(id);
    item.setTenantId(tenantId);
    item.setName(name);
    item.setDescription(name + " desc");
    item.setContainer(container);
    item.setEquipmentSlot(equipmentSlot);
    return item;
  }

  private static EquipmentSlotDefinition slot(String slotKey, String slotGroupKey) {
    EquipmentSlotDefinition slot = new EquipmentSlotDefinition();
    slot.setTenantId(1L);
    slot.setVersionId(1L);
    slot.setSlotKey(slotKey);
    slot.setDisplayName(slotKey);
    slot.setSlotGroupKey(slotGroupKey);
    return slot;
  }

  private static ItemInstance itemInstance(
      Long id, Long tenantId, Character character, Item item, String equipmentSlot) {
    ItemInstance instance = new ItemInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setCharacter(character);
    instance.setItem(item);
    instance.setEquipmentSlot(equipmentSlot);
    return instance;
  }
}
