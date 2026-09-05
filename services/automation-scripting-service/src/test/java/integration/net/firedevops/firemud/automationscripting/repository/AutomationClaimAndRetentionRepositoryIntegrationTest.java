package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventAudit.SCRIPT_EVENT_AUDIT;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventIngressAudit.SCRIPT_EVENT_INGRESS_AUDIT;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptHandoffEvents.SCRIPT_HANDOFF_EVENTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptWorkItems.SCRIPT_WORK_ITEMS;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationClaimAndRetentionRepositoryIntegrationTest {
  private static final Instant OLD = Instant.parse("2020-01-01T00:00:00Z");
  private static final Instant STALE_BEFORE = Instant.parse("2026-08-01T00:00:30Z");
  private static final Instant RENEWED_AT = Instant.parse("2026-08-01T00:01:00Z");
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private ScriptEventIngressAuditRepository ingressRepository;
  private ScriptWorkItemRepository workItemRepository;
  private ScriptEventAuditRepository eventAuditRepository;
  private ScriptHandoffEventRepository handoffRepository;
  private ExecutorService executor;

  @BeforeAll
  void setUpRepositories() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();

    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    ingressRepository = new ScriptEventIngressAuditRepository(dsl);
    workItemRepository = new ScriptWorkItemRepository(dsl);
    eventAuditRepository = new ScriptEventAuditRepository(dsl);
    handoffRepository = new ScriptHandoffEventRepository(dsl);
  }

  @BeforeEach
  void cleanTables() {
    dsl.execute(
        "TRUNCATE TABLE script_event_audit, script_handoff_events, script_work_items,"
            + " script_event_ingress_audit RESTART IDENTITY CASCADE");
    executor = Executors.newFixedThreadPool(3);
  }

  @AfterEach
  void stopExecutor() {
    executor.shutdownNow();
  }

  @Test
  void concurrentPinnedIngressClaimsHaveOnePostgresWinnerAndOneLoser() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ScriptEventIngressAuditRepository.IdempotentInsertResult>> futures =
        new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return new ScriptEventIngressAuditRepository(dsl)
                    .insertIfAbsentByIdentity(pinnedIngressClaim());
              }));
    }

    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    var first = get(futures.get(0));
    var second = get(futures.get(1));

    assertThat(List.of(first.inserted(), second.inserted())).containsExactlyInAnyOrder(true, false);
    assertThat(first.audit().getId()).isEqualTo(second.audit().getId());
    assertThat(dsl.fetchCount(SCRIPT_EVENT_INGRESS_AUDIT)).isEqualTo(1);
  }

  @Test
  void concurrentPinnedWorkClaimsHaveOnePostgresWinnerAndOneLoser() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ScriptWorkItemRepository.IdempotentInsertResult>> futures = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return new ScriptWorkItemRepository(dsl)
                    .insertIfAbsentByTriggerIdentity(pinnedWorkItem());
              }));
    }

    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    var first = get(futures.get(0));
    var second = get(futures.get(1));

    assertThat(List.of(first.inserted(), second.inserted())).containsExactlyInAnyOrder(true, false);
    assertThat(first.workItem().getId()).isEqualTo(second.workItem().getId());
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isEqualTo(1);
  }

  @Test
  void renewalWinsAgainstStaleReclaimAndReclaimAdvancesTheFence() throws Exception {
    ScriptEventIngressAudit claim =
        ingressRepository.insertIfAbsentByIdentity(pinnedIngressClaim()).audit();
    markIngressInProgress(claim.getId(), OLD, 0);
    claim.setSourceState("IN_PROGRESS");
    claim.setClaimStartedAt(OLD);
    claim.setRowVersion(0);
    ScriptEventIngressAudit ownerClaim = claim;

    CountDownLatch ownerLockHeld = new CountDownLatch(1);
    CountDownLatch releaseOwner = new CountDownLatch(1);
    String ownerApplicationName = "automation-claim-owner-" + System.nanoTime();
    DSLContext ownerDsl = newDsl(ownerApplicationName);
    DSLContext reclaimDsl = newDsl("automation-claim-reclaim-" + System.nanoTime());
    Future<Boolean> renewal =
        executor.submit(
            () ->
                ownerDsl.transactionResult(
                    configuration -> {
                      boolean renewed =
                          new ScriptEventIngressAuditRepository(configuration.dsl())
                              .renewClaimIfCurrent(ownerClaim, RENEWED_AT);
                      ownerLockHeld.countDown();
                      await(releaseOwner);
                      return renewed;
                    }));
    assertThat(ownerLockHeld.await(5, TimeUnit.SECONDS)).isTrue();

    AtomicLong reclaimBackendPid = new AtomicLong();
    CountDownLatch reclaimStarted = new CountDownLatch(1);
    Future<Optional<ScriptEventIngressAudit>> reclaim =
        executor.submit(
            () ->
                reclaimDsl.transactionResult(
                    configuration -> {
                      DSLContext transactionDsl = configuration.dsl();
                      reclaimBackendPid.set(currentBackendPid(transactionDsl));
                      reclaimStarted.countDown();
                      return new ScriptEventIngressAuditRepository(transactionDsl)
                          .reclaimStaleInProgress(
                              ownerClaim, STALE_BEFORE, RENEWED_AT.plusSeconds(1));
                    }));
    assertThat(reclaimStarted.await(5, TimeUnit.SECONDS)).isTrue();
    awaitPostgresLockWait(reclaimBackendPid.get());
    releaseOwner.countDown();

    assertThat(get(renewal)).isTrue();
    assertThat(get(reclaim)).isEmpty();
    assertThat(
            dsl.fetchValue(
                SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION,
                SCRIPT_EVENT_INGRESS_AUDIT.ID.eq(claim.getId())))
        .isEqualTo(0);

    markIngressInProgress(claim.getId(), OLD, 0);
    ScriptEventIngressAudit staleClaim = ownerClaim;
    Optional<ScriptEventIngressAudit> reclaimed =
        ingressRepository.reclaimStaleInProgress(staleClaim, STALE_BEFORE, RENEWED_AT);

    assertThat(reclaimed).get().extracting(ScriptEventIngressAudit::getRowVersion).isEqualTo(1);
    assertThat(ingressRepository.renewClaimIfCurrent(staleClaim, RENEWED_AT.plusSeconds(1)))
        .isFalse();
  }

  @Test
  void retentionWaitsForParentLockAndDisposesFkChildrenBeforeParent() throws Exception {
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptEventAudit audit = eventAuditRepository.save(retainedEventAudit(parent.getId()));
    ScriptHandoffEvent handoff = handoffRepository.save(retainedHandoff(parent.getId()));
    assertThat(audit.getId()).isNotNull();
    assertThat(handoff.getId()).isNotNull();

    CountDownLatch parentLockHeld = new CountDownLatch(1);
    CountDownLatch releaseParent = new CountDownLatch(1);
    String lockApplicationName = "automation-retention-owner-" + System.nanoTime();
    DSLContext lockDsl = newDsl(lockApplicationName);
    DSLContext cleanupDsl = newDsl("automation-retention-cleanup-" + System.nanoTime());
    Future<Void> lock =
        executor.submit(
            () ->
                lockDsl.transactionResult(
                    configuration -> {
                      configuration
                          .dsl()
                          .selectFrom(SCRIPT_WORK_ITEMS)
                          .where(SCRIPT_WORK_ITEMS.ID.eq(parent.getId()))
                          .forUpdate()
                          .fetchOne();
                      parentLockHeld.countDown();
                      await(releaseParent);
                      return null;
                    }));
    assertThat(parentLockHeld.await(5, TimeUnit.SECONDS)).isTrue();

    AtomicLong cleanupBackendPid = new AtomicLong();
    CountDownLatch cleanupStarted = new CountDownLatch(1);
    Future<Long> cleanup =
        executor.submit(
            () ->
                cleanupDsl.transactionResult(
                    configuration -> {
                      DSLContext transactionDsl = configuration.dsl();
                      cleanupBackendPid.set(currentBackendPid(transactionDsl));
                      cleanupStarted.countDown();
                      return new ScriptWorkItemRepository(transactionDsl)
                          .deleteByStatusAndUpdatedAtBefore("HANDED_OFF", Instant.now());
                    }));
    assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
    awaitPostgresLockWait(cleanupBackendPid.get());
    releaseParent.countDown();

    assertThat(get(lock)).isNull();
    assertThat(get(cleanup)).isEqualTo(1L);
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_EVENT_AUDIT)).isEqualTo(1);
    assertThat(
            dsl.fetchValue(
                SCRIPT_EVENT_AUDIT.WORK_ITEM_ID, SCRIPT_EVENT_AUDIT.ID.eq(audit.getId())))
        .isNull();
  }

  private void markIngressInProgress(Long id, Instant claimStartedAt, int rowVersion) {
    dsl.update(SCRIPT_EVENT_INGRESS_AUDIT)
        .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_STATE, "IN_PROGRESS")
        .set(SCRIPT_EVENT_INGRESS_AUDIT.CLAIM_STARTED_AT, claimStartedAt.atOffset(ZoneOffset.UTC))
        .set(SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION, rowVersion)
        .where(SCRIPT_EVENT_INGRESS_AUDIT.ID.eq(id))
        .execute();
  }

  private ScriptEventIngressAudit pinnedIngressClaim() {
    ScriptEventIngressAudit claim = new ScriptEventIngressAudit();
    claim.setTenantId("tenant-1");
    claim.setGameInstanceId("instance-1");
    claim.setRegionId("region-1");
    claim.setRegionEpoch(1L);
    claim.setEntityId("entity-1");
    claim.setPlayableStateScope("INSTANCE");
    claim.setScriptId("script-1");
    claim.setEventType("onEnterRegion");
    claim.setEventSchemaVersion("v1");
    claim.setScriptPatchVersion("patch-1");
    claim.setScriptPinEpoch(2L);
    claim.setScriptPinControlPlaneRequestId("pin-request-1");
    claim.setScriptEventId("event-1");
    claim.setSourceService("game-session-service");
    claim.setTriggerMode("EVENT");
    claim.setSourceState("IN_PROGRESS");
    claim.setAdmitted(true);
    claim.setAdmissionOutcome("ADMITTED");
    claim.setAdmissionReason("accepted");
    return claim;
  }

  private ScriptWorkItem pinnedWorkItem() {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId("tenant-1");
    item.setGameInstanceId("instance-1");
    item.setRegionId("region-1");
    item.setRegionEpoch(1L);
    item.setEntityId("entity-1");
    item.setPlayableStateScope("INSTANCE");
    item.setScriptId("script-1");
    item.setEventType("onEnterRegion");
    item.setEventSchemaVersion("v1");
    item.setScriptPatchVersion("patch-1");
    item.setScriptPinEpoch(2L);
    item.setScriptPinControlPlaneRequestId("pin-request-1");
    item.setPluginId("");
    item.setPluginVersionId("");
    item.setBindingId("");
    item.setScriptEventId("event-1");
    item.setSourceService("game-session-service");
    item.setTriggerMode("EVENT");
    item.setStatus("PENDING_EVALUATION");
    return item;
  }

  private ScriptWorkItem retainedWorkItem() {
    ScriptWorkItem item = pinnedWorkItem();
    item.setScriptEventId("retained-event");
    item.setStatus("HANDED_OFF");
    item.setCreatedAt(OLD);
    item.setUpdatedAt(OLD);
    return item;
  }

  private ScriptEventAudit retainedEventAudit(Long workItemId) {
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId("tenant-1");
    audit.setGameInstanceId("instance-1");
    audit.setRegionId("region-1");
    audit.setRegionEpoch(1L);
    audit.setEntityId("entity-1");
    audit.setPlayableStateScope("INSTANCE");
    audit.setScriptId("script-1");
    audit.setEventType("onEnterRegion");
    audit.setEventSchemaVersion("v1");
    audit.setScriptPatchVersion("patch-1");
    audit.setScriptPinEpoch(2L);
    audit.setScriptPinControlPlaneRequestId("pin-request-1");
    audit.setScriptEventId("retained-event");
    audit.setSourceService("game-session-service");
    audit.setTriggerMode("EVENT");
    audit.setWorkItemId(workItemId);
    audit.setFinalStage("HANDOFF");
    audit.setFinalOutcome("HANDED_OFF");
    audit.setFinalReason("accepted");
    audit.setCreatedAt(OLD);
    audit.setUpdatedAt(OLD);
    return audit;
  }

  private ScriptHandoffEvent retainedHandoff(Long workItemId) {
    ScriptHandoffEvent handoff = new ScriptHandoffEvent();
    handoff.setEventId("handoff-event-1");
    handoff.setTenantId("tenant-1");
    handoff.setGameInstanceId("instance-1");
    handoff.setScriptPatchVersion("patch-1");
    handoff.setScriptPinEpoch(2L);
    handoff.setScriptPinControlPlaneRequestId("pin-request-1");
    handoff.setScriptId("script-1");
    handoff.setWorkItemId(workItemId);
    handoff.setCommandOrdinal(0);
    handoff.setAutomationDispatchId("dispatch-1");
    handoff.setTargetEntityId("entity-1");
    handoff.setEmittedCommandText("");
    handoff.setHandoffOutcome("HANDED_OFF");
    handoff.setHandoffReason("accepted");
    handoff.setObservedAt(OLD);
    return handoff;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test coordination latch");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for test coordination latch", e);
    }
  }

  private static <T> T get(Future<T> future)
      throws InterruptedException, ExecutionException, TimeoutException {
    return future.get(10, TimeUnit.SECONDS);
  }

  private DSLContext newDsl(String applicationName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl() + "?ApplicationName=" + applicationName);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return DSL.using(dataSource, SQLDialect.POSTGRES);
  }

  private static long currentBackendPid(DSLContext transactionDsl) {
    Object value = transactionDsl.fetchValue("SELECT pg_backend_pid()");
    if (!(value instanceof Number number)) {
      throw new IllegalStateException("PostgreSQL backend PID was not returned");
    }
    return number.longValue();
  }

  private void awaitPostgresLockWait(long backendPid) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline)) {
      Number waitingBackends =
          (Number)
              dsl.fetchValue(
                  "SELECT count(*) FROM pg_stat_activity "
                      + "WHERE pid = ? AND state = 'active' "
                      + "AND cardinality(pg_blocking_pids(pid)) > 0",
                  backendPid);
      if (waitingBackends != null && waitingBackends.longValue() > 0) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("PostgreSQL backend did not enter a lock wait: " + backendPid);
  }
}
