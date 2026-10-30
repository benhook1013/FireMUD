package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.service.impl.CharacterServiceImpl;
import net.firedevops.firemud.entitymanagement.service.impl.EntityManagementGrpcService;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class OrdinaryAccountUuidOwnerPostgresIntegrationTest {
  @TempDir Path tlsDirectory;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void upgradePreservesNumericSourcesAndQueriesOnlyExactOrdinaryUuidWithoutPromotion()
      throws Exception {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/migration", "classpath:db/migration/saga")
            .placeholders(Map.of("serviceSchema", "public"));
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.target(MigrationVersion.fromVersion("1023")).load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO characters (tenant_id, account_id, name) VALUES (1, 98765, 'retained')");
    List<Map<String, Object>> retained =
        jdbc.queryForList("SELECT *, xmin::text AS retained_xmin FROM characters");
    List<Map<String, Object>> history =
        jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
    String parentCore =
        java.util.Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'characters'::regclass AND conname = 'ck_character_created_core'",
                String.class));
    configuration.target(MigrationVersion.fromVersion("1024")).load().migrate();
    String currentCore =
        java.util.Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'characters'::regclass AND conname = 'ck_character_created_core'",
                String.class));
    assertThat(parentCore).contains("'PLAYER_CREATED'");
    assertThat(currentCore).contains("'PLAYER_CREATED'");
    assertThat(currentCore.substring(currentCore.indexOf("'PLAYER_CREATED'")))
        .isEqualTo(parentCore.substring(parentCore.indexOf("'PLAYER_CREATED'")));

    assertThat(jdbc.queryForList("SELECT *, xmin::text AS retained_xmin FROM characters"))
        .isEqualTo(retained);
    var after = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(after).hasSize(history.size() + 1);
    assertThat(after.subList(0, history.size())).isEqualTo(history);
    assertThat(after.getLast().get("version")).isEqualTo("1024");
    var repository = new CharacterRepository(DSL.using(source, SQLDialect.POSTGRES));
    UUID owner = UUID.randomUUID();
    Character ordinary = new Character();
    ordinary.setTenantId(1L);
    ordinary.setAccountUuid(owner);
    ordinary.setPlayableStateKey("shared-live");
    ordinary.setName("owned");
    Character saved = repository.save(ordinary);
    Character loaded = repository.findById(saved.getId()).orElseThrow();
    assertThat(loaded.getAccountUuid()).isEqualTo(owner);
    assertThat(loaded.getAccountId()).isNull();
    assertThat(saved.getAccountId()).isNull();
    assertThat(saved.getAccountUuid()).isEqualTo(owner);
    assertThat(saved.getActorIdentity().accountUuid()).isEqualTo(owner);
    assertThat(saved.getActorIdentity().status()).isEqualTo(ActorIdentityStatus.QUARANTINED);
    assertThat(saved.getActorIdentity().tenantUuid()).isNull();
    assertThat(saved.getActorIdentity().playableStateNamespaceId()).isNull();
    assertThat(
            repository
                .findByTenantIdAndAccountIdAndPlayableStateKey(1L, owner, "shared-live", null)
                .getContent())
        .extracting(Character::getId)
        .containsExactly(saved.getId());
    assertThat(
            repository
                .findByTenantIdAndAccountIdAndPlayableStateKey(2L, owner, "shared-live", null)
                .getContent())
        .isEmpty();
    assertThat(
            repository
                .findByTenantIdAndAccountIdAndPlayableStateKey(
                    1L, UUID.randomUUID(), "shared-live", null)
                .getContent())
        .isEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT *, xmin::text AS retained_xmin FROM characters WHERE name = 'retained'"))
        .isEqualTo(retained);
    assertMtlsReceiverOwnerAndTenantIsolation(repository, jdbc, saved, owner);
    List<Map<String, Object>> ordinaryBeforeRefusals =
        jdbc.queryForList(
            "SELECT *, xmin::text AS retained_xmin FROM characters WHERE id = ?", saved.getId());
    for (String invalidChange :
        List.of(
            "account_uuid = NULL",
            "account_uuid = '00000000-0000-0000-0000-000000000000'::UUID",
            "tenant_uuid = '11111111-1111-4111-8111-111111111111'::UUID",
            "playable_state_namespace_id = '22222222-2222-4222-8222-222222222222'::UUID",
            "playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED'",
            "actor_identity_status = 'OWNER_RESOLVED', actor_identity_quarantine_reason = NULL",
            "actor_identity_status = 'OWNER_RESOLVED', actor_identity_quarantine_reason = NULL, "
                + "tenant_uuid = '11111111-1111-4111-8111-111111111111'::UUID, "
                + "playable_state_namespace_id = '22222222-2222-4222-8222-222222222222'::UUID, "
                + "playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED'",
            "actor_identity_quarantine_reason = NULL",
            "actor_entry_policy = 'PLAYER_CREATED'",
            "creation_target_namespace = 'dev'",
            "creation_operation_id = '33333333-3333-4333-8333-333333333333'::UUID",
            "authored_initial_core_json = '{}'",
            "creation_descriptor_json = '{}'",
            "creation_descriptor_digest = 'sha256:" + "a".repeat(64) + "'")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE characters SET " + invalidChange + " WHERE id = ?", saved.getId()))
          .as(invalidChange)
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(
              jdbc.queryForList(
                  "SELECT *, xmin::text AS retained_xmin FROM characters WHERE id = ?",
                  saved.getId()))
          .isEqualTo(ordinaryBeforeRefusals);
    }
    for (String requiredColumn :
        List.of(
            "playable_state_key",
            "level",
            "experience",
            "strength",
            "agility",
            "intelligence",
            "stamina",
            "health",
            "mana",
            "body_layout_key")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE characters SET " + requiredColumn + " = NULL WHERE id = ?",
                      saved.getId()))
          .as(requiredColumn)
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    assertThat(
            jdbc.queryForList(
                "SELECT *, xmin::text AS retained_xmin FROM characters WHERE id = ?",
                saved.getId()))
        .isEqualTo(ordinaryBeforeRefusals);
    assertThat(
            jdbc.queryForList(
                "SELECT *, xmin::text AS retained_xmin FROM characters WHERE name = 'retained'"))
        .isEqualTo(retained);
  }

  /**
   * Real TLS/receiver/service/mapper/PostgreSQL composition, using controlled PKI and owner rows.
   * R1274 separately proves Account-authenticated seeding and packaged Spring runtime wiring; this
   * harness does not establish production PKI, Account issuance or public admission.
   */
  private void assertMtlsReceiverOwnerAndTenantIsolation(
      CharacterRepository repository, JdbcTemplate jdbc, Character saved, UUID owner)
      throws Exception {
    String namespace = "ordinary-owner-proof";
    var pki =
        LoopbackMtlsTestPki.create(
            tlsDirectory,
            "Ordinary Entity owner proof CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "entity",
                "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service",
                true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "gs", "spiffe://firemud/ns/" + namespace + "/sa/game-session-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign", "spiffe://firemud/ns/other/sa/game-session-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "social", "spiffe://firemud/ns/" + namespace + "/sa/social-groups-service", false));
    var meters = new SimpleMeterRegistry();
    var stateKeys = new PlayableStateKeyResolver();
    var characters =
        new CharacterServiceImpl(
            repository,
            Mappers.getMapper(CharacterMapper.class),
            new ConcurrentMapCacheManager("characterGraph"),
            meters,
            stateKeys,
            new ScopedCharacterResolver(repository, stateKeys));
    var receiver =
        new EntityManagementGrpcService(
            Mockito.mock(net.firedevops.firemud.entitymanagement.service.PingService.class),
            characters,
            Mockito.mock(net.firedevops.firemud.entitymanagement.service.ActorStateService.class),
            Mockito.mock(
                net.firedevops.firemud.entitymanagement.service.ActorConditionMutationService
                    .class),
            Mockito.mock(
                net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService
                    .class),
            Mockito.mock(net.firedevops.firemud.entitymanagement.service.EquipmentService.class),
            Mockito.mock(net.firedevops.firemud.entitymanagement.service.InventoryService.class),
            Mockito.mock(net.firedevops.firemud.entitymanagement.service.ContainerService.class),
            Mockito.mock(net.firedevops.firemud.entitymanagement.service.RoomEntityService.class),
            Mockito.mock(
                net.firedevops.firemud.entitymanagement.service.RuntimeInstanceCleanupService
                    .class),
            Mockito.mock(
                net.firedevops.firemud.entitymanagement.service.impl
                    .EntityMutationEffectReplayService.class),
            Mockito.mock(
                net.firedevops.firemud.entitymanagement.service.EntityUpgradeValidationService
                    .class),
            Mockito.mock(
                net.firedevops.firemud.entitymanagement.service.EntityTemplateReferenceService
                    .class),
            Mockito.mock(
                net.firedevops.firemud.common.security.GameplaySessionAttestationService.class),
            meters,
            Mockito.mock(net.firedevops.firemud.entitymanagement.effect.EffectPayloadParser.class),
            namespace);
    List<Map<String, Object>> before =
        jdbc.queryForList("SELECT *, xmin::text AS retained_xmin FROM characters ORDER BY id");
    var identity = pki.identity("entity");
    Server server =
        NettyServerBuilder.forPort(0)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(receiver, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    try {
      for (String alias : List.of("gs", "foreign", "social")) {
        var caller = pki.identity(alias);
        ManagedChannel channel =
            NettyChannelBuilder.forAddress("localhost", server.getPort())
                .sslContext(
                    GrpcSslContexts.forClient()
                        .trustManager(pki.caCertificate())
                        .keyManager(caller.privateKey(), caller.certificate(), pki.caCertificate())
                        .build())
                .intercept(
                    new GrpcServerPeerIdentityClientInterceptor(
                        "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service"))
                .build();
        try {
          var stub =
              EntityManagementServiceGrpc.newBlockingStub(channel)
                  .withDeadlineAfter(5, TimeUnit.SECONDS);
          var exact = stub.listCharactersByAccount(rosterRequest("1", owner.toString()));
          if (!alias.equals("gs")) {
            assertThat(exact.hasError()).isTrue();
            assertThat(exact.getError().getCode()).isEqualTo("PERMISSION_DENIED");
            assertThat(exact.getCharactersList()).isEmpty();
            continue;
          }
          assertThat(exact.hasError()).isFalse();
          assertThat(exact.getCharactersCount()).isEqualTo(1);
          assertThat(exact.getCharacters(0).getId()).isEqualTo(saved.getId().toString());
          assertThat(exact.getCharacters(0).getAccountId()).isEqualTo(owner.toString());
          assertThat(exact.getCharacters(0).getTenantId()).isEqualTo("1");
          for (var request :
              List.of(
                  rosterRequest("1", UUID.randomUUID().toString()),
                  rosterRequest("2", owner.toString()))) {
            var denied = stub.listCharactersByAccount(request);
            assertThat(denied.hasError()).isFalse();
            assertThat(denied.getCharactersList()).isEmpty();
          }
          // Retained numeric source evidence is not a public Account selector.
          var unresolved = stub.listCharactersByAccount(rosterRequest("1", "98765"));
          assertThat(unresolved.hasError()).isTrue();
          assertThat(unresolved.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
          assertThat(unresolved.getCharactersList()).isEmpty();
        } finally {
          channel.shutdownNow();
          assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
      }
      assertThat(
              jdbc.queryForList(
                  "SELECT *, xmin::text AS retained_xmin FROM characters ORDER BY id"))
          .isEqualTo(before);
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      meters.close();
    }
  }

  private static ListCharactersByAccountRequest rosterRequest(String tenant, String owner) {
    return ListCharactersByAccountRequest.newBuilder()
        .setTenantId(tenant)
        .setAccountId(owner)
        .setGameInstanceId("44")
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .build();
  }
}
