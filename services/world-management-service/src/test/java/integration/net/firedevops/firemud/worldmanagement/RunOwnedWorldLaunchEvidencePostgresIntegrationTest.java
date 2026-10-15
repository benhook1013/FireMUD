package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.entitymanagement.v1.CleanupRuntimeInstanceResponse;
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
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
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
import net.firedevops.firemud.worldmanagement.service.impl.WorldLifecycleCommandServiceImpl;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL proof for fixture binding durability, uniqueness, lock serialization, and CAS.
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
  @Autowired private WorldInstanceRepository repository;
  @Autowired private WorldLifecycleCommandService lifecycleService;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  // Controlled evidence inputs keep this proof focused on the real PostgreSQL lifecycle/CAS.
  @MockitoBean private RunOwnedWorldLaunchEvidenceProvider fixtureProvider;
  @MockitoBean private RunOwnedWorldLaunchEvidence fixtureEvidence;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @Test
  void prepareActivationReplayRestartChangedReceiptAndDuplicateOperationUseDurableBinding() {
    long tenantId = 8_810_001L;
    long gameInstanceId = uniqueId();
    PreparedWorldInstanceRequest request = request(tenantId, gameInstanceId, operationId());
    String binding = "a".repeat(64);
    configureEvidence(binding);
    try {
      var prepared = lifecycleService.prepareWorldInstance(request);
      WorldInstance stored =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(prepared.status()).isEqualTo("PREPARING");
      assertThat(stored.getLifecycleEpoch()).isEqualTo(1L);
      assertThat(stored.getRunOwnedFixturePreparingLifecycleEpoch()).isEqualTo(1L);
      assertThat(stored.getRunOwnedFixtureBindingSha256()).isEqualTo(binding);
      assertThat(stored.getPublishedReleaseBundleRef()).isEqualTo(RELEASE_REF);
      assertThat(stored.getRowVersion()).isZero();

      var active = lifecycleService.activatePreparedWorldInstance(tenantId, gameInstanceId, 1L);
      assertThat(active.status()).isEqualTo("ACTIVE");
      assertThat(active.lifecycleEpoch()).isEqualTo(2L);
      WorldInstance activeRow =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(activeRow.getRunOwnedFixtureBindingSha256()).isEqualTo(binding);
      assertThat(activeRow.getRunOwnedFixturePreparingLifecycleEpoch()).isEqualTo(1L);
      long activeRowVersion = activeRow.getRowVersion();

      RunOwnedWorldLaunchEvidenceProvider restartedProvider =
          mock(RunOwnedWorldLaunchEvidenceProvider.class);
      RunOwnedWorldLaunchEvidence restartedEvidence = mock(RunOwnedWorldLaunchEvidence.class);
      when(restartedProvider.isEnabled()).thenReturn(true);
      when(restartedProvider.requireAuthenticatedEvidence()).thenReturn(restartedEvidence);
      when(restartedEvidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
          .thenReturn(binding);
      WorldInstanceRepository restartedRepository = new WorldInstanceRepository(dsl);
      WorldLifecycleCommandServiceImpl restartedService =
          restartedLifecycleService(restartedRepository, restartedProvider);

      var replay = restartedService.activatePreparedWorldInstance(tenantId, gameInstanceId, 1L);
      assertThat(replay).isEqualTo(active);
      verify(restartedProvider).requireAuthenticatedEvidence();
      assertThat(
              repository
                  .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
                  .orElseThrow()
                  .getRowVersion())
          .isEqualTo(activeRowVersion);
      var prepareReplay = restartedService.prepareWorldInstance(request);
      assertThat(prepareReplay).isEqualTo(active);
      assertThat(
              repository
                  .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
                  .orElseThrow()
                  .getRowVersion())
          .isEqualTo(activeRowVersion);
      verify(restartedProvider, org.mockito.Mockito.times(2)).requireAuthenticatedEvidence();

      RunOwnedWorldLaunchEvidenceProvider changedProvider =
          mock(RunOwnedWorldLaunchEvidenceProvider.class);
      RunOwnedWorldLaunchEvidence changedEvidence = mock(RunOwnedWorldLaunchEvidence.class);
      when(changedProvider.isEnabled()).thenReturn(true);
      when(changedProvider.requireAuthenticatedEvidence()).thenReturn(changedEvidence);
      when(changedEvidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
          .thenReturn("b".repeat(64));
      assertThatThrownBy(
              () ->
                  restartedLifecycleService(new WorldInstanceRepository(dsl), changedProvider)
                      .activatePreparedWorldInstance(tenantId, gameInstanceId, 1L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("RUN_OWNED_WORLD_LAUNCH_EVIDENCE_MISMATCH:");
      assertThat(
              repository
                  .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
                  .orElseThrow()
                  .getRowVersion())
          .isEqualTo(activeRowVersion);

      PreparedWorldInstanceRequest duplicateOperation =
          request(tenantId, uniqueId(), request.controlPlaneRequestId());
      when(fixtureEvidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
          .thenReturn(binding);
      assertThatThrownBy(() -> lifecycleService.prepareWorldInstance(duplicateOperation))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("RUN_OWNED_WORLD_LAUNCH_IDENTITY_CONFLICT:");
      verify(gameDesignClient, never()).getPublishedReleaseBundle(anyLong(), anyLong());
      verify(gameDesignClient, never()).getVersionAssetArtifactState(anyLong(), anyLong());
      verify(gameDesignClient, never()).getVersionState(anyLong(), anyLong());
      verify(fixtureProvider, org.mockito.Mockito.atLeast(3)).requireAuthenticatedEvidence();

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

      WorldInstance duplicate = new WorldInstance();
      duplicate.setTenantId(tenantId);
      duplicate.setGameInstanceId(uniqueId());
      duplicate.setGameTemplateId(17L);
      duplicate.setControlPlaneRequestId(request.controlPlaneRequestId());
      duplicate.setLaunchDescriptorId("launch-descriptor-fixture-11");
      duplicate.setVersionId(11L);
      duplicate.setRuntimeFlagsJson("{}");
      duplicate.setGenerationConfigRevision("genrev-11");
      duplicate.setReleaseBundleId(77L);
      duplicate.setPublishedReleaseBundleRef(RELEASE_REF);
      duplicate.setVersionStateEpoch(77L);
      duplicate.setLifecycleEpoch(1L);
      duplicate.setStatus("PREPARING");
      duplicate.setCreatedAt(Instant.now());
      duplicate.setUpdatedAt(Instant.now());
      duplicate.setRowVersion(0L);
      duplicate.setRunOwnedFixtureBindingSha256("c".repeat(64));
      duplicate.setRunOwnedFixturePreparingLifecycleEpoch(1L);
      assertThatThrownBy(() -> repository.save(duplicate)).isInstanceOf(RuntimeException.class);
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
  void fixtureFailureRequiresExactOwnerAndPersistsTheLockedPredecessorTransition() {
    long tenantId = 8_810_004L;
    long gameInstanceId = uniqueId();
    PreparedWorldInstanceRequest request = request(tenantId, gameInstanceId, operationId());
    String binding = "c".repeat(64);
    configureEvidence(binding);
    try {
      var prepared = lifecycleService.prepareWorldInstance(request);
      assertThat(prepared.status()).isEqualTo("PREPARING");
      WorldInstance beforeFailure =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(beforeFailure.getLifecycleEpoch()).isEqualTo(1L);
      assertThat(beforeFailure.getRowVersion()).isZero();

      when(fixtureEvidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
          .thenReturn("d".repeat(64));
      assertThatThrownBy(
              () -> lifecycleService.failPreparedWorldInstance(tenantId, gameInstanceId, 1L, "failed"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("RUN_OWNED_WORLD_LAUNCH_EVIDENCE_MISMATCH:");
      WorldInstance unchanged =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(unchanged.getStatus()).isEqualTo("PREPARING");
      assertThat(unchanged.getLifecycleEpoch()).isEqualTo(1L);
      assertThat(unchanged.getRowVersion()).isZero();

      when(fixtureEvidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
          .thenReturn(binding);
      var failed =
          lifecycleService.failPreparedWorldInstance(tenantId, gameInstanceId, 1L, "failed");
      WorldInstance durable =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(failed.status()).isEqualTo("FAILED_PRE_ACTIVATION");
      assertThat(failed.lifecycleEpoch()).isEqualTo(2L);
      assertThat(durable.getStatus()).isEqualTo("FAILED_PRE_ACTIVATION");
      assertThat(durable.getLifecycleEpoch()).isEqualTo(2L);
      assertThat(durable.getRowVersion()).isEqualTo(1L);
      assertThat(durable.getRunOwnedFixtureBindingSha256()).isEqualTo(binding);
      assertThat(durable.getRunOwnedFixturePreparingLifecycleEpoch()).isEqualTo(1L);
    } finally {
      dsl.execute("DELETE FROM world_instance WHERE tenant_id = ?", tenantId);
    }
  }

  @Test
  void fixtureTerminationHonorsPhysicalHoldAndRetainsEntityCleanupFailureForExactReplay() {
    long tenantId = 8_810_005L;
    long gameInstanceId = uniqueId();
    PreparedWorldInstanceRequest request = request(tenantId, gameInstanceId, operationId());
    String binding = "e".repeat(64);
    UUID holdId = UUID.randomUUID();
    configureEvidence(binding);
    AtomicBoolean failEntityCleanup = new AtomicBoolean(true);
    when(entityManagementClient.cleanupRuntimeInstance(anyLong(), anyLong(), anyString()))
        .thenAnswer(
            invocation -> {
              if (failEntityCleanup.getAndSet(false)) {
                throw new IllegalStateException("Entity cleanup unavailable");
              }
              return CleanupRuntimeInstanceResponse.newBuilder().build();
            });
    try {
      lifecycleService.prepareWorldInstance(request);
      var active = lifecycleService.activatePreparedWorldInstance(tenantId, gameInstanceId, 1L);
      assertThat(active.status()).isEqualTo("ACTIVE");
      WorldInstance activeRow =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(activeRow.getLifecycleEpoch()).isEqualTo(2L);
      assertThat(activeRow.getRowVersion()).isEqualTo(1L);

      dsl.execute(
          "INSERT INTO initial_admission_bind_hold ("
              + "hold_id, hold_fence, tenant_id, realm_uuid, playable_state_namespace_uuid, "
              + "playable_state_scope, game_instance_id, version_id, active_lifecycle_epoch, "
              + "initial_admission_request_id, request_digest, expected_no_prior_pointer, "
              + "expected_catalog_revision, status, diagnostic_expires_at) "
              + "VALUES (?, ?, ?, ?, ?, 'ISOLATED', ?, 11, 2, ?, ?, TRUE, 1, 'PENDING', "
              + "CURRENT_TIMESTAMP + INTERVAL '1 hour')",
          holdId,
          UUID.randomUUID(),
          tenantId,
          UUID.randomUUID(),
          UUID.randomUUID(),
          gameInstanceId,
          operationId(),
          "a".repeat(64));

      assertThatThrownBy(
              () ->
                  lifecycleService.terminateWorldInstance(
                      tenantId, gameInstanceId, 2L, "term-fixture", "stop"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("INITIAL_ADMISSION_BIND_HOLD_ACTIVE:");
      WorldInstance held =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(held.getStatus()).isEqualTo("ACTIVE");
      assertThat(held.getLifecycleEpoch()).isEqualTo(2L);
      assertThat(held.getRowVersion()).isEqualTo(1L);
      verify(entityManagementClient, never())
          .cleanupRuntimeInstance(tenantId, gameInstanceId, "term-fixture");
      dsl.execute("DELETE FROM initial_admission_bind_hold WHERE hold_id = ?", holdId);

      assertThatThrownBy(
              () ->
                  lifecycleService.terminateWorldInstance(
                      tenantId, gameInstanceId, 2L, "term-fixture", "stop"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Entity cleanup unavailable");
      WorldInstance terminating =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(terminating.getStatus()).isEqualTo("TERMINATING");
      assertThat(terminating.getLifecycleEpoch()).isEqualTo(3L);
      assertThat(terminating.getTerminationRequestId()).isEqualTo("term-fixture");
      assertThat(terminating.getRowVersion()).isEqualTo(2L);
      assertThat(terminating.getRunOwnedFixtureBindingSha256()).isEqualTo(binding);

      var terminated =
          lifecycleService.terminateWorldInstance(
              tenantId, gameInstanceId, 3L, "term-fixture", "stop");
      assertThat(terminated.status()).isEqualTo("TERMINATED");
      assertThat(terminated.lifecycleEpoch()).isEqualTo(4L);
      WorldInstance terminalRow =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(terminalRow.getRowVersion()).isEqualTo(3L);
      assertThat(terminalRow.getTerminationRequestId()).isEqualTo("term-fixture");

      var replay =
          lifecycleService.terminateWorldInstance(
              tenantId, gameInstanceId, 2L, "term-fixture", "stop");
      assertThat(replay).isEqualTo(terminated);
      assertThat(
              repository
                  .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
                  .orElseThrow()
                  .getRowVersion())
          .isEqualTo(3L);
      assertThatThrownBy(
              () ->
                  lifecycleService.terminateWorldInstance(
                      tenantId, gameInstanceId, 2L, "term-other", "stop"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("terminated under a different request id");
      verify(entityManagementClient, org.mockito.Mockito.times(2))
          .cleanupRuntimeInstance(tenantId, gameInstanceId, "term-fixture");
    } finally {
      dsl.execute("DELETE FROM initial_admission_bind_hold WHERE hold_id = ?", holdId);
      dsl.execute("DELETE FROM world_instance WHERE tenant_id = ?", tenantId);
    }
  }

  @Test
  void ordinaryFailureJoinsAndRollsBackWithItsCallerTransaction() {
    long tenantId = 8_810_006L;
    long gameInstanceId = uniqueId();
    when(fixtureProvider.isEnabled()).thenReturn(false);
    configureOrdinaryLaunchInputs(tenantId);
    try {
      var prepared =
          lifecycleService.prepareWorldInstance(
              request(tenantId, gameInstanceId, operationId()));
      assertThat(prepared.status()).isEqualTo("PREPARING");

      TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);
      assertThatThrownBy(
              () ->
                  callerTransaction.executeWithoutResult(
                      status -> {
                        var failed =
                            lifecycleService.failPreparedWorldInstance(
                                tenantId, gameInstanceId, 1L, "caller will roll back");
                        assertThat(failed.status()).isEqualTo("FAILED_PRE_ACTIVATION");
                        assertThat(failed.lifecycleEpoch()).isEqualTo(2L);
                        throw new IllegalStateException("rollback ordinary caller");
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("rollback ordinary caller");

      WorldInstance afterRollback =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(afterRollback.getStatus()).isEqualTo("PREPARING");
      assertThat(afterRollback.getLifecycleEpoch()).isEqualTo(1L);
      assertThat(afterRollback.getRowVersion()).isZero();
    } finally {
      dsl.execute("DELETE FROM world_instance WHERE tenant_id = ?", tenantId);
    }
  }

  @Test
  void concurrentPrepareRetriesCreateOneFixtureBoundWorldInstance() throws Exception {
    long tenantId = 8_810_003L;
    long gameInstanceId = uniqueId();
    PreparedWorldInstanceRequest request = request(tenantId, gameInstanceId, operationId());
    configureEvidence("d".repeat(64));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<WorldInstanceLifecycleSnapshotDto> first =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return lifecycleService.prepareWorldInstance(request);
              });
      Future<WorldInstanceLifecycleSnapshotDto> second =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return lifecycleService.prepareWorldInstance(request);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      var firstResult = first.get(20, TimeUnit.SECONDS);
      var secondResult = second.get(20, TimeUnit.SECONDS);
      assertThat(firstResult).isEqualTo(secondResult);
      assertThat(firstResult.status()).isEqualTo("PREPARING");
      WorldInstance stored =
          repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).orElseThrow();
      assertThat(stored.getRunOwnedFixtureBindingSha256()).isEqualTo("d".repeat(64));
      assertThat(stored.getRunOwnedFixturePreparingLifecycleEpoch()).isEqualTo(1L);
      verify(fixtureProvider, org.mockito.Mockito.atLeast(2)).requireAuthenticatedEvidence();
    } finally {
      start.countDown();
      dsl.execute("DELETE FROM world_instance WHERE tenant_id = ?", tenantId);
    }
  }

  private void configureEvidence(String binding) {
    when(fixtureProvider.isEnabled()).thenReturn(true);
    when(fixtureProvider.requireAuthenticatedEvidence()).thenReturn(fixtureEvidence);
    when(fixtureEvidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn(binding);
  }

  private void configureOrdinaryLaunchInputs(long tenantId) {
    when(gameDesignClient.getPublishedReleaseBundle(tenantId, 11L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(77L)
                        .setVersionId(11L)
                        .setAttestationSchemaVersion("v1")
                        .setManifestHash("manifest-11")
                        .addRequiredManifestAssetKeys("manifest.json")
                        .setGenerationConfigRevision("genrev-11")
                        .setPublishedReleaseBundleRef(RELEASE_REF)
                        .build())
                .build());
    when(gameDesignClient.getVersionAssetArtifactState(tenantId, 11L))
        .thenReturn(
            GetVersionAssetArtifactStateResponse.newBuilder()
                .setArtifactState(
                    VersionAssetArtifactState.newBuilder()
                        .setTenantId(Long.toString(tenantId))
                        .setVersionId(11L)
                        .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
                        .setStateEpoch(3L)
                        .setManifestHash("manifest-11")
                        .addExportedManifestAssetKeys("manifest.json")
                        .build())
                .build());
    when(gameDesignClient.getVersionState(tenantId, 11L))
        .thenReturn(
            GetVersionStateResponse.newBuilder()
                .setVersionState(
                    VersionStateSnapshot.newBuilder()
                        .setTenantId(Long.toString(tenantId))
                        .setVersionId(11L)
                        .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setVersionStateEpoch(77L)
                        .build())
                .build());
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
