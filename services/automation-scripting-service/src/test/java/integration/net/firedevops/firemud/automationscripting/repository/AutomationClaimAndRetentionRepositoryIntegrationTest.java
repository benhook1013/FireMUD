package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.AutomationAdmissionRequestHistory.AUTOMATION_ADMISSION_REQUEST_HISTORY;
import static net.firedevops.firemud.automationscripting.jooq.tables.AutomationAdmissionStates.AUTOMATION_ADMISSION_STATES;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptDeadLetterReplayRequests.SCRIPT_DEAD_LETTER_REPLAY_REQUESTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptDeadLetterReplayResults.SCRIPT_DEAD_LETTER_REPLAY_RESULTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventAudit.SCRIPT_EVENT_AUDIT;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventIngressAudit.SCRIPT_EVENT_INGRESS_AUDIT;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptHandoffEvents.SCRIPT_HANDOFF_EVENTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptScheduleInstances.SCRIPT_SCHEDULE_INSTANCES;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptWorkItems.SCRIPT_WORK_ITEMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService.AdmissionStateSummary;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService.SetAdmissionModeCommand;
import net.firedevops.firemud.automationscripting.service.impl.AutomationAdmissionStateServiceImpl;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private ScriptEventIngressAuditRepository ingressRepository;
  private ScriptDeadLetterReplayRepository replayRepository;
  private ScriptWorkItemRepository workItemRepository;
  private ScriptEventAuditRepository eventAuditRepository;
  private ScriptHandoffEventRepository handoffRepository;
  private ScriptPatchPinProjectionRepository pinProjectionRepository;
  private ScriptScheduleInstanceRepository scheduleInstanceRepository;
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
    replayRepository = new ScriptDeadLetterReplayRepository(dsl);
    workItemRepository = new ScriptWorkItemRepository(dsl);
    eventAuditRepository = new ScriptEventAuditRepository(dsl);
    handoffRepository = new ScriptHandoffEventRepository(dsl);
    pinProjectionRepository = new ScriptPatchPinProjectionRepository(dsl);
    scheduleInstanceRepository = new ScriptScheduleInstanceRepository(dsl);
  }

  @BeforeEach
  void cleanTables() {
    dsl.execute(
        "TRUNCATE TABLE script_dead_letter_replay_results, script_dead_letter_replay_requests,"
            + " automation_admission_request_history, automation_admission_states,"
            + " script_patch_pin_projections, script_schedule_instances, script_event_audit, script_handoff_events,"
            + " script_work_items,"
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
  void distinctPinnedOwnerRequestsConflictWithoutCreatingSecondPreAdmissionClaim() {
    ScriptEventIngressAudit first = pinnedIngressClaim();
    ScriptEventIngressAudit second = pinnedIngressClaim();
    second.setScriptPinControlPlaneRequestId("pin-request-2");

    var firstResult = ingressRepository.insertIfAbsentByIdentity(first);

    assertThat(firstResult.inserted()).isTrue();
    assertThatThrownBy(() -> ingressRepository.insertIfAbsentByIdentity(second))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script_pin_control_plane_request_id conflicts with existing identity");
    assertThat(dsl.fetchCount(SCRIPT_EVENT_INGRESS_AUDIT)).isEqualTo(1);
    assertThat(
            dsl.fetchValue(
                SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID,
                SCRIPT_EVENT_INGRESS_AUDIT.ID.eq(firstResult.audit().getId())))
        .isEqualTo("pin-request-1");
  }

  @Test
  void concurrentPreInstanceOnLoadClaimsWithNullableScopeHaveOnePostgresWinnerAndOneLoser()
      throws Exception {
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
                    .insertIfAbsentByIdentity(preInstanceIngressClaim());
              }));
    }

    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    var first = get(futures.get(0));
    var second = get(futures.get(1));

    assertThat(List.of(first.inserted(), second.inserted())).containsExactlyInAnyOrder(true, false);
    assertThat(first.audit().getId()).isEqualTo(second.audit().getId());
    assertThat(first.audit().getGameInstanceId()).isNull();
    assertThat(first.audit().getScriptPinEpoch()).isNull();
    assertThat(first.audit().getScriptId()).isEqualTo("script-on-load");
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
  void concurrentUnpinnedWorkClaimsHaveOnePostgresWinnerAndOneLoser() throws Exception {
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
                    .insertIfAbsentByTriggerIdentity(unpinnedWorkItem());
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
  void handlerAuditInsertDiscriminatorPreservesTenantIdentityOnDuplicate() {
    ScriptEventAudit first = handlerAudit("tenant-handler-audit");

    ScriptEventAuditRepository.IdempotentInsertResult inserted =
        eventAuditRepository.insertIfAbsentByHandlerIdentity(first);

    assertThat(inserted.inserted()).isTrue();
    assertThat(inserted.audit().getId()).isNotNull();
    assertThat(dsl.fetchCount(SCRIPT_EVENT_AUDIT)).isEqualTo(1);

    ScriptEventAudit duplicate = handlerAudit("tenant-handler-audit");
    duplicate.setFinalOutcome("DUPLICATE_ATTEMPT");
    duplicate.setFinalReason("duplicate-attempt");

    ScriptEventAuditRepository.IdempotentInsertResult existing =
        eventAuditRepository.insertIfAbsentByHandlerIdentity(duplicate);

    assertThat(existing.inserted()).isFalse();
    assertThat(existing.audit().getId()).isEqualTo(inserted.audit().getId());
    assertThat(existing.audit().getTenantId()).isEqualTo("tenant-handler-audit");
    assertThat(existing.audit().getScriptEventId()).isEqualTo("handler-event-1");
    assertThat(existing.audit().getSourceService()).isEqualTo("automation-scripting-service");
    assertThat(existing.audit().getFinalOutcome()).isEqualTo("HANDLER_ACCEPTED");
    assertThat(dsl.fetchCount(SCRIPT_EVENT_AUDIT)).isEqualTo(1);
    assertThat(
            dsl.fetchCount(
                SCRIPT_EVENT_AUDIT, SCRIPT_EVENT_AUDIT.TENANT_ID.eq("tenant-handler-audit")))
        .isEqualTo(1);

    ScriptEventAudit conflictingOwnerEvidence = handlerAudit("tenant-handler-audit");
    conflictingOwnerEvidence.setScriptPinControlPlaneRequestId("pin-request-2");

    assertThatThrownBy(
            () -> eventAuditRepository.insertIfAbsentByHandlerIdentity(conflictingOwnerEvidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script_pin_control_plane_request_id conflicts with existing identity");
    assertThat(
            dsl.fetchValue(
                SCRIPT_EVENT_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID,
                SCRIPT_EVENT_AUDIT.ID.eq(inserted.audit().getId())))
        .isEqualTo("pin-request-1");
    assertThat(dsl.fetchCount(SCRIPT_EVENT_AUDIT)).isEqualTo(1);
  }

  @Test
  void scriptOnlyPluginIdentityDefaultsToEmptyAndLookupFindsTheCanonicalRow() {
    dsl.insertInto(SCRIPT_WORK_ITEMS)
        .set(SCRIPT_WORK_ITEMS.TENANT_ID, "tenant-script-only")
        .set(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID, "instance-script-only")
        .set(SCRIPT_WORK_ITEMS.REGION_ID, "region-script-only")
        .set(SCRIPT_WORK_ITEMS.REGION_EPOCH, 1L)
        .set(SCRIPT_WORK_ITEMS.ENTITY_ID, "entity-script-only")
        .set(SCRIPT_WORK_ITEMS.SCRIPT_ID, "script-only")
        .set(SCRIPT_WORK_ITEMS.EVENT_TYPE, "onEnterRegion")
        .set(SCRIPT_WORK_ITEMS.EVENT_SCHEMA_VERSION, "v1")
        .set(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION, "patch-script-only")
        .set(SCRIPT_WORK_ITEMS.SCRIPT_EVENT_ID, "event-script-only")
        .set(SCRIPT_WORK_ITEMS.SOURCE_SERVICE, "automation-scripting-service")
        .set(SCRIPT_WORK_ITEMS.TRIGGER_MODE, "EVENT")
        .execute();

    assertThat(
            dsl.fetchValue(
                SCRIPT_WORK_ITEMS.PLUGIN_ID, SCRIPT_WORK_ITEMS.TENANT_ID.eq("tenant-script-only")))
        .isEmpty();
    assertThat(
            dsl.fetchValue(
                SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID,
                SCRIPT_WORK_ITEMS.TENANT_ID.eq("tenant-script-only")))
        .isEmpty();

    assertThat(
            workItemRepository
                .findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInOrderByCreatedAtAscIdAsc(
                    "tenant-script-only", null, null, List.of("PENDING_EVALUATION")))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getPluginId()).isEmpty();
              assertThat(item.getPluginVersionId()).isEmpty();
            });
  }

  @Test
  void replayRequestsRoundTripPerTenantAndResultsHydrateRetainedFailureEvidence() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptDeadLetterReplayRepository.ReplayRequest tenantA =
        replayRepository.insertOrGet(
            "tenant-replay-a",
            "replay-request-shared",
            REQUEST_DIGEST,
            "operator-a",
            "retry-evaluation",
            now);
    ScriptDeadLetterReplayRepository.ReplayRequest tenantB =
        replayRepository.insertOrGet(
            "tenant-replay-b",
            "replay-request-shared",
            "b".repeat(64),
            "operator-b",
            "retry-evaluation",
            now);

    assertThat(tenantA.id()).isNotEqualTo(tenantB.id());
    assertThat(replayRepository.findRequest("tenant-replay-a", "replay-request-shared"))
        .contains(tenantA);
    assertThat(replayRepository.findRequest("tenant-replay-b", "replay-request-shared"))
        .contains(tenantB);
    assertThat(replayRepository.findRequest("tenant-replay-other", "replay-request-shared"))
        .isEmpty();

    replayRepository.saveResult(
        "tenant-replay-a",
        tenantA.id(),
        9001L,
        null,
        "rejected",
        "not_found_or_not_owned",
        "",
        2L,
        4L,
        5L,
        3L,
        "DSL_EVAL",
        "sandbox_error",
        now);

    assertThat(replayRepository.findResults("tenant-replay-a", tenantA.id()))
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.requestedWorkItemId()).isEqualTo(9001L);
              assertThat(result.workItemId()).isNull();
              assertThat(result.outcome()).isEqualTo("rejected");
              assertThat(result.rejectionReason()).isEqualTo("not_found_or_not_owned");
              assertThat(result.failureReason()).isEmpty();
              assertThat(result.scriptPinEpoch()).isEqualTo(2L);
              assertThat(result.pluginActivationEpoch()).isEqualTo(4L);
              assertThat(result.lifecycleRevision()).isEqualTo(5L);
              assertThat(result.failureGeneration()).isEqualTo(3L);
              assertThat(result.originalFailureStage()).isEqualTo("DSL_EVAL");
              assertThat(result.originalFailureReason()).isEqualTo("sandbox_error");
            });
    assertThat(replayRepository.findResults("tenant-replay-b", tenantB.id())).isEmpty();
    assertThat(
            dsl.fetchValue(
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.TENANT_ID,
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REPLAY_REQUEST_ID.eq(tenantA.id())))
        .isEqualTo("tenant-replay-a");
  }

  @Test
  void replayRequestAndResultRetentionHoldsRequireTheOwningTenant() {
    Instant holdUntil = Instant.parse("2026-09-01T00:00:00Z");
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptDeadLetterReplayRepository.ReplayRequest tenantA =
        replayRepository.insertOrGet(
            "tenant-hold-a",
            "replay-request-shared",
            REQUEST_DIGEST,
            "operator-a",
            "retention-review",
            now);
    ScriptDeadLetterReplayRepository.ReplayRequest tenantB =
        replayRepository.insertOrGet(
            "tenant-hold-b",
            "replay-request-shared",
            "b".repeat(64),
            "operator-b",
            "retention-review",
            now);

    replayRepository.saveResult(
        "tenant-hold-a",
        tenantA.id(),
        9101L,
        null,
        "rejected",
        "not_found_or_not_owned",
        "",
        0L,
        0L,
        0L,
        1L,
        now);
    replayRepository.saveResult(
        "tenant-hold-b",
        tenantB.id(),
        9102L,
        null,
        "rejected",
        "not_found_or_not_owned",
        "",
        0L,
        0L,
        0L,
        1L,
        now);
    long resultAId =
        dsl.fetchValue(
            SCRIPT_DEAD_LETTER_REPLAY_RESULTS.ID,
            SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REPLAY_REQUEST_ID.eq(tenantA.id()));
    long resultBId =
        dsl.fetchValue(
            SCRIPT_DEAD_LETTER_REPLAY_RESULTS.ID,
            SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REPLAY_REQUEST_ID.eq(tenantB.id()));

    assertThat(replayRepository.setRequestRetentionHold("tenant-hold-a", tenantA.id(), holdUntil))
        .isTrue();
    assertThat(replayRepository.setRequestRetentionHold("tenant-hold-b", tenantA.id(), holdUntil))
        .isFalse();
    assertThat(
            dsl.fetchValue(
                SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.RETENTION_HOLD_UNTIL,
                SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.ID.eq(tenantA.id())))
        .isEqualTo(holdUntil.atOffset(ZoneOffset.UTC));
    assertThat(
            dsl.fetchValue(
                SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.RETENTION_HOLD_UNTIL,
                SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.ID.eq(tenantB.id())))
        .isNull();
    assertThat(replayRepository.setRequestRetentionHold("tenant-hold-a", tenantA.id(), null))
        .isTrue();

    assertThat(replayRepository.setResultRetentionHold("tenant-hold-a", resultAId, holdUntil))
        .isTrue();
    assertThat(replayRepository.setResultRetentionHold("tenant-hold-b", resultAId, holdUntil))
        .isFalse();
    assertThat(
            dsl.fetchValue(
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.RETENTION_HOLD_UNTIL,
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.ID.eq(resultAId)))
        .isEqualTo(holdUntil.atOffset(ZoneOffset.UTC));
    assertThat(
            dsl.fetchValue(
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.RETENTION_HOLD_UNTIL,
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.ID.eq(resultBId)))
        .isNull();
    assertThat(replayRepository.setResultRetentionHold("tenant-hold-a", resultAId, null)).isTrue();
  }

  @Test
  void scriptPatchPinProjectionRoundTripsGeneratedPinEpoch() {
    ScriptPatchPinProjection projection = new ScriptPatchPinProjection();
    projection.setTenantId("tenant-1");
    projection.setGameInstanceId("instance-1");
    projection.setObservedPinnedScriptPatchVersion("patch-1");
    projection.setScriptPinEpoch(2L);
    projection.setLastObservedControlPlaneRequestId("pin-request-1");

    ScriptPatchPinProjection saved = pinProjectionRepository.save(projection);

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getScriptPinEpoch()).isEqualTo(2L);
    assertThat(saved.getLastObservedControlPlaneRequestId()).isEqualTo("pin-request-1");
    assertThat(pinProjectionRepository.findByTenantIdAndGameInstanceId("tenant-1", "instance-1"))
        .get()
        .satisfies(
            found -> {
              assertThat(found.getScriptPinEpoch()).isEqualTo(2L);
              assertThat(found.getObservedPinnedScriptPatchVersion()).isEqualTo("patch-1");
            });
  }

  @Test
  void scheduleBindingIdNormalizesBlankValuesAndPreservesNonBlankValues() {
    ScriptScheduleInstance schedule = scheduleInstance();
    schedule.setBindingId(null);

    ScriptScheduleInstance saved = scheduleInstanceRepository.save(schedule);

    assertThat(saved.getBindingId()).isEmpty();
    assertThat(
            dsl.fetchValue(
                SCRIPT_SCHEDULE_INSTANCES.BINDING_ID,
                SCRIPT_SCHEDULE_INSTANCES.ID.eq(saved.getId())))
        .isEmpty();

    saved.setBindingId("   ");
    saved = scheduleInstanceRepository.save(saved);
    assertThat(saved.getBindingId()).isEmpty();

    saved.setBindingId("binding-1");
    saved = scheduleInstanceRepository.save(saved);
    assertThat(saved.getBindingId()).isEqualTo("binding-1");
    assertThat(
            dsl.fetchValue(
                SCRIPT_SCHEDULE_INSTANCES.BINDING_ID,
                SCRIPT_SCHEDULE_INSTANCES.ID.eq(saved.getId())))
        .isEqualTo("binding-1");
  }

  @Test
  void concurrentAdmissionRetriesAdvanceOnePauseEpochAndReturnOneDurableResult() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    SetAdmissionModeCommand command =
        admissionCommand("PAUSED_FOR_ROLLBACK", "workflow-admission-retry", "actor-1", "rollback");
    List<Future<AdmissionStateSummary>> futures = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      futures.add(
          executor.submit(
              () -> {
                DSLContext transactionRoot =
                    newDsl("automation-admission-retry-" + System.nanoTime());
                ready.countDown();
                await(start);
                return transactionRoot.transactionResult(
                    configuration -> admissionService(configuration.dsl()).setMode(command));
              }));
    }

    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    AdmissionStateSummary first = get(futures.get(0));
    AdmissionStateSummary second = get(futures.get(1));

    assertThat(second).isEqualTo(first);
    assertThat(first.outcome()).isEqualTo(AutomationAdmissionStateService.OUTCOME_APPLIED);
    assertThat(first.targetMode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(first.admissionEpoch()).isEqualTo(2L);
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_STATES)).isEqualTo(1);
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_REQUEST_HISTORY)).isEqualTo(1);
    assertThat(
            dsl.fetchValue(
                AUTOMATION_ADMISSION_STATES.ADMISSION_EPOCH,
                AUTOMATION_ADMISSION_STATES.TENANT_ID.eq("tenant-admission")))
        .isEqualTo(2L);
    assertThat(
            dsl.fetchValue(
                AUTOMATION_ADMISSION_REQUEST_HISTORY.OUTCOME,
                AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID.eq(
                    "workflow-admission-retry")))
        .isEqualTo(AutomationAdmissionStateService.OUTCOME_APPLIED);
  }

  @Test
  void concurrentAdmissionFingerprintConflictKeepsOneWinnerAndOneHistoryRow() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<SetAdmissionModeCommand> commands =
        List.of(
            admissionCommand(
                "PAUSED_FOR_ROLLBACK", "workflow-admission-conflict", "actor-1", "rollback-1"),
            admissionCommand(
                "PAUSED_FOR_ROLLBACK", "workflow-admission-conflict", "actor-2", "rollback-2"));
    List<Future<MutationAttempt>> futures = new ArrayList<>();
    for (SetAdmissionModeCommand command : commands) {
      futures.add(
          executor.submit(
              () -> {
                DSLContext transactionRoot =
                    newDsl("automation-admission-conflict-" + System.nanoTime());
                ready.countDown();
                await(start);
                try {
                  return MutationAttempt.success(
                      transactionRoot.transactionResult(
                          configuration -> admissionService(configuration.dsl()).setMode(command)));
                } catch (RuntimeException exception) {
                  return MutationAttempt.failure(exception);
                }
              }));
    }

    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    List<MutationAttempt> attempts = List.of(get(futures.get(0)), get(futures.get(1)));

    assertThat(attempts.stream().filter(attempt -> attempt.summary() != null).count()).isEqualTo(1);
    assertThat(attempts.stream().filter(attempt -> attempt.failure() != null).count()).isEqualTo(1);
    MutationAttempt conflict =
        attempts.stream().filter(attempt -> attempt.failure() != null).findFirst().orElseThrow();
    assertThat(conflict.failure())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different admission-mode request");
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_STATES)).isEqualTo(1);
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_REQUEST_HISTORY)).isEqualTo(1);
    assertThat(
            dsl.fetchValue(
                AUTOMATION_ADMISSION_STATES.ADMISSION_EPOCH,
                AUTOMATION_ADMISSION_STATES.TENANT_ID.eq("tenant-admission")))
        .isEqualTo(2L);
  }

  @Test
  void concurrentDistinctAdmissionRequestsShareOnePauseEpochAndPersistBothResults()
      throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<SetAdmissionModeCommand> commands =
        List.of(
            admissionCommand(
                "PAUSED_FOR_ROLLBACK", "workflow-admission-distinct-1", "actor-1", "rollback"),
            admissionCommand(
                "PAUSED_FOR_ROLLBACK", "workflow-admission-distinct-2", "actor-1", "rollback"));
    List<Future<AdmissionStateSummary>> futures = new ArrayList<>();
    for (SetAdmissionModeCommand command : commands) {
      futures.add(
          executor.submit(
              () -> {
                DSLContext transactionRoot =
                    newDsl("automation-admission-distinct-" + System.nanoTime());
                ready.countDown();
                await(start);
                return transactionRoot.transactionResult(
                    configuration -> admissionService(configuration.dsl()).setMode(command));
              }));
    }

    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    List<AdmissionStateSummary> summaries = List.of(get(futures.get(0)), get(futures.get(1)));

    assertThat(
            summaries.stream()
                .filter(s -> AutomationAdmissionStateService.OUTCOME_APPLIED.equals(s.outcome()))
                .count())
        .isEqualTo(1);
    assertThat(
            summaries.stream()
                .filter(
                    s ->
                        AutomationAdmissionStateService.OUTCOME_ALREADY_APPLIED.equals(s.outcome()))
                .count())
        .isEqualTo(1);
    assertThat(summaries)
        .allSatisfy(
            summary -> {
              assertThat(summary.targetMode()).isEqualTo("PAUSED_FOR_ROLLBACK");
              assertThat(summary.admissionEpoch()).isEqualTo(2L);
              assertThat(summary.requestFingerprint()).matches("[0-9a-f]{64}");
            });

    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_STATES)).isEqualTo(1);
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_REQUEST_HISTORY)).isEqualTo(2);
    var historyRows =
        dsl.selectFrom(AUTOMATION_ADMISSION_REQUEST_HISTORY)
            .where(
                AUTOMATION_ADMISSION_REQUEST_HISTORY
                    .TENANT_ID
                    .eq("tenant-admission")
                    .and(
                        AUTOMATION_ADMISSION_REQUEST_HISTORY.GAME_INSTANCE_ID.eq(
                            "instance-admission"))
                    .and(AUTOMATION_ADMISSION_REQUEST_HISTORY.REGION_ID.eq("region-admission")))
            .fetch();
    assertThat(historyRows)
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row.getMode()).isEqualTo("PAUSED_FOR_ROLLBACK");
              assertThat(row.getAdmissionEpoch()).isEqualTo(2L);
              assertThat(row.getRequestFingerprint()).matches("[0-9a-f]{64}");
              assertThat(row.getOutcome())
                  .isIn(
                      AutomationAdmissionStateService.OUTCOME_APPLIED,
                      AutomationAdmissionStateService.OUTCOME_ALREADY_APPLIED);
            });
    assertThat(historyRows)
        .extracting(row -> row.getControlPlaneRequestId())
        .containsExactlyInAnyOrder(
            "workflow-admission-distinct-1", "workflow-admission-distinct-2");
    assertThat(
            dsl.fetchValue(
                AUTOMATION_ADMISSION_STATES.ADMISSION_EPOCH,
                AUTOMATION_ADMISSION_STATES.TENANT_ID.eq("tenant-admission")))
        .isEqualTo(2L);
  }

  @Test
  void workflowRequestIdCanSpanModeKeysAndReadbackReturnsTheCurrentResult() {
    AdmissionStateSummary pause =
        dsl.transactionResult(
            configuration ->
                admissionService(configuration.dsl())
                    .setMode(
                        admissionCommand(
                            "PAUSED_FOR_ROLLBACK",
                            "workflow-admission-modes",
                            "actor-1",
                            "pause")));
    AdmissionStateSummary normal =
        dsl.transactionResult(
            configuration ->
                admissionService(configuration.dsl())
                    .setMode(
                        admissionCommand(
                            "NORMAL", "workflow-admission-modes", "actor-1", "resume")));

    assertThat(pause.outcome()).isEqualTo(AutomationAdmissionStateService.OUTCOME_APPLIED);
    assertThat(pause.admissionEpoch()).isEqualTo(2L);
    assertThat(normal.outcome()).isEqualTo(AutomationAdmissionStateService.OUTCOME_APPLIED);
    assertThat(normal.targetMode()).isEqualTo("NORMAL");
    assertThat(normal.admissionEpoch()).isEqualTo(2L);
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_STATES)).isEqualTo(1);
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_REQUEST_HISTORY)).isEqualTo(2);
    assertThat(
            dsl.select(AUTOMATION_ADMISSION_REQUEST_HISTORY.MODE)
                .from(AUTOMATION_ADMISSION_REQUEST_HISTORY)
                .where(
                    AUTOMATION_ADMISSION_REQUEST_HISTORY
                        .TENANT_ID
                        .eq("tenant-admission")
                        .and(
                            AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID.eq(
                                "workflow-admission-modes")))
                .fetch(AUTOMATION_ADMISSION_REQUEST_HISTORY.MODE))
        .containsExactlyInAnyOrder("NORMAL", "PAUSED_FOR_ROLLBACK");

    Optional<AdmissionStateSummary> readback =
        dsl.transactionResult(
            configuration ->
                admissionService(configuration.dsl())
                    .findState("tenant-admission", "instance-admission", "region-admission"));
    assertThat(readback).isPresent();
    assertThat(readback.orElseThrow()).isEqualTo(normal);
  }

  @Test
  void newRequestForCurrentModeStoresAlreadyAppliedAndExactRetryReturnsSameDurableResult() {
    SetAdmissionModeCommand command =
        admissionCommand(
            "NORMAL", "workflow-admission-already-applied", "actor-1", "confirm-normal");

    AdmissionStateSummary first =
        dsl.transactionResult(
            configuration -> admissionService(configuration.dsl()).setMode(command));
    var stateAfterFirst =
        dsl.selectFrom(AUTOMATION_ADMISSION_STATES)
            .where(
                AUTOMATION_ADMISSION_STATES
                    .TENANT_ID
                    .eq("tenant-admission")
                    .and(AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID.eq("instance-admission"))
                    .and(AUTOMATION_ADMISSION_STATES.REGION_ID.eq("region-admission")))
            .fetchOne();
    var historyAfterFirst =
        dsl.selectFrom(AUTOMATION_ADMISSION_REQUEST_HISTORY)
            .where(
                AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID.eq(
                    "workflow-admission-already-applied"))
            .fetchOne();

    AdmissionStateSummary retry =
        dsl.transactionResult(
            configuration -> admissionService(configuration.dsl()).setMode(command));
    var stateAfterRetry =
        dsl.selectFrom(AUTOMATION_ADMISSION_STATES)
            .where(
                AUTOMATION_ADMISSION_STATES
                    .TENANT_ID
                    .eq("tenant-admission")
                    .and(AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID.eq("instance-admission"))
                    .and(AUTOMATION_ADMISSION_STATES.REGION_ID.eq("region-admission")))
            .fetchOne();
    var historyAfterRetry =
        dsl.selectFrom(AUTOMATION_ADMISSION_REQUEST_HISTORY)
            .where(
                AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID.eq(
                    "workflow-admission-already-applied"))
            .fetchOne();

    assertThat(first.outcome()).isEqualTo(AutomationAdmissionStateService.OUTCOME_ALREADY_APPLIED);
    assertThat(first.targetMode()).isEqualTo("NORMAL");
    assertThat(retry).isEqualTo(first);
    assertThat(stateAfterRetry).isEqualTo(stateAfterFirst);
    assertThat(historyAfterRetry).isEqualTo(historyAfterFirst);
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_STATES)).isEqualTo(1);
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_REQUEST_HISTORY)).isEqualTo(1);
  }

  @Test
  void missingAdmissionReadOnlyLookupDoesNotCreateStateOrHistory() {
    Optional<AdmissionStateSummary> missing =
        dsl.transactionResult(
            configuration ->
                admissionService(configuration.dsl())
                    .findState("tenant-admission", "instance-admission", "region-admission"));

    assertThat(missing).isEmpty();
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_STATES)).isZero();
    assertThat(dsl.fetchCount(AUTOMATION_ADMISSION_REQUEST_HISTORY)).isZero();
  }

  @Test
  void databaseEnforcesAdmissionRequestIdentityPair() {
    assertThatThrownBy(
            () ->
                dsl.insertInto(AUTOMATION_ADMISSION_STATES)
                    .set(AUTOMATION_ADMISSION_STATES.TENANT_ID, "tenant-invalid-fingerprint")
                    .set(
                        AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID,
                        "instance-invalid-fingerprint")
                    .set(AUTOMATION_ADMISSION_STATES.REGION_ID, "region-invalid-fingerprint")
                    .set(
                        AUTOMATION_ADMISSION_STATES.CONTROL_PLANE_REQUEST_FINGERPRINT,
                        REQUEST_DIGEST)
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_automation_admission_state_request_identity");

    assertThatThrownBy(
            () ->
                dsl.insertInto(AUTOMATION_ADMISSION_STATES)
                    .set(AUTOMATION_ADMISSION_STATES.TENANT_ID, "tenant-invalid-request")
                    .set(AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID, "instance-invalid-request")
                    .set(AUTOMATION_ADMISSION_STATES.REGION_ID, "region-invalid-request")
                    .set(
                        AUTOMATION_ADMISSION_STATES.CONTROL_PLANE_REQUEST_ID,
                        "request-without-fingerprint")
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_automation_admission_state_request_identity");

    assertThatThrownBy(
            () ->
                dsl.insertInto(AUTOMATION_ADMISSION_STATES)
                    .set(AUTOMATION_ADMISSION_STATES.TENANT_ID, "tenant-invalid-blank-request")
                    .set(
                        AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID,
                        "instance-invalid-blank-request")
                    .set(AUTOMATION_ADMISSION_STATES.REGION_ID, "region-invalid-blank-request")
                    .set(AUTOMATION_ADMISSION_STATES.CONTROL_PLANE_REQUEST_ID, "   ")
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_automation_admission_state_request_identity");

    assertThat(
            dsl.insertInto(AUTOMATION_ADMISSION_STATES)
                .set(AUTOMATION_ADMISSION_STATES.TENANT_ID, "tenant-absent-identity")
                .set(AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID, "instance-absent-identity")
                .set(AUTOMATION_ADMISSION_STATES.REGION_ID, "region-absent-identity")
                .set(AUTOMATION_ADMISSION_STATES.CONTROL_PLANE_REQUEST_FINGERPRINT, "")
                .execute())
        .isEqualTo(1);

    assertThat(
            dsl.insertInto(AUTOMATION_ADMISSION_STATES)
                .set(AUTOMATION_ADMISSION_STATES.TENANT_ID, "tenant-paired-identity")
                .set(AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID, "instance-paired-identity")
                .set(AUTOMATION_ADMISSION_STATES.REGION_ID, "region-paired-identity")
                .set(AUTOMATION_ADMISSION_STATES.CONTROL_PLANE_REQUEST_ID, "request-paired")
                .set(AUTOMATION_ADMISSION_STATES.CONTROL_PLANE_REQUEST_FINGERPRINT, REQUEST_DIGEST)
                .execute())
        .isEqualTo(1);

    assertThatThrownBy(
            () ->
                dsl.insertInto(AUTOMATION_ADMISSION_REQUEST_HISTORY)
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.TENANT_ID, "tenant-history-empty")
                    .set(
                        AUTOMATION_ADMISSION_REQUEST_HISTORY.GAME_INSTANCE_ID,
                        "instance-history-empty")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.REGION_ID, "region-history-empty")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.MODE, "PAUSED_FOR_ROLLBACK")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID, "")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.REQUEST_FINGERPRINT, REQUEST_DIGEST)
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.ADMISSION_EPOCH, 2L)
                    .set(
                        AUTOMATION_ADMISSION_REQUEST_HISTORY.OUTCOME,
                        AutomationAdmissionStateService.OUTCOME_APPLIED)
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.ACTOR_PRINCIPAL, "actor-1")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.REASON, "rollback")
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_automation_admission_request_history_fingerprint");

    assertThatThrownBy(
            () ->
                dsl.insertInto(AUTOMATION_ADMISSION_REQUEST_HISTORY)
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.TENANT_ID, "tenant-history-space")
                    .set(
                        AUTOMATION_ADMISSION_REQUEST_HISTORY.GAME_INSTANCE_ID,
                        "instance-history-space")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.REGION_ID, "region-history-space")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.MODE, "PAUSED_FOR_ROLLBACK")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID, "   ")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.REQUEST_FINGERPRINT, REQUEST_DIGEST)
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.ADMISSION_EPOCH, 2L)
                    .set(
                        AUTOMATION_ADMISSION_REQUEST_HISTORY.OUTCOME,
                        AutomationAdmissionStateService.OUTCOME_APPLIED)
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.ACTOR_PRINCIPAL, "actor-1")
                    .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.REASON, "rollback")
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_automation_admission_request_history_fingerprint");

    assertThat(
            dsl.insertInto(AUTOMATION_ADMISSION_REQUEST_HISTORY)
                .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.TENANT_ID, "tenant-history-valid")
                .set(
                    AUTOMATION_ADMISSION_REQUEST_HISTORY.GAME_INSTANCE_ID, "instance-history-valid")
                .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.REGION_ID, "region-history-valid")
                .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.MODE, "PAUSED_FOR_ROLLBACK")
                .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID, "request-valid")
                .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.REQUEST_FINGERPRINT, REQUEST_DIGEST)
                .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.ADMISSION_EPOCH, 2L)
                .set(
                    AUTOMATION_ADMISSION_REQUEST_HISTORY.OUTCOME,
                    AutomationAdmissionStateService.OUTCOME_APPLIED)
                .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.ACTOR_PRINCIPAL, "actor-1")
                .set(AUTOMATION_ADMISSION_REQUEST_HISTORY.REASON, "rollback")
                .execute())
        .isEqualTo(1);
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

  @Test
  void retentionAndParentCleanupSerializeParentBeforeChildWithoutDeadlock() throws Exception {
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    handoffRepository.save(retainedHandoff(parent.getId()));
    Instant cutoff = Instant.parse("2021-01-01T00:00:00Z");
    Instant now = Instant.parse("2021-01-02T00:00:00Z");

    DSLContext parentCleanupDsl = newDsl("automation-retention-parent-cleanup-race-");
    DSLContext handoffCleanupDsl = newDsl("automation-retention-handoff-cleanup-race-");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Long> parentCleanup =
          executor.submit(
              () ->
                  parentCleanupDsl.transactionResult(
                      configuration -> {
                        ready.countDown();
                        await(start);
                        return new ScriptWorkItemRepository(configuration.dsl())
                            .deleteByStatusAndUpdatedAtBefore("HANDED_OFF", now);
                      }));
      Future<Long> handoffCleanup =
          executor.submit(
              () ->
                  handoffCleanupDsl.transactionResult(
                      configuration -> {
                        ready.countDown();
                        await(start);
                        return new ScriptHandoffEventRepository(configuration.dsl())
                            .deleteExpiredRetentionEvidence(cutoff, now);
                      }));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(get(parentCleanup)).isBetween(0L, 1L);
      assertThat(get(handoffCleanup)).isBetween(0L, 1L);
    } finally {
      start.countDown();
    }
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isZero();
  }

  @Test
  void retentionRechecksSiblingHoldCommittedAfterCandidateSnapshot() throws Exception {
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptHandoffEvent candidate = retainedHandoff(parent.getId());
    candidate.setEventId("retention-sibling-hold-candidate");
    handoffRepository.save(candidate);
    ScriptHandoffEvent sibling = retainedHandoff(parent.getId());
    sibling.setEventId("retention-sibling-hold-sibling");
    sibling.setCommandOrdinal(1);
    sibling.setAutomationDispatchId("retention-sibling-hold-dispatch");
    long siblingId = handoffRepository.save(sibling).getId();

    Instant cutoff = Instant.parse("2021-01-01T00:00:00Z");
    Instant now = Instant.parse("2021-01-02T00:00:00Z");
    CountDownLatch holdStarted = new CountDownLatch(1);
    CountDownLatch releaseHold = new CountDownLatch(1);
    DSLContext holdDsl = newDsl("automation-retention-sibling-hold-" + System.nanoTime());
    Future<Void> hold =
        executor.submit(
            () ->
                holdDsl.transactionResult(
                    configuration -> {
                      configuration
                          .dsl()
                          .update(SCRIPT_HANDOFF_EVENTS)
                          .set(
                              SCRIPT_HANDOFF_EVENTS.RETENTION_HOLD_UNTIL,
                              now.plusSeconds(60).atOffset(ZoneOffset.UTC))
                          .where(SCRIPT_HANDOFF_EVENTS.ID.eq(siblingId))
                          .execute();
                      holdStarted.countDown();
                      await(releaseHold);
                      return null;
                    }));
    assertThat(holdStarted.await(5, TimeUnit.SECONDS)).isTrue();

    AtomicLong cleanupBackendPid = new AtomicLong();
    CountDownLatch cleanupStarted = new CountDownLatch(1);
    DSLContext cleanupDsl = newDsl("automation-retention-sibling-cleanup-" + System.nanoTime());
    Future<Long> cleanup =
        executor.submit(
            () ->
                cleanupDsl.transactionResult(
                    configuration -> {
                      DSLContext transactionDsl = configuration.dsl();
                      cleanupBackendPid.set(currentBackendPid(transactionDsl));
                      cleanupStarted.countDown();
                      return new ScriptHandoffEventRepository(transactionDsl)
                          .deleteExpiredRetentionEvidence(cutoff, now);
                    }));
    assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
    awaitPostgresLockWait(cleanupBackendPid.get());
    releaseHold.countDown();

    assertThat(get(hold)).isNull();
    assertThat(get(cleanup)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(2);
  }

  @Test
  void retentionRechecksReplayReceiptCommittedAfterCandidateSnapshot() throws Exception {
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptHandoffEvent handoff = retainedHandoff(parent.getId());
    handoff.setEventId("retention-replay-receipt-candidate");
    handoffRepository.save(handoff);
    ScriptDeadLetterReplayRepository.ReplayRequest request =
        replayRepository.insertOrGet(
            "tenant-1",
            "retention-replay-receipt-request",
            REQUEST_DIGEST,
            "operator-retention",
            "retention-race",
            OLD);

    Instant cutoff = Instant.parse("2021-01-01T00:00:00Z");
    Instant now = Instant.parse("2021-01-02T00:00:00Z");
    CountDownLatch receiptStarted = new CountDownLatch(1);
    CountDownLatch releaseReceipt = new CountDownLatch(1);
    DSLContext receiptDsl = newDsl("automation-retention-receipt-" + System.nanoTime());
    Future<Void> receipt =
        executor.submit(
            () ->
                receiptDsl.transactionResult(
                    configuration -> {
                      new ScriptDeadLetterReplayRepository(configuration.dsl())
                          .saveResult(
                              "tenant-1",
                              request.id(),
                              parent.getId(),
                              parent.getId(),
                              "rejected",
                              "retention-race",
                              "",
                              2L,
                              0L,
                              0L,
                              1L,
                              now);
                      receiptStarted.countDown();
                      await(releaseReceipt);
                      return null;
                    }));
    assertThat(receiptStarted.await(5, TimeUnit.SECONDS)).isTrue();

    AtomicLong cleanupBackendPid = new AtomicLong();
    CountDownLatch cleanupStarted = new CountDownLatch(1);
    DSLContext cleanupDsl = newDsl("automation-retention-receipt-cleanup-" + System.nanoTime());
    Future<Long> cleanup =
        executor.submit(
            () ->
                cleanupDsl.transactionResult(
                    configuration -> {
                      DSLContext transactionDsl = configuration.dsl();
                      cleanupBackendPid.set(currentBackendPid(transactionDsl));
                      cleanupStarted.countDown();
                      return new ScriptHandoffEventRepository(transactionDsl)
                          .deleteExpiredRetentionEvidence(cutoff, now);
                    }));
    assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
    awaitPostgresLockWait(cleanupBackendPid.get());
    releaseReceipt.countDown();

    assertThat(get(receipt)).isNull();
    assertThat(get(cleanup)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(1);
    assertThat(
            dsl.fetchCount(
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS,
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.WORK_ITEM_ID.eq(parent.getId())))
        .isEqualTo(1);
  }

  @Test
  void retentionRechecksParentStatusCommittedAfterCandidateSnapshot() throws Exception {
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptHandoffEvent handoff = retainedHandoff(parent.getId());
    handoff.setEventId("retention-parent-status-candidate");
    handoffRepository.save(handoff);

    Instant cutoff = Instant.parse("2021-01-01T00:00:00Z");
    Instant now = Instant.parse("2021-01-02T00:00:00Z");
    CountDownLatch statusStarted = new CountDownLatch(1);
    CountDownLatch releaseStatus = new CountDownLatch(1);
    DSLContext statusDsl = newDsl("automation-retention-parent-status-" + System.nanoTime());
    Future<Void> status =
        executor.submit(
            () ->
                statusDsl.transactionResult(
                    configuration -> {
                      configuration
                          .dsl()
                          .update(SCRIPT_WORK_ITEMS)
                          .set(SCRIPT_WORK_ITEMS.STATUS, "DEAD_LETTERED")
                          .where(SCRIPT_WORK_ITEMS.ID.eq(parent.getId()))
                          .execute();
                      statusStarted.countDown();
                      await(releaseStatus);
                      return null;
                    }));
    assertThat(statusStarted.await(5, TimeUnit.SECONDS)).isTrue();

    AtomicLong cleanupBackendPid = new AtomicLong();
    CountDownLatch cleanupStarted = new CountDownLatch(1);
    DSLContext cleanupDsl = newDsl("automation-retention-status-cleanup-" + System.nanoTime());
    Future<Long> cleanup =
        executor.submit(
            () ->
                cleanupDsl.transactionResult(
                    configuration -> {
                      DSLContext transactionDsl = configuration.dsl();
                      cleanupBackendPid.set(currentBackendPid(transactionDsl));
                      cleanupStarted.countDown();
                      return new ScriptHandoffEventRepository(transactionDsl)
                          .deleteExpiredRetentionEvidence(cutoff, now);
                    }));
    assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
    awaitPostgresLockWait(cleanupBackendPid.get());
    releaseStatus.countDown();

    assertThat(get(status)).isNull();
    assertThat(get(cleanup)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isEqualTo(1);
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(1);
    assertThat(dsl.fetchValue(SCRIPT_WORK_ITEMS.STATUS, SCRIPT_WORK_ITEMS.ID.eq(parent.getId())))
        .isEqualTo("DEAD_LETTERED");
  }

  @Test
  void deadLetterAgeRetentionLeavesParentWhenChildOutcomeIsIncomplete() {
    ScriptWorkItem parent = workItemRepository.save(deadLetteredWorkItem("dead-letter-age"));
    ScriptHandoffEvent handoff = retainedHandoff(parent.getId());
    handoff.setEventId("incomplete-age-handoff");
    handoff.setHandoffOutcome("");
    handoffRepository.save(handoff);

    assertThat(workItemRepository.deleteByStatusAndUpdatedAtBefore("DEAD_LETTERED", Instant.now()))
        .isZero();
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isEqualTo(1);
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(1);
  }

  @Test
  void retainedReplayReceiptIsCountedAndContinuesToBlockAgedParentDeletion() {
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptDeadLetterReplayRepository.ReplayRequest request =
        replayRepository.insertOrGet(
            "tenant-1", "retained-parent-replay", REQUEST_DIGEST, "operator", "retry", OLD);
    replayRepository.saveResult(
        "tenant-1",
        request.id(),
        parent.getId(),
        parent.getId(),
        "rejected",
        "retained-result",
        "",
        2L,
        0L,
        0L,
        1L,
        OLD);

    assertThat(
            workItemRepository.countTerminalRowsBlockedByReplayReceipts(
                "HANDED_OFF", Instant.now()))
        .isEqualTo(1L);
    assertThat(workItemRepository.deleteByStatusAndUpdatedAtBefore("HANDED_OFF", Instant.now()))
        .isZero();
    assertThat(dsl.fetchExists(SCRIPT_WORK_ITEMS, SCRIPT_WORK_ITEMS.ID.eq(parent.getId()))).isTrue();
    assertThat(
            dsl.fetchCount(
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS,
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.WORK_ITEM_ID.eq(parent.getId())))
        .isEqualTo(1);
  }

  @Test
  void deadLetterRowCapLeavesParentWhenChildOutcomeIsIncomplete() {
    ScriptWorkItem parent = workItemRepository.save(deadLetteredWorkItem("dead-letter-cap"));
    ScriptHandoffEvent handoff = retainedHandoff(parent.getId());
    handoff.setEventId("incomplete-cap-handoff");
    handoff.setHandoffOutcome("   ");
    handoffRepository.save(handoff);

    assertThat(workItemRepository.deleteOldestByStatus("DEAD_LETTERED", 1)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isEqualTo(1);
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(1);
  }

  @Test
  void handedOffAgeRetentionLeavesParentWhenChildOutcomeIsIncomplete() {
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptHandoffEvent handoff = retainedHandoff(parent.getId());
    handoff.setEventId("incomplete-handed-off-age-handoff");
    handoff.setHandoffOutcome("");
    handoffRepository.save(handoff);

    assertThat(workItemRepository.deleteByStatusAndUpdatedAtBefore("HANDED_OFF", Instant.now()))
        .isZero();
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isEqualTo(1);
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(1);
  }

  @Test
  void incompleteChildBlocksOnlyItsCorrelatedParentDuringAgeRetention() {
    ScriptWorkItem incompleteParent = workItemRepository.save(retainedWorkItem());
    ScriptHandoffEvent incompleteHandoff = retainedHandoff(incompleteParent.getId());
    incompleteHandoff.setEventId("incomplete-correlated-parent");
    incompleteHandoff.setHandoffOutcome("");
    incompleteHandoff = handoffRepository.save(incompleteHandoff);

    ScriptWorkItem completeParent = retainedWorkItem();
    completeParent.setScriptEventId("complete-correlated-parent");
    completeParent = workItemRepository.save(completeParent);
    ScriptHandoffEvent completeHandoff = retainedHandoff(completeParent.getId());
    completeHandoff.setEventId("complete-correlated-child");
    handoffRepository.save(completeHandoff);

    assertThat(workItemRepository.deleteByStatusAndUpdatedAtBefore("HANDED_OFF", Instant.now()))
        .isEqualTo(1L);
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isEqualTo(1);
    assertThat(
            dsl.fetchExists(SCRIPT_WORK_ITEMS, SCRIPT_WORK_ITEMS.ID.eq(incompleteParent.getId())))
        .isTrue();
    assertThat(
            dsl.fetchExists(
                SCRIPT_HANDOFF_EVENTS, SCRIPT_HANDOFF_EVENTS.ID.eq(incompleteHandoff.getId())))
        .isTrue();
  }

  @Test
  void ageRetentionKeepsOldParentUntilItsCompleteHandoffChildIsOldEnough() {
    Instant cutoff = Instant.parse("2021-01-01T00:00:00Z");
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptHandoffEvent handoff = retainedHandoff(parent.getId());
    handoff.setEventId("newer-complete-child");
    handoff.setObservedAt(Instant.parse("2022-01-01T00:00:00Z"));
    handoff = handoffRepository.save(handoff);

    assertThat(workItemRepository.deleteByStatusAndUpdatedAtBefore("HANDED_OFF", cutoff)).isZero();
    assertThat(dsl.fetchExists(SCRIPT_WORK_ITEMS, SCRIPT_WORK_ITEMS.ID.eq(parent.getId())))
        .isTrue();
    assertThat(dsl.fetchExists(SCRIPT_HANDOFF_EVENTS, SCRIPT_HANDOFF_EVENTS.ID.eq(handoff.getId())))
        .isTrue();

    Instant laterCutoff = Instant.parse("2023-01-01T00:00:00Z");
    assertThat(workItemRepository.deleteByStatusAndUpdatedAtBefore("HANDED_OFF", laterCutoff))
        .isEqualTo(1L);
    assertThat(dsl.fetchExists(SCRIPT_WORK_ITEMS, SCRIPT_WORK_ITEMS.ID.eq(parent.getId())))
        .isFalse();
    assertThat(dsl.fetchExists(SCRIPT_HANDOFF_EVENTS, SCRIPT_HANDOFF_EVENTS.ID.eq(handoff.getId())))
        .isFalse();
  }

  @Test
  void handoffAgeRetentionKeepsOldChildWhileAnySiblingIsTooNewOrHeld() {
    Instant cutoff = Instant.parse("2021-01-01T00:00:00Z");
    Instant now = Instant.parse("2021-01-02T00:00:00Z");
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptHandoffEvent candidate = retainedHandoff(parent.getId());
    candidate.setEventId("old-candidate-handoff");
    candidate = handoffRepository.save(candidate);
    ScriptHandoffEvent sibling = retainedHandoff(parent.getId());
    sibling.setEventId("new-sibling-handoff");
    sibling.setCommandOrdinal(1);
    sibling.setAutomationDispatchId("dispatch-2");
    sibling.setObservedAt(Instant.parse("2022-01-01T00:00:00Z"));
    sibling = handoffRepository.save(sibling);

    assertThat(handoffRepository.deleteExpiredRetentionEvidence(cutoff, now)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(2);

    dsl.update(SCRIPT_HANDOFF_EVENTS)
        .set(SCRIPT_HANDOFF_EVENTS.OBSERVED_AT, OLD.atOffset(ZoneOffset.UTC).toLocalDateTime())
        .where(SCRIPT_HANDOFF_EVENTS.ID.eq(sibling.getId()))
        .execute();
    assertThat(handoffRepository.setRetentionHold("tenant-1", sibling.getId(), now.plusSeconds(1)))
        .isTrue();

    assertThat(handoffRepository.deleteExpiredRetentionEvidence(cutoff, now)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(2);

    assertThat(handoffRepository.setRetentionHold("tenant-1", sibling.getId(), null)).isTrue();
    assertThat(handoffRepository.deleteExpiredRetentionEvidence(cutoff, now)).isEqualTo(2L);
    assertThat(
            dsl.fetchExists(SCRIPT_HANDOFF_EVENTS, SCRIPT_HANDOFF_EVENTS.ID.eq(candidate.getId())))
        .isFalse();
    assertThat(dsl.fetchExists(SCRIPT_HANDOFF_EVENTS, SCRIPT_HANDOFF_EVENTS.ID.eq(sibling.getId())))
        .isFalse();
  }

  @Test
  void canceledRowCapRetentionLeavesParentWhenChildOutcomeIsWhitespace() {
    ScriptWorkItem parent = retainedWorkItem();
    parent.setScriptEventId("canceled-incomplete-cap");
    parent.setStatus("CANCELED");
    parent = workItemRepository.save(parent);
    ScriptHandoffEvent handoff = retainedHandoff(parent.getId());
    handoff.setEventId("incomplete-canceled-cap-handoff");
    handoff.setHandoffOutcome(" \t ");
    handoffRepository.save(handoff);

    assertThat(workItemRepository.deleteOldestByStatus("CANCELED", 1)).isZero();
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isEqualTo(1);
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(1);
  }

  @Test
  void handoffAgeRetentionLeavesBlankOutcomeChildForTerminalParent() {
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptHandoffEvent handoff = retainedHandoff(parent.getId());
    handoff.setEventId("incomplete-direct-age-handoff");
    handoff.setHandoffOutcome(" \t ");
    handoffRepository.save(handoff);

    assertThat(handoffRepository.deleteExpiredRetentionEvidence(Instant.now(), Instant.now()))
        .isZero();
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isEqualTo(1);
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(1);
  }

  @Test
  void handoffAgeRetentionLeavesCompleteChildWhenSiblingOutcomeIsIncomplete() {
    ScriptWorkItem parent = workItemRepository.save(retainedWorkItem());
    ScriptHandoffEvent complete = retainedHandoff(parent.getId());
    complete.setEventId("complete-sibling-age-handoff");
    handoffRepository.save(complete);
    ScriptHandoffEvent incomplete = retainedHandoff(parent.getId());
    incomplete.setEventId("incomplete-sibling-age-handoff");
    incomplete.setCommandOrdinal(1);
    incomplete.setAutomationDispatchId("dispatch-2");
    incomplete.setHandoffOutcome("");
    handoffRepository.save(incomplete);

    assertThat(handoffRepository.deleteExpiredRetentionEvidence(Instant.now(), Instant.now()))
        .isZero();
    assertThat(dsl.fetchCount(SCRIPT_WORK_ITEMS)).isEqualTo(1);
    assertThat(dsl.fetchCount(SCRIPT_HANDOFF_EVENTS)).isEqualTo(2);
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
    claim.setRequestDigest(REQUEST_DIGEST);
    claim.setSourceService("game-session-service");
    claim.setTriggerMode("EVENT");
    claim.setSourceState("IN_PROGRESS");
    claim.setAdmitted(true);
    claim.setAdmissionOutcome("ADMITTED");
    claim.setAdmissionReason("accepted");
    return claim;
  }

  private ScriptEventIngressAudit preInstanceIngressClaim() {
    ScriptEventIngressAudit claim = new ScriptEventIngressAudit();
    claim.setTenantId("tenant-pre-instance");
    claim.setScriptId("script-on-load");
    claim.setEventType("onLoad");
    claim.setEventSchemaVersion("v1");
    claim.setScriptPatchVersion("patch-1");
    claim.setScriptEventId("on-load-event-1");
    claim.setRequestDigest(REQUEST_DIGEST);
    claim.setSourceService("automation-scripting-service");
    claim.setTriggerMode("ON_LOAD");
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

  private ScriptWorkItem unpinnedWorkItem() {
    ScriptWorkItem item = pinnedWorkItem();
    item.setScriptPinEpoch(0L);
    item.setScriptPinControlPlaneRequestId(null);
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

  private ScriptWorkItem deadLetteredWorkItem(String eventId) {
    ScriptWorkItem item = retainedWorkItem();
    item.setScriptEventId(eventId);
    item.setStatus("DEAD_LETTERED");
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

  private ScriptEventAudit handlerAudit(String tenantId) {
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId(tenantId);
    audit.setGameInstanceId("instance-handler-audit");
    audit.setRegionId("region-handler-audit");
    audit.setRegionEpoch(1L);
    audit.setEntityId("entity-handler-audit");
    audit.setPlayableStateScope("INSTANCE");
    audit.setScriptId("script-handler-audit");
    audit.setEventType("onEnterRegion");
    audit.setEventSchemaVersion("v1");
    audit.setScriptPatchVersion("patch-handler-audit");
    audit.setScriptPinEpoch(2L);
    audit.setScriptPinControlPlaneRequestId("pin-request-1");
    audit.setScriptEventId("handler-event-1");
    audit.setSourceService("automation-scripting-service");
    audit.setTriggerMode("EVENT");
    audit.setFinalStage("HANDLER");
    audit.setFinalOutcome("HANDLER_ACCEPTED");
    audit.setFinalReason("accepted");
    return audit;
  }

  private ScriptScheduleInstance scheduleInstance() {
    ScriptScheduleInstance schedule = new ScriptScheduleInstance();
    schedule.setTenantId("tenant-schedule-binding");
    schedule.setGameInstanceId("instance-schedule-binding");
    schedule.setScriptPatchVersion("patch-schedule-binding");
    schedule.setScriptId("script-schedule-binding");
    schedule.setEventType("onEnterRegion");
    schedule.setScheduleDefinitionId("schedule-binding");
    schedule.setScheduleKind("RECURRING");
    schedule.setCadenceValue(1);
    schedule.setCadenceUnit("TICKS");
    schedule.setMaterializationStatus("READY");
    schedule.setScheduleMetadataJson("{}");
    schedule.setScheduleSemanticsHash("hash-schedule-binding");
    return schedule;
  }

  private AutomationAdmissionStateService admissionService(DSLContext transactionDsl) {
    return new AutomationAdmissionStateServiceImpl(
        new AutomationAdmissionStateRepository(transactionDsl),
        transactionDsl,
        new AutomationAdmissionRequestHistoryRepository(transactionDsl));
  }

  private SetAdmissionModeCommand admissionCommand(
      String mode, String requestId, String actor, String reason) {
    return new SetAdmissionModeCommand(
        "tenant-admission",
        "instance-admission",
        "region-admission",
        mode,
        requestId,
        actor,
        reason);
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

  private record MutationAttempt(AdmissionStateSummary summary, RuntimeException failure) {
    private static MutationAttempt success(AdmissionStateSummary summary) {
      return new MutationAttempt(summary, null);
    }

    private static MutationAttempt failure(RuntimeException failure) {
      return new MutationAttempt(null, failure);
    }
  }

  private DSLContext newDsl(String applicationName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "ApplicationName=" + applicationName);
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
