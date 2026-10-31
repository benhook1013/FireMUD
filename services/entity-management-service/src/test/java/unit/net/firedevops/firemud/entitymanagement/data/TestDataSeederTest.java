package net.firedevops.firemud.entitymanagement.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.BodyLayoutSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.EquipmentSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.DefaultApplicationArguments;

class TestDataSeederTest {
  @Mock private CharacterRepository characterRepository;
  @Mock private ItemRepository itemRepository;
  @Mock private ItemInstanceRepository itemInstanceRepository;
  @Mock private ContainerInstanceRepository containerInstanceRepository;
  @Mock private EquipmentSlotDefinitionRepository equipmentSlotDefinitionRepository;
  @Mock private BodyLayoutSlotDefinitionRepository bodyLayoutSlotDefinitionRepository;

  @Mock
  private net.firedevops.firemud.entitymanagement.client.DemoAccountAuthenticationClient
      demoAccountAuthenticationClient;

  private static final java.util.UUID OWNER =
      java.util.UUID.fromString("11111111-1111-4111-8111-111111111111");
  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    when(demoAccountAuthenticationClient.authenticateDemoOwner()).thenReturn(OWNER);
    seeder =
        new TestDataSeeder(
            demoAccountAuthenticationClient,
            characterRepository,
            itemRepository,
            itemInstanceRepository,
            containerInstanceRepository,
            equipmentSlotDefinitionRepository,
            bodyLayoutSlotDefinitionRepository);
  }

  @Test
  void runSeedsCanonicalRuntimeRoomInstanceIdForRoomFixtures() throws Exception {
    when(characterRepository.findByTenantIdAndPlayableStateKeyAndNameIgnoreCase(
            1L, "shared-live", "demo"))
        .thenReturn(Optional.empty());
    when(characterRepository.save(any()))
        .thenAnswer(invocation -> withCharacterId(invocation.getArgument(0)));

    when(equipmentSlotDefinitionRepository.existsByTenantIdAndVersionIdAndSlotKey(
            any(), any(), any()))
        .thenReturn(false);
    when(bodyLayoutSlotDefinitionRepository.existsByTenantIdAndVersionIdAndBodyLayoutKeyAndSlotKey(
            any(), any(), any(), any()))
        .thenReturn(false);

    when(itemRepository.findByTenantIdAndNameIgnoreCase(any(), any())).thenReturn(Optional.empty());
    when(itemRepository.save(any()))
        .thenAnswer(invocation -> withItemId(invocation.getArgument(0)));

    when(itemInstanceRepository.existsByTenantIdAndVisibleRef(any(), any())).thenReturn(false);
    when(itemInstanceRepository.findByTenantIdAndVisibleRef(any(), any()))
        .thenReturn(Optional.empty());
    when(itemInstanceRepository.save(any()))
        .thenAnswer(invocation -> withItemInstanceId(invocation.getArgument(0)));

    when(containerInstanceRepository.findByItemInstance_Id(any())).thenReturn(Optional.empty());
    when(containerInstanceRepository.save(any()))
        .thenAnswer(invocation -> withContainerInstanceId(invocation.getArgument(0)));

    seeder.run(new DefaultApplicationArguments(new String[] {}));
    ArgumentCaptor<Character> ownerCaptor = ArgumentCaptor.forClass(Character.class);
    verify(characterRepository).save(ownerCaptor.capture());
    assertEquals(OWNER, ownerCaptor.getValue().getAccountUuid());
    org.junit.jupiter.api.Assertions.assertNull(ownerCaptor.getValue().getAccountId());

    ArgumentCaptor<ItemInstance> itemInstanceCaptor = ArgumentCaptor.forClass(ItemInstance.class);
    verify(itemInstanceRepository, atLeast(1)).save(itemInstanceCaptor.capture());
    List<ItemInstance> roomScopedInstances = new ArrayList<>();
    for (ItemInstance itemInstance : itemInstanceCaptor.getAllValues()) {
      if (itemInstance.getGameInstanceId() != null || itemInstance.getRoomInstanceId() != null) {
        roomScopedInstances.add(itemInstance);
      }
    }

    assertEquals(2, roomScopedInstances.size());
    assertEquals("R-1021", roomScopedInstances.get(0).getRoomInstanceId());
    assertEquals("R-1021", roomScopedInstances.get(1).getRoomInstanceId());

    ArgumentCaptor<ContainerInstance> containerCaptor =
        ArgumentCaptor.forClass(ContainerInstance.class);
    verify(containerInstanceRepository).save(containerCaptor.capture());
    assertEquals("R-1021", containerCaptor.getValue().getRoomInstanceId());
  }

  @Test
  void refusesRetainedMissingOrMismatchedOwnershipWithoutRepair() {
    for (java.util.UUID retained : new java.util.UUID[] {null, java.util.UUID.randomUUID()}) {
      Character character = new Character();
      character.setId(123L);
      character.setAccountId(999L);
      character.setAccountUuid(retained);
      when(characterRepository.findByTenantIdAndPlayableStateKeyAndNameIgnoreCase(
              1L, "shared-live", "demo"))
          .thenReturn(Optional.of(character));
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalStateException.class,
          () -> seeder.run(new DefaultApplicationArguments(new String[] {})));
      assertEquals(retained, character.getAccountUuid());
      assertEquals(999L, character.getAccountId());
    }
    org.mockito.Mockito.verify(characterRepository, org.mockito.Mockito.never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(
        itemRepository,
        itemInstanceRepository,
        containerInstanceRepository,
        equipmentSlotDefinitionRepository,
        bodyLayoutSlotDefinitionRepository);
  }

  private Character withCharacterId(Character character) {
    if (character.getId() == null) {
      character.setId(100L);
    }
    return character;
  }

  private Item withItemId(Item item) {
    if (item.getId() == null) {
      item.setId((long) (item.getName().hashCode() & Integer.MAX_VALUE));
    }
    return item;
  }

  private ItemInstance withItemInstanceId(ItemInstance itemInstance) {
    if (itemInstance.getId() == null) {
      itemInstance.setId((long) (itemInstance.getVisibleRef().hashCode() & Integer.MAX_VALUE));
    }
    return itemInstance;
  }

  private ContainerInstance withContainerInstanceId(ContainerInstance containerInstance) {
    if (containerInstance.getId() == null) {
      containerInstance.setId(200L);
    }
    return containerInstance;
  }
}
