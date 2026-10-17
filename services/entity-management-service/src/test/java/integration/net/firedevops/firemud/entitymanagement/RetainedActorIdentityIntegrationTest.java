package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriend;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriendKey;
import net.firedevops.firemud.entitymanagement.repository.ActorActiveConditionRepository;
import net.firedevops.firemud.entitymanagement.repository.ActorIdentityRepository;
import net.firedevops.firemud.entitymanagement.repository.ActorResourceStateRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterFriendRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
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
  private long tenantId;
  private long accountId;
  private String instanceId;
  private UUID tenantUuid;
  private UUID accountUuid;
  private UUID namespaceUuid;
  private String testServiceSchema;

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
    registry.add(
        "spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration/saga");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private CharacterRepository characterRepository;
  @Autowired private ActorIdentityRepository actorIdentityRepository;
  @Autowired private ActorActiveConditionRepository conditionRepository;
  @Autowired private ActorResourceStateRepository resourceRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CharacterFriendRepository friendRepository;
  @Autowired private Flyway flyway;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @BeforeEach
  void allocateIsolatedFixtureIdentities() {
    tenantId = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
    accountId = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
    instanceId = "GI-RETAINED-" + UUID.randomUUID();
    tenantUuid = UUID.randomUUID();
    accountUuid = UUID.randomUUID();
    namespaceUuid = UUID.randomUUID();
  }

  @Test
  void legacySaveAllocatesQuarantinedUuidAndPreservesLegacyReadbackAndUpdates() {
    Character actor = new Character();
    actor.setTenantId(tenantId);
    actor.setAccountId(accountId);
    actor.setPlayableStateKey("instance:" + instanceId);
    actor.setName("Retained actor");
    actor.setExperience(17);
    actor = characterRepository.save(actor);
    long actorId = actor.getId();
    UUID characterUuid = actor.getActorIdentity().characterUuid();
    assertThat(characterUuid).isNotNull().isNotEqualTo(new UUID(0L, 0L));
    assertThat(actor.getActorIdentity().status().name()).isEqualTo("QUARANTINED");
    assertThat(actor.getActorIdentity().quarantineReason()).isEqualTo("OWNER_PROVENANCE_MISSING");
    assertThat(actor.getActorIdentity().accountUuid()).isNull();
    assertThat(actor.getActorIdentity().tenantUuid()).isNull();
    assertThat(actor.getActorIdentity().playableStateNamespaceId()).isNull();
    assertThat(actor.getActorIdentity().playableStateScope()).isNull();
    assertThat(characterRepository.findById(actorId)).isPresent();
    assertThat(characterRepository.findWithInventoryById(actorId)).isPresent();
    assertThat(characterRepository.findByIdAndTenantId(actorId, tenantId)).isPresent();
    assertThat(
            characterRepository.findByIdAndTenantIdAndPlayableStateKey(
                actorId, tenantId, actor.getPlayableStateKey()))
        .isPresent();
    assertThat(
            characterRepository
                .findByTenantIdAndAccountIdAndPlayableStateKey(
                    tenantId, accountId, actor.getPlayableStateKey(), null)
                .getContent())
        .extracting(Character::getId)
        .containsExactly(actorId);
    assertThat(
            characterRepository.findByTenantIdAndPlayableStateKeyAndNameIgnoreCase(
                tenantId, actor.getPlayableStateKey(), "retained ACTOR"))
        .isPresent();

    int originalVersion = actor.getVersion();
    actor.setName("Retained actor updated");
    actor.setExperience(18);
    Character saved = characterRepository.save(actor);
    assertThat(saved.getName()).isEqualTo("Retained actor updated");
    assertThat(saved.getExperience()).isEqualTo(18);
    assertThat(saved.getVersion()).isEqualTo(originalVersion + 1);
    assertThat(saved.getActorIdentity()).isEqualTo(actor.getActorIdentity());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT character_uuid FROM characters WHERE id = ?", UUID.class, actorId))
        .isEqualTo(characterUuid);
  }

  @Test
  void legacyConditionalInsertsPreserveNullablePostgresColumnTypes() {
    Character actor = new Character();
    actor.setTenantId(tenantId);
    actor.setAccountId(accountId);
    actor.setPlayableStateKey("instance:" + instanceId);
    actor.setName("Nullable legacy values");
    actor = characterRepository.save(actor);

    ActorActiveCondition condition = new ActorActiveCondition();
    condition.setTenantId(tenantId);
    condition.setPlayableStateKey(actor.getPlayableStateKey());
    condition.setCharacterId(actor.getId());
    condition.setConditionKey("permanent-condition");
    condition.setSourceType("TEST");
    conditionRepository.save(condition);
    assertThat(condition.getId()).isNotNull();
    var conditions =
        conditionRepository.findActiveForCharacter(
            tenantId, actor.getPlayableStateKey(), actor.getId(), java.time.Instant.now());
    assertThat(conditions).hasSize(1);
    assertThat(conditions.getFirst().getExpiresAt()).isNull();
    assertThat(conditions.getFirst().getSourceId()).isNull();
    assertThat(conditions.getFirst().getEffectPayloadJson()).isNull();

    ActorResourceState resource = new ActorResourceState();
    resource.setTenantId(tenantId);
    resource.setPlayableStateKey(actor.getPlayableStateKey());
    resource.setCharacterId(actor.getId());
    resource.setStatKey("nullable-stat");
    resource.setCurrentValue(5L);
    resourceRepository.save(resource);
    assertThat(resource.getId()).isNotNull();
    var resources =
        resourceRepository.findByTenantIdAndPlayableStateKeyAndCharacterIdOrderByStatKeyAsc(
            tenantId, actor.getPlayableStateKey(), actor.getId());
    assertThat(resources).hasSize(1);
    assertThat(resources.getFirst().getCurrentValue()).isEqualTo(5L);
    assertThat(resources.getFirst().getMaxValue()).isNull();
    assertThat(resources.getFirst().getBaseValue()).isNull();
    assertThat(resources.getFirst().getSourceId()).isNull();
  }

  @Test
  void legacyFriendInsertUsesGeneratedColumnTypesAndRejectsOwnerResolvedEndpoints() {
    Character character = new Character();
    character.setTenantId(tenantId);
    character.setAccountId(accountId);
    character.setPlayableStateKey("instance:" + instanceId);
    character.setName("Legacy friend source");
    character = characterRepository.save(character);

    Character friendActor = new Character();
    friendActor.setTenantId(tenantId);
    friendActor.setAccountId(accountId);
    friendActor.setPlayableStateKey("instance:" + instanceId);
    friendActor.setName("Legacy friend target");
    friendActor = characterRepository.save(friendActor);

    Instant createdAt = Instant.parse("2026-10-15T12:34:56Z");
    CharacterFriendKey friendKey = new CharacterFriendKey();
    friendKey.setCharacterId(character.getId());
    friendKey.setFriendId(friendActor.getId());
    CharacterFriend friend = new CharacterFriend();
    friend.setId(friendKey);
    friend.setTenantId(tenantId);
    friend.setCreatedAt(createdAt);

    friendRepository.save(friend);

    CharacterFriend readBack = friendRepository.findById(friendKey).orElseThrow();
    assertThat(readBack.getId().getCharacterId()).isEqualTo(character.getId());
    assertThat(readBack.getId().getFriendId()).isEqualTo(friendActor.getId());
    assertThat(readBack.getTenantId()).isEqualTo(tenantId);
    assertThat(readBack.getCreatedAt()).isEqualTo(createdAt);
    assertThat(friendRepository.countByTenantId(tenantId)).isEqualTo(1L);

    insertNamespace(tenantUuid, namespaceUuid, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    long ownerResolvedId =
        insertActor(
            "Owner-resolved friend endpoint",
            UUID.randomUUID(),
            accountUuid,
            tenantUuid,
            namespaceUuid,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "shared-live",
            "OWNER_RESOLVED",
            null,
            accountId,
            tenantId);
    CharacterFriendKey rejectedKey = new CharacterFriendKey();
    rejectedKey.setCharacterId(character.getId());
    rejectedKey.setFriendId(ownerResolvedId);
    CharacterFriend rejectedFriend = new CharacterFriend();
    rejectedFriend.setId(rejectedKey);
    rejectedFriend.setTenantId(tenantId);
    rejectedFriend.setCreatedAt(createdAt);

    assertThatThrownBy(() -> friendRepository.save(rejectedFriend))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Legacy friend endpoints are unavailable");
  }

  @Test
  void storedCanonicalIdentityRequiresExactTupleWithoutPromotingLegacyRows() {
    // SQL fixtures prove storage isolation only; they provide no owner authority or runtime
    // admission.
    insertNamespace(tenantUuid, namespaceUuid, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    UUID legacyActorUuid = UUID.randomUUID();
    long legacyId =
        insertActor(
            "legacy-shared",
            legacyActorUuid,
            null,
            null,
            null,
            null,
            "shared-live",
            "QUARANTINED",
            "OWNER_PROVENANCE_MISSING",
            accountId,
            tenantId);
    UUID resolvedActorUuid = UUID.randomUUID();
    long resolvedId =
        insertActor(
            "owner-resolved",
            resolvedActorUuid,
            accountUuid,
            tenantUuid,
            namespaceUuid,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "shared-live",
            "OWNER_RESOLVED",
            null,
            accountId,
            tenantId);
    assertThat(
            storedActorUuids(
                tenantUuid,
                accountUuid,
                namespaceUuid,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .containsExactly(resolvedActorUuid);
    assertThat(
            storedActorUuids(
                UUID.randomUUID(),
                accountUuid,
                namespaceUuid,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isEmpty();
    assertThat(
            storedActorUuids(
                tenantUuid,
                UUID.randomUUID(),
                namespaceUuid,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isEmpty();
    assertThat(
            storedActorUuids(
                tenantUuid,
                accountUuid,
                UUID.randomUUID(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isEmpty();
    assertThat(
            storedActorUuids(
                tenantUuid,
                accountUuid,
                namespaceUuid,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .isEmpty();
    assertThat(characterRepository.findById(resolvedId)).isEmpty();
    var resolved =
        actorIdentityRepository
            .findOwnerResolvedActor(
                tenantUuid.toString(),
                accountUuid.toString(),
                namespaceUuid.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                resolvedActorUuid.toString())
            .orElseThrow();
    assertThat(resolved.characterUuid()).isEqualTo(resolvedActorUuid);
    assertThat(resolved.accountUuid()).isEqualTo(accountUuid);
    assertThat(resolved.tenantUuid()).isEqualTo(tenantUuid);
    assertThat(resolved.playableStateNamespaceId()).isEqualTo(namespaceUuid);
    assertThat(resolved.playableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(resolved.status().name()).isEqualTo("OWNER_RESOLVED");
    assertThat(
            characterRepository.findById(legacyId).orElseThrow().getActorIdentity().status().name())
        .isEqualTo("QUARANTINED");
    assertThatThrownBy(
            () ->
                insertActor(
                    "duplicate UUID",
                    resolvedActorUuid,
                    null,
                    null,
                    null,
                    null,
                    "shared-live",
                    "QUARANTINED",
                    "OWNER_PROVENANCE_MISSING",
                    accountId,
                    tenantId))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
        .hasMessageContaining("ux_characters_character_uuid");
    assertThatThrownBy(
            () ->
                insertNamespace(
                    tenantUuid, namespaceUuid, PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
        .hasMessageContaining("pk_entity_playable_state_namespace_scopes");
  }

  private List<UUID> storedActorUuids(
      UUID tenantUuid, UUID accountUuid, UUID namespaceUuid, PlayableStateScope scope) {
    return jdbcTemplate.queryForList(
        "SELECT character_uuid FROM characters WHERE tenant_uuid = ? AND account_uuid = ? "
            + "AND playable_state_namespace_id = ? AND playable_state_scope = ? "
            + "AND actor_identity_status = 'OWNER_RESOLVED' "
            + "AND actor_identity_quarantine_reason IS NULL ORDER BY character_uuid",
        UUID.class,
        tenantUuid,
        accountUuid,
        namespaceUuid,
        scope.name());
  }

  @Test
  void canonicalOwnerRepositoryReadsExactScopedActorsAndExcludesQuarantine() {
    // These stored identities are controlled SQL inputs, not owner issuance or player admission.
    var shared = PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
    var isolated = PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
    UUID otherAccount = UUID.randomUUID();
    UUID otherTenant = UUID.randomUUID();
    UUID otherNamespace = UUID.randomUUID();
    UUID targetActor = UUID.randomUUID();
    UUID accountActor = UUID.randomUUID();
    UUID tenantActor = UUID.randomUUID();
    UUID namespaceActor = UUID.randomUUID();
    UUID quarantinedActor = UUID.randomUUID();
    insertNamespace(tenantUuid, namespaceUuid, shared);
    insertNamespace(tenantUuid, otherNamespace, shared);
    insertNamespace(otherTenant, namespaceUuid, shared);
    // Identical private numeric keys deliberately do not grant canonical tuple equivalence.
    insertActor(
        "reader-target",
        targetActor,
        accountUuid,
        tenantUuid,
        namespaceUuid,
        shared,
        "shared-live",
        "OWNER_RESOLVED",
        null,
        accountId,
        tenantId);
    insertActor(
        "reader-account",
        accountActor,
        otherAccount,
        tenantUuid,
        namespaceUuid,
        shared,
        "shared-live",
        "OWNER_RESOLVED",
        null,
        accountId,
        tenantId);
    insertActor(
        "reader-tenant",
        tenantActor,
        accountUuid,
        otherTenant,
        namespaceUuid,
        shared,
        "shared-live",
        "OWNER_RESOLVED",
        null,
        accountId,
        tenantId);
    insertActor(
        "reader-namespace",
        namespaceActor,
        accountUuid,
        tenantUuid,
        otherNamespace,
        shared,
        "shared-live",
        "OWNER_RESOLVED",
        null,
        accountId,
        tenantId);
    insertActor(
        "reader-quarantine",
        quarantinedActor,
        accountUuid,
        tenantUuid,
        namespaceUuid,
        shared,
        "shared-live",
        "QUARANTINED",
        "TEST_ONLY_UNQUALIFIED",
        accountId,
        tenantId);
    var originalRows =
        jdbcTemplate.queryForList(
            "SELECT to_jsonb(actor)::text || ':' || xmin::text FROM characters actor "
                + "WHERE id IN (SELECT id FROM characters WHERE tenant_id = ? AND account_id = ?) "
                + "ORDER BY character_uuid",
            String.class,
            tenantId,
            accountId);
    assertThat(originalRows).hasSize(5);

    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                tenantUuid.toString(), accountUuid.toString(), namespaceUuid.toString(), shared))
        .extracting(identity -> identity.characterUuid())
        .containsExactly(targetActor);
    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                tenantUuid.toString(), otherAccount.toString(), namespaceUuid.toString(), shared))
        .extracting(identity -> identity.characterUuid())
        .containsExactly(accountActor);
    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                otherTenant.toString(), accountUuid.toString(), namespaceUuid.toString(), shared))
        .extracting(identity -> identity.characterUuid())
        .containsExactly(tenantActor);
    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                tenantUuid.toString(), accountUuid.toString(), otherNamespace.toString(), shared))
        .extracting(identity -> identity.characterUuid())
        .containsExactly(namespaceActor);
    assertThat(
            actorIdentityRepository.findOwnerResolvedRoster(
                tenantUuid.toString(), accountUuid.toString(), namespaceUuid.toString(), isolated))
        .isEmpty();
    assertThat(
            actorIdentityRepository.findOwnerResolvedActor(
                tenantUuid.toString(),
                accountUuid.toString(),
                namespaceUuid.toString(),
                shared,
                targetActor.toString()))
        .get()
        .satisfies(
            identity -> {
              assertThat(identity.characterUuid()).isEqualTo(targetActor);
              assertThat(identity.accountUuid()).isEqualTo(accountUuid);
              assertThat(identity.tenantUuid()).isEqualTo(tenantUuid);
              assertThat(identity.playableStateNamespaceId()).isEqualTo(namespaceUuid);
              assertThat(identity.playableStateScope()).isEqualTo(shared);
              assertThat(identity.status().name()).isEqualTo("OWNER_RESOLVED");
            });
    for (UUID excluded : List.of(accountActor, tenantActor, namespaceActor, quarantinedActor)) {
      assertThat(
              actorIdentityRepository.findOwnerResolvedActor(
                  tenantUuid.toString(),
                  accountUuid.toString(),
                  namespaceUuid.toString(),
                  shared,
                  excluded.toString()))
          .isEmpty();
    }
    assertThat(
            actorIdentityRepository.findOwnerResolvedActor(
                tenantUuid.toString(),
                otherAccount.toString(),
                namespaceUuid.toString(),
                shared,
                targetActor.toString()))
        .isEmpty();
    assertThat(
            actorIdentityRepository.findOwnerResolvedActor(
                otherTenant.toString(),
                accountUuid.toString(),
                namespaceUuid.toString(),
                shared,
                targetActor.toString()))
        .isEmpty();
    assertThat(
            actorIdentityRepository.findOwnerResolvedActor(
                tenantUuid.toString(),
                accountUuid.toString(),
                otherNamespace.toString(),
                shared,
                targetActor.toString()))
        .isEmpty();
    assertThat(
            actorIdentityRepository.findOwnerResolvedActor(
                tenantUuid.toString(),
                accountUuid.toString(),
                namespaceUuid.toString(),
                isolated,
                targetActor.toString()))
        .isEmpty();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT to_jsonb(actor)::text || ':' || xmin::text FROM characters actor "
                    + "WHERE id IN (SELECT id FROM characters WHERE tenant_id = ? AND account_id = ?) "
                    + "ORDER BY character_uuid",
                String.class,
                tenantId,
                accountId))
        .isEqualTo(originalRows);
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
  void freshDefaultFlywayInstallsCompleteCombinedManifest() {
    // Flyway normalizes the declared Saga sub-location into its recursive parent location.
    assertThat(flyway.getConfiguration().getLocations())
        .extracting(location -> location.getDescriptor())
        .containsExactly("classpath:db/migration");
    assertThat(flyway.getConfiguration().getTable())
        .isEqualTo("flyway_schema_history_entity_management_service");
    assertThat(flyway.getConfiguration().isOutOfOrder()).isFalse();
    assertThat(flyway.info().applied())
        .filteredOn(migration -> migration.getVersion() != null)
        .extracting(migration -> migration.getVersion().getVersion())
        .containsExactly(
            "1", "1000", "1001", "1002", "1003", "1004", "1005", "1006", "1007", "1008", "1009",
            "1010");
    assertThat(flyway.info().applied())
        .filteredOn(migration -> migration.getVersion() != null)
        .extracting(migration -> migration.getScript())
        .containsExactly(
            "V1__baseline.sql",
            "saga/V1000__create_saga_schema.sql",
            "saga/V1001__saga_instance_table.sql",
            "saga/V1002__saga_step_table.sql",
            "V1003__quarantine_unresolved_actor_identity.sql",
            "V1004__build_actor_identity_indexes_concurrently.sql",
            "V1005__validate_actor_identity_constraints.sql",
            "V1006__preseeded_actor_assignment.sql",
            "V1007__preseeded_assignment_owner_snapshot.sql",
            "V1008__canonical_gameplay_roster_snapshots.sql",
            "V1009__canonical_publication_uuid_actor_evidence.sql",
            "V1010__canonical_roster_owner_counters.sql");
    assertThat(flyway.info().current().getVersion())
        .isEqualTo(MigrationVersion.fromVersion("1010"));
    assertThat(flyway.info().applied())
        .extracting(migration -> migration.getState())
        .doesNotContain(MigrationState.OUT_OF_ORDER);
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void flywayParentFrontierToLatestRetainsLegacyRowsAndValidatesConstraints() throws SQLException {
    migrateLegacySchemaToParentFrontier();
    List<MigrationHistoryEntry> parentHistory = readMigrationHistory();
    assertThat(parentHistory)
        .extracting(MigrationHistoryEntry::version)
        .containsExactly("1", "1000", "1001", "1002");
    assertThat(parentHistory)
        .extracting(MigrationHistoryEntry::script)
        .containsExactly(
            "V1__baseline.sql",
            "saga/V1000__create_saga_schema.sql",
            "saga/V1001__saga_instance_table.sql",
            "saga/V1002__saga_step_table.sql");
    assertThat(parentHistory)
        .allSatisfy(
            entry -> {
              assertThat(entry.success()).isTrue();
              assertThat(entry.checksum()).isNotNull();
            });
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

    List<MigrationHistoryEntry> upgradedHistory = readMigrationHistory();
    assertThat(upgradedHistory.subList(0, parentHistory.size()))
        .containsExactlyElementsOf(parentHistory);
    assertThat(upgradedHistory)
        .extracting(MigrationHistoryEntry::version)
        .containsExactly(
            "1", "1000", "1001", "1002", "1003", "1004", "1005", "1006", "1007", "1008", "1009",
            "1010");
    assertThat(upgradedHistory)
        .extracting(MigrationHistoryEntry::script)
        .containsExactly(
            "V1__baseline.sql",
            "saga/V1000__create_saga_schema.sql",
            "saga/V1001__saga_instance_table.sql",
            "saga/V1002__saga_step_table.sql",
            "V1003__quarantine_unresolved_actor_identity.sql",
            "V1004__build_actor_identity_indexes_concurrently.sql",
            "V1005__validate_actor_identity_constraints.sql",
            "V1006__preseeded_actor_assignment.sql",
            "V1007__preseeded_assignment_owner_snapshot.sql",
            "V1008__canonical_gameplay_roster_snapshots.sql",
            "V1009__canonical_publication_uuid_actor_evidence.sql",
            "V1010__canonical_roster_owner_counters.sql");
    assertThat(upgradedHistory).allSatisfy(entry -> assertThat(entry.success()).isTrue());

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

  private void migrateLegacySchemaToParentFrontier() throws SQLException {
    testServiceSchema = "rai_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + testServiceSchema);
    }
    Flyway parentFlyway = migrationFlyway().target(MigrationVersion.fromVersion("1002")).load();
    assertThat(parentFlyway.getConfiguration().isOutOfOrder()).isFalse();
    parentFlyway.migrate();
    assertThat(parentFlyway.info().current().getVersion())
        .isEqualTo(MigrationVersion.fromVersion("1002"));
  }

  private void migrateLegacySchemaToLatest() {
    Flyway upgradeFlyway = migrationFlyway().load();
    assertThat(upgradeFlyway.getConfiguration().isOutOfOrder()).isFalse();
    upgradeFlyway.migrate();
    assertThat(upgradeFlyway.info().current().getVersion())
        .isEqualTo(MigrationVersion.fromVersion("1010"));
    assertThat(upgradeFlyway.info().applied())
        .extracting(migration -> migration.getState())
        .doesNotContain(MigrationState.OUT_OF_ORDER);
  }

  private FluentConfiguration migrationFlyway() {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(testServiceSchema)
            .defaultSchema(testServiceSchema)
            .table("flyway_schema_history_" + testServiceSchema)
            .locations("classpath:db/migration", "classpath:db/migration/saga")
            .placeholders(Map.of("serviceSchema", testServiceSchema));
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
      statement.execute("SET search_path TO " + testServiceSchema);
    }
    return connection;
  }

  private List<MigrationHistoryEntry> readMigrationHistory() throws SQLException {
    List<MigrationHistoryEntry> history = new ArrayList<>();
    try (Connection connection = connectToMigrationSchema();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT installed_rank, version, script, checksum, success FROM flyway_schema_history_"
                    + testServiceSchema
                    + " WHERE version IS NOT NULL ORDER BY installed_rank")) {
      while (rows.next()) {
        history.add(
            new MigrationHistoryEntry(
                rows.getInt("installed_rank"),
                rows.getString("version"),
                rows.getString("script"),
                rows.getObject("checksum", Integer.class),
                rows.getBoolean("success")));
      }
    }
    return history;
  }

  private record MigrationHistoryEntry(
      int installedRank, String version, String script, Integer checksum, boolean success) {}

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
