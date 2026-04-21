package net.firedevops.firemud.entitymanagement.data;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.entity.BodyLayoutSlotDefinition;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.EquipmentSlotDefinition;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.BodyLayoutSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.EquipmentSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds deterministic local gameplay fixtures when the {@code dev} Spring profile is active. */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class TestDataSeeder implements ApplicationRunner {
  private static final long DEMO_TENANT_ID = 1L;
  private static final long DEMO_ACCOUNT_ID = 1L;
  private static final long VERSION_ID = 1L;
  private static final String SHARED_LIVE_PLAYABLE_STATE = "shared-live";
  private static final String DEMO_CHARACTER_NAME = "demo";
  private static final String DEMO_GAME_INSTANCE_ID = "1";
  private static final String STARTER_ROOM_INSTANCE_ID = "1021";

  private final CharacterRepository characterRepository;
  private final ItemRepository itemRepository;
  private final ItemInstanceRepository itemInstanceRepository;
  private final EquipmentSlotDefinitionRepository equipmentSlotDefinitionRepository;
  private final BodyLayoutSlotDefinitionRepository bodyLayoutSlotDefinitionRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    Character demo = ensureDemoCharacter();
    ensureEquipmentSchema();
    Item torch = ensureItem("Torch", "A small torch", null);
    Item leatherCap = ensureItem("Leather Cap", "A small cap", "HEAD");
    Item ironBoots = ensureItem("Iron Boots", "Heavy iron boots", "FEET");

    ensureRoomItem(torch, "torch", 1L, "torch#1");
    ensureCarriedItem(demo, leatherCap, "cap", 1L, "cap#1");
    ensureCarriedItem(demo, ironBoots, "boots", 1L, "boots#1");
  }

  private Character ensureDemoCharacter() {
    return characterRepository
        .findByTenantIdAndPlayableStateKeyAndNameIgnoreCase(
            DEMO_TENANT_ID, SHARED_LIVE_PLAYABLE_STATE, DEMO_CHARACTER_NAME)
        .map(
            character -> {
              character.setAccountId(DEMO_ACCOUNT_ID);
              character.setBodyLayoutKey("DEFAULT");
              return characterRepository.save(character);
            })
        .orElseGet(
            () -> {
              Character character = new Character();
              character.setTenantId(DEMO_TENANT_ID);
              character.setAccountId(DEMO_ACCOUNT_ID);
              character.setPlayableStateKey(SHARED_LIVE_PLAYABLE_STATE);
              character.setName(DEMO_CHARACTER_NAME);
              character.setBodyLayoutKey("DEFAULT");
              character.setLevel(1);
              character.setExperience(0);
              character.setStrength(10);
              character.setAgility(10);
              character.setIntelligence(10);
              character.setStamina(10);
              character.setHealth(100);
              character.setMana(50);
              return characterRepository.save(character);
            });
  }

  private void ensureEquipmentSchema() {
    ensureEquipmentSlot("HEAD", "Head", "headwear");
    ensureEquipmentSlot("FEET", "Feet", "footwear");
    ensureBodyLayoutSlot("DEFAULT", "HEAD");
  }

  private void ensureEquipmentSlot(String slotKey, String displayName, String slotGroupKey) {
    if (equipmentSlotDefinitionRepository.existsByTenantIdAndVersionIdAndSlotKey(
        DEMO_TENANT_ID, VERSION_ID, slotKey)) {
      return;
    }
    EquipmentSlotDefinition definition = new EquipmentSlotDefinition();
    definition.setTenantId(DEMO_TENANT_ID);
    definition.setVersionId(VERSION_ID);
    definition.setSlotKey(slotKey);
    definition.setDisplayName(displayName);
    definition.setSlotGroupKey(slotGroupKey);
    equipmentSlotDefinitionRepository.save(definition);
  }

  private void ensureBodyLayoutSlot(String bodyLayoutKey, String slotKey) {
    if (bodyLayoutSlotDefinitionRepository.existsByTenantIdAndVersionIdAndBodyLayoutKeyAndSlotKey(
        DEMO_TENANT_ID, VERSION_ID, bodyLayoutKey, slotKey)) {
      return;
    }
    BodyLayoutSlotDefinition definition = new BodyLayoutSlotDefinition();
    definition.setTenantId(DEMO_TENANT_ID);
    definition.setVersionId(VERSION_ID);
    definition.setBodyLayoutKey(bodyLayoutKey);
    definition.setSlotKey(slotKey);
    bodyLayoutSlotDefinitionRepository.save(definition);
  }

  private Item ensureItem(String name, String description, String equipmentSlot) {
    return itemRepository
        .findByTenantIdAndNameIgnoreCase(DEMO_TENANT_ID, name)
        .map(
            item -> {
              item.setVersionId(VERSION_ID);
              item.setDescription(description);
              item.setEquipmentSlot(equipmentSlot);
              item.setContainer(false);
              item.setStackable(false);
              return itemRepository.save(item);
            })
        .orElseGet(
            () -> {
              Item item = new Item();
              item.setTenantId(DEMO_TENANT_ID);
              item.setVersionId(VERSION_ID);
              item.setName(name);
              item.setDescription(description);
              item.setEquipmentSlot(equipmentSlot);
              item.setContainer(false);
              item.setStackable(false);
              return itemRepository.save(item);
            });
  }

  private void ensureRoomItem(Item item, String token, long sequence, String visibleRef) {
    if (itemInstanceRepository.existsByTenantIdAndVisibleRef(DEMO_TENANT_ID, visibleRef)) {
      return;
    }
    ItemInstance instance = new ItemInstance();
    instance.setTenantId(DEMO_TENANT_ID);
    instance.setItem(item);
    instance.setGameInstanceId(DEMO_GAME_INSTANCE_ID);
    instance.setRoomInstanceId(STARTER_ROOM_INSTANCE_ID);
    instance.setVisibleRefToken(token);
    instance.setVisibleRefSequence(sequence);
    instance.setVisibleRef(visibleRef);
    itemInstanceRepository.save(instance);
  }

  private void ensureCarriedItem(
      Character character, Item item, String token, long sequence, String visibleRef) {
    if (itemInstanceRepository.existsByTenantIdAndVisibleRef(DEMO_TENANT_ID, visibleRef)) {
      return;
    }
    ItemInstance instance = new ItemInstance();
    instance.setTenantId(DEMO_TENANT_ID);
    instance.setCharacter(character);
    instance.setItem(item);
    instance.setVisibleRefToken(token);
    instance.setVisibleRefSequence(sequence);
    instance.setVisibleRef(visibleRef);
    itemInstanceRepository.save(instance);
  }
}
