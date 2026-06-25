package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
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
        "TRUNCATE TABLE remote_followup_result, remote_followup, remote_command_coordinator RESTART IDENTITY CASCADE");
  }

  @Test
  void findForControlPlaneUsesIdTieBreakForLatestResultFilters() {
    Instant sharedObservedAt = Instant.parse("2026-06-25T12:00:00Z");

    coordinatorRepository.save(remoteCoordinator(sharedObservedAt));
    followupRepository.save(remoteFollowup(sharedObservedAt));
    resultRepository.save(
        remoteResult("result-older", sharedObservedAt, "REMOTE_APPLIED", "RATE_LIMIT"));
    resultRepository.save(
        remoteResult("result-newer", sharedObservedAt, "REMOTE_REJECTED", "INVALID_TARGET"));

    assertThat(findForLatestResult("REMOTE_APPLIED", "RATE_LIMIT")).isEmpty();
    assertThat(findForLatestResult("REMOTE_REJECTED", "INVALID_TARGET"))
        .extracting(RemoteCommandCoordinator::getCoordinatorId)
        .containsExactly("coord-1");
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
