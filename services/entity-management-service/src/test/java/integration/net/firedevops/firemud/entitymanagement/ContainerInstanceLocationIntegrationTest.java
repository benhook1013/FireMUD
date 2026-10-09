package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus;
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
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.data.domain.Pageable;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Conditional PostgreSQL proof for holder persistence after an assumed actor scope guard. The
 * mocked resolver admits only explicitly registered saved-character fixtures with the exact test
 * tenant, instance, and scope; those rows remain quarantined. This class proves nested holder and
 * persistence behavior, not owner provenance, runtime admission, or PLAY activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    classes = EntityManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class ContainerInstanceLocationIntegrationTest {
  private Long tenantId;
  private static final String GAME_INSTANCE_ID = "GI-1";
  private static final String ROOM_INSTANCE_ID = "R-1";
  private static final PlayableStateScope PLAYABLE_STATE_SCOPE =
      PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
  private final Map<Long, Character> registeredCharacterFixtures = new HashMap<>();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "entity_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private InventoryService inventoryService;
  @Autowired private ContainerService containerService;
  @Autowired private CharacterRepository characterRepository;
  @Autowired private ItemRepository itemRepository;
  @Autowired private ItemInstanceRepository itemInstanceRepository;
  @Autowired private ItemStackRepository itemStackRepository;
  @Autowired private ContainerInstanceRepository containerInstanceRepository;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private ScopedCharacterResolver scopedCharacterResolver;

  @BeforeEach
  void cleanDatabase() {
    tenantId = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
    registeredCharacterFixtures.clear();
    Mockito.doAnswer(
            invocation -> {
              Long tenantId = invocation.getArgument(0);
              Long characterId = invocation.getArgument(1);
              String gameInstanceId = invocation.getArgument(2);
              PlayableStateScope playableStateScope = invocation.getArgument(3);
              Character character = registeredCharacterFixtures.get(characterId);
              if (!this.tenantId.equals(tenantId)
                  || !GAME_INSTANCE_ID.equals(gameInstanceId)
                  || PLAYABLE_STATE_SCOPE != playableStateScope
                  || character == null
                  || !this.tenantId.equals(character.getTenantId())) {
                throw new IllegalArgumentException(
                    "Test scoped resolver fixture does not match requested tenant, character, instance, or scope");
              }
              return character;
            })
        .when(scopedCharacterResolver)
        .requireScopedCharacter(
            Mockito.nullable(Long.class),
            Mockito.nullable(Long.class),
            Mockito.nullable(String.class),
            Mockito.nullable(PlayableStateScope.class));
  }

  @Test
  void nonEmptyContainerSurvivesDropAndPickupBetweenHolders() {
    Character alice = characterRepository.save(character("Alice"));
    Character bob = characterRepository.save(character("Bob"));
    registerCharacterFixture(alice);
    registerCharacterFixture(bob);
    Item backpack = itemRepository.save(containerItem("Backpack"));
    Item torch = itemRepository.save(ordinaryItem("Torch"));

    inventoryService.addItem(
        tenantId, alice.getId(), GAME_INSTANCE_ID, PLAYABLE_STATE_SCOPE, backpack.getId(), 1);
    inventoryService.addItem(
        tenantId, alice.getId(), GAME_INSTANCE_ID, PLAYABLE_STATE_SCOPE, torch.getId(), 1);

    var inventory =
        inventoryService.listInventory(
            tenantId, alice.getId(), GAME_INSTANCE_ID, PLAYABLE_STATE_SCOPE, Pageable.unpaged());
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
        tenantId,
        alice.getId(),
        backpackEntry.containerInstanceId(),
        GAME_INSTANCE_ID,
        PLAYABLE_STATE_SCOPE,
        null,
        torch.getId(),
        torchEntry.itemInstanceId(),
        null,
        1,
        null,
        null);

    inventoryService.dropItemToRoom(
        tenantId,
        alice.getId(),
        GAME_INSTANCE_ID,
        PLAYABLE_STATE_SCOPE,
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
            tenantId, GAME_INSTANCE_ID, ROOM_INSTANCE_ID, Pageable.unpaged());
    var droppedBackpack =
        roomGround.getContent().stream()
            .filter(entry -> entry.itemId().equals(backpack.getId()))
            .findFirst()
            .orElseThrow();

    assertThat(droppedBackpack.containerInstanceId())
        .isEqualTo(backpackEntry.containerInstanceId());

    var roomContents =
        containerService.listContainerContents(
            tenantId,
            alice.getId(),
            backpackEntry.containerInstanceId(),
            GAME_INSTANCE_ID,
            PLAYABLE_STATE_SCOPE,
            ROOM_INSTANCE_ID,
            Pageable.unpaged());
    assertThat(roomContents.getContent())
        .singleElement()
        .satisfies(item -> assertThat(item.itemName()).isEqualTo("Torch"));

    inventoryService.pickupItemFromRoom(
        tenantId,
        bob.getId(),
        GAME_INSTANCE_ID,
        PLAYABLE_STATE_SCOPE,
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
            tenantId,
            bob.getId(),
            backpackEntry.containerInstanceId(),
            GAME_INSTANCE_ID,
            PLAYABLE_STATE_SCOPE,
            null,
            Pageable.unpaged());
    assertThat(bobContents.getContent())
        .singleElement()
        .satisfies(item -> assertThat(item.itemName()).isEqualTo("Torch"));
  }

  @Test
  void identicalContainersKeepDistinctContentsByContainerInstance() {
    Character alice = characterRepository.save(character("Alice"));
    registerCharacterFixture(alice);
    Item backpack = itemRepository.save(containerItem("Backpack"));
    Item torch = itemRepository.save(ordinaryItem("Torch"));
    Item ration = itemRepository.save(ordinaryItem("Ration"));

    inventoryService.addItem(
        tenantId, alice.getId(), GAME_INSTANCE_ID, PLAYABLE_STATE_SCOPE, backpack.getId(), 2);
    inventoryService.addItem(
        tenantId, alice.getId(), GAME_INSTANCE_ID, PLAYABLE_STATE_SCOPE, torch.getId(), 1);
    inventoryService.addItem(
        tenantId, alice.getId(), GAME_INSTANCE_ID, PLAYABLE_STATE_SCOPE, ration.getId(), 1);

    var inventory =
        inventoryService.listInventory(
            tenantId, alice.getId(), GAME_INSTANCE_ID, PLAYABLE_STATE_SCOPE, Pageable.unpaged());
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
        tenantId,
        alice.getId(),
        backpacks.get(0).containerInstanceId(),
        GAME_INSTANCE_ID,
        PLAYABLE_STATE_SCOPE,
        null,
        torch.getId(),
        torchEntry.itemInstanceId(),
        null,
        1,
        null,
        null);
    containerService.putItemIntoContainer(
        tenantId,
        alice.getId(),
        backpacks.get(1).containerInstanceId(),
        GAME_INSTANCE_ID,
        PLAYABLE_STATE_SCOPE,
        null,
        ration.getId(),
        rationEntry.itemInstanceId(),
        null,
        1,
        null,
        null);

    var firstContents =
        containerService.listContainerContents(
            tenantId,
            alice.getId(),
            backpacks.get(0).containerInstanceId(),
            GAME_INSTANCE_ID,
            PLAYABLE_STATE_SCOPE,
            null,
            Pageable.unpaged());
    var secondContents =
        containerService.listContainerContents(
            tenantId,
            alice.getId(),
            backpacks.get(1).containerInstanceId(),
            GAME_INSTANCE_ID,
            PLAYABLE_STATE_SCOPE,
            null,
            Pageable.unpaged());

    assertThat(firstContents.getContent())
        .singleElement()
        .satisfies(item -> assertThat(item.itemName()).isEqualTo("Torch"));
    assertThat(secondContents.getContent())
        .singleElement()
        .satisfies(item -> assertThat(item.itemName()).isEqualTo("Ration"));
  }

  private Character character(String name) {
    Character character = new Character();
    character.setTenantId(tenantId);
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

  private void registerCharacterFixture(Character character) {
    assertThat(character.getActorIdentity()).isNotNull();
    assertThat(character.getActorIdentity().status()).isEqualTo(ActorIdentityStatus.QUARANTINED);
    registeredCharacterFixtures.put(character.getId(), character);
  }

  private Item containerItem(String name) {
    Item item = ordinaryItem(name);
    item.setContainer(true);
    return item;
  }

  private Item ordinaryItem(String name) {
    Item item = new Item();
    item.setTenantId(tenantId);
    item.setVersionId(1L);
    item.setName(name);
    item.setDescription(name + " desc");
    item.setContainer(false);
    item.setStackable(false);
    item.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_ONLY);
    return item;
  }
}
