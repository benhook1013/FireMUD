package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.WorldLifecycleCommandService;
import net.firedevops.firemud.worldmanagement.service.impl.InitialAdmissionBindHoldReconciler;
import net.firedevops.firemud.worldmanagement.service.impl.InstanceServiceImpl;
import net.firedevops.firemud.worldmanagement.service.impl.WorldEventServiceImpl;
import net.firedevops.firemud.worldmanagement.service.impl.WorldLifecycleCommandServiceImpl;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof of fixture lifecycle denial before effects and passive binding storage.
 * Controlled storage rows do not establish World materialization or activation authority.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = {"spring.grpc.server.port=0"})
class RunOwnedWorldLaunchEvidencePostgresIntegrationTest {
  private static final String RELEASE_REF = "8ce42642-53ae-4d07-aab8-37c43ce66e0c";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private AutowireCapableBeanFactory beanFactory;
  @MockitoSpyBean private WorldInstanceRepository repository;
  @Autowired private WorldLifecycleCommandService lifecycleService;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private RunOwnedWorldLaunchEvidenceProvider fixtureProvider;
  @MockitoBean private RunOwnedWorldLaunchEvidence fixtureEvidence;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;
  // Keep unrelated scheduled writers inert so whole-schema snapshots measure fixture effects.
  @MockitoBean private InitialAdmissionBindHoldReconciler initialAdmissionBindHoldReconciler;
  @MockitoBean private WorldEventServiceImpl worldEventService;
  @MockitoBean private InstanceServiceImpl instanceService;

  @Test
  void fixturePrepareAndActivationRetryAndRestartDenyBeforeReadsOrPostgresEffects() {
    long tenantId = 8_810_001L;
    long gameInstanceId = uniqueId();
    PreparedWorldInstanceRequest request = request(tenantId, gameInstanceId, operationId());
    // This pre-existing controlled storage row supplies no canonical association or authority.
    repository.save(passiveStorageRow(request, "a".repeat(64)));
    when(fixtureProvider.isEnabled()).thenReturn(true);
    clearInvocations(
        repository,
        fixtureEvidence,
        gameDesignClient,
        gameSessionClient,
        entityManagementClient,
        bindProofClient);
    Map<String, List<String>> before = postgresState();
    try {
      assertFixtureDenied(() -> lifecycleService.prepareWorldInstance(request));
      assertFixtureDenied(() -> lifecycleService.prepareWorldInstance(request));
      assertFixtureDenied(
          () -> lifecycleService.activatePreparedWorldInstance(tenantId, gameInstanceId, 1L));
      assertFixtureDenied(
          () -> lifecycleService.activatePreparedWorldInstance(tenantId, gameInstanceId, 1L));
      assertFixtureDenied(
          () ->
              lifecycleService.prepareWorldInstance(
                  request(tenantId, uniqueId(), request.controlPlaneRequestId())));

      RunOwnedWorldLaunchEvidenceProvider restartedProvider =
          mock(RunOwnedWorldLaunchEvidenceProvider.class);
      when(restartedProvider.isEnabled()).thenReturn(true);
      WorldInstanceRepository restartedRepository = spy(new WorldInstanceRepository(dsl));
      WorldLifecycleCommandServiceImpl restartedService =
          restartedLifecycleService(restartedRepository, restartedProvider);
      assertFixtureDenied(() -> restartedService.prepareWorldInstance(request));
      assertFixtureDenied(
          () -> restartedService.activatePreparedWorldInstance(tenantId, gameInstanceId, 1L));
      assertThat(postgresState()).isEqualTo(before);
      verifyNoInteractions(repository, restartedRepository);
      org.mockito.Mockito.verify(restartedProvider, org.mockito.Mockito.never())
          .requireAuthenticatedEvidence();
      verifyNoUpstreamReads();
    } finally {
      dsl.execute("DELETE FROM world_instance WHERE tenant_id = ?", tenantId);
    }
  }

