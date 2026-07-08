package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemStack;
import net.firedevops.firemud.entitymanagement.entity.ItemStackCompatibilityMode;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemStackRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class InventoryServiceImplTest {
  private static final PlayableStateScope PLAYABLE_STATE_SCOPE =
      PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;

  @Test
  void listInventoryReturnsInstanceBackedDtos() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 11L);
    Item item = item(2L, 11L, "Torch", true, null, false);
    ItemInstance instance = itemInstance(501L, 11L, character, item, null, null, null);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(99L);
    containerInstance.setTenantId(11L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(item);
    containerInstance.setItemInstance(instance);

    when(characterRepo.findByIdAndTenantId(1L, 11L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(instance)));
    when(itemStackRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullOrderByIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of()));
    when(containerInstanceRepo.findByItemInstance_Id(501L))
        .thenReturn(Optional.of(containerInstance));

    var result = service.listInventory(11L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals(501L, result.getContent().get(0).itemInstanceId());
    assertEquals(99L, result.getContent().get(0).containerInstanceId());
    assertEquals(1, result.getContent().get(0).quantity());
  }

  @Test
  void listInventoryKeepsDuplicateNonStackableItemsSeparate() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 11L);
    Item item = item(2L, 11L, "Torch", false, null, false);
    ItemInstance first = itemInstance(501L, 11L, character, item, null, null, null);
    first.setVisibleRef("torch1");
    ItemInstance second = itemInstance(502L, 11L, character, item, null, null, null);
    second.setVisibleRef("torch2");

    when(characterRepo.findByIdAndTenantId(1L, 11L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(first, second)));
    when(itemStackRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullOrderByIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of()));

    var result = service.listInventory(11L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, Pageable.unpaged());

    assertEquals(2, result.getTotalElements());
    assertEquals(501L, result.getContent().get(0).itemInstanceId());
    assertEquals("torch1", result.getContent().get(0).visibleRef());
    assertEquals(502L, result.getContent().get(1).itemInstanceId());
    assertEquals("torch2", result.getContent().get(1).visibleRef());
  }

  @Test
  void listInventoryReturnsStackBackedDtosForStackableItems() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 11L);
    Item arrows = item(2L, 11L, "Arrows", false, null, true);
    ItemStack stack = stack(701L, 11L, character, arrows, 4);
    stack.setStackFamilyKey("ammo/iron");

    when(characterRepo.findByIdAndTenantId(1L, 11L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of()));
    when(itemStackRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullOrderByIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(stack)));

    var result = service.listInventory(11L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals(4, result.getContent().get(0).quantity());
    assertNull(result.getContent().get(0).itemInstanceId());
    assertEquals("ammo/iron", result.getContent().get(0).visibleRef());
  }

  @Test
  void listRoomGroundItemsReturnsOnlyCurrentRoomGroundDtos() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Item torch = item(2L, 11L, "Torch", false, null, false);
    Item arrows = item(3L, 11L, "Arrows", false, null, true);
    arrows.setStackVariantKey("ammo/iron");
    ItemInstance roomTorch = itemInstance(501L, 11L, null, torch, null, "GI-1", "R-1");
    ItemStack roomArrows = stack(701L, 11L, null, arrows, 12);
    roomArrows.setGameInstanceId("GI-1");
    roomArrows.setRoomInstanceId("R-1");
    roomArrows.setStackFamilyKey("ammo/iron");

    when(itemInstanceRepo
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
                11L, "GI-1", "R-1", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(roomTorch)));
    when(itemStackRepo
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullOrderByIdAsc(
                11L, "GI-1", "R-1", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(roomArrows)));

    var result = service.listRoomGroundItems(11L, "GI-1", "R-1", Pageable.unpaged());

    assertEquals(2, result.getTotalElements());
    assertEquals(3L, result.getContent().get(0).itemId());
    assertEquals(12, result.getContent().get(0).quantity());
    assertEquals("ammo/iron", result.getContent().get(0).visibleRef());
    assertEquals(501L, result.getContent().get(1).itemInstanceId());
    assertEquals("GI-1", result.getContent().get(1).gameInstanceId());
    assertEquals("R-1", result.getContent().get(1).roomInstanceId());
    verify(characterRepo, never()).findByIdAndTenantId(anyLong(), anyLong());
  }

  @Test
  void listRoomGroundItemsRejectsLegacyRuntimeRoomIdsBeforeRepoLookup() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.listRoomGroundItems(11L, "GI-1", "room-1", Pageable.unpaged()));

    assertEquals("roomInstanceId must be a runtime room id like R-1021", ex.getMessage());
    verify(itemInstanceRepo, never())
        .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
            anyLong(), any(), any(), any());
    verify(itemStackRepo, never())
        .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullOrderByIdAsc(
            anyLong(), any(), any(), any());
  }

  @Test
  void addItemRejectsCrossTenantOwnership() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character(1L, 1L)));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.addItem(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, 2L, 1));
  }

  @Test
  void dropItemMovesSelectedInstanceToRoom() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null, false);
    ItemInstance first = itemInstance(41L, 1L, character, item, null, null, null);
    ItemInstance second = itemInstance(42L, 1L, character, item, null, null, null);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(first, second));
    when(itemInstanceRepo.save(Mockito.any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var dropped =
        service.dropItemToRoom(
            1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 1);

    assertEquals("GI-1", first.getGameInstanceId());
    assertEquals("R-1", first.getRoomInstanceId());
    assertEquals(41L, dropped.itemInstanceId());
    verify(itemInstanceRepo).save(first);
  }

  @Test
  void dropItemRejectsLegacyRuntimeRoomIdsBeforeStateLookup() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.dropItemToRoom(
                    1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "room-1", 2L, null, null, null, 1));

    assertEquals("roomInstanceId must be a runtime room id like R-1021", ex.getMessage());
    verify(characterRepo, never()).findByIdAndTenantId(anyLong(), anyLong());
    verify(itemRepo, never()).findByIdAndTenantId(anyLong(), anyLong());
  }

  @Test
  void dropItemWritesInstanceTransferAudit() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    ItemTransferAuditWriter itemTransferAuditWriter = Mockito.mock(ItemTransferAuditWriter.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator,
            itemTransferAuditWriter);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null, false);
    ItemInstance first = itemInstance(41L, 1L, character, item, null, null, null);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(first));
    when(itemInstanceRepo.save(any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.dropItemToRoom(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 1);

    ItemTransferSupport transferSupport = new ItemTransferSupport();
    verify(itemTransferAuditWriter)
        .recordInstanceTransfer(
            first,
            transferSupport.inventory(1L, 1L),
            transferSupport.room("GI-1", "R-1"),
            transferSupport.audit("DROP", 1L));
  }

  @Test
  void dropItemCarriesEffectIdIntoAuditContext() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    ItemTransferAuditWriter itemTransferAuditWriter = Mockito.mock(ItemTransferAuditWriter.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator,
            itemTransferAuditWriter);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null, false);
    ItemInstance first = itemInstance(41L, 1L, character, item, null, null, null);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(first));
    when(itemInstanceRepo.save(any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.dropItemToRoom(
        1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 1, "effect-1");

    ItemTransferSupport transferSupport = new ItemTransferSupport();
    verify(itemTransferAuditWriter)
        .recordInstanceTransfer(
            first,
            transferSupport.inventory(1L, 1L),
            transferSupport.room("GI-1", "R-1"),
            transferSupport.audit("DROP", 1L, "effect-1"));
  }

  @Test
  void dropItemMovesStackQuantityToRoom() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 1L);
    Item arrows = item(2L, 1L, "Arrows", false, null, true);
    arrows.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_AND_FAMILY);
    arrows.setStackVariantKey("ammo/iron");
    ItemStack inventoryStack = stack(41L, 1L, character, arrows, 3);
    inventoryStack.setStackFamilyKey("ammo/iron");
    inventoryStack.setCompatibilityFingerprint("item-definition:2:family:ammo/iron");

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(arrows));
    when(itemStackRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(inventoryStack));
    when(itemStackRepo
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
                1L, "GI-1", "R-1", 2L, "item-definition:2:family:ammo/iron"))
        .thenReturn(Optional.empty());
    when(itemStackRepo.save(Mockito.any(ItemStack.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var dropped =
        service.dropItemToRoom(
            1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 2);

    assertEquals(2, dropped.quantity());
    assertEquals(1, inventoryStack.getQuantity());
    assertNull(dropped.itemInstanceId());
    assertEquals("ammo/iron", dropped.visibleRef());
    verify(itemStackRepo, Mockito.atLeastOnce()).save(Mockito.any(ItemStack.class));
  }

  @Test
  void dropItemWritesStackTransferAudit() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    ItemTransferAuditWriter itemTransferAuditWriter = Mockito.mock(ItemTransferAuditWriter.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator,
            itemTransferAuditWriter);

    Character character = character(1L, 1L);
    Item arrows = item(2L, 1L, "Arrows", false, null, true);
    arrows.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_AND_FAMILY);
    arrows.setStackVariantKey("ammo/iron");
    ItemStack inventoryStack = stack(41L, 1L, character, arrows, 3);
    inventoryStack.setStackFamilyKey("ammo/iron");
    inventoryStack.setCompatibilityFingerprint("item-definition:2:family:ammo/iron");

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(arrows));
    when(itemStackRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(inventoryStack));
    when(itemStackRepo
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
                1L, "GI-1", "R-1", 2L, "item-definition:2:family:ammo/iron"))
        .thenReturn(Optional.empty());
    when(itemStackRepo.save(any(ItemStack.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.dropItemToRoom(1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 2);

    ItemTransferSupport transferSupport = new ItemTransferSupport();
    verify(itemTransferAuditWriter)
        .recordStackTransfer(
            1L,
            arrows,
            2,
            "ammo/iron",
            transferSupport.inventoryHolder(1L, 1L),
            transferSupport.roomHolder(1L, "GI-1", "R-1"),
            transferSupport.audit("DROP", 1L));
  }

  @Test
  void dropItemRejectsAmbiguousStackFamiliesForSameItemDefinition() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 1L);
    Item arrows = item(2L, 1L, "Arrows", false, null, true);
    arrows.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_AND_FAMILY);
    ItemStack first = stack(41L, 1L, character, arrows, 3);
    first.setStackFamilyKey("ammo/iron");
    first.setCompatibilityFingerprint("item-definition:2:family:ammo/iron");
    ItemStack second = stack(42L, 1L, character, arrows, 4);
    second.setStackFamilyKey("ammo/steel");
    second.setCompatibilityFingerprint("item-definition:2:family:ammo/steel");

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(arrows));
    when(itemStackRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(first, second));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.dropItemToRoom(
                    1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 1));

    assertEquals(
        "Multiple stack families exist for item 2; explicit stack selection required",
        ex.getMessage());
  }

  @Test
  void pickupItemRejectsMissingQuantity() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null, false);
    ItemInstance stale = itemInstance(41L, 1L, character, item, null, "GI-OLD", "R-OLD");

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(stale));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.dropItemToRoom(
                1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 1));
  }

  @Test
  void pickupItemRejectsLegacyRuntimeRoomIdsBeforeStateLookup() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.pickupItemFromRoom(
                    1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "room-1", 2L, null, null, null, 1));

    assertEquals("roomInstanceId must be a runtime room id like R-1021", ex.getMessage());
    verify(characterRepo, never()).findByIdAndTenantId(anyLong(), anyLong());
    verify(itemRepo, never()).findByIdAndTenantId(anyLong(), anyLong());
  }

  @Test
  void pickupItemRejectsStaleSourceMismatch() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null, false);
    ItemInstance stale = itemInstance(41L, 1L, null, item, null, null, null);
    stale.setCharacter(character);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
                1L, "GI-1", "R-1", 2L))
        .thenReturn(List.of(stale));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.pickupItemFromRoom(
                1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 1));
  }

  @Test
  void pickupItemRejectsStaleSourceMismatchWithoutAuditWrite() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    ItemTransferAuditWriter itemTransferAuditWriter = Mockito.mock(ItemTransferAuditWriter.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator,
            itemTransferAuditWriter);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null, false);
    ItemInstance stale = itemInstance(41L, 1L, null, item, null, null, null);
    stale.setCharacter(character);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
                1L, "GI-1", "R-1", 2L))
        .thenReturn(List.of(stale));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.pickupItemFromRoom(
                1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 1));

    verify(itemTransferAuditWriter, never()).recordInstanceTransfer(any(), any(), any(), any());
    verify(itemTransferAuditWriter, never())
        .recordStackTransfer(anyLong(), any(), anyInt(), any(), any(), any(), any());
  }

  @Test
  void dropItemRejectsAlreadyMovedToDestination() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null, false);
    ItemInstance stale = itemInstance(41L, 1L, character, item, null, "GI-1", "R-1");
    stale.setCharacter(null);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(stale));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.dropItemToRoom(
                    1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, null, 1));

    assertEquals("Item already at destination", ex.getMessage());
  }

  @Test
  void dropItemRejectsExplicitItemInstanceIdWithQuantityGreaterThanOne() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null, false);
    ItemInstance first = itemInstance(41L, 1L, character, item, null, null, null);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(first));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.dropItemToRoom(
                1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, 41L, null, null, 2));
  }

  @Test
  void dropItemSelectsExplicitStackFamilyWhenMultipleFamiliesExist() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        service(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            visibleRefAllocator);

    Character character = character(1L, 1L);
    Item arrows = item(2L, 1L, "Arrows", false, null, true);
    arrows.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_AND_FAMILY);
    ItemStack first = stack(41L, 1L, character, arrows, 3);
    first.setStackFamilyKey("ammo/iron");
    first.setCompatibilityFingerprint("item-definition:2:family:ammo/iron");
    ItemStack second = stack(42L, 1L, character, arrows, 4);
    second.setStackFamilyKey("ammo/steel");
    second.setCompatibilityFingerprint("item-definition:2:family:ammo/steel");

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(arrows));
    when(itemStackRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(first, second));
    when(itemStackRepo
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
                1L, "GI-1", "R-1", 2L, "item-definition:2:family:ammo/steel"))
        .thenReturn(Optional.empty());
    when(itemStackRepo.save(Mockito.any(ItemStack.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var dropped =
        service.dropItemToRoom(
            1L, 1L, "GI-1", PLAYABLE_STATE_SCOPE, "R-1", 2L, null, null, "ammo/steel", 2);

    assertEquals("ammo/steel", dropped.visibleRef());
    assertEquals(2, second.getQuantity());
    assertEquals(3, first.getQuantity());
  }

  private InventoryServiceImpl service(
      ItemInstanceRepository itemInstanceRepo,
      ContainerInstanceRepository containerInstanceRepo,
      CharacterRepository characterRepo,
      ItemRepository itemRepo,
      ItemStackRepository itemStackRepo,
      ItemVisibleRefAllocator visibleRefAllocator) {
    return service(
        itemInstanceRepo,
        containerInstanceRepo,
        characterRepo,
        itemRepo,
        itemStackRepo,
        visibleRefAllocator,
        new NoOpItemTransferAuditWriter());
  }

  private InventoryServiceImpl service(
      ItemInstanceRepository itemInstanceRepo,
      ContainerInstanceRepository containerInstanceRepo,
      CharacterRepository characterRepo,
      ItemRepository itemRepo,
      ItemStackRepository itemStackRepo,
      ItemVisibleRefAllocator visibleRefAllocator,
      ItemTransferAuditWriter itemTransferAuditWriter) {
    return new InventoryServiceImpl(
        itemInstanceRepo,
        containerInstanceRepo,
        characterRepo,
        itemRepo,
        itemStackRepo,
        visibleRefAllocator,
        new ItemTransferSupport(),
        itemTransferAuditWriter,
        new ContainerHolderSyncSupport(containerInstanceRepo),
        new StackableItemSupport());
  }

  private static Character character(Long id, Long tenantId) {
    Character character = new Character();
    character.setId(id);
    character.setTenantId(tenantId);
    character.setPlayableStateKey("shared-live");
    return character;
  }

  private static Item item(
      Long id,
      Long tenantId,
      String name,
      boolean container,
      String equipmentSlot,
      boolean stackable) {
    Item item = new Item();
    item.setId(id);
    item.setTenantId(tenantId);
    item.setName(name);
    item.setDescription(name + " desc");
    item.setContainer(container);
    item.setEquipmentSlot(equipmentSlot);
    item.setStackable(stackable);
    item.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_ONLY);
    return item;
  }

  private static ItemInstance itemInstance(
      Long id,
      Long tenantId,
      Character character,
      Item item,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId) {
    ItemInstance instance = new ItemInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setCharacter(character);
    instance.setItem(item);
    instance.setEquipmentSlot(equipmentSlot);
    instance.setGameInstanceId(gameInstanceId);
    instance.setRoomInstanceId(roomInstanceId);
    instance.setVisibleRef("item" + id);
    instance.setVisibleRefToken("item");
    instance.setVisibleRefSequence(id);
    return instance;
  }

  private static ItemStack stack(
      Long id, Long tenantId, Character character, Item item, int quantity) {
    ItemStack stack = new ItemStack();
    stack.setId(id);
    stack.setTenantId(tenantId);
    stack.setCharacter(character);
    stack.setItem(item);
    stack.setStackFamilyKey(item.getStackVariantKey());
    stack.setCompatibilityFingerprint("item-definition:" + item.getId());
    stack.setQuantity(quantity);
    return stack;
  }
}
