package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.client.CanonicalInitialPlayerLocationClient;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.gamesession.service.CanonicalRegionBootstrapAssignmentResolver;
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
import org.junit.jupiter.api.Test;
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

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(REDIS_PORT);

  @Test
  void composesLeaseInstallRevalidationHandoffAndFailClosedSourceChanges() throws Exception {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(POSTGRES);
    DriverManagerDataSource dataSource = dataSource();
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
      var bindingEvidence = provisionalEvidence(world.request());
      var bindingInventory = mock(CanonicalGameplayBindingInventoryRepository.class);
      when(bindingInventory.readProvisionalCasEvidence(TRANSITION_ID)).thenReturn(bindingEvidence);

      var firstOwner = owner(bindingInventory, statusRepository, redisOwner, world, "instance-a");
      var qualifiedSource = source(world.originalEvidence());
      byte[] redisKey =
          ascii(
              CanonicalGameplayRegionRedisKeys.lease(
                  qualifiedSource.tenantId(),
                  qualifiedSource.gameInstanceId(),
                  qualifiedSource.regionId()));

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
          owner(wrongTargetInventory, statusRepository, redisOwner, world, "wrong-target");
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
          owner(wrongEpochInventory, statusRepository, redisOwner, world, "wrong-epoch");
      assertThatThrownBy(() -> wrongEpochOwner.installForTransition(TRANSITION_ID, world.request()))
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

      var firstInstallation = firstOwner.installForTransition(TRANSITION_ID, world.request());
      var firstStatus = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(firstStatus.regionEpoch()).isEqualTo(1L);
      assertThat(firstStatus.lifecycleEpoch()).isEqualTo(7L);
      assertThat(firstStatus.lastCommittedTickId()).isEqualTo(-1L);
      Long sequenceAfterInstall = fenceSequenceValue(dsl);

      firstOwner.revalidateBeforeBridge(firstInstallation);
      firstOwner.revalidateBeforeBridge(firstInstallation);

      assertThat(statusRepository.find(qualifiedSource)).contains(firstStatus);
      assertThat(fenceSequenceValue(dsl)).isEqualTo(sequenceAfterInstall);

      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();
      assertThatThrownBy(() -> firstOwner.revalidateBeforeBridge(firstInstallation))
          .isInstanceOf(IllegalStateException.class);
      assertThat(statusRepository.find(qualifiedSource)).contains(firstStatus);

      var secondOwner = owner(bindingInventory, statusRepository, redisOwner, world, "instance-b");
      secondOwner.installForTransition(TRANSITION_ID, world.request());
      var secondStatus = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(secondStatus.executorFence()).isGreaterThan(firstStatus.executorFence());
      assertThat(secondStatus.regionEpoch()).isEqualTo(firstStatus.regionEpoch());
      assertThat(secondStatus.lastCommittedTickId()).isEqualTo(-1L);
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
      var progressed = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();

      var thirdOwner = owner(bindingInventory, statusRepository, redisOwner, world, "instance-c");
      var thirdInstallation = thirdOwner.installForTransition(TRANSITION_ID, world.request());
      var thirdStatus = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(thirdStatus.executorFence()).isGreaterThan(progressed.executorFence());
      assertThat(thirdStatus.regionEpoch()).isEqualTo(1L);
      assertThat(thirdStatus.lastCommittedTickId()).isEqualTo(8L);
      assertThat(thirdStatus.lastCommittedTickBatchId()).isEqualTo("batch-8");
      assertThat(thirdStatus.ownerInstanceId()).isEqualTo("instance-c");
      assertThat(thirdInstallation).isNotNull();

      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();
      WorldCanonicalInstanceLifecycleEvidence changed = changedEvidence(world.originalEvidence());
      world.replaceOnRead(2, changed);
      var fourthOwner = owner(bindingInventory, statusRepository, redisOwner, world, "instance-d");
      assertThatThrownBy(() -> fourthOwner.installForTransition(TRANSITION_ID, world.request()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(statusRepository.find(qualifiedSource)).contains(thirdStatus);

      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();
      world.resetReads();
      world.replaceOnRead(3, changed);
      var fifthOwner = owner(bindingInventory, statusRepository, redisOwner, world, "instance-e");
      assertThatThrownBy(() -> fifthOwner.installForTransition(TRANSITION_ID, world.request()))
          .isInstanceOf(IllegalStateException.class);
      var installedBeforeChangedSourceGate = statusRepository.find(qualifiedSource).orElseThrow();
      assertThat(installedBeforeChangedSourceGate.executorFence())
          .isGreaterThan(thirdStatus.executorFence());
      assertThat(installedBeforeChangedSourceGate.lastCommittedTickId()).isEqualTo(8L);
      assertThat(installedBeforeChangedSourceGate.ownerInstanceId()).isEqualTo("instance-e");
      assertThat(redis.admin().pexpire(redisKey, 0L)).isTrue();

      world.resetReads();
      world.setAfterRead(2, POSTGRES::stop);
      var sixthOwner = owner(bindingInventory, statusRepository, redisOwner, world, "instance-f");
      assertThatThrownBy(() -> sixthOwner.installForTransition(TRANSITION_ID, world.request()))
          .isInstanceOf(RuntimeException.class);
      assertThat(redis.admin().type(redisKey)).isEqualTo("string");
      assertThat(redis.admin().pttl(redisKey)).isPositive();
    }
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(POSTGRES.getDriverClassName());
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private static CanonicalGameplayRegionLeaseOwner owner(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionLeaseRedisOwner redisOwner,
      WorldFixture world,
      String instanceId) {
    return new CanonicalGameplayRegionLeaseOwner(
        bindingInventory,
        statusRepository,
        redisOwner,
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
    when(evidence.transition()).thenReturn(transition);
    when(evidence.candidate()).thenReturn(candidate);
    when(transition.status())
        .thenReturn(CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL);
    when(transition.transitionId()).thenReturn(TRANSITION_ID);
    when(candidate.lifecycle())
        .thenReturn(CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL);
    when(candidate.transitionId()).thenReturn(TRANSITION_ID);
    when(candidate.identity()).thenReturn(identity);
    when(identity.accountId()).thenReturn(UUID.fromString(request.getCanonicalAccountUuid()));
    when(identity.characterId()).thenReturn(UUID.fromString(request.getCharacterUuid()));
    when(identity.tenantId()).thenReturn(tenantId);
    when(identity.gameInstanceId()).thenReturn(UUID.fromString(target.getGameInstanceId()));
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
      this.original = original;
      this.current = new AtomicReference<>(original);
      this.request = initialLocationRequest(original.request());
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
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.TYPE)
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
}
