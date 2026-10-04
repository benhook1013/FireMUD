package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.repository.ActorActiveConditionRepository;
import net.firedevops.firemud.entitymanagement.repository.ActorIdentityRepository;
import net.firedevops.firemud.entitymanagement.repository.ActorResourceStateRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.QuarantinedActorRetentionRepository;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.service.RuntimeInstanceCleanupService;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
class RetainedActorIdentityIntegrationTest {
  private static final long TENANT_ID = 72L;
  private static final long ACCOUNT_ID = 88L;
  private static final String INSTANCE_ID = "GI-RETAINED";
  private static final UUID TENANT_UUID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID ACCOUNT_UUID = UUID.fromString("20000000-0000-4000-8000-000000000002");
  private static final UUID NAMESPACE_UUID =
      UUID.fromString("30000000-0000-4000-8000-000000000003");
  private String migrationSchema;

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "entity_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private ActorIdentityRepository actorIdentityRepository;
  @Autowired private CharacterRepository characterRepository;
  @Autowired private ActorActiveConditionRepository actorActiveConditionRepository;
  @Autowired private ActorResourceStateRepository actorResourceStateRepository;
  @Autowired private QuarantinedActorRetentionRepository quarantinedActorRetentionRepository;
  @Autowired private RuntimeInstanceCleanupService runtimeInstanceCleanupService;
  @Autowired private ScopedCharacterResolver scopedCharacterResolver;
  @Autowired private CharacterService characterService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @BeforeEach
  void clearServiceSchema() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE entity_mutation_effects, item_transfer_audits, actor_active_conditions, "
            + "actor_resource_states, character_friend, character_equipment, inventory, item_stacks, "
            + "item_instances, container_instances, room_ground_inventory, items, characters, "
            + "entity_playable_state_namespace_scopes RESTART IDENTITY CASCADE");
  }

  @Test
  void canonicalRosterIsExactOrderedAndExcludesLegacyQuarantine() {
    insertNamespace(TENANT_UUID, NAMESPACE_UUID, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    UUID first = UUID.fromString("40000000-0000-4000-8000-000000000001");
    UUID second = UUID.fromString("40000000-0000-4000-8000-000000000002");
    UUID third = UUID.fromString("40000000-0000-4000-8000-000000000003");
    long legacyId =
        insertActor(
            "legacy-shared",
            UUID.fromString("40000000-0000-4000-8000-000000000000"),
            null,
            null,
            null,
            null,
            "shared-live",
            "QUARANTINED",
            "OWNER_PROVENANCE_MISSING",
            ACCOUNT_ID,
            TENANT_ID);

    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                TENANT_UUID.toString(),
                ACCOUNT_UUID.toString(),
                NAMESPACE_UUID.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isEmpty();
    assertThat(characterRepository.findByIdAndTenantId(legacyId, TENANT_ID)).isEmpty();

    insertActor(
        "third",
        third,
        ACCOUNT_UUID,
        TENANT_UUID,
        NAMESPACE_UUID,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        "shared-live",
        "OWNER_RESOLVED",
        null,
        ACCOUNT_ID,
        TENANT_ID);
    insertActor(
        "first",
        first,
        ACCOUNT_UUID,
        TENANT_UUID,
        NAMESPACE_UUID,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        "shared-live",
        "OWNER_RESOLVED",
        null,
        ACCOUNT_ID,
        TENANT_ID);
    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                TENANT_UUID.toString(),
                ACCOUNT_UUID.toString(),
                NAMESPACE_UUID.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .extracting(identity -> identity.characterUuid())
        .containsExactly(first, third);
    insertActor(
        "second",
        second,
        ACCOUNT_UUID,
        TENANT_UUID,
        NAMESPACE_UUID,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        "shared-live",
        "OWNER_RESOLVED",
        null,
        ACCOUNT_ID,
        TENANT_ID);

    var roster =
        actorIdentityRepository.findOwnerResolvedRoster(
            TENANT_UUID.toString(),
            ACCOUNT_UUID.toString(),
            NAMESPACE_UUID.toString(),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(roster)
        .extracting(identity -> identity.characterUuid())
        .containsExactly(first, second, third);
    assertThat(
            actorIdentityRepository.findOwnerResolvedActor(
                TENANT_UUID.toString(),
                ACCOUNT_UUID.toString(),
                NAMESPACE_UUID.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                first.toString()))
        .get()
        .extracting(identity -> identity.characterUuid())
        .isEqualTo(first);

    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                "50000000-0000-4000-8000-000000000005",
                ACCOUNT_UUID.toString(),
                NAMESPACE_UUID.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isEmpty();
    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                TENANT_UUID.toString(),
                "60000000-0000-4000-8000-000000000006",
                NAMESPACE_UUID.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isEmpty();
    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                TENANT_UUID.toString(),
                ACCOUNT_UUID.toString(),
                "70000000-0000-4000-8000-000000000007",
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isEmpty();
    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                TENANT_UUID.toString(),
                ACCOUNT_UUID.toString(),
                NAMESPACE_UUID.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .isEmpty();
    assertThat(
            actorIdentityRepository.findOwnerResolvedActor(
                TENANT_UUID.toString(),
                ACCOUNT_UUID.toString(),
                NAMESPACE_UUID.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                "40000000-0000-4000-8000-000000000000"))
        .isEmpty();

    assertThatThrownBy(
            () ->
                actorIdentityRepository.findOwnerResolvedRoster(
                    null,
                    ACCOUNT_UUID.toString(),
                    NAMESPACE_UUID.toString(),
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                actorIdentityRepository.findOwnerResolvedRoster(
                    TENANT_UUID.toString(),
                    ACCOUNT_UUID.toString(),
                    "not-a-uuid",
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                actorIdentityRepository.findOwnerResolvedRoster(
                    TENANT_UUID.toString(),
                    "00000000-0000-0000-0000-000000000000",
                    NAMESPACE_UUID.toString(),
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                insertNamespace(
                    TENANT_UUID, NAMESPACE_UUID, PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE entity_playable_state_namespace_scopes SET playable_state_scope = ? "
                        + "WHERE tenant_uuid = ? AND playable_state_namespace_id = ?",
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED.name(),
                    TENANT_UUID,
                    NAMESPACE_UUID))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void namespaceIdentityAndScopeAreImmutableWithoutActorReferences() {
    for (PlayableStateScope scope :
        new PlayableStateScope[] {
          PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
          PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED
        }) {
      UUID tenantUuid = UUID.randomUUID();
      UUID namespaceUuid = UUID.randomUUID();
      UUID changedTenantUuid = UUID.randomUUID();
      UUID changedNamespaceUuid = UUID.randomUUID();
      PlayableStateScope changedScope =
          scope == PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
              ? PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED
              : PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      insertNamespace(tenantUuid, namespaceUuid, scope);

      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM characters WHERE tenant_uuid = ? AND playable_state_namespace_id = ?",
                  Integer.class,
                  tenantUuid,
                  namespaceUuid))
          .isZero();
      assertThat(
              jdbcTemplate.update(
                  "UPDATE entity_playable_state_namespace_scopes "
                      + "SET tenant_uuid = ?, playable_state_namespace_id = ?, playable_state_scope = ? "
                      + "WHERE tenant_uuid = ? AND playable_state_namespace_id = ?",
                  tenantUuid,
                  namespaceUuid,
                  scope.name(),
                  tenantUuid,
                  namespaceUuid))
          .isOne();

      assertNamespaceIdentityUpdateRejected(
          "UPDATE entity_playable_state_namespace_scopes SET tenant_uuid = ? "
              + "WHERE tenant_uuid = ? AND playable_state_namespace_id = ?",
          changedTenantUuid,
          tenantUuid,
          namespaceUuid);
      assertNamespaceIdentityUpdateRejected(
          "UPDATE entity_playable_state_namespace_scopes SET playable_state_namespace_id = ? "
              + "WHERE tenant_uuid = ? AND playable_state_namespace_id = ?",
          changedNamespaceUuid,
          tenantUuid,
          namespaceUuid);
      assertNamespaceIdentityUpdateRejected(
          "UPDATE entity_playable_state_namespace_scopes SET playable_state_scope = ? "
              + "WHERE tenant_uuid = ? AND playable_state_namespace_id = ?",
          changedScope.name(),
          tenantUuid,
          namespaceUuid);

      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT playable_state_scope FROM entity_playable_state_namespace_scopes "
                      + "WHERE tenant_uuid = ? AND playable_state_namespace_id = ?",
                  String.class,
                  tenantUuid,
                  namespaceUuid))
          .isEqualTo(scope.name());
    }
  }

  @Test
  void quarantinedActorLookupMutationExpiryAndCleanupRemainHeld() {
    Character actor = new Character();
    actor.setTenantId(TENANT_ID);
    actor.setAccountId(ACCOUNT_ID);
    actor.setPlayableStateKey("instance:" + INSTANCE_ID);
    actor.setName("Retained actor");
    actor.setExperience(17);
    actor = characterRepository.save(actor);
    long actorId = actor.getId();
    assertThat(actor.getActorIdentity().status().name()).isEqualTo("QUARANTINED");
    assertThat(actor.getActorIdentity().characterUuid()).isNotNull();
    assertThat(characterRepository.findById(actorId)).isEmpty();

    ActorResourceState resourceWrite = new ActorResourceState();
    resourceWrite.setTenantId(TENANT_ID);
    resourceWrite.setCharacterId(actorId);
    resourceWrite.setPlayableStateKey("instance:" + INSTANCE_ID);
    resourceWrite.setStatKey("health");
    resourceWrite.setCurrentValue(31L);
    assertThatThrownBy(() -> actorResourceStateRepository.save(resourceWrite))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ACTOR_IDENTITY_NOT_OWNER_RESOLVED");

    ActorActiveCondition conditionWrite = new ActorActiveCondition();
    conditionWrite.setTenantId(TENANT_ID);
    conditionWrite.setCharacterId(actorId);
    conditionWrite.setPlayableStateKey("instance:" + INSTANCE_ID);
    conditionWrite.setConditionKey("stunned");
    conditionWrite.setSourceType("TEST");
    assertThatThrownBy(() -> actorActiveConditionRepository.save(conditionWrite))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ACTOR_IDENTITY_NOT_OWNER_RESOLVED");

    jdbcTemplate.update(
        "INSERT INTO items (id, name, tenant_id, is_container) VALUES "
            + "(3001, 'Backpack', ?, true), (3002, 'Pouch', ?, true), (3003, 'Gem', ?, false)",
        TENANT_ID,
        TENANT_ID,
        TENANT_ID);
    jdbcTemplate.update(
        "INSERT INTO inventory (character_id, item_id, quantity, version) VALUES (?, 3003, 5, 2)",
        actorId);
    jdbcTemplate.update(
        "INSERT INTO character_equipment (character_id, slot, item_id, version) "
            + "VALUES (?, 'hand', 3003, 3)",
        actorId);
    jdbcTemplate.update(
        "INSERT INTO character_friend (character_id, friend_id, tenant_id, status) "
            + "VALUES (?, ?, ?, 'pending')",
        actorId,
        actorId,
        TENANT_ID);
    jdbcTemplate.update(
        "INSERT INTO actor_resource_states "
            + "(tenant_id, character_id, stat_key, current_value, max_value, base_value, source_type, source_id, playable_state_key, version) "
            + "VALUES (?, ?, 'health', 31, 90, 40, 'LEGACY_SOURCE', 'source-17', ?, 4)",
        TENANT_ID,
        actorId,
        "instance:" + INSTANCE_ID);
    jdbcTemplate.update(
        "INSERT INTO actor_active_conditions "
            + "(tenant_id, character_id, condition_key, stack_count, source_type, source_id, started_at, expires_at, effect_payload_json, playable_state_key, version) "
            + "VALUES (?, ?, 'stunned', 3, 'LEGACY_SOURCE', 'condition-19', '2020-01-01T00:00:00Z', '2020-01-02T00:00:00Z', '{\"modifier\":2}', ?, 5)",
        TENANT_ID,
        actorId,
        "instance:" + INSTANCE_ID);

    jdbcTemplate.update(
        "INSERT INTO container_instances (id, tenant_id, character_id, game_instance_id, item_id, version) "
            + "VALUES (3101, ?, ?, ?, 3001, 6)",
        TENANT_ID,
        actorId,
        INSTANCE_ID);
    insertItemInstance(3201, actorId, INSTANCE_ID, null, 3001, "retained-backpack");
    jdbcTemplate.update("UPDATE container_instances SET item_instance_id = 3201 WHERE id = 3101");
    insertItemInstance(3202, null, INSTANCE_ID, 3101L, 3002, "retained-pouch");
    jdbcTemplate.update(
        "INSERT INTO container_instances (id, tenant_id, game_instance_id, item_id, version, item_instance_id) "
            + "VALUES (3102, ?, ?, 3002, 7, 3202)",
        TENANT_ID,
        INSTANCE_ID);
    insertItemInstance(3203, null, INSTANCE_ID, 3102L, 3003, "retained-gem");
    jdbcTemplate.update(
        "INSERT INTO item_stacks (id, tenant_id, character_id, game_instance_id, item_id, compatibility_fingerprint, quantity, version, stack_family_key) "
            + "VALUES (3301, ?, ?, ?, 3003, 'actor-stack', 8, 2, 'actor-family')",
        TENANT_ID,
        actorId,
        INSTANCE_ID);
    jdbcTemplate.update(
        "INSERT INTO item_stacks (id, tenant_id, game_instance_id, container_instance_id, item_id, compatibility_fingerprint, quantity, version, stack_family_key) "
            + "VALUES (3302, ?, ?, 3102, 3003, 'nested-stack', 9, 3, 'nested-family')",
        TENANT_ID,
        INSTANCE_ID);
    jdbcTemplate.update(
        "INSERT INTO room_ground_inventory (tenant_id, game_instance_id, room_instance_id, item_id, quantity, version) "
            + "VALUES (?, ?, 'room-retained', 3003, 11, 8)",
        TENANT_ID,
        INSTANCE_ID);
    jdbcTemplate.update(
        "INSERT INTO item_transfer_audits "
            + "(id, tenant_id, item_id, item_instance_id, quantity, verb, actor_character_id, effect_id, correlation_key, source_holder_kind, source_character_id, source_game_instance_id, destination_holder_kind, destination_game_instance_id, destination_room_instance_id) "
            + "VALUES (3401, ?, 3001, 3201, 1, 'DROP', ?, 'effect-retained', 'corr-retained', 'CHARACTER', ?, ?, 'ROOM', ?, 'room-retained')",
        TENANT_ID,
        actorId,
        actorId,
        INSTANCE_ID,
        INSTANCE_ID);
    jdbcTemplate.update(
        "INSERT INTO entity_mutation_effects (id, tenant_id, effect_id, operation_name, response_type, response_payload, status) "
            + "VALUES (3501, ?, 'effect-retained', 'DROP', 'DropResponse', decode('aabb', 'hex'), 'APPLIED')",
        TENANT_ID);

    assertThatThrownBy(
            () ->
                scopedCharacterResolver.requireScopedCharacter(
                    TENANT_ID,
                    actorId,
                    INSTANCE_ID,
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OWNER_RESOLVED_ACTOR_IDENTITY_REQUIRED");
    assertThatThrownBy(
            () ->
                characterService.gainExperience(
                    TENANT_ID,
                    actorId,
                    INSTANCE_ID,
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
                    100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                runtimeInstanceCleanupService.cleanupRuntimeInstance(
                    TENANT_ID, INSTANCE_ID, "termination-retained"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ENTITY_UNCLASSIFIED_OR_QUARANTINED_EVIDENCE_BLOCKS_CLEANUP");

    assertThat(
            quarantinedActorRetentionRepository.hasUnclassifiedOrQuarantinedRuntimeEvidence(
                TENANT_ID, INSTANCE_ID))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_quarantined_actor_item_instances WHERE tenant_id = ?",
                Integer.class,
                TENANT_ID))
        .isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_quarantined_actor_container_instances WHERE tenant_id = ?",
                Integer.class,
                TENANT_ID))
        .isEqualTo(2);
    assertThat(actorActiveConditionRepository.deleteExpired(Instant.parse("2030-01-01T00:00:00Z")))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT experience FROM characters WHERE id = ?", Integer.class, actorId))
        .isEqualTo(17);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory WHERE character_id = ? AND item_id = 3003",
                Integer.class,
                actorId))
        .isEqualTo(5);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT version FROM character_equipment WHERE character_id = ? AND slot = 'hand'",
                Integer.class,
                actorId))
        .isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM character_friend WHERE character_id = ? AND friend_id = ?",
                String.class,
                actorId,
                actorId))
        .isEqualTo("pending");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT current_value FROM actor_resource_states WHERE character_id = ? AND stat_key = 'health'",
                Long.class,
                actorId))
        .isEqualTo(31L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT effect_payload_json FROM actor_active_conditions WHERE character_id = ?",
                String.class,
                actorId))
        .isEqualTo("{\"modifier\":2}");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM item_instances WHERE tenant_id = ? AND game_instance_id = ?",
                Integer.class,
                TENANT_ID,
                INSTANCE_ID))
        .isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM container_instances WHERE tenant_id = ? AND game_instance_id = ?",
                Integer.class,
                TENANT_ID,
                INSTANCE_ID))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT sum(quantity) FROM item_stacks WHERE tenant_id = ? AND game_instance_id = ?",
                Integer.class,
                TENANT_ID,
                INSTANCE_ID))
        .isEqualTo(17);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT quantity FROM room_ground_inventory WHERE tenant_id = ? AND game_instance_id = ?",
                Integer.class,
                TENANT_ID,
                INSTANCE_ID))
        .isEqualTo(11);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT correlation_key FROM item_transfer_audits WHERE id = 3401", String.class))
        .isEqualTo("corr-retained");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT encode(response_payload, 'hex') FROM entity_mutation_effects WHERE id = 3501",
                String.class))
        .isEqualTo("aabb");
  }

  @Test
  void unresolvedRoomGroundIsHeldWithoutInferringActorOrS3Mapping() {
    insertActor(
        "legacy shared actor",
        UUID.fromString("41000000-0000-4000-8000-000000000001"),
        null,
        null,
        null,
        null,
        "shared-live",
        "QUARANTINED",
        "OWNER_PROVENANCE_MISSING",
        ACCOUNT_ID,
        TENANT_ID);
    jdbcTemplate.update(
        "INSERT INTO items (id, name, tenant_id, is_container) VALUES (3901, 'Unresolved ground item', ?, false)",
        TENANT_ID);
    jdbcTemplate.update(
        "INSERT INTO room_ground_inventory (tenant_id, game_instance_id, room_instance_id, item_id, quantity, version) "
            + "VALUES (?, ?, 'room-unresolved', 3901, 2, 1)",
        TENANT_ID,
        INSTANCE_ID);

    assertThat(
            quarantinedActorRetentionRepository.hasUnclassifiedOrQuarantinedRuntimeEvidence(
                TENANT_ID, INSTANCE_ID))
        .isTrue();
    assertThatThrownBy(
            () ->
                runtimeInstanceCleanupService.cleanupRuntimeInstance(
                    TENANT_ID, INSTANCE_ID, "termination-unresolved-ground"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ENTITY_UNCLASSIFIED_OR_QUARANTINED_EVIDENCE_BLOCKS_CLEANUP");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT quantity FROM room_ground_inventory WHERE tenant_id = ? AND game_instance_id = ?",
                Integer.class,
                TENANT_ID,
                INSTANCE_ID))
        .isEqualTo(2);
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void flywayV1ToLatestRetainsLegacyRowsAndValidatesConstraints() throws SQLException {
    migrateLegacySchemaToV1();
    try (Connection connection = connectToMigrationSchema()) {
      var statement = connection.createStatement();
      statement.executeUpdate(
          "INSERT INTO characters (id, account_id, name, tenant_id, playable_state_key, level, experience, strength, agility, intelligence, stamina, health, mana, version, body_layout_key) "
              + "VALUES (101, 81, 'Legacy Shared', 71, 'shared-live', 9, 17, 21, 22, 23, 24, 31, 32, 12, 'LEGACY_BODY'), "
              + "(102, 81, 'Legacy Instance', 71, 'instance:44', 3, 7, 11, 12, 13, 14, 15, 16, 5, 'LEGACY_BODY')");
      statement.executeUpdate(
          "INSERT INTO items (id, name, tenant_id, is_container) VALUES (201, 'Bag', 71, true), (202, 'Gem', 71, false)");
      statement.executeUpdate(
          "INSERT INTO inventory (character_id, item_id, quantity, version) VALUES (101, 202, 6, 3)");
      statement.executeUpdate(
          "INSERT INTO character_friend (character_id, friend_id, tenant_id, status) VALUES (101, 102, 71, 'pending')");
      statement.executeUpdate(
          "INSERT INTO character_equipment (character_id, slot, item_id, version) VALUES (101, 'hand', 202, 4)");
      statement.executeUpdate(
          "INSERT INTO actor_resource_states (id, tenant_id, character_id, stat_key, current_value, max_value, base_value, source_type, source_id, playable_state_key, version) VALUES (301, 71, 101, 'health', 31, 90, 40, 'LEGACY_SOURCE', 'resource-source', 'shared-live', 8)");
      statement.executeUpdate(
          "INSERT INTO actor_active_conditions (id, tenant_id, character_id, condition_key, stack_count, source_type, source_id, started_at, expires_at, effect_payload_json, playable_state_key, version) VALUES (302, 71, 101, 'stunned', 3, 'LEGACY_SOURCE', 'condition-source', '2020-01-01T00:00:00Z', '2020-01-02T00:00:00Z', '{\"modifier\":2}', 'shared-live', 9)");
      statement.executeUpdate(
          "INSERT INTO container_instances (id, tenant_id, character_id, game_instance_id, item_id, version) VALUES (401, 71, 101, '44', 201, 10)");
      statement.executeUpdate(
          "INSERT INTO item_instances (id, tenant_id, character_id, game_instance_id, container_instance_id, item_id, visible_ref_token, visible_ref_sequence, visible_ref, version) VALUES (501, 71, 101, '44', NULL, 201, 'legacy', 1, 'legacy-bag', 11)");
      statement.executeUpdate(
          "UPDATE container_instances SET item_instance_id = 501 WHERE id = 401");
      statement.executeUpdate(
          "INSERT INTO item_instances (id, tenant_id, game_instance_id, container_instance_id, item_id, visible_ref_token, visible_ref_sequence, visible_ref, version) VALUES (502, 71, '44', 401, 202, 'legacy', 2, 'legacy-gem', 12)");
      statement.executeUpdate(
          "INSERT INTO item_stacks (id, tenant_id, character_id, game_instance_id, item_id, compatibility_fingerprint, quantity, version, stack_family_key) VALUES (601, 71, 101, '44', 202, 'legacy-fingerprint', 13, 14, 'legacy-family')");
      statement.executeUpdate(
          "INSERT INTO room_ground_inventory (tenant_id, game_instance_id, room_instance_id, item_id, quantity, version) VALUES (71, '44', 'room-9', 202, 15, 16)");
      statement.executeUpdate(
          "INSERT INTO item_transfer_audits (id, tenant_id, item_id, item_instance_id, quantity, verb, actor_character_id, effect_id, correlation_key, source_holder_kind, source_character_id, source_game_instance_id, destination_holder_kind, destination_game_instance_id, destination_room_instance_id) VALUES (701, 71, 202, 502, 2, 'DROP', 101, 'legacy-effect', 'legacy-correlation', 'CHARACTER', 101, '44', 'ROOM', '44', 'room-9')");
      statement.executeUpdate(
          "INSERT INTO entity_mutation_effects (id, tenant_id, effect_id, operation_name, response_type, response_payload, status) VALUES (801, 71, 'legacy-effect', 'DROP', 'DropResponse', decode('cafe', 'hex'), 'APPLIED')");
      statement.close();
    }

    migrateLegacySchemaToLatest();

    try (Connection connection = connectToMigrationSchema();
        var statement = connection.createStatement()) {
      assertThat(
              readLong(
                  statement,
                  "SELECT count(*) FROM pg_constraint "
                      + "WHERE conrelid = 'characters'::regclass AND convalidated "
                      + "AND conname IN ('ck_characters_actor_identity_status', "
                      + "'ck_characters_actor_identity_quarantine_reason', "
                      + "'ck_characters_actor_identity_uuid_non_nil', "
                      + "'ck_characters_actor_identity_scope', "
                      + "'ck_characters_owner_resolved_provenance', "
                      + "'fk_characters_owner_resolved_namespace_scope', "
                      + "'ck_characters_character_uuid_non_nil', "
                      + "'ux_characters_character_uuid')"))
          .isEqualTo(8L);
      assertThat(
              readLong(
                  statement,
                  "SELECT count(*) FROM pg_index indexes "
                      + "JOIN pg_class index_relation ON index_relation.oid = indexes.indexrelid "
                      + "JOIN pg_namespace index_schema ON index_schema.oid = index_relation.relnamespace "
                      + "WHERE index_schema.nspname = current_schema() "
                      + "AND index_relation.relname IN ('ux_characters_character_uuid', "
                      + "'idx_characters_owner_resolved_roster') "
                      + "AND indexes.indisvalid AND indexes.indisready"))
          .isEqualTo(2L);
      assertThat(
              readLong(
                  statement,
                  "SELECT count(*) FROM pg_attribute "
                      + "WHERE attrelid = 'characters'::regclass AND attname = 'character_uuid' "
                      + "AND attnotnull AND NOT attisdropped"))
          .isEqualTo(1L);
      try (var rows =
          statement.executeQuery(
              "SELECT id, account_id, name, tenant_id, playable_state_key, level, experience, strength, agility, intelligence, stamina, health, mana, version, body_layout_key, character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, playable_state_scope, actor_identity_status, actor_identity_quarantine_reason FROM characters ORDER BY id")) {
        assertThat(rows.next()).isTrue();
        UUID firstActorUuid = rows.getObject("character_uuid", UUID.class);
        assertThat(firstActorUuid).isNotNull().isNotEqualTo(new UUID(0L, 0L));
        assertThat(rows.getLong("id")).isEqualTo(101L);
        assertThat(rows.getLong("account_id")).isEqualTo(81L);
        assertThat(rows.getString("name")).isEqualTo("Legacy Shared");
        assertThat(rows.getLong("tenant_id")).isEqualTo(71L);
        assertThat(rows.getString("playable_state_key")).isEqualTo("shared-live");
        assertThat(rows.getInt("level")).isEqualTo(9);
        assertThat(rows.getInt("experience")).isEqualTo(17);
        assertThat(rows.getInt("strength")).isEqualTo(21);
        assertThat(rows.getInt("agility")).isEqualTo(22);
        assertThat(rows.getInt("intelligence")).isEqualTo(23);
        assertThat(rows.getInt("stamina")).isEqualTo(24);
        assertThat(rows.getInt("health")).isEqualTo(31);
        assertThat(rows.getInt("mana")).isEqualTo(32);
        assertThat(rows.getInt("version")).isEqualTo(12);
        assertThat(rows.getString("body_layout_key")).isEqualTo("LEGACY_BODY");
        assertThat(rows.getObject("account_uuid")).isNull();
        assertThat(rows.getObject("tenant_uuid")).isNull();
        assertThat(rows.getObject("playable_state_namespace_id")).isNull();
        assertThat(rows.getString("playable_state_scope")).isNull();
        assertThat(rows.getString("actor_identity_status")).isEqualTo("QUARANTINED");
        assertThat(rows.getString("actor_identity_quarantine_reason"))
            .isEqualTo("OWNER_PROVENANCE_MISSING");
        assertThat(rows.next()).isTrue();
        UUID secondActorUuid = rows.getObject("character_uuid", UUID.class);
        assertThat(secondActorUuid).isNotNull().isNotEqualTo(firstActorUuid);
        assertThat(rows.getLong("id")).isEqualTo(102L);
        assertThat(rows.getLong("account_id")).isEqualTo(81L);
        assertThat(rows.getLong("tenant_id")).isEqualTo(71L);
        assertThat(rows.getString("playable_state_key")).isEqualTo("instance:44");
        assertThat(rows.getString("actor_identity_status")).isEqualTo("QUARANTINED");
        assertThat(rows.getObject("account_uuid")).isNull();
        assertThat(rows.getObject("playable_state_namespace_id")).isNull();
        assertThat(rows.next()).isFalse();
      }
      assertThat(
              readLong(
                  statement,
                  "SELECT quantity FROM inventory WHERE character_id = 101 AND item_id = 202"))
          .isEqualTo(6L);
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', character_id, item_id, quantity, version) FROM inventory WHERE character_id = 101 AND item_id = 202"))
          .isEqualTo("101|202|6|3");
      assertThat(
              readString(statement, "SELECT status FROM character_friend WHERE character_id = 101"))
          .isEqualTo("pending");
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', character_id, friend_id, tenant_id, status) FROM character_friend WHERE character_id = 101"))
          .isEqualTo("101|102|71|pending");
      assertThat(
              readLong(
                  statement, "SELECT version FROM character_equipment WHERE character_id = 101"))
          .isEqualTo(4L);
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', character_id, slot, item_id, version) FROM character_equipment WHERE character_id = 101"))
          .isEqualTo("101|hand|202|4");
      assertThat(
              readLong(statement, "SELECT current_value FROM actor_resource_states WHERE id = 301"))
          .isEqualTo(31L);
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', id, tenant_id, character_id, stat_key, current_value, max_value, base_value, source_type, source_id, playable_state_key, version) FROM actor_resource_states WHERE id = 301"))
          .isEqualTo("301|71|101|health|31|90|40|LEGACY_SOURCE|resource-source|shared-live|8");
      assertThat(
              readString(
                  statement,
                  "SELECT effect_payload_json FROM actor_active_conditions WHERE id = 302"))
          .isEqualTo("{\"modifier\":2}");
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', id, tenant_id, character_id, condition_key, stack_count, source_type, source_id, playable_state_key, version) FROM actor_active_conditions WHERE id = 302"))
          .isEqualTo("302|71|101|stunned|3|LEGACY_SOURCE|condition-source|shared-live|9");
      assertThat(
              readLong(
                  statement,
                  "SELECT extract(epoch FROM started_at)::bigint FROM actor_active_conditions WHERE id = 302"))
          .isEqualTo(1577836800L);
      assertThat(
              readLong(
                  statement,
                  "SELECT extract(epoch FROM expires_at)::bigint FROM actor_active_conditions WHERE id = 302"))
          .isEqualTo(1577923200L);
      assertThat(readLong(statement, "SELECT count(*) FROM item_instances WHERE id IN (501, 502)"))
          .isEqualTo(2L);
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', id, tenant_id, character_id, game_instance_id, container_instance_id, item_id, visible_ref_token, visible_ref_sequence, visible_ref, version) FROM item_instances WHERE id = 501"))
          .isEqualTo("501|71|101|44|201|legacy|1|legacy-bag|11");
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', id, tenant_id, character_id, game_instance_id, container_instance_id, item_id, visible_ref_token, visible_ref_sequence, visible_ref, version) FROM item_instances WHERE id = 502"))
          .isEqualTo("502|71|44|401|202|legacy|2|legacy-gem|12");
      assertThat(
              readLong(
                  statement, "SELECT container_instance_id FROM item_instances WHERE id = 502"))
          .isEqualTo(401L);
      assertThat(
              readLong(
                  statement, "SELECT item_instance_id FROM container_instances WHERE id = 401"))
          .isEqualTo(501L);
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', id, tenant_id, character_id, game_instance_id, item_id, item_instance_id, version) FROM container_instances WHERE id = 401"))
          .isEqualTo("401|71|101|44|201|501|10");
      assertThat(readLong(statement, "SELECT quantity FROM item_stacks WHERE id = 601"))
          .isEqualTo(13L);
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', id, tenant_id, character_id, game_instance_id, item_id, compatibility_fingerprint, quantity, version, stack_family_key) FROM item_stacks WHERE id = 601"))
          .isEqualTo("601|71|101|44|202|legacy-fingerprint|13|14|legacy-family");
      assertThat(
              readLong(
                  statement,
                  "SELECT quantity FROM room_ground_inventory WHERE room_instance_id = 'room-9'"))
          .isEqualTo(15L);
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', tenant_id, game_instance_id, room_instance_id, item_id, quantity, version) FROM room_ground_inventory WHERE room_instance_id = 'room-9'"))
          .isEqualTo("71|44|room-9|202|15|16");
      assertThat(
              readString(
                  statement, "SELECT correlation_key FROM item_transfer_audits WHERE id = 701"))
          .isEqualTo("legacy-correlation");
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', id, tenant_id, item_id, item_instance_id, quantity, verb, actor_character_id, effect_id, correlation_key, source_holder_kind, source_character_id, source_game_instance_id, destination_holder_kind, destination_game_instance_id, destination_room_instance_id) FROM item_transfer_audits WHERE id = 701"))
          .isEqualTo(
              "701|71|202|502|2|DROP|101|legacy-effect|legacy-correlation|CHARACTER|101|44|ROOM|44|room-9");
      assertThat(
              readString(
                  statement,
                  "SELECT encode(response_payload, 'hex') FROM entity_mutation_effects WHERE id = 801"))
          .isEqualTo("cafe");
      assertThat(
              readString(
                  statement,
                  "SELECT concat_ws('|', id, tenant_id, effect_id, operation_name, response_type, encode(response_payload, 'hex'), status) FROM entity_mutation_effects WHERE id = 801"))
          .isEqualTo("801|71|legacy-effect|DROP|DropResponse|cafe|APPLIED");
    }
  }

  private void insertNamespace(UUID tenantUuid, UUID namespaceUuid, PlayableStateScope scope) {
    jdbcTemplate.update(
        "INSERT INTO entity_playable_state_namespace_scopes "
            + "(tenant_uuid, playable_state_namespace_id, playable_state_scope) VALUES (?, ?, ?)",
        tenantUuid,
        namespaceUuid,
        scope.name());
  }

  private void assertNamespaceIdentityUpdateRejected(String sql, Object... arguments) {
    assertThatThrownBy(() -> jdbcTemplate.update(sql, arguments))
        .hasMessageContaining("entity playable-state namespace identity is immutable");
  }

  @Test
  void characterUpdateRequiresExactTenantAndPreservesOwnerResolvedRowOnMismatch() {
    UUID characterUuid = UUID.fromString("40000000-0000-4000-8000-000000000010");
    long actorId =
        insertActor(
            "Tenant-owned actor",
            characterUuid,
            ACCOUNT_UUID,
            TENANT_UUID,
            NAMESPACE_UUID,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "shared-live",
            "OWNER_RESOLVED",
            null,
            ACCOUNT_ID,
            TENANT_ID);

    Character crossTenantUpdate =
        characterRepository.findByIdAndTenantId(actorId, TENANT_ID).orElseThrow();
    int originalVersion = crossTenantUpdate.getVersion();
    int originalExperience = crossTenantUpdate.getExperience();
    crossTenantUpdate.setTenantId(TENANT_ID + 1);
    crossTenantUpdate.setName("Cross-tenant overwrite");
    crossTenantUpdate.setExperience(originalExperience + 99);

    assertThatThrownBy(() -> characterRepository.save(crossTenantUpdate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CHARACTER_IDENTITY_NOT_OWNER_RESOLVED");

    Character unchanged = characterRepository.findByIdAndTenantId(actorId, TENANT_ID).orElseThrow();
    assertThat(unchanged.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(unchanged.getName()).isEqualTo("Tenant-owned actor");
    assertThat(unchanged.getExperience()).isEqualTo(originalExperience);
    assertThat(unchanged.getVersion()).isEqualTo(originalVersion);

    Character validUpdate =
        characterRepository.findByIdAndTenantId(actorId, TENANT_ID).orElseThrow();
    validUpdate.setName("Tenant-owned actor updated");
    Character saved = characterRepository.save(validUpdate);
    assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(saved.getName()).isEqualTo("Tenant-owned actor updated");
    assertThat(saved.getVersion()).isEqualTo(originalVersion + 1);
  }

  private long insertActor(
      String name,
      UUID characterUuid,
      UUID accountUuid,
      UUID tenantUuid,
      UUID namespaceUuid,
      PlayableStateScope scope,
      String legacyStateKey,
      String status,
      String quarantineReason,
      long legacyAccountId,
      long legacyTenantId) {
    Long actorId =
        jdbcTemplate.queryForObject(
            "INSERT INTO characters (account_id, name, tenant_id, playable_state_key, character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, playable_state_scope, actor_identity_status, actor_identity_quarantine_reason) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class,
            legacyAccountId,
            name,
            legacyTenantId,
            legacyStateKey,
            characterUuid,
            accountUuid,
            tenantUuid,
            namespaceUuid,
            scope == null ? null : scope.name(),
            status,
            quarantineReason);
    return Objects.requireNonNull(
        actorId, "Character INSERT ... RETURNING id must return the inserted actor ID");
  }

  private void insertItemInstance(
      long id,
      Long characterId,
      String gameInstanceId,
      Long containerId,
      long itemId,
      String visibleRef) {
    jdbcTemplate.update(
        "INSERT INTO item_instances (id, tenant_id, character_id, game_instance_id, container_instance_id, item_id, visible_ref_token, visible_ref_sequence, visible_ref, version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
        id,
        TENANT_ID,
        characterId,
        gameInstanceId,
        containerId,
        itemId,
        "retained",
        id,
        visibleRef);
  }

  private void migrateLegacySchemaToV1() throws SQLException {
    migrationSchema = "retained_actor_v1_to_v2_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + migrationSchema);
    }
    migrationFlyway().target(MigrationVersion.fromVersion("1")).load().migrate();
  }

  private void migrateLegacySchemaToLatest() {
    migrationFlyway().load().migrate();
  }

  private FluentConfiguration migrationFlyway() {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(migrationSchema)
            .defaultSchema(migrationSchema)
            .locations("classpath:db/migration")
            .placeholders(Map.of("serviceSchema", migrationSchema));
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    return configuration.jdbcProperties(
        Map.of("options", "-c lock_timeout=30s -c statement_timeout=120s"));
  }

  private Connection connectToMigrationSchema() throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    try (var statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + migrationSchema);
    }
    return connection;
  }

  private long readLong(java.sql.Statement statement, String sql) throws SQLException {
    try (var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private String readString(java.sql.Statement statement, String sql) throws SQLException {
    try (var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }
}
