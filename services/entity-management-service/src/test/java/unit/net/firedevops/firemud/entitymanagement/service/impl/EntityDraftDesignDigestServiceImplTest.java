package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.BodyLayoutSlotDefinition;
import net.firedevops.firemud.entitymanagement.entity.EquipmentSlotDefinition;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.repository.BodyLayoutSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.CraftingRecipeRepository;
import net.firedevops.firemud.entitymanagement.repository.EquipmentSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.NpcRepository;
import net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService.EntityDraftDesignDigest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class EntityDraftDesignDigestServiceImplTest {
  @Test
  void rejectsZeroTenantIdBeforeRepositoryReads() {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    EntityDraftDesignDigestServiceImpl service =
        service(itemRepository, npcRepository, craftingRecipeRepository);

    assertThrows(IllegalArgumentException.class, () -> service.getDraftDesignDigest("0", "7"));

    Mockito.verifyNoInteractions(itemRepository, npcRepository, craftingRecipeRepository);
  }

  @Test
  void rejectsZeroVersionIdBeforeRepositoryReads() {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    EntityDraftDesignDigestServiceImpl service =
        service(itemRepository, npcRepository, craftingRecipeRepository);

    assertThrows(IllegalArgumentException.class, () -> service.getDraftDesignDigest("1", "0"));

    Mockito.verifyNoInteractions(itemRepository, npcRepository, craftingRecipeRepository);
  }

  @Test
  void computesDigestFromVersionScopedTemplateRows() {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    EntityDraftDesignDigestServiceImpl service =
        service(itemRepository, npcRepository, craftingRecipeRepository);

    var digest = service.getDraftDesignDigest("1", "7");

    assertEquals("1", digest.tenantId());
    assertEquals("7", digest.scopeValue());
    assertEquals("version:7", digest.appliedCommitId());
    Mockito.verify(itemRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L);
    Mockito.verify(itemRepository, Mockito.never()).findByTenantIdOrderByIdAsc(Mockito.anyLong());
  }

  @Test
  void normalizesMissingAndBlankEquipmentSlotGroupKeysToTheSameDigest() {
    var missingDigest = digestForEquipmentSlotGroupKey(null);
    var blankDigest = digestForEquipmentSlotGroupKey(" \t ");

    assertEquals(2, missingDigest.digestSchemaVersion());
    assertEquals(missingDigest.contentDigest(), blankDigest.contentDigest());
  }

  @Test
  void equipmentSlotGroupConstraintChangesTheDigest() {
    var wingsDigest = digestForEquipmentSlotGroupKey(" wings ");
    var normalizedWingsDigest = digestForEquipmentSlotGroupKey("WINGS");
    var torsoDigest = digestForEquipmentSlotGroupKey("TORSO");

    assertEquals(2, wingsDigest.digestSchemaVersion());
    assertEquals(wingsDigest.contentDigest(), normalizedWingsDigest.contentDigest());
    assertNotEquals(wingsDigest.contentDigest(), torsoDigest.contentDigest());
  }

  @Test
  void canonicalizesEquivalentEquipmentSlotValues() {
    var spacedDigest = digestForEquipmentSlot(" head ");
    var normalizedDigest = digestForEquipmentSlot("HEAD");

    assertEquals(spacedDigest.contentDigest(), normalizedDigest.contentDigest());
  }

  @Test
  void distinctEquipmentSlotValuesChangeTheDigest() {
    var headDigest = digestForEquipmentSlot("HEAD");
    var torsoDigest = digestForEquipmentSlot("TORSO");

    assertNotEquals(headDigest.contentDigest(), torsoDigest.contentDigest());
  }

  @Test
  void equipmentSlotDefinitionSemanticsChangeTheDigest() {
    var head = slotDefinition("HEAD", "Head", "UPPER");
    var differentDisplayName = slotDefinition("HEAD", "Crown", "UPPER");
    var differentGroup = slotDefinition("HEAD", "Head", "LOWER");
    var differentSlot = slotDefinition("TORSO", "Head", "UPPER");

    var original = digestForDefinitions(List.of(head), List.of());

    assertNotEquals(
        original.contentDigest(),
        digestForDefinitions(List.of(differentDisplayName), List.of()).contentDigest());
    assertNotEquals(
        original.contentDigest(),
        digestForDefinitions(List.of(differentGroup), List.of()).contentDigest());
    assertNotEquals(
        original.contentDigest(),
        digestForDefinitions(List.of(differentSlot), List.of()).contentDigest());
  }

  @Test
  void bodyLayoutSlotSemanticsChangeTheDigest() {
    var original = digestForDefinitions(List.of(), List.of(bodyLayoutSlot("HUMANOID", "HEAD")));
    var differentLayout =
        digestForDefinitions(List.of(), List.of(bodyLayoutSlot("QUADRUPED", "HEAD")));
    var differentSlot =
        digestForDefinitions(List.of(), List.of(bodyLayoutSlot("HUMANOID", "TORSO")));

    assertNotEquals(original.contentDigest(), differentLayout.contentDigest());
    assertNotEquals(original.contentDigest(), differentSlot.contentDigest());
  }

  @Test
  void canonicalizesVocabularyKeysAndIgnoresRowOrderAndOptimisticVersion() {
    var firstSlot = slotDefinition(" HEAD ", "Head", " upper ");
    firstSlot.setVersion(2);
    var secondSlot = slotDefinition("TORSO", "Torso", null);
    secondSlot.setVersion(8);
    var firstLayoutSlot = bodyLayoutSlot(" Humanoid ", " Head ");
    firstLayoutSlot.setVersion(3);
    var secondLayoutSlot = bodyLayoutSlot("HUMANOID", "TORSO");
    secondLayoutSlot.setVersion(9);

    var firstDigest =
        digestForDefinitions(
            List.of(firstSlot, secondSlot), List.of(firstLayoutSlot, secondLayoutSlot));

    var normalizedSlot = slotDefinition("HEAD", "Head", "UPPER");
    normalizedSlot.setVersion(200);
    var normalizedSecondSlot = slotDefinition("TORSO", "Torso", " ");
    normalizedSecondSlot.setVersion(800);
    var normalizedFirstLayoutSlot = bodyLayoutSlot("HUMANOID", "HEAD");
    normalizedFirstLayoutSlot.setVersion(300);
    var normalizedSecondLayoutSlot = bodyLayoutSlot(" humanoid ", " torso ");
    normalizedSecondLayoutSlot.setVersion(900);

    var equivalentDigest =
        digestForDefinitions(
            List.of(normalizedSecondSlot, normalizedSlot),
            List.of(normalizedSecondLayoutSlot, normalizedFirstLayoutSlot));

    assertEquals(firstDigest.contentDigest(), equivalentDigest.contentDigest());
  }

  @Test
  void readsEquipmentVocabularyWithinRequestedTenantAndVersion() {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    EquipmentSlotDefinitionRepository equipmentSlotDefinitionRepository =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotDefinitionRepository =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    for (long tenantId : List.of(1L, 2L)) {
      for (long versionId : List.of(7L, 8L)) {
        Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, versionId))
            .thenReturn(List.of());
        Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, versionId))
            .thenReturn(List.of());
        Mockito.when(
                craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(
                    tenantId, versionId))
            .thenReturn(List.of());
        Mockito.when(
                equipmentSlotDefinitionRepository.findByTenantIdAndVersionIdOrderBySlotKeyAsc(
                    tenantId, versionId))
            .thenReturn(List.of(slotDefinition("SLOT-" + tenantId + versionId, "Slot", null)));
        Mockito.when(
                bodyLayoutSlotDefinitionRepository
                    .findByTenantIdAndVersionIdOrderByBodyLayoutKeyAscSlotKeyAsc(
                        tenantId, versionId))
            .thenReturn(List.of(bodyLayoutSlot("LAYOUT-" + tenantId + versionId, "SLOT")));
      }
    }
    EntityDraftDesignDigestServiceImpl service =
        new EntityDraftDesignDigestServiceImpl(
            itemRepository,
            npcRepository,
            craftingRecipeRepository,
            equipmentSlotDefinitionRepository,
            bodyLayoutSlotDefinitionRepository,
            new ObjectMapper());

    var tenantOneVersionSeven = service.getDraftDesignDigest("1", "7");
    var tenantOneVersionEight = service.getDraftDesignDigest("1", "8");
    var tenantTwoVersionSeven = service.getDraftDesignDigest("2", "7");

    assertNotEquals(tenantOneVersionSeven.contentDigest(), tenantOneVersionEight.contentDigest());
    assertNotEquals(tenantOneVersionSeven.contentDigest(), tenantTwoVersionSeven.contentDigest());
    Mockito.verify(equipmentSlotDefinitionRepository)
        .findByTenantIdAndVersionIdOrderBySlotKeyAsc(1L, 7L);
    Mockito.verify(bodyLayoutSlotDefinitionRepository)
        .findByTenantIdAndVersionIdOrderByBodyLayoutKeyAscSlotKeyAsc(1L, 7L);
  }

  @Test
  void canonicalizesEquivalentEffectPayloadJson() {
    var firstDigest =
        digestForEffectPayload(
            "{\"modifiers\":[{\"operation\":\"ADD\",\"target_key\":\"health\","
                + "\"value\":1,\"metadata\":{\"b\":2,\"a\":1}}]}");
    var equivalentDigest =
        digestForEffectPayload(
            " { \"modifiers\" : [ { \"metadata\" : { \"a\" : 1, \"b\" : 2 },"
                + " \"value\" : 1, \"target_key\" : \"health\", \"operation\" : \"ADD\" } ] } ");

    assertEquals(firstDigest.contentDigest(), equivalentDigest.contentDigest());
  }

  @Test
  void distinctEffectPayloadJsonChangesTheDigest() {
    var addDigest =
        digestForEffectPayload(
            "{\"modifiers\":[{\"operation\":\"ADD\",\"target_key\":\"health\",\"value\":1}]}");
    var subtractDigest =
        digestForEffectPayload(
            "{\"modifiers\":[{\"operation\":\"SUBTRACT\",\"target_key\":\"health\",\"value\":1}]}");

    assertNotEquals(addDigest.contentDigest(), subtractDigest.contentDigest());
  }

  @Test
  void rejectsMalformedNonBlankEffectPayloadJson() {
    assertThrows(IllegalStateException.class, () -> digestForEffectPayload("{\"modifiers\":[}"));
  }

  @Test
  void normalizesMissingAndBlankEffectPayloadJsonToTheSameDigest() {
    var missingDigest = digestForEffectPayload(null);
    var blankDigest = digestForEffectPayload(" \t ");

    assertEquals(missingDigest.contentDigest(), blankDigest.contentDigest());
  }

  @Test
  void readsItemsWithinTheRequestedTenantAndVersion() {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(item(11L, "WINGS")));
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 8L))
        .thenReturn(List.of(item(11L, "TORSO")));
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(2L, 7L))
        .thenReturn(List.of(item(11L, null)));
    for (long tenantId : List.of(1L, 2L)) {
      for (long versionId : List.of(7L, 8L)) {
        Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, versionId))
            .thenReturn(List.of());
        Mockito.when(
                craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(
                    tenantId, versionId))
            .thenReturn(List.of());
      }
    }
    EntityDraftDesignDigestServiceImpl service =
        service(itemRepository, npcRepository, craftingRecipeRepository);

    var tenantOneVersionSeven = service.getDraftDesignDigest("1", "7");
    var tenantOneVersionEight = service.getDraftDesignDigest("1", "8");
    var tenantTwoVersionSeven = service.getDraftDesignDigest("2", "7");

    assertNotEquals(tenantOneVersionSeven.contentDigest(), tenantOneVersionEight.contentDigest());
    assertNotEquals(tenantOneVersionSeven.contentDigest(), tenantTwoVersionSeven.contentDigest());
    Mockito.verify(itemRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L);
    Mockito.verify(itemRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 8L);
    Mockito.verify(itemRepository).findByTenantIdAndVersionIdOrderByIdAsc(2L, 7L);
    Mockito.verify(itemRepository, Mockito.never()).findByTenantIdOrderByIdAsc(Mockito.anyLong());
  }

  private static EntityDraftDesignDigest digestForEquipmentSlotGroupKey(String groupKey) {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(item(11L, groupKey)));
    Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    EntityDraftDesignDigestServiceImpl service =
        service(itemRepository, npcRepository, craftingRecipeRepository);

    return service.getDraftDesignDigest("1", "7");
  }

  private static EntityDraftDesignDigest digestForEffectPayload(String effectPayloadJson) {
    Item item = item(11L, null);
    item.setEffectPayloadJson(effectPayloadJson);
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(item));
    Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    EntityDraftDesignDigestServiceImpl service =
        service(itemRepository, npcRepository, craftingRecipeRepository);

    return service.getDraftDesignDigest("1", "7");
  }

  private static EntityDraftDesignDigest digestForDefinitions(
      List<EquipmentSlotDefinition> slotDefinitions,
      List<BodyLayoutSlotDefinition> bodyLayoutSlots) {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    EquipmentSlotDefinitionRepository equipmentSlotDefinitionRepository =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotDefinitionRepository =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(
            equipmentSlotDefinitionRepository.findByTenantIdAndVersionIdOrderBySlotKeyAsc(1L, 7L))
        .thenReturn(slotDefinitions);
    Mockito.when(
            bodyLayoutSlotDefinitionRepository
                .findByTenantIdAndVersionIdOrderByBodyLayoutKeyAscSlotKeyAsc(1L, 7L))
        .thenReturn(bodyLayoutSlots);
    EntityDraftDesignDigestServiceImpl service =
        new EntityDraftDesignDigestServiceImpl(
            itemRepository,
            npcRepository,
            craftingRecipeRepository,
            equipmentSlotDefinitionRepository,
            bodyLayoutSlotDefinitionRepository,
            new ObjectMapper());

    return service.getDraftDesignDigest("1", "7");
  }

  private static EquipmentSlotDefinition slotDefinition(
      String slotKey, String displayName, String slotGroupKey) {
    EquipmentSlotDefinition definition = new EquipmentSlotDefinition();
    definition.setSlotKey(slotKey);
    definition.setDisplayName(displayName);
    definition.setSlotGroupKey(slotGroupKey);
    return definition;
  }

  private static BodyLayoutSlotDefinition bodyLayoutSlot(String bodyLayoutKey, String slotKey) {
    BodyLayoutSlotDefinition definition = new BodyLayoutSlotDefinition();
    definition.setBodyLayoutKey(bodyLayoutKey);
    definition.setSlotKey(slotKey);
    return definition;
  }

  private static EntityDraftDesignDigest digestForEquipmentSlot(String equipmentSlot) {
    Item item = item(11L, null);
    item.setEquipmentSlot(equipmentSlot);
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(item));
    Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    EntityDraftDesignDigestServiceImpl service =
        service(itemRepository, npcRepository, craftingRecipeRepository);

    return service.getDraftDesignDigest("1", "7");
  }

  private static Item item(Long id, String equipmentSlotGroupKey) {
    Item item = new Item();
    item.setId(id);
    item.setName("Test item");
    item.setEquipmentSlotGroupKey(equipmentSlotGroupKey);
    return item;
  }

  private static EntityDraftDesignDigestServiceImpl service(
      ItemRepository itemRepository,
      NpcRepository npcRepository,
      CraftingRecipeRepository craftingRecipeRepository) {
    EquipmentSlotDefinitionRepository equipmentSlotDefinitionRepository =
        Mockito.mock(EquipmentSlotDefinitionRepository.class);
    BodyLayoutSlotDefinitionRepository bodyLayoutSlotDefinitionRepository =
        Mockito.mock(BodyLayoutSlotDefinitionRepository.class);
    Mockito.when(
            equipmentSlotDefinitionRepository.findByTenantIdAndVersionIdOrderBySlotKeyAsc(
                Mockito.anyLong(), Mockito.anyLong()))
        .thenReturn(List.of());
    Mockito.when(
            bodyLayoutSlotDefinitionRepository
                .findByTenantIdAndVersionIdOrderByBodyLayoutKeyAscSlotKeyAsc(
                    Mockito.anyLong(), Mockito.anyLong()))
        .thenReturn(List.of());
    return new EntityDraftDesignDigestServiceImpl(
        itemRepository,
        npcRepository,
        craftingRecipeRepository,
        equipmentSlotDefinitionRepository,
        bodyLayoutSlotDefinitionRepository,
        new ObjectMapper());
  }
}
