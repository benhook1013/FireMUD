package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteCommandCoordinatorRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private RemoteCommandCoordinatorRepository coordinatorRepository;
  private RemoteFollowupRepository followupRepository;
  private RemoteFollowupResultRepository resultRepository;

  @BeforeAll
  void setUpRepositories() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure()
        .dataSource(dataSource)
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    coordinatorRepository = new RemoteCommandCoordinatorRepository(dsl);
    followupRepository = new RemoteFollowupRepository(dsl);
    resultRepository = new RemoteFollowupResultRepository(dsl);
  }

  @BeforeEach
  void cleanTables() {
    dsl.execute(
        "TRUNCATE TABLE runtime_region_status, remote_followup_result, remote_followup,"
            + " remote_command_coordinator RESTART IDENTITY CASCADE");
  }

  @Test
  void findForControlPlaneUsesIdTieBreakForLatestResultFilters() {
    Instant sharedObservedAt = Instant.parse("2026-06-25T12:00:00Z");

    RemoteCommandCoordinator exactCoordinator = remoteCoordinator(sharedObservedAt);
    coordinatorRepository.save(exactCoordinator);
    followupRepository.save(remoteFollowup(sharedObservedAt));
    resultRepository.save(
        remoteResult("result-older", sharedObservedAt, "REMOTE_APPLIED", "RATE_LIMIT"));
    resultRepository.save(
        remoteResult("result-newer", sharedObservedAt, "REMOTE_REJECTED", "INVALID_TARGET"));
    RemoteFollowupResult wrongScope =
        remoteResult("result-wrong-scope", sharedObservedAt.plusSeconds(1), "WRONG_SCOPE", "BAD");
    wrongScope.setTargetGameInstanceId(7L);
    wrongScope.setTargetRegionId("region-other");
    wrongScope.setTargetRegionEpoch(99L);
    resultRepository.save(wrongScope);
    RemoteFollowupResult wrongOrigin =
        remoteResult("result-wrong-origin", sharedObservedAt.plusSeconds(2), "WRONG_ORIGIN", "BAD");
    wrongOrigin.setOriginGameInstanceId(6L);
    wrongOrigin.setOriginRegionId("region-other");
    wrongOrigin.setOriginRegionEpoch(99L);
    resultRepository.save(wrongOrigin);
    RemoteFollowupResult wrongFollowup =
        remoteResult(
            "result-wrong-followup", sharedObservedAt.plusSeconds(3), "WRONG_FOLLOWUP", "BAD");
    wrongFollowup.setFollowupId("other-followup");
    resultRepository.save(wrongFollowup);

    assertThat(findForLatestResult("REMOTE_APPLIED", "RATE_LIMIT")).isEmpty();
    assertThat(findForLatestResult("REMOTE_REJECTED", "INVALID_TARGET"))
        .extracting(RemoteCommandCoordinator::getCoordinatorId)
        .containsExactly("coord-1");
    assertThat(findForLatestResult("WRONG_SCOPE", "BAD")).isEmpty();
    assertThat(findForLatestResult("WRONG_ORIGIN", "BAD")).isEmpty();
    assertThat(findForLatestResult("WRONG_FOLLOWUP", "BAD")).isEmpty();
    assertThat(resultRepository.findLatestForCoordinator(exactCoordinator))
        .get()
        .extracting(RemoteFollowupResult::getResultId)
        .isEqualTo("result-newer");
    assertThat(resultRepository.findForCoordinatorScopes(List.of(exactCoordinator)))
        .extracting(RemoteFollowupResult::getResultId)
        .containsExactly("result-older", "result-newer");
  }

  @Test
  void controlPlaneListJoinsRequireCompleteTargetScope() {
    Instant observedAt = Instant.parse("2026-06-25T12:00:00Z");
    coordinatorRepository.save(remoteCoordinator(observedAt));
    followupRepository.save(remoteFollowup(observedAt));
    resultRepository.save(
        remoteResult("result-1", observedAt, "REMOTE_REJECTED", "INVALID_TARGET"));

    insertGameplayCommand(
        "same-tenant-wrong-instance", 1L, 7L, "region-target", 4L, "APPLIED", "rf-1");
    insertGameplayCommand(
        "same-tenant-wrong-region", 1L, 9L, "region-other", 4L, "APPLIED", "rf-1");
    insertGameplayCommand(
        "same-tenant-wrong-epoch", 1L, 9L, "region-target", 99L, "APPLIED", "rf-1");
    insertGameplayCommand("exact-target", 1L, 9L, "region-target", 4L, "STAGED", "rf-1");
    insertGameplayCommand(
        "same-target-wrong-followup", 1L, 9L, "region-target", 4L, "APPLIED", "other-followup");
    insertGameplayCommand("foreign-target", 2L, 9L, "region-target", 4L, "APPLIED", null);
    dsl.execute(
        "UPDATE remote_followup_result SET result_command_id = ? WHERE result_id = ?",
        "same-target-wrong-followup",
        "result-1");

    assertThat(findCoordinatorsByTargetOutcome("APPLIED")).isEmpty();
    assertThat(findCoordinatorsByTargetOutcome("STAGED"))
        .extracting(RemoteCommandCoordinator::getCoordinatorId)
        .containsExactly("coord-1");
    assertThat(findFollowupsByTargetOutcome("APPLIED")).isEmpty();
    assertThat(findFollowupsByTargetOutcome("STAGED"))
        .extracting(RemoteFollowup::getFollowupId)
        .containsExactly("rf-1");
    assertThat(findResultsByCommandOutcome("APPLIED")).isEmpty();

    dsl.execute(
        "UPDATE remote_followup_result SET result_command_id = ? WHERE result_id = ?",
        "foreign-target",
        "result-1");
    assertThat(findResultsByCommandOutcome("APPLIED")).isEmpty();

    dsl.execute(
        "UPDATE remote_followup_result SET result_command_id = ? WHERE result_id = ?",
        "exact-target",
        "result-1");
    assertThat(findResultsByCommandOutcome("STAGED"))
        .extracting(RemoteFollowupResult::getResultId)
        .containsExactly("result-1");
  }

  @Test
  void controlPlaneListJoinsRequireExactCurrentTargetRegionScope() {
    Instant observedAt = Instant.parse("2026-06-25T12:00:00Z");
    coordinatorRepository.save(remoteCoordinator(observedAt));
    followupRepository.save(remoteFollowup(observedAt));
    resultRepository.save(
        remoteResult("result-current-target-scope", observedAt, "REMOTE_APPLIED", "EXACT"));
    insertRuntimeRegionStatus(1L, 9L, "region-sibling", 99L);

    assertThat(findCoordinatorsByCurrentTarget("region-sibling", 99L, 9L)).isEmpty();
    assertThat(findFollowupsByCurrentTarget("region-sibling", 99L, 9L)).isEmpty();
    assertThat(findResultsByCurrentTarget("region-sibling", 99L, 9L)).isEmpty();
  }

  @Test
  void controlPlaneQueriesRequireCompleteOriginScope() {
    Instant observedAt = Instant.parse("2026-06-25T12:00:00Z");
    RemoteCommandCoordinator exactCoordinator = remoteCoordinator(observedAt);
    exactCoordinator.setLateResultPolicy("exact-policy");
    coordinatorRepository.save(exactCoordinator);
    followupRepository.save(remoteFollowup(observedAt));

    RemoteCommandCoordinator wrongOriginCoordinator = remoteCoordinator(observedAt.plusSeconds(1));
    wrongOriginCoordinator.setCoordinatorId("coord-wrong-origin");
    wrongOriginCoordinator.setCommandId("cmd-wrong-origin");
    wrongOriginCoordinator.setOriginGameInstanceId(6L);
    wrongOriginCoordinator.setOriginRegionId("region-other");
    wrongOriginCoordinator.setOriginRegionEpoch(99L);
    wrongOriginCoordinator.setOriginDeadlineRegionEpoch(99L);
    wrongOriginCoordinator.setOriginDeadlineTickId(999L);
    wrongOriginCoordinator.setLateResultPolicy("wrong-policy");
    coordinatorRepository.save(wrongOriginCoordinator);

    insertGameplayCommand("origin-exact-target", 1L, 9L, "region-target", 4L, "STAGED", "rf-1");
    RemoteFollowupResult exactResult =
        remoteResult("result-exact", observedAt, "REMOTE_APPLIED", "EXACT");
    exactResult.setResultCommandId("origin-exact-target");
    resultRepository.save(exactResult);
    RemoteFollowupResult wrongOriginResult =
        remoteResult("result-wrong-origin", observedAt.plusSeconds(1), "REMOTE_APPLIED", "WRONG");
    wrongOriginResult.setCoordinatorId("coord-wrong-origin");
    wrongOriginResult.setOriginGameInstanceId(6L);
    wrongOriginResult.setOriginRegionId("region-other");
    wrongOriginResult.setOriginRegionEpoch(99L);
    wrongOriginResult.setResultCommandId("origin-exact-target");
    resultRepository.save(wrongOriginResult);

    assertThat(findCoordinatorsByTargetOutcome("STAGED", "SCHEDULED"))
        .extracting(RemoteCommandCoordinator::getCoordinatorId)
        .containsExactly("coord-1");
    assertThat(findFollowupsByTargetOutcome("STAGED", "wrong-policy")).isEmpty();
    assertThat(findFollowupsByTargetOutcome("STAGED", 99L, 0L, "")).isEmpty();
    assertThat(findFollowupsByTargetOutcome("STAGED", 0L, 999L, "")).isEmpty();
    assertThat(findFollowupsByTargetOutcome("STAGED", "exact-policy"))
        .extracting(RemoteFollowup::getFollowupId)
        .containsExactly("rf-1");
    assertThat(findResultsByCommandOutcome("APPLIED", "effect-1", "wrong-policy")).isEmpty();
    assertThat(findResultsByCommandOutcome("APPLIED", "effect-1", "exact-policy"))
        .extracting(RemoteFollowupResult::getResultId)
        .containsExactly("result-exact");
  }

  private java.util.List<RemoteCommandCoordinator> findCoordinatorsByTargetOutcome(
      String targetCommandExecutionOutcome) {
    return findCoordinatorsByTargetOutcome(targetCommandExecutionOutcome, "");
  }

  private java.util.List<RemoteCommandCoordinator> findCoordinatorsByTargetOutcome(
      String targetCommandExecutionOutcome, String followupStatus) {
    return findCoordinatorsByTargetOutcome(
        targetCommandExecutionOutcome, followupStatus, "", 0L, null);
  }

  private java.util.List<RemoteCommandCoordinator> findCoordinatorsByCurrentTarget(
      String currentTargetRuntimeRegionId,
      long currentTargetRuntimeRegionEpoch,
      Long currentTargetRuntimeGameInstanceId) {
    return findCoordinatorsByTargetOutcome(
        "",
        "",
        currentTargetRuntimeRegionId,
        currentTargetRuntimeRegionEpoch,
        currentTargetRuntimeGameInstanceId);
  }

  private java.util.List<RemoteCommandCoordinator> findCoordinatorsByTargetOutcome(
      String targetCommandExecutionOutcome,
      String followupStatus,
      String currentTargetRuntimeRegionId,
      long currentTargetRuntimeRegionEpoch,
      Long currentTargetRuntimeGameInstanceId) {
    return coordinatorRepository.findForControlPlane(
        1L, // tenantId
        null, // originGameInstanceId
        "", // originRegionId
        0L, // originRegionEpoch
        null, // targetGameInstanceId
        "", // targetRegionId
        0L, // targetRegionEpoch
        "", // currentOriginRuntimeRegionId
        0L, // currentOriginRuntimeRegionEpoch
        null, // currentOriginRuntimeGameInstanceId
        currentTargetRuntimeRegionId, // currentTargetRuntimeRegionId
        currentTargetRuntimeRegionEpoch, // currentTargetRuntimeRegionEpoch
        currentTargetRuntimeGameInstanceId, // currentTargetRuntimeGameInstanceId
        "", // state
        "", // followupId
        "", // scriptId
        "", // pluginId
        "", // scriptPatchVersion
        "", // pluginVersionId
        "", // playableStateScope
        "", // worldSlug
        "", // realmSlug
        null, // pointerVersion
        "", // targetEntityId
        "", // claimTargetAggregate
        "", // effectKey
        "", // payloadKind
        "", // originSourceKind
        "", // originSourceState
        "", // automationWorkItemId
        "", // eventType
        "", // scriptEventId
        "", // lateResultPolicy
        "", // executionOutcome
        "", // gameplayResult
        followupStatus, // followupStatus
        "", // followupClaimedTickBatchId
        null, // followupRequiresSoloTick
        "", // followupQueueSourceKind
        "", // followupQueueSourceState
        0L, // followupQueueSourceOrdinal
        0L, // followupQueueSourceDueTickId
        0L, // followupQueueSourceDueAtMs
        "", // automationDispatchId
        "", // commandId
        "", // targetCommandId
        targetCommandExecutionOutcome, // targetCommandExecutionOutcome
        "", // targetCommandGameplayResult
        "", // latestResultOutcome
        "", // latestResultErrorCode
        PageRequest.of(0, 20));
  }

  private java.util.List<RemoteFollowup> findFollowupsByTargetOutcome(
      String targetCommandExecutionOutcome) {
    return findFollowupsByTargetOutcome(targetCommandExecutionOutcome, 0L, 0L, "");
  }

  private java.util.List<RemoteFollowup> findFollowupsByTargetOutcome(
      String targetCommandExecutionOutcome, String lateResultPolicy) {
    return findFollowupsByTargetOutcome(targetCommandExecutionOutcome, 0L, 0L, lateResultPolicy);
  }

  private java.util.List<RemoteFollowup> findFollowupsByCurrentTarget(
      String currentTargetRuntimeRegionId,
      long currentTargetRuntimeRegionEpoch,
      Long currentTargetRuntimeGameInstanceId) {
    return findFollowupsByTargetOutcome(
        "",
        0L,
        0L,
        "",
        currentTargetRuntimeRegionId,
        currentTargetRuntimeRegionEpoch,
        currentTargetRuntimeGameInstanceId);
  }

  private java.util.List<RemoteFollowup> findFollowupsByTargetOutcome(
      String targetCommandExecutionOutcome,
      long originDeadlineRegionEpoch,
      long originDeadlineTickId,
      String lateResultPolicy) {
    return findFollowupsByTargetOutcome(
        targetCommandExecutionOutcome,
        originDeadlineRegionEpoch,
        originDeadlineTickId,
        lateResultPolicy,
        "",
        0L,
        null);
  }

  private java.util.List<RemoteFollowup> findFollowupsByTargetOutcome(
      String targetCommandExecutionOutcome,
      long originDeadlineRegionEpoch,
      long originDeadlineTickId,
      String lateResultPolicy,
      String currentTargetRuntimeRegionId,
      long currentTargetRuntimeRegionEpoch,
      Long currentTargetRuntimeGameInstanceId) {
    return followupRepository.findForControlPlane(
        1L, // tenantId
        "", // targetRegionId
        "", // status
        null, // originGameInstanceId
        "", // originRegionId
        0L, // originRegionEpoch
        null, // targetGameInstanceId
        0L, // targetRegionEpoch
        "", // currentOriginRuntimeRegionId
        0L, // currentOriginRuntimeRegionEpoch
        null, // currentOriginRuntimeGameInstanceId
        currentTargetRuntimeRegionId, // currentTargetRuntimeRegionId
        currentTargetRuntimeRegionEpoch, // currentTargetRuntimeRegionEpoch
        currentTargetRuntimeGameInstanceId, // currentTargetRuntimeGameInstanceId
        "", // followupId
        "", // scriptId
        "", // pluginId
        "", // scriptPatchVersion
        "", // pluginVersionId
        "", // playableStateScope
        "", // worldSlug
        "", // realmSlug
        null, // pointerVersion
        "", // payloadKind
        "", // originSourceKind
        "", // originSourceState
        "", // automationWorkItemId
        "", // targetEntityId
        "", // claimTargetAggregate
        "", // effectKey
        "", // failureCode
        null, // requiresSoloTick
        "", // claimedTickBatchId
        "", // queueSourceKind
        "", // queueSourceState
        0L, // queueSourceOrdinal
        0L, // queueSourceDueTickId
        0L, // queueSourceDueAtMs
        "", // requestedCommand
        "", // eventType
        "", // scriptEventId
        originDeadlineRegionEpoch,
        originDeadlineTickId,
        lateResultPolicy,
        "", // automationDispatchId
        "", // commandId
        "", // targetCommandId
        targetCommandExecutionOutcome, // targetCommandExecutionOutcome
        "", // targetCommandGameplayResult
        PageRequest.of(0, 20));
  }

  private java.util.List<RemoteFollowupResult> findResultsByCommandOutcome(
      String resultCommandExecutionOutcome) {
    return findResultsByCommandOutcome(resultCommandExecutionOutcome, "", "");
  }

  private java.util.List<RemoteFollowupResult> findResultsByCommandOutcome(
      String resultCommandExecutionOutcome, String effectKey, String lateResultPolicy) {
    return findResultsByCommandOutcome(
        resultCommandExecutionOutcome, effectKey, lateResultPolicy, "", 0L, null);
  }

  private java.util.List<RemoteFollowupResult> findResultsByCurrentTarget(
      String currentTargetRuntimeRegionId,
      long currentTargetRuntimeRegionEpoch,
      Long currentTargetRuntimeGameInstanceId) {
    return findResultsByCommandOutcome(
        "",
        "",
        "",
        currentTargetRuntimeRegionId,
        currentTargetRuntimeRegionEpoch,
        currentTargetRuntimeGameInstanceId);
  }

  private java.util.List<RemoteFollowupResult> findResultsByCommandOutcome(
      String resultCommandExecutionOutcome,
      String effectKey,
      String lateResultPolicy,
      String currentTargetRuntimeRegionId,
      long currentTargetRuntimeRegionEpoch,
      Long currentTargetRuntimeGameInstanceId) {
    return resultRepository.findForControlPlane(
        1L, // tenantId
        "", // coordinatorId
        "", // followupId
        null, // originGameInstanceId
        "", // originRegionId
        0L, // originRegionEpoch
        null, // targetGameInstanceId
        "", // targetRegionId
        0L, // targetRegionEpoch
        "", // currentOriginRuntimeRegionId
        0L, // currentOriginRuntimeRegionEpoch
        null, // currentOriginRuntimeGameInstanceId
        currentTargetRuntimeRegionId, // currentTargetRuntimeRegionId
        currentTargetRuntimeRegionEpoch, // currentTargetRuntimeRegionEpoch
        currentTargetRuntimeGameInstanceId, // currentTargetRuntimeGameInstanceId
        "", // outcome
        "", // scriptId
        "", // pluginId
        "", // scriptPatchVersion
        "", // pluginVersionId
        "", // playableStateScope
        "", // worldSlug
        "", // realmSlug
        null, // pointerVersion
        "", // resultErrorCode
        "", // automationWorkItemId
        "", // resultCommandId
        resultCommandExecutionOutcome, // resultCommandExecutionOutcome
        "", // resultCommandGameplayResult
        "", // targetEntityId
        "", // claimTargetAggregate
        effectKey, // effectKey
        "", // failureCode
        "", // payloadKind
        "", // originSourceKind
        "", // originSourceState
        "", // eventType
        "", // scriptEventId
        "", // resultMessage
        null, // requiresSoloTick
        "", // queueSourceKind
        "", // queueSourceState
        0L, // queueSourceOrdinal
        0L, // queueSourceDueTickId
        0L, // queueSourceDueAtMs
        lateResultPolicy, // lateResultPolicy
        "", // claimedTickBatchId
        "", // automationDispatchId
        "", // commandId
        PageRequest.of(0, 20));
  }

  private void insertGameplayCommand(
      String commandId,
      long tenantId,
      long gameInstanceId,
      String regionId,
      long regionEpoch,
      String executionOutcome,
      String remoteFollowupId) {
    dsl.execute(
        """
        INSERT INTO gameplay_command
            (command_id, tenant_id, game_instance_id, session_id, command_name,
             sanitized_command_text, requires_solo_tick, execution_outcome,
             gameplay_result, accepted_at, attempt_count, enqueue_seq, region_id,
             region_epoch, remote_followup_id)
        VALUES (?, ?, ?, 0, 'LOOK', 'LOOK', false, ?, 'PENDING',
                TIMESTAMP '2026-06-25 12:00:00', 0,
                nextval('gameplay_command_enqueue_seq_seq'), ?, ?, ?)
        """,
        commandId,
        tenantId,
        gameInstanceId,
        executionOutcome,
        regionId,
        regionEpoch,
        remoteFollowupId);
  }

  private void insertRuntimeRegionStatus(
      long tenantId, long gameInstanceId, String regionId, long regionEpoch) {
    dsl.execute(
        """
        INSERT INTO runtime_region_status
            (tenant_id, game_instance_id, region_epoch, executor_fence, owner_service,
             owner_instance_id, paused, updated_at, last_committed_tick_id, region_id)
        VALUES (?, ?, ?, 'fence-sibling', 'game-session-service', 'runtime-sibling', false,
                TIMESTAMP '2026-06-25 12:00:00', 0, ?)
        """,
        tenantId,
        gameInstanceId,
        regionEpoch,
        regionId);
  }

  private java.util.List<RemoteCommandCoordinator> findForLatestResult(
      String latestResultOutcome, String latestResultErrorCode) {
    return coordinatorRepository.findForControlPlane(
        1L, // tenantId
        null, // originGameInstanceId
        "", // originRegionId
        0L, // originRegionEpoch
        null, // targetGameInstanceId
        "", // targetRegionId
        0L, // targetRegionEpoch
        "", // currentOriginRuntimeRegionId
        0L, // currentOriginRuntimeRegionEpoch
        null, // currentOriginRuntimeGameInstanceId
        "", // currentTargetRuntimeRegionId
        0L, // currentTargetRuntimeRegionEpoch
        null, // currentTargetRuntimeGameInstanceId
        "", // state
        "", // followupId
        "", // scriptId
        "", // pluginId
        "", // scriptPatchVersion
        "", // pluginVersionId
        "", // playableStateScope
        "", // worldSlug
        "", // realmSlug
        null, // pointerVersion
        "", // targetEntityId
        "", // claimTargetAggregate
        "", // effectKey
        "", // payloadKind
        "", // originSourceKind
        "", // originSourceState
        "", // automationWorkItemId
        "", // eventType
        "", // scriptEventId
        "", // lateResultPolicy
        "", // executionOutcome
        "", // gameplayResult
        "", // followupStatus
        "", // followupClaimedTickBatchId
        null, // followupRequiresSoloTick
        "", // followupQueueSourceKind
        "", // followupQueueSourceState
        0L, // followupQueueSourceOrdinal
        0L, // followupQueueSourceDueTickId
        0L, // followupQueueSourceDueAtMs
        "", // automationDispatchId
        "", // commandId
        "", // targetCommandId
        "", // targetCommandExecutionOutcome
        "", // targetCommandGameplayResult
        latestResultOutcome,
        latestResultErrorCode,
        PageRequest.of(0, 20));
  }

  private static RemoteCommandCoordinator remoteCoordinator(Instant updatedAt) {
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setCommandId("cmd-1");
    coordinator.setFollowupId("rf-1");
    coordinator.setOriginGameInstanceId(7L);
    coordinator.setOriginRegionId("region-origin");
    coordinator.setOriginRegionEpoch(3L);
    coordinator.setTargetGameInstanceId(9L);
    coordinator.setTargetRegionId("region-target");
    coordinator.setTargetRegionEpoch(4L);
    coordinator.setTargetDueTickId(55L);
    coordinator.setOriginDeadlineRegionEpoch(3L);
    coordinator.setOriginDeadlineTickId(56L);
    coordinator.setState("PENDING_REMOTE");
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    coordinator.setUpdatedAt(updatedAt);
    return coordinator;
  }

  private static RemoteFollowup remoteFollowup(Instant observedAt) {
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId("rf-1");
    followup.setTenantId(1L);
    followup.setOriginGameInstanceId(7L);
    followup.setOriginRegionId("region-origin");
    followup.setOriginRegionEpoch(3L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-target");
    followup.setTargetRegionEpoch(4L);
    followup.setDueTickId(55L);
    followup.setEffectKey("effect-1");
    followup.setClaimTargetAggregate("entity:9");
    followup.setStatus("SCHEDULED");
    followup.setCreatedAt(observedAt);
    followup.setUpdatedAt(observedAt);
    return followup;
  }

  private static RemoteFollowupResult remoteResult(
      String resultId, Instant observedAt, String outcome, String errorCode) {
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setResultId(resultId);
    result.setTenantId(1L);
    result.setCoordinatorId("coord-1");
    result.setFollowupId("rf-1");
    result.setOriginGameInstanceId(7L);
    result.setOriginRegionId("region-origin");
    result.setOriginRegionEpoch(3L);
    result.setTargetGameInstanceId(9L);
    result.setTargetRegionId("region-target");
    result.setTargetRegionEpoch(4L);
    result.setOutcome(outcome);
    result.setResultErrorCode(errorCode);
    result.setObservedAt(observedAt);
    return result;
  }
}
