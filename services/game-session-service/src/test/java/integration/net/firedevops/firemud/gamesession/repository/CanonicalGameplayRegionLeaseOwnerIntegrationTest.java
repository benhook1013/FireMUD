package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.CanonicalPlayerCreatedRuntimeReferenceEvidence;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRuntimeReferenceServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorOrigin;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceResponse;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.client.CanonicalInitialPlayerLocationClient;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.gamesession.service.CanonicalRegionBootstrapAssignmentResolver;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRegionResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationResponse;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Controlled source protocol proof; fixture World evidence is not production activation proof. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayRegionLeaseOwnerIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final int REDIS_PORT = 6379;
  private static final UUID TRANSITION_ID = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID ACCOUNT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID CHARACTER_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID CANONICAL_REGION_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OPERATIONAL_REGION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
  private static final String ENTITY_CLIENT_PEER =
      "spiffe://firemud/ns/firemud/sa/game-session-service";
  private static final String ENTITY_SERVER_PEER =
      "spiffe://firemud/ns/firemud/sa/entity-management-service";

  @TempDir static Path pkiDirectory;
  private static LoopbackMtlsTestPki.Pki pki;

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(REDIS_PORT);

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            pkiDirectory,
            "Region member order test CA",
            new LoopbackMtlsTestPki.IdentitySpec("client", ENTITY_CLIENT_PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec("entity-server", ENTITY_SERVER_PEER, true));
  }

  @Test
  void composesLeaseInstallRevalidationHandoffAndFailClosedSourceChanges() throws Exception {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(POSTGRES);
    ControlledConnectionFailureDataSource dataSource = dataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    var statusRepository = new CanonicalGameplayRegionRuntimeStatusRepository(dsl);

    try (RedisFixture redis = RedisFixture.open()) {
      var redisOwner =
          new CanonicalGameplayRegionLeaseRedisOwner(redis.ownerClient(), redis.catalog());
      WorldFixture world = WorldFixture.open();
      ControlledStableIncarnationSource incarnationSource = new ControlledStableIncarnationSource();
      var bindingEvidence = provisionalEvidence(world.request());
      var bindingInventory = mock(CanonicalGameplayBindingInventoryRepository.class);
      when(bindingInventory.readProvisionalCasEvidence(TRANSITION_ID)).thenReturn(bindingEvidence);

      var firstOwner =
          owner(
              bindingInventory,
              statusRepository,
              redisOwner,
              world,
              "instance-a",
              incarnationSource);
      var qualifiedSource = source(world.originalEvidence());
      byte[] redisKey =
          ascii(
              CanonicalGameplayRegionRedisKeys.lease(
                  qualifiedSource.tenantId(),
                  qualifiedSource.gameInstanceId(),
                  qualifiedSource.regionId()));
      String initialMetaKey =
          CanonicalGameplayRegionRedisKeys.meta(
              qualifiedSource.tenantId(),
              qualifiedSource.gameInstanceId(),
              qualifiedSource.regionId());

      try {
        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(() -> firstOwner.installForTransition(TRANSITION_ID, world.request()))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      assertThat(redis.admin().type(redisKey)).isEqualTo("none");
      assertThat(statusRepository.find(qualifiedSource)).isEmpty();

      var wrongTargetInventory = mock(CanonicalGameplayBindingInventoryRepository.class);
      var wrongTargetEvidence =
          provisionalEvidence(
              world.request(),
              UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
              BigInteger.ONE);
      when(wrongTargetInventory.readProvisionalCasEvidence(TRANSITION_ID))
          .thenReturn(wrongTargetEvidence);
      var wrongTargetOwner =
          owner(
              wrongTargetInventory,
              statusRepository,
              redisOwner,
              world,
              "wrong-target",
              incarnationSource);
      assertThatThrownBy(
              () -> wrongTargetOwner.installForTransition(TRANSITION_ID, world.request()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().type(redisKey)).isEqualTo("none");
      assertThat(statusRepository.find(qualifiedSource)).isEmpty();

      var wrongEpochInventory = mock(CanonicalGameplayBindingInventoryRepository.class);
      var wrongEpochEvidence =
          provisionalEvidence(world.request(), qualifiedSource.tenantId(), BigInteger.TWO);
      when(wrongEpochInventory.readProvisionalCasEvidence(TRANSITION_ID))
          .thenReturn(wrongEpochEvidence);
      var wrongEpochOwner =
          owner(
              wrongEpochInventory,
              statusRepository,
              redisOwner,
              world,
              "wrong-epoch",
              incarnationSource);
      assertThatThrownBy(() -> wrongEpochOwner.installForTransition(TRANSITION_ID, world.request()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().type(redisKey)).isEqualTo("none");
      assertThat(statusRepository.find(qualifiedSource)).isEmpty();

      var unavailableIncarnationOwner =
          owner(
              bindingInventory,
              statusRepository,
              redisOwner,
              world,
              "unavailable-incarnation",
              CanonicalGameplayRegionIncarnationSource.unavailable());
      assertThatThrownBy(
              () ->
                  unavailableIncarnationOwner.installForTransition(TRANSITION_ID, world.request()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().type(redisKey)).isEqualTo("none");
      assertThat(statusRepository.find(qualifiedSource)).isEmpty();

      try {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> firstOwner.installForTransition(TRANSITION_ID, world.request()))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      assertThat(redis.admin().type(redisKey)).isEqualTo("none");
      assertThat(statusRepository.find(qualifiedSource)).isEmpty();

      world.setAfterRead(2, incarnationSource::changeRecoveryEvidence);
      Throwable recoveryDriftFailure =
          catchThrowable(() -> firstOwner.installForTransition(TRANSITION_ID, world.request()));
      assertThat(recoveryDriftFailure).isInstanceOf(IllegalStateException.class);
      assertThat(statusRepository.find(qualifiedSource)).isEmpty();
      Record retainedPreparation =
          dsl.fetchOne(
              "SELECT * FROM game_session_region_timeline_preparation WHERE transition_id = ?",
              TRANSITION_ID);
      assertThat(retainedPreparation)
          .as(
              "Timeline preparation must precede recovery drift; original failure: %s",
              recoveryDriftFailure.getCause())
          .isNotNull();
      assertThat(retainedPreparation.get("phase", String.class)).isEqualTo("REDIS_INITIALIZED");
      UUID initialTimelineOperationId =
          retainedPreparation.get("initialization_operation_id", UUID.class);
      String initialTimelineSourceDigest =
          retainedPreparation.get("preparation_digest", String.class);
      assertThat(redis.admin().hlen(ascii(initialMetaKey))).isEqualTo(5L);
      assertThat(redis.admin().pttl(ascii(initialMetaKey))).isEqualTo(-1L);
      world.resetReads();
      incarnationSource.restoreRecoveryEvidence();
      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();

      var firstInstallation = firstOwner.installForTransition(TRANSITION_ID, world.request());
      var firstStatus = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(firstStatus.regionEpoch()).isEqualTo(1L);
      assertThat(firstStatus.lifecycleEpoch()).isEqualTo(7L);
      assertThat(firstStatus.lastCommittedTickId()).isEqualTo(-1L);
      assertThat(redis.admin().hlen(ascii(initialMetaKey))).isEqualTo(5L);
      assertThat(redis.admin().pttl(ascii(initialMetaKey))).isEqualTo(-1L);
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("region_epoch")))
          .containsExactly(ascii("1"));
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("current_tick_id")))
          .containsExactly(ascii("-1"));
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("current_tick_state")))
          .containsExactly(ascii("APPLIED"));
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("first_timeline_operation_id")))
          .containsExactly(ascii(initialTimelineOperationId.toString()));
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("first_timeline_source_digest")))
          .containsExactly(ascii(initialTimelineSourceDigest));

      var preparation = statusRepository.findTimelinePreparation(qualifiedSource).orElseThrow();
      byte[] initialLeaseValue = redis.admin().get(redisKey);
      assertThat(redis.admin().hdel(ascii(initialMetaKey), ascii("current_tick_id"))).isEqualTo(1L);
      assertThatThrownBy(
              () ->
                  redisOwner.initializeOrResumeTimeline(
                      preparation,
                      null,
                      Duration.ofSeconds(30),
                      new RuntimeIdentity(
                          "game-session-service",
                          "instance-a",
                          "localhost",
                          NOW,
                          "controlled-test",
                          "controlled-sha",
                          "controlled-image")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Canonical Game Session region lease is unavailable")
          .satisfies(failure -> assertThat(failure.getCause()).isNull());
      assertThatThrownBy(
              () -> redisOwner.verifyInitializedTimeline(firstInstallation.grant, preparation))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Canonical Game Session region lease is unavailable")
          .satisfies(failure -> assertThat(failure.getCause()).isNull());
      assertThat(redis.admin().hlen(ascii(initialMetaKey))).isEqualTo(4L);
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("current_tick_id"))).isNull();
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("region_epoch")))
          .containsExactly(ascii("1"));
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("current_tick_state")))
          .containsExactly(ascii("APPLIED"));
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("first_timeline_operation_id")))
          .containsExactly(ascii(initialTimelineOperationId.toString()));
      assertThat(redis.admin().hget(ascii(initialMetaKey), ascii("first_timeline_source_digest")))
          .containsExactly(ascii(initialTimelineSourceDigest));
      assertThat(redis.admin().get(redisKey)).containsExactly(initialLeaseValue);
      assertThat(redis.admin().pttl(redisKey)).isPositive();

      redis.admin().hset(ascii(initialMetaKey), ascii("current_tick_id"), ascii("-1"));
      redisOwner.verifyInitializedTimeline(firstInstallation.grant, preparation);

      var currentIncarnation =
          incarnationSource.readCurrent(
              CanonicalGameplayRegionIncarnationSource.RegionScope.from(qualifiedSource));
      Record preparationRow =
          dsl.fetchOne(
              "SELECT * FROM game_session_region_timeline_preparation WHERE transition_id = ?",
              TRANSITION_ID);
      assertThat(preparationRow).isNotNull();
      assertThat(preparationRow.get("phase", String.class)).isEqualTo("INSTALLED");
      assertThat(preparationRow.get("initialization_operation_id", UUID.class))
          .isEqualTo(initialTimelineOperationId);
      assertThat(preparationRow.get("preparation_digest", String.class))
          .isEqualTo(initialTimelineSourceDigest);
      assertThat(preparationRow.get("stable_incarnation_scope_bytes", byte[].class))
          .containsExactly(currentIncarnation.scopeBytes());
      assertThat(preparationRow.get("stable_incarnation_source_bytes", byte[].class))
          .containsExactly(currentIncarnation.stableCanonicalBytes());
      assertThat(preparationRow.get("stable_incarnation_qualification_bytes", byte[].class))
          .containsExactly(
              currentIncarnation.timelineInitializationQualification().canonicalBytes());
      Long sequenceAfterInstall = fenceSequenceValue(dsl);
      assertThat(
              statusRepository.installOwner(
                  qualifiedSource,
                  firstStatus.regionEpoch(),
                  firstStatus,
                  firstStatus.leaseTokenCorrelation(),
                  firstStatus.ownerService(),
                  firstStatus.ownerInstanceId(),
                  NOW.plusSeconds(1)))
          .isEqualTo(firstStatus);
      assertThat(fenceSequenceValue(dsl)).isEqualTo(sequenceAfterInstall);

      firstOwner.revalidateBeforeBridge(firstInstallation);
      firstOwner.revalidateBeforeBridge(firstInstallation);

      assertThat(statusRepository.find(qualifiedSource)).contains(firstStatus);
      assertThat(fenceSequenceValue(dsl)).isEqualTo(sequenceAfterInstall);
      assertThat(redis.admin().pexpire(ascii(initialMetaKey), 60_000L)).isTrue();
      assertThatThrownBy(() -> firstOwner.revalidateBeforeBridge(firstInstallation))
          .isInstanceOf(IllegalStateException.class);
      assertThat(statusRepository.find(qualifiedSource)).contains(firstStatus);
      assertThat(redis.admin().persist(ascii(initialMetaKey))).isTrue();

      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();
      assertThatThrownBy(() -> firstOwner.revalidateBeforeBridge(firstInstallation))
          .isInstanceOf(IllegalStateException.class);
      assertThat(statusRepository.find(qualifiedSource)).contains(firstStatus);

      dsl.execute(
          "UPDATE game_session_region_runtime_status"
              + " SET last_committed_tick_batch_id = 'batch-0', last_committed_tick_id = 0"
              + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
          qualifiedSource.tenantId(),
          qualifiedSource.gameInstanceId(),
          qualifiedSource.regionId());
      redis
          .admin()
          .hset(
              ascii(
                  CanonicalGameplayRegionRedisKeys.meta(
                      qualifiedSource.tenantId(),
                      qualifiedSource.gameInstanceId(),
                      qualifiedSource.regionId())),
              java.util.Map.of(
                  ascii("current_tick_id"), ascii("0"),
                  ascii("current_tick_state"), ascii("APPLIED"),
                  ascii("current_tick_terminal_at_ms"), ascii("1792972800000")));
      var secondOwner =
          owner(
              bindingInventory,
              statusRepository,
              redisOwner,
              world,
              "instance-b",
              incarnationSource);
      secondOwner.installForTransition(TRANSITION_ID, world.request());
      var secondStatus = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(secondStatus.executorFence()).isGreaterThan(firstStatus.executorFence());
      assertThat(secondStatus.regionEpoch()).isEqualTo(firstStatus.regionEpoch());
      assertThat(secondStatus.lastCommittedTickId()).isZero();
      assertThat(secondStatus.lastCommittedTickBatchId()).isEqualTo("batch-0");
      assertThat(secondStatus.ownerInstanceId()).isEqualTo("instance-b");
      assertThatThrownBy(() -> firstOwner.revalidateBeforeBridge(firstInstallation))
          .isInstanceOf(IllegalStateException.class);
      assertThat(statusRepository.find(qualifiedSource)).contains(secondStatus);

      dsl.execute(
          "UPDATE game_session_region_runtime_status"
              + " SET last_committed_tick_batch_id = 'batch-8', last_committed_tick_id = 8"
              + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
          qualifiedSource.tenantId(),
          qualifiedSource.gameInstanceId(),
          qualifiedSource.regionId());
      redis
          .admin()
          .hset(
              ascii(
                  CanonicalGameplayRegionRedisKeys.meta(
                      qualifiedSource.tenantId(),
                      qualifiedSource.gameInstanceId(),
                      qualifiedSource.regionId())),
              java.util.Map.of(
                  ascii("current_tick_id"), ascii("8"),
                  ascii("current_tick_state"), ascii("APPLIED"),
                  ascii("current_tick_terminal_at_ms"), ascii("1792972800000")));
      var progressed = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();

      var thirdOwner =
          owner(
              bindingInventory,
              statusRepository,
              redisOwner,
              world,
              "instance-c",
              incarnationSource);
      var thirdInstallation = thirdOwner.installForTransition(TRANSITION_ID, world.request());
      var thirdStatus = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(thirdStatus.executorFence()).isGreaterThan(progressed.executorFence());
      assertThat(thirdStatus.regionEpoch()).isEqualTo(1L);
      assertThat(thirdStatus.lastCommittedTickId()).isEqualTo(8L);
      assertThat(thirdStatus.lastCommittedTickBatchId()).isEqualTo("batch-8");
      assertThat(thirdStatus.ownerInstanceId()).isEqualTo("instance-c");
      assertThat(thirdInstallation).isNotNull();

      byte[] liveLeaseBeforeIncarnationChange = redis.admin().get(redisKey);
      Long fenceBeforeIncarnationChange = fenceSequenceValue(dsl);
      incarnationSource.changeStorageGeneration("storage-generation-2");
      var changedIncarnationOwner =
          owner(
              bindingInventory,
              statusRepository,
              redisOwner,
              world,
              "changed-incarnation",
              incarnationSource);
      assertThatThrownBy(
              () -> changedIncarnationOwner.installForTransition(TRANSITION_ID, world.request()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().get(redisKey)).containsExactly(liveLeaseBeforeIncarnationChange);
      assertThat(statusRepository.find(qualifiedSource)).contains(thirdStatus);
      assertThat(fenceSequenceValue(dsl)).isEqualTo(fenceBeforeIncarnationChange);
      incarnationSource.restoreStorageGeneration();

      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();
      WorldCanonicalInstanceLifecycleEvidence changed = changedEvidence(world.originalEvidence());
      world.replaceOnRead(2, changed);
      var fourthOwner =
          owner(
              bindingInventory,
              statusRepository,
              redisOwner,
              world,
              "instance-d",
              incarnationSource);
      assertThatThrownBy(() -> fourthOwner.installForTransition(TRANSITION_ID, world.request()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(statusRepository.find(qualifiedSource)).contains(thirdStatus);

      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();
      world.resetReads();
      world.replaceOnRead(3, changed);
      var fifthOwner =
          owner(
              bindingInventory,
              statusRepository,
              redisOwner,
              world,
              "instance-e",
              incarnationSource);
      assertThatThrownBy(() -> fifthOwner.installForTransition(TRANSITION_ID, world.request()))
          .isInstanceOf(IllegalStateException.class);
      var installedBeforeChangedSourceGate = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(installedBeforeChangedSourceGate.executorFence())
          .isGreaterThan(thirdStatus.executorFence());
      assertThat(installedBeforeChangedSourceGate.lastCommittedTickId()).isEqualTo(8L);
      assertThat(installedBeforeChangedSourceGate.ownerInstanceId()).isEqualTo("instance-e");
      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();

      world.resetReads();
      var ownerStatusBeforeSqlOutage = statusRepository.find(qualifiedSource).orElseThrow();
      world.setAfterRead(2, dataSource::failConnections);
      var sixthOwner =
          owner(
              bindingInventory,
              statusRepository,
              redisOwner,
              world,
              "instance-f",
              incarnationSource);
      Throwable sqlOutageFailure =
          catchThrowable(() -> sixthOwner.installForTransition(TRANSITION_ID, world.request()));
      dataSource.restoreConnections();
      assertThat(sqlOutageFailure)
          .isInstanceOf(RuntimeException.class)
          .hasRootCauseInstanceOf(SQLException.class);
      assertThat(statusRepository.find(qualifiedSource)).contains(ownerStatusBeforeSqlOutage);
      assertThat(redis.admin().type(redisKey)).isEqualTo("string");
      assertThat(redis.admin().pttl(redisKey)).isPositive();
    }
  }

  @Test
  void accountMemberAcknowledgementAfterRegionBridgePreservesOriginalBridgeEvidence()
      throws Exception {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(POSTGRES);
    TestSchema testSchema = newTestSchema();
    try {
      // The shared test Redis container may have state from the sibling owner test.
      try (RedisFixture redis = RedisFixture.open()) {
        redis.admin().flushdb();
      }
      // This local candidate-launch source is created through the real Game Session owners;
      // its upstream publication inputs remain controlled test evidence.
      var runtimeTarget =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              testSchema.dataSource(), UUID.randomUUID(), "firemud");
      WorldFixture world = WorldFixture.open(runtimeTarget);
      CanonicalPlayableTarget currentTarget = runtimeTarget.playableTarget();
      // World and Entity responses are controlled fixtures; region and Account owners are actual.
      try (var entitySource = EntityReferenceFixture.open()) {
        CanonicalGameplayRegionOwnerCompositionFixture.MemberRepairProof proof =
            CanonicalGameplayRegionOwnerCompositionFixture.installAndBridgeThenRepairAccountMember(
                testSchema.dsl(),
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT),
                world.request(),
                currentTarget,
                world.resolver(),
                entitySource.client());

        assertThat(proof.regionBridge().exactLostReplyRetry()).isTrue();
        assertThat(proof.memberAcknowledgement().schemaVersion()).isEqualTo(2);
        assertThat(proof.memberAcknowledgement().transitionId())
            .isEqualTo(proof.regionBridge().transitionId());
        assertThat(proof.memberAcknowledgement().readback().member())
            .isEqualTo(proof.memberAcknowledgement().operation().member());
        assertThat(proof.memberAcknowledgedInventoryRevision())
            .isEqualTo(proof.regionAcknowledgedInventoryRevision().add(BigInteger.ONE));
        assertThat(proof.sharedRevisionPropagated()).isTrue();
        assertThat(proof.regionAcknowledgementEvidenceUnchanged()).isTrue();
        assertThat(proof.physicalBridgeEvidenceUnchanged()).isTrue();
        assertThat(proof.accountMemberAddedExactly()).isTrue();
        assertThat(proof.accountControlStatePreserved()).isTrue();
        assertThat(proof.exactMemberRetryPreservedState()).isTrue();
      }
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void acknowledgedRegionBridgeSurvivesExpiredGrantReplacement() throws Exception {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(POSTGRES);
    TestSchema testSchema = newTestSchema();
    try {
      try (RedisFixture redis = RedisFixture.open()) {
        redis.admin().flushdb();
      }
      // This local candidate-launch source is created through the real Game Session owners;
      // its upstream publication inputs remain controlled test evidence.
      var runtimeTarget =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              testSchema.dataSource(), UUID.randomUUID(), "firemud");
      WorldFixture world = WorldFixture.open(runtimeTarget);
      try (var entitySource = EntityReferenceFixture.open()) {
        CanonicalGameplayRegionOwnerCompositionFixture.ChangedGrantProof proof =
            CanonicalGameplayRegionOwnerCompositionFixture.installAndBridgeThenChangedGrantRefuses(
                testSchema.dsl(),
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT),
                world.request(),
                runtimeTarget.playableTarget(),
                world.resolver(),
                entitySource.client());

        assertThat(proof.acknowledgedBridge().exactLostReplyRetry()).isTrue();
        assertThat(proof.replacementGrantInstalled()).isTrue();
        assertThat(proof.replacementExecutorFence())
            .isGreaterThan(proof.acknowledgedBridge().executorFence());
        assertThat(proof.bridgeRefused()).isTrue();
        assertThat(proof.refusalMessage())
            .isEqualTo(
                "A completed region bridge acknowledgement cannot be rewritten for a new grant");
        assertThat(proof.acknowledgementRowPreserved()).isTrue();
        assertThat(proof.inventoryStatePreserved()).isTrue();
        assertThat(proof.physicalBridgeStatePreserved()).isTrue();
      }
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void createdOriginNativeBridgeRetainsOriginalPurposeAndRejectsSourceSubstitution()
      throws Exception {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(POSTGRES);
    TestSchema testSchema = newTestSchema();
    try {
      try (RedisFixture redis = RedisFixture.open()) {
        redis.admin().flushdb();
      }
      // Reuse the existing controlled launch/lifecycle scaffolding. This does not establish an
      // actual World placement, protected ACTIVE/hold authority, or first OPEN admission.
      var runtimeTarget =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              testSchema.dataSource(), UUID.randomUUID(), "firemud");
      var originalWorld = WorldFixture.open(runtimeTarget);
      var origin =
          PlayerCreatedActorOrigin.newBuilder()
              .setTargetNamespace("firemud")
              .setCreationOperationUuid(UUID.randomUUID().toString())
              .setMutationDigest("2".repeat(64))
              .setTerminalUuid(UUID.randomUUID().toString())
              .setTerminalSha256("3".repeat(64))
              .build();
      var request =
          originalWorld.request().toBuilder()
              .setSchemaVersion(2)
              .clearAssignmentUuid()
              .clearAssignmentIntentDigest()
              .setPlayerCreatedOrigin(origin)
              .build();
      var world = new WorldFixture(originalWorld.originalEvidence(), request);
      // Echo changed selectors as a controlled World transport would; refusal must come from the
      // receiving owner's original-source checks, rather than an unstubbed mock returning null.
      when(world.initialClient.readInitialPlayerLocationRegion(
              org.mockito.ArgumentMatchers.any(CanonicalInitialPlayerLocationRequest.class)))
          .thenAnswer(invocation -> regionResponse(invocation.getArgument(0), world.current.get()));
      assertThat(request.getAssignmentUuid()).isEmpty();
      assertThat(request.getAssignmentIntentDigest()).isEmpty();
      assertThat(origin.getAllFields()).hasSize(5);
      try (var preseeded = EntityReferenceFixture.open();
          var created = CreatedEntityReferenceFixture.open(request)) {
        var proof =
            CanonicalGameplayRegionOwnerCompositionFixture.installCreatedAndBridgeThenCheckSources(
                testSchema.dsl(),
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT),
                request,
                runtimeTarget.playableTarget(),
                world.resolver(),
                preseeded.client(),
                created.client,
                binding -> {
                  var preparation =
                      testSchema
                          .dsl()
                          .fetchSingle(
                              "SELECT * FROM game_session_region_timeline_preparation WHERE transition_id = ?",
                              binding.acknowledgement().obligation().transitionId());
                  var obligation =
                      testSchema
                          .dsl()
                          .fetchSingle(
                              "SELECT * FROM game_session_canonical_binding_region_bridge_obligation"
                                  + " WHERE transition_id = ?",
                              binding.acknowledgement().obligation().transitionId());
                  for (Record row : List.of(preparation, obligation)) {
                    assertThat(row.get("assignment_uuid")).isNull();
                    assertThat(row.get("assignment_intent_digest")).isNull();
                    assertThat(
                            CanonicalPlayerCreatedRuntimeReferenceEvidence.originFromStoredJson(
                                row.get("entity_created_origin", String.class)))
                        .isEqualTo(origin);
                  }
                  assertThat(preparation.get("initialization_operation_id", UUID.class))
                      .isNotEqualTo(UUID.fromString(origin.getCreationOperationUuid()))
                      .isNotEqualTo(UUID.fromString(request.getPlacementOperationUuid()))
                      .isNotEqualTo(obligation.get("bridge_operation_id", UUID.class));
                  assertThat(obligation.get("bridge_operation_id", UUID.class))
                      .isNotEqualTo(UUID.fromString(origin.getCreationOperationUuid()))
                      .isNotEqualTo(UUID.fromString(request.getPlacementOperationUuid()));
                  assertThat(request.getPlacementOperationUuid())
                      .isNotEqualTo(origin.getCreationOperationUuid());
                  assertThat(obligation.get("status", String.class)).isEqualTo("ACKNOWLEDGED");
                  assertThat(obligation.get("redis_readback_digest", String.class)).isNotBlank();
                  assertThat(binding.acknowledgement().obligation().actorOrigin())
                      .isEqualTo(
                          new net.firedevops.firemud.gamesession.client
                              .CanonicalGameplayRuntimeActorReference.CreationOrigin(origin));
                  var changed =
                      request.toBuilder()
                          .setPlayerCreatedOrigin(
                              origin.toBuilder().setMutationDigest("4".repeat(64)))
                          .build();
                  assertThatThrownBy(
                          () ->
                              binding
                                  .owner()
                                  .installForTransition(
                                      binding.installation(),
                                      binding.acknowledgement().obligation().transitionId(),
                                      changed))
                      .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
                  var substituted =
                      request.toBuilder()
                          .setAssignmentUuid(origin.getCreationOperationUuid())
                          .setAssignmentIntentDigest(origin.getMutationDigest())
                          .build();
                  assertThatThrownBy(
                          () ->
                              binding
                                  .owner()
                                  .installForTransition(
                                      binding.installation(),
                                      binding.acknowledgement().obligation().transitionId(),
                                      substituted))
                      .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
                  var unknownOrigin =
                      origin.toBuilder()
                          .setUnknownFields(
                              com.google.protobuf.UnknownFieldSet.newBuilder()
                                  .addField(
                                      99,
                                      com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                          .addVarint(1L)
                                          .build())
                                  .build())
                          .build();
                  var extended = request.toBuilder().setPlayerCreatedOrigin(unknownOrigin).build();
                  assertThatThrownBy(
                          () ->
                              binding
                                  .owner()
                                  .installForTransition(
                                      binding.installation(),
                                      binding.acknowledgement().obligation().transitionId(),
                                      extended))
                      .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
                  // Still structurally valid authenticated response, but no longer the original
                  // projection.
                  created.projectionFence.incrementAndGet();
                  assertThatThrownBy(() -> binding.owner().bridge(binding.installation()))
                      .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
                      .hasMessage("A retry changed the retained Entity or World bridge source");
                  created.projectionFence.decrementAndGet();
                  assertThat(binding.owner().bridge(binding.installation()).obligation())
                      .isEqualTo(binding.acknowledgement().obligation());
                });
        assertThat(proof.exactLostReplyRetry()).isTrue();
        assertThat(proof.runtimeEntityId()).isEqualTo(191L);
        assertThat(proof.entityProjectionGeneration()).isEqualTo(21L);
        assertThat(proof.entityProjectionFence()).isEqualTo(22L);
        assertThat(created.reads.get()).isGreaterThanOrEqualTo(13);
      }
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void laterPlacementAcquiresFreshGrantWithoutChangingInitialTimeline() throws Exception {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(POSTGRES);
    TestSchema schema = newTestSchema();
    try {
      try (RedisFixture redis = RedisFixture.open()) {
        redis.admin().flushdb();
      }
      var target =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              schema.dataSource(), UUID.randomUUID(), "firemud");
      var first = WorldFixture.open(target);
      var original = first.original.request();
      var secondRequest =
          first.request().toBuilder()
              .setPlacementOperationUuid(UUID.randomUUID().toString())
              .setCharacterUuid(UUID.randomUUID().toString())
              .setAssignmentUuid(UUID.randomUUID().toString())
              .build();
      var secondLifecycle =
          new WorldCanonicalInstanceLifecycleEvidence.Request(
              original.schemaVersion(),
              UUID.nameUUIDFromBytes(
                  ("firemud-world-initial-location-lifecycle-read/v1/"
                          + secondRequest.getPlacementOperationUuid())
                      .getBytes(StandardCharsets.US_ASCII)),
              original.targetNamespace(),
              original.canonicalTenantId(),
              original.worldSlug(),
              original.canonicalGameInstanceId(),
              original.playableStateNamespaceId(),
              original.playableStateScope(),
              original.publicProduction(),
              original.controlPlaneRequestId(),
              original.canonicalVersionId(),
              original.expectedDescriptorRequestDigest(),
              original.expectedDescriptorResultDigest(),
              original.expectedReleaseAttestationDigest());
      var secondEvidence = mock(WorldCanonicalInstanceLifecycleEvidence.class);
      when(secondEvidence.request()).thenReturn(secondLifecycle);
      when(secondEvidence.lifecycleStatus())
          .thenAnswer(ignored -> first.original.lifecycleStatus());
      when(secondEvidence.lifecycleEpoch()).thenAnswer(ignored -> first.original.lifecycleEpoch());
      when(secondEvidence.rowVersion()).thenAnswer(ignored -> first.original.rowVersion());
      when(secondEvidence.captureId()).thenAnswer(ignored -> first.original.captureId());
      when(secondEvidence.graphSha256()).thenAnswer(ignored -> first.original.graphSha256());
      when(secondEvidence.preparationInputDigest())
          .thenAnswer(ignored -> first.original.preparationInputDigest());
      when(secondEvidence.operationalRegionAssignments())
          .thenAnswer(ignored -> first.original.operationalRegionAssignments());
      var second = new WorldFixture(secondEvidence, secondRequest);
      assertThat(secondLifecycle.readRequestId()).isNotEqualTo(original.readRequestId());
      try (var entity = EntityReferenceFixture.open(secondRequest.getCharacterUuid())) {
        var result =
            CanonicalGameplayRegionOwnerCompositionFixture.installAndBridgeTwoCandidates(
                schema.dsl(),
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT),
                first.request(),
                target.playableTarget(),
                first.resolver(),
                entity.client(),
                new CanonicalGameplayRegionOwnerCompositionFixture.SecondBinding(
                    second.request(),
                    target.playableTarget(),
                    second.resolver(),
                    entity.client(),
                    true));
        assertThat(result.firstTimelineUnchanged()).isTrue();
        assertThat(result.initialMetaUnchanged()).isTrue();
        assertThat(result.first().exactLostReplyRetry()).isTrue();
        assertThat(result.second().exactLostReplyRetry()).isTrue();
        assertThat(result.second().executorFence()).isGreaterThan(result.first().executorFence());
        assertThat(result.foreignOwnerCapabilityRejectedWithoutStateChange()).isTrue();
        assertThat(result.expiredGrantRejectedWithoutStateChange()).isTrue();
      }
    } finally {
      dropSchema(schema.schema());
    }
  }

  private static Server entityReferenceServer(String secondCharacterId) throws Exception {
    var service =
        new CanonicalPreseededActorAssignmentServiceGrpc
            .CanonicalPreseededActorAssignmentServiceImplBase() {
          @Override
          public void readPreseededActorRuntimeReference(
              ReadPreseededActorRuntimeReferenceRequest request,
              StreamObserver<ReadPreseededActorRuntimeReferenceResponse> observer) {
            var peer = GrpcPeerIdentity.current();
            if (peer == null || !ENTITY_CLIENT_PEER.equals(peer.uri())) {
              observer.onError(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            // Controlled source maps distinct actors to distinct scoped runtime projections.
            observer.onNext(
                entityResponse(request).toBuilder()
                    .setRuntimeEntityId(
                        request.getCharacterUuid().equals(secondCharacterId) ? 92L : 91L)
                    .build());
            observer.onCompleted();
          }
        };
    return entityReferenceServer(service);
  }

  private static Server entityReferenceServer(io.grpc.BindableService service) throws Exception {
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            pki.keyManagerFactory(pki.identity("entity-server")),
            pki.trustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    factory.addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()));
    return factory.createServer().start();
  }

  private static ReadPreseededActorRuntimeReferenceResponse entityResponse(
      ReadPreseededActorRuntimeReferenceRequest request) {
    return ReadPreseededActorRuntimeReferenceResponse.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(request.getAssignmentUuid())
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setCharacterUuid(request.getCharacterUuid())
        .setIntentDigest(request.getIntentDigest())
        .setExpectedTarget(request.getExpectedTarget())
        .setPublishedOwnerProofDigest("f".repeat(64))
        .setRuntimeEntityId(91L)
        .setProjectionGeneration(11L)
        .setProjectionFence(12L)
        .setCorePayload(
            PreseededActorAssignmentCorePayload.newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Fixture Player"))
        .build();
  }

  private static ControlledConnectionFailureDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(POSTGRES.getDriverClassName());
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return new ControlledConnectionFailureDataSource(dataSource);
  }

  private static final class ControlledConnectionFailureDataSource extends DelegatingDataSource {
    private volatile boolean connectionsUnavailable;

    private ControlledConnectionFailureDataSource(DataSource target) {
      super(target);
    }

    void failConnections() {
      connectionsUnavailable = true;
    }

    void restoreConnections() {
      connectionsUnavailable = false;
    }

    @Override
    public Connection getConnection() throws SQLException {
      if (connectionsUnavailable) {
        throw new SQLException("Controlled test-owned PostgreSQL connection failure");
      }
      return super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      if (connectionsUnavailable) {
        throw new SQLException("Controlled test-owned PostgreSQL connection failure");
      }
      return super.getConnection(username, password);
    }
  }

  private static TestSchema newTestSchema() {
    String schema = "gs_region_member_order_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(POSTGRES.getDriverClassName());
    String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();
    return new TestSchema(schema, dataSource, DSL.using(dataSource, SQLDialect.POSTGRES));
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Failed to dispose reverse-order region/member schema", failure);
    }
  }

  /** Controlled current-incarnation fixture; it does not attest production Redis custody. */
  private static CanonicalGameplayRegionIncarnationSource.StableEvidence stableIncarnation(
      CanonicalGameplayRegionIncarnationSource.RegionScope scope, String storageGeneration) {
    return stableIncarnation(
        scope, storageGeneration, "e".repeat(64), "f".repeat(64), "1".repeat(64));
  }

  private static CanonicalGameplayRegionIncarnationSource.StableEvidence stableIncarnation(
      CanonicalGameplayRegionIncarnationSource.RegionScope scope,
      String storageGeneration,
      String profileEvidenceDigest,
      String recoveryEvidenceDigest,
      String unfinishedWorkEvidenceDigest) {
    var deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-deployment",
            "deployment-generation-1",
            List.of("node-a"),
            "redis-build-1",
            "script-registry-v1",
            "sha256:" + "a".repeat(64),
            "READY",
            "boot-1",
            "startup-1",
            storageGeneration,
            "aof-1");
    return CanonicalGameplayRegionIncarnationSource.StableEvidence.fromCurrentSource(
        scope,
        "controlled-region-attestor",
        "b".repeat(64),
        deployment,
        "c".repeat(64),
        "d".repeat(64),
        true,
        "controlled-non-reset-profile-v1",
        profileEvidenceDigest,
        true,
        recoveryEvidenceDigest,
        true,
        unfinishedWorkEvidenceDigest);
  }

  private static final class ControlledStableIncarnationSource
      implements CanonicalGameplayRegionIncarnationSource {
    private CanonicalGameplayRegionIncarnationSource.StableEvidence current;

    @Override
    public synchronized CanonicalGameplayRegionIncarnationSource.StableEvidence readCurrent(
        CanonicalGameplayRegionIncarnationSource.RegionScope scope) {
      if (current == null) current = stableIncarnation(scope, "storage-generation-1");
      if (!current.scope().equals(scope))
        throw new IllegalStateException("Controlled scope changed");
      return current;
    }

    synchronized void changeStorageGeneration(String value) {
      current = stableIncarnation(current.scope(), value);
    }

    synchronized void restoreStorageGeneration() {
      current = stableIncarnation(current.scope(), "storage-generation-1");
    }

    synchronized void changeRecoveryEvidence() {
      current =
          stableIncarnation(
              current.scope(),
              "storage-generation-1",
              "e".repeat(64),
              "0".repeat(64),
              "1".repeat(64));
    }

    synchronized void restoreRecoveryEvidence() {
      current = stableIncarnation(current.scope(), "storage-generation-1");
    }
  }

  private static CanonicalGameplayRegionLeaseOwner owner(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionLeaseRedisOwner redisOwner,
      WorldFixture world,
      String instanceId,
      CanonicalGameplayRegionIncarnationSource incarnationSource) {
    return new CanonicalGameplayRegionLeaseOwner(
        bindingInventory,
        statusRepository,
        redisOwner,
        incarnationSource,
        world.resolver(),
        new RuntimeIdentity(
            "game-session-service",
            instanceId,
            "localhost",
            NOW,
            "controlled-test",
            "controlled-sha",
            "controlled-image"),
        Duration.ofSeconds(30),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static CanonicalGameplayBindingProvisionalCasEvidence provisionalEvidence(
      CanonicalInitialPlayerLocationRequest request) {
    return provisionalEvidence(
        request,
        UUID.fromString(request.getExpectedTarget().getCanonicalTenantUuid()),
        BigInteger.ONE);
  }

  private static CanonicalGameplayBindingProvisionalCasEvidence provisionalEvidence(
      CanonicalInitialPlayerLocationRequest request, UUID tenantId, BigInteger regionEpoch) {
    var evidence = mock(CanonicalGameplayBindingProvisionalCasEvidence.class);
    var transition = mock(CanonicalGameplayBindingTransitionSnapshot.class);
    var candidate = mock(CanonicalGameplayBindingInventoryEntry.class);
    var identity = mock(CanonicalGameplayBindingIdentity.class);
    var target = request.getExpectedTarget();
    UUID gameInstanceId = UUID.fromString(target.getGameInstanceId());
    UUID sessionId =
        UUID.nameUUIDFromBytes(
            ("region-owner-session-" + TRANSITION_ID).getBytes(StandardCharsets.UTF_8));
    CanonicalGameplayBindingRef bindingRef =
        CanonicalGameplayBindingRef.of(tenantId, gameInstanceId, sessionId.toString());
    when(evidence.transition()).thenReturn(transition);
    when(evidence.candidate()).thenReturn(candidate);
    when(transition.status())
        .thenReturn(CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL);
    when(transition.transitionId()).thenReturn(TRANSITION_ID);
    when(candidate.lifecycle())
        .thenReturn(CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL);
    when(candidate.bindingRef()).thenReturn(bindingRef);
    when(candidate.bindingGeneration()).thenReturn(BigInteger.ONE);
    when(candidate.transitionId()).thenReturn(TRANSITION_ID);
    when(candidate.identity()).thenReturn(identity);
    when(identity.accountId()).thenReturn(UUID.fromString(request.getCanonicalAccountUuid()));
    when(identity.characterId()).thenReturn(UUID.fromString(request.getCharacterUuid()));
    when(identity.tenantId()).thenReturn(tenantId);
    when(identity.gameInstanceId()).thenReturn(gameInstanceId);
    when(identity.runtimeGameInstanceId()).thenReturn(23L);
    when(identity.sessionId()).thenReturn(sessionId.toString());
    when(identity.playableStateNamespaceId())
        .thenReturn(UUID.fromString(target.getPlayableStateNamespaceUuid()));
    when(identity.playableStateScope()).thenReturn("SHARED");
    when(identity.regionId()).thenReturn(OPERATIONAL_REGION_ID);
    when(identity.regionEpoch()).thenReturn(regionEpoch);
    return evidence;
  }

  private static CanonicalGameplayRegionRuntimeStatusRepository.Source source(
      WorldCanonicalInstanceLifecycleEvidence evidence) {
    return new CanonicalGameplayRegionRuntimeStatusRepository.Source(
        evidence.request().canonicalTenantId(),
        evidence.request().canonicalGameInstanceId(),
        CANONICAL_REGION_ID,
        OPERATIONAL_REGION_ID,
        evidence.lifecycleEpoch(),
        evidence.rowVersion(),
        evidence.request(),
        evidence.captureId(),
        evidence.graphSha256(),
        evidence.preparationInputDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence changedEvidence(
      WorldCanonicalInstanceLifecycleEvidence original) {
    var changed = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(changed.request()).thenReturn(original.request());
    when(changed.lifecycleStatus()).thenReturn("ACTIVE");
    when(changed.lifecycleEpoch()).thenReturn(original.lifecycleEpoch());
    when(changed.rowVersion()).thenReturn(original.rowVersion() + 1L);
    when(changed.captureId()).thenReturn(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    when(changed.graphSha256()).thenReturn(original.graphSha256());
    when(changed.preparationInputDigest()).thenReturn(original.preparationInputDigest());
    when(changed.operationalRegionAssignments())
        .thenReturn(original.operationalRegionAssignments());
    return changed;
  }

  private static CanonicalInitialPlayerLocationRequest initialLocationRequest(
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    var target =
        PreseededActorAssignmentExpectedTarget.newBuilder()
            .setCanonicalTenantUuid(lifecycleRequest.canonicalTenantId().toString())
            .setRealmUuid("33333333-3333-4333-8333-333333333333")
            .setWorldSlug(lifecycleRequest.worldSlug())
            .setRealmSlug("test-realm")
            .setGameInstanceId(lifecycleRequest.canonicalGameInstanceId().toString())
            .setCatalogRevision(1L)
            .setCanonicalVersionUuid(lifecycleRequest.canonicalVersionId().toString())
            .setFrozenPolicyDigest("a".repeat(64))
            .setPlayableStateNamespaceUuid(lifecycleRequest.playableStateNamespaceId().toString())
            .setPublishedReleaseBundleRef("published/test/v1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .build();
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid("44444444-4444-4444-8444-444444444444")
        .setExpectedTarget(target)
        .setCanonicalAccountUuid(ACCOUNT_ID.toString())
        .setCharacterUuid(CHARACTER_ID.toString())
        .setAssignmentUuid("77777777-7777-4777-8777-777777777777")
        .setAssignmentIntentDigest("b".repeat(64))
        .setInitialAdmissionHoldUuid("88888888-8888-4888-8888-888888888888")
        .setInitialAdmissionHoldFence("99999999-9999-4999-8999-999999999999")
        .setInitialAdmissionRequestId("initial-attempt-1")
        .setInitialAdmissionRequestDigest("c".repeat(64))
        .setActiveLifecycleEpoch(7L)
        .setTerminalProofDigest("sha256:" + "d".repeat(64))
        .setCommittedPointerVersion(1L)
        .setAuditEventId(1L)
        .build();
  }

  private static CanonicalInitialPlayerLocationRequest initialLocationRequest(
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target) {
    var expectedTarget =
        PreseededActorAssignmentExpectedTarget.newBuilder()
            .setCanonicalTenantUuid(target.canonicalTenantId().toString())
            .setRealmUuid(target.realmId().toString())
            .setWorldSlug(target.worldSlug())
            .setRealmSlug(target.realmSlug())
            .setGameInstanceId(target.gameInstanceUuid().toString())
            .setCatalogRevision(target.catalogRevision())
            .setCanonicalVersionUuid(target.canonicalVersionId().toString())
            .setFrozenPolicyDigest("a".repeat(64))
            .setPlayableStateNamespaceUuid(target.playableStateNamespaceId().toString())
            .setPublishedReleaseBundleRef(target.publishedReleaseBundleRef())
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .build();
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(UUID.randomUUID().toString())
        .setExpectedTarget(expectedTarget)
        .setCanonicalAccountUuid(ACCOUNT_ID.toString())
        .setCharacterUuid(CHARACTER_ID.toString())
        .setAssignmentUuid(UUID.randomUUID().toString())
        .setAssignmentIntentDigest("b".repeat(64))
        .setInitialAdmissionHoldUuid(target.holdId().toString())
        .setInitialAdmissionHoldFence(target.holdFence().toString())
        .setInitialAdmissionRequestId(target.initialAdmissionRequestId())
        .setInitialAdmissionRequestDigest(target.initialAdmissionRequestDigest())
        .setActiveLifecycleEpoch(target.activeWorldEpoch())
        .setTerminalProofDigest(target.ownerProofDigest())
        .setCommittedPointerVersion(target.pointerVersion())
        .setAuditEventId(target.auditEventId())
        .build();
  }

  private static CanonicalInitialPlayerLocationRegionResponse regionResponse(
      CanonicalInitialPlayerLocationRequest request,
      WorldCanonicalInstanceLifecycleEvidence evidence) {
    var placement =
        CanonicalInitialPlayerLocationResponse.newBuilder()
            .setSchemaVersion(1)
            .setPlacementOperationUuid(request.getPlacementOperationUuid())
            .setRequestDigest("sha256:" + "e".repeat(64))
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setCharacterUuid(request.getCharacterUuid())
            .setAssignmentUuid(request.getAssignmentUuid())
            .setAssignmentIntentDigest(request.getAssignmentIntentDigest())
            .setResultDigest("sha256:" + "f".repeat(64))
            .setOutcome(
                CanonicalInitialPlayerLocationOutcome
                    .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED)
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRoom
                    .newBuilder()
                    .setCanonicalTenantUuid(request.getExpectedTarget().getCanonicalTenantUuid())
                    .setCanonicalVersionUuid(request.getExpectedTarget().getCanonicalVersionUuid())
                    .setRoomTemplateUuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
            .setPrivateRuntimeRoomKey("91")
            .build();
    if (request.hasPlayerCreatedOrigin()) {
      placement =
          placement.toBuilder()
              .setSchemaVersion(2)
              .setPlayerCreatedOrigin(request.getPlayerCreatedOrigin())
              .build();
    }
    var lifecycle =
        net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationLifecycleConsequence
            .newBuilder()
            .setCanonicalRequestBytes(
                com.google.protobuf.ByteString.copyFrom(evidence.request().canonicalBytes()))
            .setLifecycleStatus(evidence.lifecycleStatus())
            .setLifecycleEpoch(evidence.lifecycleEpoch())
            .setRowVersion(evidence.rowVersion())
            .setCaptureUuid(evidence.captureId().toString())
            .setGraphSha256(evidence.graphSha256())
            .setPreparationInputDigest(evidence.preparationInputDigest())
            .build();
    return CanonicalInitialPlayerLocationRegionResponse.newBuilder()
        .setSchemaVersion(1)
        .setPlacementResult(placement)
        .setExpectedTarget(request.getExpectedTarget())
        .setCanonicalRegionInstanceUuid(CANONICAL_REGION_ID.toString())
        .setOperationalRegionUuid(OPERATIONAL_REGION_ID.toString())
        .setPrivateWorldInstanceKey(7L)
        .setObservedRuntimeRoomKey(91L)
        .setLifecycle(lifecycle)
        .build();
  }

  private static long fenceSequenceValue(DSLContext dsl) {
    Record row = dsl.fetchOne("SELECT last_value FROM game_session_region_executor_fence_seq");
    if (row == null) throw new AssertionError("Region fence sequence row was missing");
    Long value = row.get("last_value", Long.class);
    if (value == null || value <= 0L) throw new AssertionError("Region fence sequence was invalid");
    return value;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static final class WorldFixture {
    private final WorldCanonicalInstanceLifecycleEvidence original;
    private final CanonicalInitialPlayerLocationRequest request;
    private final CanonicalInitialPlayerLocationClient initialClient;
    private final WorldCanonicalInstanceLifecycleClient lifecycleClient;
    private final AtomicReference<WorldCanonicalInstanceLifecycleEvidence> current;
    private final AtomicInteger reads = new AtomicInteger();
    private volatile int replaceOnRead = -1;
    private volatile WorldCanonicalInstanceLifecycleEvidence replacement;
    private volatile int callbackOnRead = -1;
    private volatile Runnable afterRead = () -> {};
    private final CanonicalInitialPlayerLocationRegionResolver resolver;

    private WorldFixture(WorldCanonicalInstanceLifecycleEvidence original) {
      this(original, initialLocationRequest(original.request()));
    }

    private WorldFixture(
        WorldCanonicalInstanceLifecycleEvidence original,
        CanonicalInitialPlayerLocationRequest request) {
      this.original = original;
      this.current = new AtomicReference<>(original);
      this.request = request;
      this.initialClient = mock(CanonicalInitialPlayerLocationClient.class);
      this.lifecycleClient = mock(WorldCanonicalInstanceLifecycleClient.class);
      when(initialClient.readInitialPlayerLocationRegion(request))
          .thenAnswer(
              invocation -> {
                int read = reads.incrementAndGet();
                if (read == replaceOnRead) current.set(replacement);
                CanonicalInitialPlayerLocationRegionResponse response =
                    regionResponse(request, current.get());
                if (read == callbackOnRead) afterRead.run();
                return response;
              });
      when(lifecycleClient.read(original.request())).thenAnswer(invocation -> current.get());
      this.resolver =
          new CanonicalInitialPlayerLocationRegionResolver(
              initialClient, new CanonicalRegionBootstrapAssignmentResolver(lifecycleClient));
    }

    static WorldFixture open() throws Exception {
      return new WorldFixture(AuthoringFixtures.lifecycleEvidence("ACTIVE", 7L));
    }

    static WorldFixture open(CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target) {
      var lifecycleRequest =
          new WorldCanonicalInstanceLifecycleEvidence.Request(
              WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
              UUID.randomUUID(),
              target.targetNamespace(),
              target.canonicalTenantId(),
              target.worldSlug(),
              target.gameInstanceUuid(),
              target.playableStateNamespaceId(),
              target.playableStateScope(),
              true,
              target.controlPlaneRequestId(),
              target.canonicalVersionId(),
              "sha256:" + "a".repeat(64),
              "sha256:" + "b".repeat(64),
              "sha256:" + "c".repeat(64));
      var evidence = mock(WorldCanonicalInstanceLifecycleEvidence.class);
      when(evidence.request()).thenReturn(lifecycleRequest);
      when(evidence.lifecycleStatus()).thenReturn("ACTIVE");
      when(evidence.lifecycleEpoch()).thenReturn(target.activeWorldEpoch());
      when(evidence.rowVersion()).thenReturn(1L);
      when(evidence.captureId()).thenReturn(UUID.randomUUID());
      when(evidence.graphSha256()).thenReturn("d".repeat(64));
      when(evidence.preparationInputDigest()).thenReturn("sha256:" + "e".repeat(64));
      when(evidence.operationalRegionAssignments())
          .thenReturn(java.util.Map.of(CANONICAL_REGION_ID, OPERATIONAL_REGION_ID));
      return new WorldFixture(evidence, initialLocationRequest(target));
    }

    CanonicalInitialPlayerLocationRegionResolver resolver() {
      return resolver;
    }

    CanonicalInitialPlayerLocationRequest request() {
      return request;
    }

    WorldCanonicalInstanceLifecycleEvidence originalEvidence() {
      return original;
    }

    void replaceOnRead(int read, WorldCanonicalInstanceLifecycleEvidence evidence) {
      resetReads();
      this.replaceOnRead = read;
      this.replacement = evidence;
    }

    void setAfterRead(int read, Runnable action) {
      resetReads();
      this.callbackOnRead = read;
      this.afterRead = action;
    }

    void resetReads() {
      reads.set(0);
      current.set(original);
      replaceOnRead = -1;
      replacement = null;
      callbackOnRead = -1;
      afterRead = () -> {};
    }
  }

  private static final class EntityReferenceFixture implements AutoCloseable {
    private final Server server;
    private final CanonicalPreseededActorRuntimeReferenceClient client;

    private EntityReferenceFixture(
        Server server, CanonicalPreseededActorRuntimeReferenceClient client) {
      this.server = server;
      this.client = client;
    }

    static EntityReferenceFixture open() throws Exception {
      return open(null);
    }

    static EntityReferenceFixture open(String secondCharacterId) throws Exception {
      Server server = entityReferenceServer(secondCharacterId);
      ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
      endpoints.setEntityManagementService("localhost:" + server.getPort());
      var client =
          new CanonicalPreseededActorRuntimeReferenceClient(
              endpoints,
              pki.clientProperties(pkiDirectory, pki.identity("client")),
              new GrpcChannelFactory(),
              "firemud");
      try {
        client.init();
        return new EntityReferenceFixture(server, client);
      } catch (Exception failure) {
        client.close();
        server.shutdownNow();
        throw failure;
      }
    }

    CanonicalPreseededActorRuntimeReferenceClient client() {
      return client;
    }

    @Override
    public void close() throws Exception {
      client.close();
      server.shutdownNow();
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Controlled Entity reference server did not terminate");
      }
    }
  }

  /** Generated Entity response over real role-bound test mTLS; not an Entity producer proof. */
  private static final class CreatedEntityReferenceFixture implements AutoCloseable {
    private final Server server;
    private final CanonicalPlayerCreatedRuntimeReferenceClient client;
    private final AtomicInteger projectionFence;
    private final AtomicInteger reads;

    private CreatedEntityReferenceFixture(
        Server server,
        CanonicalPlayerCreatedRuntimeReferenceClient client,
        AtomicInteger projectionFence,
        AtomicInteger reads) {
      this.server = server;
      this.client = client;
      this.projectionFence = projectionFence;
      this.reads = reads;
    }

    static CreatedEntityReferenceFixture open(CanonicalInitialPlayerLocationRequest worldRequest)
        throws Exception {
      var target = worldRequest.getExpectedTarget();
      var expected =
          PlayerCreatedActorRuntimeReferenceRequest.newBuilder()
              .setSchemaVersion(1)
              .setOrigin(worldRequest.getPlayerCreatedOrigin())
              .setTenantUuid(target.getCanonicalTenantUuid())
              .setAccountUuid(worldRequest.getCanonicalAccountUuid())
              .setPlayableStateNamespaceUuid(target.getPlayableStateNamespaceUuid())
              .setGameInstanceUuid(target.getGameInstanceId())
              .setCharacterUuid(worldRequest.getCharacterUuid())
              .setCanonicalVersionUuid(target.getCanonicalVersionUuid())
              .setRealmUuid(target.getRealmUuid())
              .setWorldSlug(target.getWorldSlug())
              .setRealmSlug(target.getRealmSlug())
              .build();
      var projectionFence = new AtomicInteger(22);
      var reads = new AtomicInteger();
      var service =
          new CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
              .CanonicalPlayerCreatedRuntimeReferenceServiceImplBase() {
            @Override
            public void readPlayerCreatedActorRuntimeReference(
                PlayerCreatedActorRuntimeReferenceRequest request,
                StreamObserver<PlayerCreatedActorRuntimeReferenceResponse> observer) {
              var peer = GrpcPeerIdentity.current();
              if (peer == null
                  || !ENTITY_CLIENT_PEER.equals(peer.uri())
                  || !expected.equals(request)) {
                observer.onError(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
                return;
              }
              reads.incrementAndGet();
              observer.onNext(
                  PlayerCreatedActorRuntimeReferenceResponse.newBuilder()
                      .setSchemaVersion(1)
                      .setSelector(expected)
                      .setRuntimeEntityId(191L)
                      .setProjectionGeneration(21L)
                      .setProjectionFence(projectionFence.get())
                      .setOriginalCatalogRevision(1L)
                      .setOriginalPointerVersion(1L)
                      .setOriginalActiveLifecycleEpoch(1L)
                      .setOriginalDescriptorDigest("sha256:" + "5".repeat(64))
                      .setOriginalAuthoredBindingDigest("sha256:" + "6".repeat(64))
                      .setOriginalPolicyContentDigest("sha256:" + "7".repeat(64))
                      .build());
              observer.onCompleted();
            }
          };
      Server server = entityReferenceServer(service);
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setEntityManagementService("localhost:" + server.getPort());
      var client =
          new CanonicalPlayerCreatedRuntimeReferenceClient(
              endpoints,
              pki.clientProperties(pkiDirectory, pki.identity("client")),
              new GrpcChannelFactory(),
              "firemud");
      try {
        client.init();
        return new CreatedEntityReferenceFixture(server, client, projectionFence, reads);
      } catch (Exception failure) {
        client.close();
        server.shutdownNow();
        throw failure;
      }
    }

    @Override
    public void close() throws Exception {
      client.close();
      server.shutdownNow();
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
            "Controlled created Entity reference server did not terminate");
      }
    }
  }

  private static final class RedisFixture implements AutoCloseable {
    private final RedisClient administrator;
    private final RedisClient ownerClient;
    private final StatefulRedisConnection<byte[], byte[]> adminConnection;
    private final RedisCommands<byte[], byte[]> admin;
    private final RedisScriptCatalog catalog;

    private RedisFixture(
        RedisClient administrator,
        RedisClient ownerClient,
        StatefulRedisConnection<byte[], byte[]> adminConnection,
        RedisCommands<byte[], byte[]> admin,
        RedisScriptCatalog catalog) {
      this.administrator = administrator;
      this.ownerClient = ownerClient;
      this.adminConnection = adminConnection;
      this.admin = admin;
      this.catalog = catalog;
    }

    static RedisFixture open() {
      RedisClient administrator = createClient(null, null);
      StatefulRedisConnection<byte[], byte[]> adminConnection =
          administrator.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      admin.flushdb();
      admin.aclSetuser(
          PRINCIPAL,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(PASSWORD)
              .keyPattern("tick-executor-lease:*")
              .keyPattern("tick:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.TYPE)
              .addCommand(CommandType.HGET)
              .addCommand(CommandType.HSET)
              .addCommand(CommandType.HLEN)
              .addCommand(CommandType.PTTL)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.PEXPIRE));
      RedisClient ownerClient = createClient(PRINCIPAL, PASSWORD);
      RedisScriptCatalog catalog =
          RedisScriptCatalog.fromContributions(
              List.of(new CanonicalGameplayRegionLeaseRedisScriptContribution()));
      return new RedisFixture(administrator, ownerClient, adminConnection, admin, catalog);
    }

    private static RedisClient createClient(String username, String password) {
      RedisURI.Builder builder =
          RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT))
              .withTimeout(Duration.ofSeconds(3));
      if (username != null) builder.withAuthentication(username, password);
      RedisClient client = RedisClient.create(builder.build());
      client.setOptions(ClientOptions.builder().autoReconnect(false).build());
      return client;
    }

    RedisClient ownerClient() {
      return ownerClient;
    }

    RedisCommands<byte[], byte[]> admin() {
      return admin;
    }

    RedisScriptCatalog catalog() {
      return catalog;
    }

    @Override
    public void close() {
      adminConnection.close();
      ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }

  private record TestSchema(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}
}