  @Test
  void controlledPassiveStorageRetainsBindingAndEnforcesConstraintsUniquenessAndCas() {
    clearInvocations(fixtureProvider, fixtureEvidence);
    long tenantId = 8_810_004L;
    long gameInstanceId = uniqueId();
    PreparedWorldInstanceRequest request = request(tenantId, gameInstanceId, operationId());
    try {
      WorldInstance stored = repository.save(passiveStorageRow(request, "a".repeat(64)));
      assertThat(stored.getStatus()).isEqualTo("PREPARING");
      assertThat(stored.getLifecycleEpoch()).isEqualTo(1L);
      assertThat(stored.getRunOwnedFixturePreparingLifecycleEpoch()).isEqualTo(1L);
      assertThat(stored.getRunOwnedFixtureBindingSha256()).isEqualTo("a".repeat(64));
      assertThat(stored.getPublishedReleaseBundleRef()).isEqualTo(RELEASE_REF);
      assertThat(stored.getRowVersion()).isZero();
      WorldInstanceRepository restartedRepository = new WorldInstanceRepository(dsl);
      assertThat(
              restartedRepository
                  .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
                  .orElseThrow())
          .usingRecursiveComparison()
          .isEqualTo(stored);
      assertThat(
              restartedRepository
                  .findRunOwnedFixtureByTenantIdAndControlPlaneRequestId(
                      tenantId, request.controlPlaneRequestId())
                  .orElseThrow())
          .usingRecursiveComparison()
          .isEqualTo(stored);

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE world_instance SET run_owned_fixture_preparing_lifecycle_epoch = 0 "
                          + "WHERE tenant_id = ? AND game_instance_id = ?",
                      tenantId,
                      gameInstanceId))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE world_instance SET run_owned_fixture_preparing_lifecycle_epoch = NULL "
                          + "WHERE tenant_id = ? AND game_instance_id = ?",
                      tenantId,
                      gameInstanceId))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE world_instance SET run_owned_fixture_binding_sha256 = NULL "
                          + "WHERE tenant_id = ? AND game_instance_id = ?",
                      tenantId,
                      gameInstanceId))
          .isInstanceOf(RuntimeException.class);

      assertThat(
              restartedRepository
                  .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
                  .orElseThrow())
          .usingRecursiveComparison()
          .isEqualTo(stored);

      WorldInstance staleCopy =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      WorldInstance winningCopy =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      winningCopy.setFailureReason("CAS winner");
      WorldInstance savedWinningCopy = repository.save(winningCopy);
      staleCopy.setFailureReason("stale loser");
      assertThatThrownBy(() -> repository.save(staleCopy))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Failed to update world_instance id=" + staleCopy.getId());
      WorldInstance durableWinner =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(durableWinner.getFailureReason()).isEqualTo("CAS winner");
      assertThat(durableWinner.getRowVersion()).isEqualTo(savedWinningCopy.getRowVersion());
      assertThat(durableWinner.getRunOwnedFixtureBindingSha256()).isEqualTo("a".repeat(64));
      assertThat(durableWinner.getRunOwnedFixturePreparingLifecycleEpoch()).isEqualTo(1L);
      WorldInstance duplicate =
          passiveStorageRow(
              request(tenantId, uniqueId(), request.controlPlaneRequestId()), "c".repeat(64));
      assertThatThrownBy(() -> repository.save(duplicate)).isInstanceOf(RuntimeException.class);
      assertThat(
              restartedRepository
                  .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
                  .orElseThrow())
          .usingRecursiveComparison()
          .isEqualTo(durableWinner);
      verifyNoInteractions(fixtureProvider, fixtureEvidence);
    } finally {
      dsl.execute("DELETE FROM world_instance WHERE tenant_id = ?", tenantId);
    }
  }

  @Test
  void operationAdvisoryLockSerializesConcurrentRetriesOnPostgres() throws Exception {
    long tenantId = 8_810_002L;
    String operation = operationId();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    CountDownLatch firstHasLock = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondHasLock = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> first =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        repository.lockRunOwnedFixtureOperation(tenantId, operation);
                        firstHasLock.countDown();
                        await(releaseFirst);
                      }));
      assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> second =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        repository.lockRunOwnedFixtureOperation(tenantId, operation);
                        secondHasLock.countDown();
                      }));
      assertThat(secondHasLock.await(200, TimeUnit.MILLISECONDS)).isFalse();
      releaseFirst.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
      assertThat(secondHasLock.getCount()).isZero();
    } finally {
      releaseFirst.countDown();
    }
  }

  @Test
  void concurrentFixturePrepareAndActivationRetriesDenyWithoutPostgresOrUpstreamEffects()
      throws Exception {
    long tenantId = 8_810_003L;
    long gameInstanceId = uniqueId();
    PreparedWorldInstanceRequest request = request(tenantId, gameInstanceId, operationId());
    when(fixtureProvider.isEnabled()).thenReturn(true);
    clearInvocations(
        repository,
        fixtureEvidence,
        gameDesignClient,
        gameSessionClient,
        entityManagementClient,
        bindProofClient);
    Map<String, List<String>> before = postgresState();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> first =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                assertFixtureDenied(() -> lifecycleService.prepareWorldInstance(request));
                assertFixtureDenied(
                    () ->
                        lifecycleService.activatePreparedWorldInstance(
                            tenantId, gameInstanceId, 1L));
              });
      Future<?> second =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                assertFixtureDenied(() -> lifecycleService.prepareWorldInstance(request));
                assertFixtureDenied(
                    () ->
                        lifecycleService.activatePreparedWorldInstance(
                            tenantId, gameInstanceId, 1L));
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
      assertThat(postgresState()).isEqualTo(before);
      verifyNoInteractions(repository);
      verifyNoUpstreamReads();
    } finally {
      start.countDown();
    }
  }

  private static void assertFixtureDenied(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("RUN_OWNED_WORLD_MATERIALIZATION_UNAVAILABLE:");
  }

  private void verifyNoUpstreamReads() {
    org.mockito.Mockito.verify(fixtureProvider, org.mockito.Mockito.never())
        .requireAuthenticatedEvidence();
    verifyNoInteractions(
        fixtureEvidence,
        gameDesignClient,
        gameSessionClient,
        entityManagementClient,
        bindProofClient);
  }

  private Map<String, List<String>> postgresState() {
    String schema = dsl.fetchSingle("SELECT current_schema()").get(0, String.class);
    assertThat(schema).isEqualTo("world_management_service");
    List<String> tables =
        dsl.fetch("SELECT tablename FROM pg_tables WHERE schemaname = ? ORDER BY tablename", schema)
            .getValues(0, String.class);
    assertThat(tables).isNotEmpty().contains("world_instance");
    Map<String, List<String>> state = new LinkedHashMap<>();
    for (String table : tables) {
      // Entire rows and xmin retain source/resource bytes as well as lifecycle/CAS state.
      state.put(
          table,
          dsl.fetch(
                  "SELECT to_jsonb(retained)::text || ':xmin=' || retained.xmin::text FROM "
                      + DSL.name(schema, table)
                      + " retained ORDER BY 1")
              .getValues(0, String.class));
    }
    List<String> sequences =
        dsl.fetch(
                "SELECT to_jsonb(retained)::text FROM (SELECT sequencename, last_value "
                    + "FROM pg_sequences WHERE schemaname = ? ORDER BY sequencename) retained",
                schema)
            .getValues(0, String.class);
    assertThat(sequences).isNotEmpty();
    state.put("sequences", sequences);
    return state;
  }

  private static WorldInstance passiveStorageRow(
      PreparedWorldInstanceRequest request, String binding) {
    WorldInstance row = new WorldInstance();
    row.setTenantId(request.tenantId());
    row.setGameInstanceId(request.gameInstanceId());
    row.setGameTemplateId(request.gameTemplateId());
    row.setControlPlaneRequestId(request.controlPlaneRequestId());
    row.setLaunchDescriptorId(request.launchDescriptorId());
    row.setVersionId(request.versionId());
    row.setRuntimeFlagsJson(request.runtimeFlagsJson());
    row.setGenerationConfigRevision(request.generationConfigRevision());
    row.setReleaseBundleId(request.releaseBundleId());
    row.setPublishedReleaseBundleRef(request.publishedReleaseBundleRef());
    row.setVersionStateEpoch(request.versionStateEpoch());
    row.setLifecycleEpoch(1L);
    row.setStatus("PREPARING");
    row.setCreatedAt(Instant.now());
    row.setUpdatedAt(Instant.now());
    row.setRowVersion(0L);
    row.setRunOwnedFixtureBindingSha256(binding);
    row.setRunOwnedFixturePreparingLifecycleEpoch(1L);
    return row;
  }

  private WorldLifecycleCommandServiceImpl restartedLifecycleService(
      WorldInstanceRepository restartedRepository, RunOwnedWorldLaunchEvidenceProvider provider) {
    WorldProperties properties = new WorldProperties();
    properties.setLocalShardId(7);
    WorldLifecycleCommandServiceImpl restarted =
        new WorldLifecycleCommandServiceImpl(
            restartedRepository,
            mock(RegionInstanceRepository.class),
            mock(ZoneRepository.class),
            mock(ZoneInstanceRepository.class),
            mock(RoomRepository.class),
            mock(RoomExitRepository.class),
            mock(RoomInstanceRepository.class),
            mock(RoomInstanceExitRepository.class),
            mock(WorldEventRepository.class),
            properties,
            gameDesignClient,
            mock(EntityManagementClient.class),
            new SimpleMeterRegistry(),
            transactionManager,
            provider);
    return (WorldLifecycleCommandServiceImpl)
        beanFactory.initializeBean(
            restarted, "restartedWorldLifecycleCommandService-" + UUID.randomUUID());
  }

  private static PreparedWorldInstanceRequest request(
      long tenantId, long gameInstanceId, String operationId) {
    return new PreparedWorldInstanceRequest(
        tenantId,
        gameInstanceId,
        17L,
        operationId,
        "launch-descriptor-fixture-11",
        11L,
        null,
        "{}",
        "genrev-11",
        77L,
        RELEASE_REF,
        77L,
        null);
  }

  private static long uniqueId() {
    long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return id == 0L ? 1L : id;
  }

  private static String operationId() {
    return UUID.randomUUID().toString();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("PostgreSQL fixture lock test timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("PostgreSQL fixture lock test interrupted", interrupted);
    }
  }
}
