package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.entitymanagement.v1.CleanupRuntimeInstanceResponse;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldRequest;
import net.firedevops.firemud.worldmanagement.jooq.tables.WorldInstance;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.WorldInstanceRecord;
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.service.InitialAdmissionBindHoldService;
import net.firedevops.firemud.worldmanagement.service.WorldLifecycleCommandService;
import net.firedevops.firemud.worldmanagement.service.impl.InitialAdmissionBindHoldServiceImpl;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = {"spring.grpc.server.port=0"})
class InitialAdmissionBindHoldPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          PostgresBackedServiceTestSupport.postgresImage("postgres:16-alpine"));

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private WorldInstanceRepository worldInstanceRepository;
  @Autowired private InitialAdmissionBindHoldRepository holdRepository;
  @Autowired private InitialAdmissionBindHoldService holdService;
  @Autowired private WorldLifecycleCommandService lifecycleCommandService;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void holdAcquireWinsConcurrentTerminationAndExactRetrySurvivesServiceRecreation()
      throws Exception {
    long tenantId = 81001L;
    long gameInstanceId = uniqueGameInstanceId();
    insertActiveWorldInstance(tenantId, gameInstanceId);
    InitialAdmissionBindHoldRequest request = request(tenantId, gameInstanceId);
    CountDownLatch lifecycleLocked = new CountDownLatch(1);
    CountDownLatch allowAcquireCommit = new CountDownLatch(1);
    AtomicInteger acquisitionBackendPid = new AtomicInteger();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldDto> acquireFuture =
          executor.submit(
              () ->
                  transactionTemplate.execute(
                      status -> {
                        worldInstanceRepository
                            .findByTenantIdAndGameInstanceIdForUpdate(tenantId, gameInstanceId)
                            .orElseThrow();
                        Record backendIdentity =
                            Objects.requireNonNull(
                                dsl.fetchOne("SELECT pg_backend_pid()"),
                                "PostgreSQL backend PID query returned no row");
                        acquisitionBackendPid.set(
                            Objects.requireNonNull(
                                backendIdentity.get(0, Integer.class),
                                "PostgreSQL backend PID query returned a null PID"));
                        lifecycleLocked.countDown();
                        await(allowAcquireCommit);
                        return holdService.acquire(request);
                      }));
      assertThat(lifecycleLocked.await(5, TimeUnit.SECONDS)).isTrue();
      int exactAcquisitionBackendPid = acquisitionBackendPid.get();
      assertThat(exactAcquisitionBackendPid).isPositive();

      CountDownLatch terminationStarted = new CountDownLatch(1);
      Future<String> terminationFuture =
          executor.submit(
              () -> {
                terminationStarted.countDown();
                try {
                  lifecycleCommandService.terminateWorldInstance(
                      tenantId, gameInstanceId, 4L, "terminate-race-1", "race");
                  return "unexpected termination success";
                } catch (IllegalArgumentException exception) {
                  return exception.getMessage();
                }
              });
      assertThat(terminationStarted.await(5, TimeUnit.SECONDS)).isTrue();
      int terminationBackendPid = awaitBackendBlockedBy(exactAcquisitionBackendPid);
      assertThat(isBackendBlockedBy(terminationBackendPid, exactAcquisitionBackendPid)).isTrue();
      allowAcquireCommit.countDown();

      var acquired = acquireFuture.get(10, TimeUnit.SECONDS);
      String terminationResult = terminationFuture.get(10, TimeUnit.SECONDS);
      assertThat(acquired).isNotNull();
      assertThat(terminationResult).startsWith("INITIAL_ADMISSION_BIND_HOLD_ACTIVE:");
      assertThat(holdRepository.hasNonterminalForRealm(tenantId, request.realmUuid())).isTrue();
      assertThat(holdRepository.hasNonterminalForRealm(tenantId + 1L, request.realmUuid()))
          .isFalse();
      assertThat(
              holdRepository.hasNonterminalForRealm(
                  tenantId, "00000000-0000-0000-0000-000000000099"))
          .isFalse();
      InitialAdmissionBindHoldServiceImpl restartedService =
          new InitialAdmissionBindHoldServiceImpl(holdRepository, worldInstanceRepository);
      var retry = transactionTemplate.execute(status -> restartedService.acquire(request));
      assertThat(retry).isNotNull();
      assertThat(retry.holdId()).isEqualTo(acquired.holdId());
      assertThat(retry.holdFence()).isEqualTo(acquired.holdFence());
    } finally {
      allowAcquireCommit.countDown();
      deleteFixture(tenantId, gameInstanceId);
    }
  }

  @Test
  void terminationWinsRaceAndLaterAcquireCannotCreateAnInitialHold() throws Exception {
    long tenantId = 81002L;
    long gameInstanceId = uniqueGameInstanceId();
    insertActiveWorldInstance(tenantId, gameInstanceId);
    CountDownLatch ownerCleanupCalled = new CountDownLatch(1);
    CountDownLatch allowOwnerCleanup = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              ownerCleanupCalled.countDown();
              await(allowOwnerCleanup);
              return CleanupRuntimeInstanceResponse.newBuilder().build();
            })
        .when(entityManagementClient)
        .cleanupRuntimeInstance(anyLong(), anyLong(), anyString());

    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> termination =
          executor.submit(
              () ->
                  lifecycleCommandService.terminateWorldInstance(
                      tenantId, gameInstanceId, 4L, "terminate-race-2", "race"));
      assertThat(ownerCleanupCalled.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> holdService.acquire(request(tenantId, gameInstanceId)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("WORLD_LIFECYCLE_PROOF_MISMATCH:");
      allowOwnerCleanup.countDown();
      termination.get(10, TimeUnit.SECONDS);
    } finally {
      allowOwnerCleanup.countDown();
      deleteFixture(tenantId, gameInstanceId);
    }
  }

  @Test
  void reconciliationRequiredHoldContinuesToBlockTermination() {
    long tenantId = 81003L;
    long gameInstanceId = uniqueGameInstanceId();
    insertActiveWorldInstance(tenantId, gameInstanceId);
    try {
      var acquired = holdService.acquire(request(tenantId, gameInstanceId));
      holdService.requireReconciliation(acquired.holdId(), "GS_OWNER_READ_UNAVAILABLE");

      assertThatThrownBy(
              () ->
                  lifecycleCommandService.terminateWorldInstance(
                      tenantId, gameInstanceId, 4L, "terminate-held-1", "stop"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("INITIAL_ADMISSION_BIND_HOLD_ACTIVE:");
    } finally {
      deleteFixture(tenantId, gameInstanceId);
    }
  }

  private void insertActiveWorldInstance(long tenantId, long gameInstanceId) {
    var table = WorldInstance.WORLD_INSTANCE;
    WorldInstanceRecord record = dsl.newRecord(table);
    record.setTenantId(tenantId);
    record.setGameInstanceId(gameInstanceId);
    record.setGameTemplateId(81L);
    record.setControlPlaneRequestId("initial-bind-test-" + gameInstanceId);
    record.setLaunchDescriptorId("initial-bind-launch");
    record.setVersionId(71L);
    record.setGenerationConfigRevision("initial-bind-generation");
    record.setReleaseBundleId(91L);
    record.setPublishedReleaseBundleRef("initial-bind-release");
    record.setVersionStateEpoch(31L);
    record.setLifecycleEpoch(4L);
    record.setStatus("ACTIVE");
    record.setRowVersion(0L);
    record.store();
  }

  private InitialAdmissionBindHoldRequest request(long tenantId, long gameInstanceId) {
    return new InitialAdmissionBindHoldRequest(
        tenantId,
        gameInstanceId,
        71L,
        4L,
        "initial-bind-request-" + gameInstanceId,
        "f".repeat(64),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "SHARED",
        true,
        1L);
  }

  private long uniqueGameInstanceId() {
    return Math.abs(System.nanoTime()) + 10_000L;
  }

  private int awaitBackendBlockedBy(int blockerPid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      Record blockedBackend =
          dsl.fetchOne(
              "SELECT pid FROM pg_stat_activity WHERE datname = current_database() "
                  + "AND pid <> ? AND wait_event_type = 'Lock' "
                  + "AND query ILIKE '%world_instance%' "
                  + "AND ? = ANY(pg_blocking_pids(pid)) ORDER BY pid LIMIT 1",
              blockerPid, blockerPid);
      Integer blockedPid = blockedBackend == null ? null : blockedBackend.get(0, Integer.class);
      if (blockedPid != null && isBackendBlockedBy(blockedPid, blockerPid)) {
        return blockedPid;
      }
      Thread.sleep(100L);
    } while (System.nanoTime() < deadline);
    throw new AssertionError(
        "No PostgreSQL backend was observed waiting on acquisition backend " + blockerPid);
  }

  private boolean isBackendBlockedBy(int blockedPid, int blockerPid) {
    Record backendState =
        dsl.fetchOne(
            "SELECT wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)) "
                + "FROM pg_stat_activity WHERE pid = ?",
            blockerPid,
            blockedPid);
    return backendState != null && Boolean.TRUE.equals(backendState.get(0, Boolean.class));
  }

  private void deleteFixture(long tenantId, long gameInstanceId) {
    dsl.execute(
        "DELETE FROM initial_admission_bind_hold WHERE tenant_id = ? AND game_instance_id = ?",
        tenantId,
        gameInstanceId);
    dsl.deleteFrom(WorldInstance.WORLD_INSTANCE)
        .where(
            WorldInstance.WORLD_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(WorldInstance.WORLD_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while coordinating PostgreSQL race", exception);
    }
  }
}
