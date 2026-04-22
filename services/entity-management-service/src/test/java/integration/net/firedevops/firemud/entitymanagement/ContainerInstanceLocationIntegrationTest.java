package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemStackCompatibilityMode;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemStackRepository;
import net.firedevops.firemud.entitymanagement.service.ContainerService;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.data.domain.Pageable;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    classes = EntityManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class ContainerInstanceLocationIntegrationTest {
  private static final Long TENANT_ID = 1L;
  private static final String GAME_INSTANCE_ID = "GI-1";
  private static final String ROOM_INSTANCE_ID = "R-1";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("firemud.postgres.host", postgres::getHost);
    registry.add("firemud.postgres.port", () -> postgres.getMappedPort(5432));
    registry.add("firemud.postgres.database", postgres::getDatabaseName);
    registry.add("firemud.postgres.username", postgres::getUsername);
    registry.add("firemud.postgres.password", postgres::getPassword);
    registry.add("firemud.redis.host", redis::getHost);
    registry.add("firemud.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired private InventoryService inventoryService;
  @Autowired private ContainerService containerService;
  @Autowired private CharacterRepository characterRepository;
  @Autowired private ItemRepository itemRepository;
  @Autowired private ItemInstanceRepository itemInstanceRepository;
  @Autowired private ItemStackRepository itemStackRepository;
  @Autowired private ContainerInstanceRepository containerInstanceRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @BeforeEach
  void cleanDatabase() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE item_stacks, item_instances, container_instances, items, characters RESTART IDENTITY CASCADE");
  }

  @Test
  void nonEmptyContainerSurvivesDropAndPickupBetweenHolders() {
    Character alice = characterRepository.save(character("Alice"));
    Character bob = characterRepository.save(character("Bob"));
    Item backpack = itemRepository.save(containerItem("Backpack"));
    Item torch = itemRepository.save(ordinaryItem("Torch"));

    inventoryService.addItem(TENANT_ID, alice.getId(), backpack.getId(), 1);
    inventoryService.addItem(TENANT_ID, alice.getId(), torch.getId(), 1);

    var inventory = inventoryService.listInventory(TENANT_ID, alice.getId(), Pageable.unpaged());
    var backpackEntry =
        inventory.getContent().stream()
            .filter(entry -> entry.itemId().equals(backpack.getId()))
            .findFirst()
            .orElseThrow();
    var torchEntry =
        inventory.getContent().stream()
            .filter(entry -> entry.itemId().equals(torch.getId()))
            .findFirst()
            .orElseThrow();

    containerService.putItemIntoContainer(
        TENANT_ID,
        alice.getId(),
        backpackEntry.containerInstanceId(),
        torch.getId(),
        torchEntry.itemInstanceId(),
        null,
        1,
        null,
        null);

    inventoryService.dropItemToRoom(
        TENANT_ID,
        alice.getId(),
        GAME_INSTANCE_ID,
        ROOM_INSTANCE_ID,
        backpack.getId(),
        backpackEntry.itemInstanceId(),
        Long.toString(backpackEntry.containerInstanceId()),
        null,
        1,
        null,
        null);

    var roomGround =
        inventoryService.listRoomGroundItems(
            TENANT_ID, GAME_INSTANCE_ID, ROOM_INSTANCE_ID, Pageable.unpaged());
    var droppedBackpack =
        roomGround.getContent().stream()
            .filter(entry -> entry.itemId().equals(backpack.getId()))
            .findFirst()
            .orElseThrow();

    assertThat(droppedBackpack.containerInstanceId())
        .isEqualTo(backpackEntry.containerInstanceId());

    var roomContents =
        containerService.listContainerContents(
            TENANT_ID,
            alice.getId(),
            backpackEntry.containerInstanceId(),
            GAME_INSTANCE_ID,
            ROOM_INSTANCE_ID,
            Pageable.unpaged());
    assertThat(roomContents.getContent())
        .singleElement()
        .satisfies(item -> assertThat(item.itemName()).isEqualTo("Torch"));

    inventoryService.pickupItemFromRoom(
        TENANT_ID,
        bob.getId(),
        GAME_INSTANCE_ID,
        ROOM_INSTANCE_ID,
        backpack.getId(),
        droppedBackpack.itemInstanceId(),
        Long.toString(backpackEntry.containerInstanceId()),
        null,
        1,
        null,
        null);

    var bobContents =
        containerService.listContainerContents(
            TENANT_ID, bob.getId(), backpackEntry.containerInstanceId(), Pageable.unpaged());
    assertThat(bobContents.getContent())
        .singleElement()
        .satisfies(item -> assertThat(item.itemName()).isEqualTo("Torch"));
  }

  @Test
  void identicalContainersKeepDistinctContentsByContainerInstance() {
    Character alice = characterRepository.save(character("Alice"));
    Item backpack = itemRepository.save(containerItem("Backpack"));
    Item torch = itemRepository.save(ordinaryItem("Torch"));
    Item ration = itemRepository.save(ordinaryItem("Ration"));

    inventoryService.addItem(TENANT_ID, alice.getId(), backpack.getId(), 2);
    inventoryService.addItem(TENANT_ID, alice.getId(), torch.getId(), 1);
    inventoryService.addItem(TENANT_ID, alice.getId(), ration.getId(), 1);

    var inventory = inventoryService.listInventory(TENANT_ID, alice.getId(), Pageable.unpaged());
    var backpacks =
        inventory.getContent().stream()
            .filter(entry -> entry.itemId().equals(backpack.getId()))
            .toList();
    var torchEntry =
        inventory.getContent().stream()
            .filter(entry -> entry.itemId().equals(torch.getId()))
            .findFirst()
            .orElseThrow();
    var rationEntry =
        inventory.getContent().stream()
            .filter(entry -> entry.itemId().equals(ration.getId()))
            .findFirst()
            .orElseThrow();

    assertThat(backpacks).hasSize(2);
    assertThat(backpacks.get(0).containerInstanceId())
        .isNotEqualTo(backpacks.get(1).containerInstanceId());

    containerService.putItemIntoContainer(
        TENANT_ID,
        alice.getId(),
        backpacks.get(0).containerInstanceId(),
        torch.getId(),
        torchEntry.itemInstanceId(),
        null,
        1,
        null,
        null);
    containerService.putItemIntoContainer(
        TENANT_ID,
        alice.getId(),
        backpacks.get(1).containerInstanceId(),
        ration.getId(),
        rationEntry.itemInstanceId(),
        null,
        1,
        null,
        null);

    var firstContents =
        containerService.listContainerContents(
            TENANT_ID, alice.getId(), backpacks.get(0).containerInstanceId(), Pageable.unpaged());
    var secondContents =
        containerService.listContainerContents(
            TENANT_ID, alice.getId(), backpacks.get(1).containerInstanceId(), Pageable.unpaged());

    assertThat(firstContents.getContent())
        .singleElement()
        .satisfies(item -> assertThat(item.itemName()).isEqualTo("Torch"));
    assertThat(secondContents.getContent())
        .singleElement()
        .satisfies(item -> assertThat(item.itemName()).isEqualTo("Ration"));
  }

  private Character character(String name) {
    Character character = new Character();
    character.setTenantId(TENANT_ID);
    character.setAccountId((long) name.length() + 1L);
    character.setPlayableStateKey("shared-live");
    character.setName(name);
    character.setBodyLayoutKey("DEFAULT");
    character.setLevel(1);
    character.setExperience(0);
    character.setStrength(10);
    character.setAgility(10);
    character.setIntelligence(10);
    character.setStamina(10);
    character.setHealth(100);
    character.setMana(50);
    return character;
  }

  private Item containerItem(String name) {
    Item item = ordinaryItem(name);
    item.setContainer(true);
    return item;
  }

  private Item ordinaryItem(String name) {
    Item item = new Item();
    item.setTenantId(TENANT_ID);
    item.setVersionId(1L);
    item.setName(name);
    item.setDescription(name + " desc");
    item.setContainer(false);
    item.setStackable(false);
    item.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_ONLY);
    return item;
  }
}
