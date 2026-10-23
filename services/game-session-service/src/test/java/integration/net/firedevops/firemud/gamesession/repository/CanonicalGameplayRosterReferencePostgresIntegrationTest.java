package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient.VerifiedRecovery;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual owner SQL and migration proof; synthetic target bytes do not prove deployed publication.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayRosterReferencePostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DriverManagerDataSource dataSource;
  private DSLContext dsl;
  private TransactionTemplate transaction;
  private CanonicalGameplayRosterReferenceRepository repository;

  @BeforeEach
  void upgradeActualCanonicalHistory() {
    schema = "gs_roster_" + UUID.randomUUID().toString().replace("-", "");
    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    migrate("1013");
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var before = history();
    migrate("1015");
    assertThat(history().subList(0, before.size())).containsExactlyElementsOf(before);
    assertThat(history()).hasSize(before.size() + 1);
    assertThat(history().get(history().size() - 1))
        .contains("1015", "V1015__canonical_roster_reference_owner.sql");
    var beforeObservation = history();
    migrate("1017");
    assertThat(history().subList(0, beforeObservation.size()))
        .containsExactlyElementsOf(beforeObservation);
    assertThat(history().get(history().size() - 1))
        .contains("1017", "V1017__canonical_roster_retirement_observation.sql");
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    repository =
        new CanonicalGameplayRosterReferenceRepository(
            dsl,
            new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy(
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofSeconds(1),
                8));
  }

  @AfterEach
  void dropOwnedSchema() {
    try (var admin =
        DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void originalIntentAndReceiptReplayWithoutMutationAndRemainProtected() {
    var request = request();
    var original = transaction.execute(ignored -> repository.begin(request));
    assertThat(original.getCanonicalRosterReceipt()).isEmpty();
    var receipt = receipt(request);
    var recorded = transaction.execute(ignored -> repository.record(request, receipt));
    var retained = retainedRow(request);
    assertThat(
            transaction
                .<net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse>
                    execute(ignored -> repository.begin(request)))
        .isEqualTo(recorded);
    assertThat(
            transaction
                .<net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse>
                    execute(ignored -> repository.record(request, receipt)))
        .isEqualTo(recorded);
    assertThat(retainedRow(request)).isEqualTo(retained);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.begin(
                            request.toBuilder()
                                .setSessionUuid(UUID.randomUUID().toString())
                                .build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.record(
                            request,
                            receipt.toBuilder()
                                .setSnapshotUuid(UUID.randomUUID().toString())
                                .build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_session_canonical_roster_selection"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("TRUNCATE game_session_canonical_roster_selection"))
        .isInstanceOf(DataAccessException.class);
    assertThat(retainedRow(request)).isEqualTo(retained);
  }

  @Test
  void existingTargetFenceKeepsItsConfiguredHorizonsAfterPolicyChanges() throws Exception {
    var originalPolicy = policy(50L, 20L, 25L);
    repository = repository(originalPolicy);
    var request = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(
              request,
              net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput
                  .newBuilder()
                  .setSchemaVersion(1)
                  .build());
          repository.completeSelection(
              request, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED);
        });

    Thread.sleep(80L);
    var changedPolicy = policy(2_000L, 1_000L, 1_500L);
    repository = repository(changedPolicy);

    assertThatThrownBy(() -> transaction.execute(ignored -> repository.begin(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REPLAY_WINDOW_CLOSED");

    var sameTargetNewOperation =
        request.toBuilder()
            .setOperationUuid(UUID.randomUUID().toString())
            .setSessionUuid(UUID.randomUUID().toString())
            .build();
    transaction.execute(ignored -> repository.begin(sameTargetNewOperation));
    assertThat(fencePolicy(request)).containsExactly(50L, 25L, "ACTIVE");

    var distinctTarget = request();
    transaction.execute(ignored -> repository.begin(distinctTarget));
    assertThat(fencePolicy(distinctTarget)).containsExactly(2_000L, 1_500L, "ACTIVE");
  }

  @Test
  void selectionInputChosenActorAndAssignmentResultCannotChangeWithinOriginalOperation() {
    var request = request();
    UUID menu = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    var input =
        net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
            .setSchemaVersion(1)
            .setSelector("1")
            .setMenuSnapshotUuid(menu.toString())
            .setMenuSnapshotDigest("d".repeat(64))
            .build();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, input);
        });
    var roster =
        receipt(request).toBuilder()
            .setSnapshotUuid(menu.toString())
            .addActors(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
                    .setCharacterUuid(first.toString())
                    .setDisplayName("First")
                    .setActorKind(
                        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                            .CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
            .addActors(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
                    .setCharacterUuid(second.toString())
                    .setDisplayName("Second")
                    .setActorKind(
                        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                            .CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
            .build();
    transaction.executeWithoutResult(ignored -> repository.record(request, roster));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.reserveSelectionInput(
                            request, input.toBuilder().setSelector("2").build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
    transaction.executeWithoutResult(ignored -> repository.selectOriginal(request, first));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.selectOriginal(request, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
    var assignment =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse
            .newBuilder()
            .setRequestUuid(request.getOperationUuid())
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setSelectedCharacterUuid(first.toString())
            .setTarget(roster.getTarget())
            .setSnapshot(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference
                    .newBuilder()
                    .setSnapshotUuid(roster.getSnapshotUuid())
                    .setSnapshotDigest(roster.getSnapshotDigest()))
            .setAssignmentUuid(UUID.randomUUID().toString())
            .setIntentDigest("e".repeat(64))
            .build();
    transaction.executeWithoutResult(
        ignored -> repository.recordSelectedAssignment(request, assignment));
    var retained = retainedRow(request);
    transaction.executeWithoutResult(
        ignored -> {
          repository.reserveSelectionInput(request, input);
          repository.selectOriginal(request, first);
          repository.recordSelectedAssignment(request, assignment);
        });
    assertThat(retainedRow(request)).isEqualTo(retained);
    assertThat(
            CanonicalGameplayRosterReferenceEvidence.selectedAssignmentReceipt(
                repository.read(request)))
        .isEqualTo(assignment);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordSelectedAssignment(
                            request,
                            assignment.toBuilder()
                                .setAssignmentUuid(UUID.randomUUID().toString())
                                .build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
  }

  @Test
  void actualOutcomeAndIndependentCurrentOwnerSupersessionSealEveryOriginalAtOneTargetFence()
      throws Exception {
    var request = request();
    var input =
        net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
            .setSchemaVersion(1)
            .build();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, input);
          repository.markRosterReadStarted(request);
          repository.record(request, receipt(request));
        });
    var routeReader =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class);
    var current = current(request);
    org.mockito.Mockito.when(
            routeReader.readCurrentTarget(
                UUID.fromString(
                    CanonicalGameplayRosterReferenceEvidence.target(request).getTenantUuid())))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return current;
            });
    // Controlled admission source here; the existing admission-owner integration fixture separately
    // exercises the real committed reader and actual pointer/attempt/catalog row locks.
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var snapshot = currentSnapshot(request);
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return snapshot;
            });
    org.mockito.Mockito.when(admissions.lockExactCurrentOpenSnapshot(snapshot))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              return snapshot;
            });
    var owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            repository,
            transaction,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routeReader,
            admissions,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient
                    .class));
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current)))
        .isFalse();
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenReturn(
            new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(
                snapshot.ownerProof(), "e".repeat(64)));
    assertThatThrownBy(() -> owner.retireTarget(request))
        .hasMessageContaining("CURRENT_SOURCE_CONFLICT");
    org.mockito.Mockito.verify(admissions, org.mockito.Mockito.never())
        .lockExactCurrentOpenSnapshot(snapshot);
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenReturn(snapshot);
    transaction.executeWithoutResult(
        ignored ->
            repository.completeSelection(
                request, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED));
    Thread.sleep(5L);
    org.mockito.Mockito.doThrow(
            new CanonicalInitialAdmissionRepository
                .CanonicalInitialAdmissionReconciliationRequiredException(
                "controlled pointer changed after qualification"))
        .when(admissions)
        .lockExactCurrentOpenSnapshot(snapshot);
    assertThatThrownBy(() -> owner.retireTarget(request))
        .hasMessageContaining("changed after qualification");
    assertThat(repository.read(request).getRetirementUuid()).isEmpty();
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              return snapshot;
            })
        .when(admissions)
        .lockExactCurrentOpenSnapshot(snapshot);
    assertThat(owner.retireTarget(request)).isTrue();
    var retired = repository.read(request);
    assertThat(CanonicalGameplayRosterReferenceEvidence.requireRetirement(retired))
        .isEqualTo(retired);
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.begin(request)))
        .hasMessageContaining("RETIRED");
    var newOriginal = request.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.begin(newOriginal)))
        .hasMessageContaining("RETIRED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.markRosterReadStarted(request)))
        .hasMessageContaining("RETIRED");
  }

  @Test
  void newAndRecoveredReferencesSerializeAgainstRetirementOnTheSameTargetFence() throws Exception {
    var request = request();
    var input =
        net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
            .setSchemaVersion(1)
            .build();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, input);
          repository.record(request, receipt(request));
          repository.completeSelection(
              request, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED);
        });
    Thread.sleep(5L);
    CountDownLatch sealed = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    CountDownLatch recoveryStarted = new CountDownLatch(1);
    AtomicInteger retirementPid = new AtomicInteger();
    AtomicInteger recoveryPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var retirement =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        retirementPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        boolean result = repository.sealTargetRetirement(request, current(request));
                        sealed.countDown();
                        try {
                          if (!commit.await(15L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("fixture timeout");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return result;
                      }));
      assertThat(sealed.await(5L, TimeUnit.SECONDS)).isTrue();
      var recovery =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        recoveryPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        recoveryStarted.countDown();
                        return repository.begin(
                            request.toBuilder()
                                .setOperationUuid(UUID.randomUUID().toString())
                                .build());
                      }));
      assertThat(recoveryStarted.await(5L, TimeUnit.SECONDS)).isTrue();
      awaitBackendBlockedBy(dsl, recoveryPid.get(), retirementPid.get());
      commit.countDown();
      assertThat(retirement.get(5L, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> recovery.get(5L, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
    } finally {
      commit.countDown();
    }
  }

  private static void awaitBackendBlockedBy(DSLContext observer, int waitingPid, int holderPid)
      throws InterruptedException {
    assertThat(waitingPid).isPositive().isNotEqualTo(holderPid);
    assertThat(holderPid).isPositive();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean blocked = false;
    while (System.nanoTime() < deadline) {
      blocked =
          Boolean.TRUE.equals(
              observer
                  .fetchSingle("SELECT ? = ANY(pg_blocking_pids(?))", holderPid, waitingPid)
                  .get(0, Boolean.class));
      if (blocked) break;
      TimeUnit.MILLISECONDS.sleep(10L);
    }
    assertThat(blocked)
        .as("PostgreSQL backend %s waits on exact holder %s", waitingPid, holderPid)
        .isTrue();
  }

  private static CanonicalInitialAdmissionRepository.CurrentOpenSnapshot currentSnapshot(
      GetCanonicalGameplayRosterReferenceRequest request) {
    var target = CanonicalGameplayRosterReferenceEvidence.target(request);
    var proof =
        new net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof(
            net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome
                .COMMITTED,
            "controlled-current-owner",
            "1".repeat(64),
            request.getTargetNamespace(),
            UUID.fromString(target.getTenantUuid()),
            target.getWorldSlug(),
            UUID.fromString(target.getRealmUuid()),
            UUID.fromString(target.getPlayableStateNamespaceUuid()),
            "SHARED",
            UUID.fromString(target.getGameInstanceUuid()),
            UUID.fromString(target.getCanonicalVersionUuid()),
            target.getActiveWorldEpoch() + 1L,
            target.getCatalogRevision() + 1L,
            net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind
                .EXPECT_CLOSED,
            target.getPointerVersion(),
            UUID.fromString(request.getOperationUuid()),
            UUID.fromString(request.getSessionUuid()),
            "sha256:" + "2".repeat(64),
            target.getPointerVersion() + 1L,
            1L,
            "sha256:" + "3".repeat(64),
            false,
            java.time.Instant.parse("2026-10-01T00:00:00Z"));
    return new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(proof, "f".repeat(64));
  }

  private static net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget current(
      GetCanonicalGameplayRosterReferenceRequest request) {
    var target = CanonicalGameplayRosterReferenceEvidence.target(request);
    var snapshot = currentSnapshot(request);
    var p = snapshot.ownerProof();
    return new net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget(
        p.targetNamespace(),
        "controlled-tenant",
        p.canonicalTenantId(),
        p.worldSlug(),
        "Controlled World",
        p.realmId(),
        target.getRealmSlug(),
        "Controlled Realm",
        1L,
        1L,
        p.playableStateNamespaceId(),
        p.playableStateScope(),
        p.canonicalGameInstanceId(),
        p.canonicalVersionId(),
        1L,
        p.expectedCatalogRevision(),
        p.committedPointerVersion(),
        snapshot.admissionPointerSnapshotDigest(),
        p.activeLifecycleEpoch(),
        p.initialAdmissionRequestId(),
        p.requestDigest(),
        p.originKind(),
        p.expectedPriorPointerVersion(),
        p.holdId(),
        p.holdFence(),
        p.holdBindingDigest(),
        p.auditEventId(),
        p.proofDigest(),
        p.outcome(),
        p.positiveDurableAbort(),
        p.terminalAt(),
        "PRESEEDED_ONLY");
  }

  @Test
  void missingOriginalAndRolledBackReservationNeverSupplyPositiveEvidence() {
    var request = request();
    assertThatThrownBy(() -> repository.read(request)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.begin(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TRANSACTION_REQUIRED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      repository.begin(request);
                      throw new IllegalStateException("controlled rollback");
                    }))
        .hasMessage("controlled rollback");
    assertThatThrownBy(() -> repository.read(request)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void concurrentSameOriginalReservesOneFenceAndCannotReplaceItsIntent() throws Exception {
    var request = request();
    CountDownLatch reserved = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    CountDownLatch replayStarted = new CountDownLatch(1);
    AtomicInteger firstPid = new AtomicInteger();
    AtomicInteger replayPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        firstPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        var value = repository.begin(request);
                        reserved.countDown();
                        try {
                          if (!finish.await(15L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("fixture timeout");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return value;
                      }));
      assertThat(reserved.await(5L, TimeUnit.SECONDS)).isTrue();
      var replay =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        replayPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        replayStarted.countDown();
                        return repository.begin(request);
                      }));
      assertThat(replayStarted.await(5L, TimeUnit.SECONDS)).isTrue();
      awaitBackendBlockedBy(dsl, replayPid.get(), firstPid.get());
      finish.countDown();
      assertThat(replay.get(5L, TimeUnit.SECONDS)).isEqualTo(first.get(5L, TimeUnit.SECONDS));
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_roster_selection")
                  .get(0, Long.class))
          .isEqualTo(1L);
    } finally {
      finish.countDown();
    }
  }

  @Test
  void interruptedRosterReadRecoversExactReceiptAndFencesLateWritersBeforeFiniteRetirement()
      throws Exception {
    var request = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, selectionInput());
          repository.markRosterReadStarted(request);
        });
    Thread.sleep(5L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(request, current(request)))
            .getFirst();
    var marked = repository.read(request);
    assertThat(marked.getRecoveryUuid()).isEqualTo(recovery.getRecoveryUuid());
    assertThat(marked.getTerminalOutcome()).isEmpty();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(request))))
        .containsExactly(recovery);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isFalse();
    var originalReceipt = receipt(request);
    assertThatThrownBy(
            () -> transaction.execute(ignored -> repository.record(request, originalReceipt)))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.reserveSelectionInput(request, selectionInput())))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.markRosterReadStarted(request)))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.completeSelection(
                            request,
                            CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED)))
        .hasMessageContaining("RECOVERY_FENCED");
    transaction.executeWithoutResult(ignored -> repository.completeUnissuedDenied(request));
    assertThat(repository.read(request).getTerminalOutcome()).isEmpty();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_selection SET selection_input = ? WHERE operation_uuid = ?",
                    selectionInput().toBuilder().setSelector("1").build().toByteArray(),
                    UUID.fromString(request.getOperationUuid())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_selection SET recovery_uuid = ? WHERE operation_uuid = ?",
                    UUID.randomUUID(),
                    UUID.fromString(request.getOperationUuid())))
        .isInstanceOf(DataAccessException.class);
    var response = recoveryResponse(recovery, originalReceipt);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.finishRecovery(
                            verifiedRecovery(
                                recovery,
                                response.toBuilder()
                                    .setTargetFenceUuid(UUID.randomUUID().toString())
                                    .build()))))
        .hasMessageContaining("FENCE_CONFLICT");
    var wrongMarker = recovery.toBuilder().setRecoveryUuid(UUID.randomUUID().toString()).build();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.finishRecovery(
                            verifiedRecovery(
                                wrongMarker,
                                response.toBuilder().setRequest(wrongMarker).build()))))
        .hasMessageContaining("recovery fence differs");
    transaction.executeWithoutResult(
        ignored -> repository.finishRecovery(verifiedRecovery(recovery, response)));
    assertThat(repository.read(request).getCanonicalRosterReceipt())
        .isEqualTo(originalReceipt.toByteString());
    assertThat(repository.read(request).getTerminalOutcome()).isEqualTo("DENIED");
    var retained = retainedRow(request);
    transaction.executeWithoutResult(
        ignored -> repository.finishRecovery(verifiedRecovery(recovery, response)));
    assertThat(retainedRow(request)).isEqualTo(retained);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.finishRecovery(
                            verifiedRecovery(
                                recovery,
                                response.toBuilder()
                                    .setAdmissionUuid(UUID.randomUUID().toString())
                                    .build()))))
        .hasMessageContaining("RECEIPT_CONFLICT");
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isTrue();
    assertThat(CanonicalGameplayRosterReferenceEvidence.requireRetirement(repository.read(request)))
        .isEqualTo(repository.read(request));
  }

  @Test
  void frozenActorWithoutAssignmentReceiptClosesOnlyWithExactRecoveredRoster() throws Exception {
    var request = request();
    var actor = UUID.randomUUID();
    var roster =
        receipt(request).toBuilder()
            .addActors(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
                    .setCharacterUuid(actor.toString())
                    .setDisplayName("Chosen")
                    .setActorKind(
                        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                            .CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
            .build();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, selectionInput());
          repository.markRosterReadStarted(request);
          repository.record(request, roster);
          repository.selectOriginal(request, actor);
        });
    Thread.sleep(5L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(request, current(request)))
            .getFirst();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.selectOriginal(request, actor)))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordSelectedAssignment(
                            request,
                            net.firedevops.firemud.entitymanagement.v1
                                .CanonicalGameplayRosterSelectedAssignmentResponse
                                .getDefaultInstance())))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.finishRecovery(
                            verifiedRecovery(recovery, recoveryResponse(recovery, null)))))
        .hasMessageContaining("ROSTER_CONFLICT");
    transaction.executeWithoutResult(
        ignored ->
            repository.finishRecovery(
                verifiedRecovery(recovery, recoveryResponse(recovery, roster))));
    var closed = repository.read(request);
    assertThat(closed.getSelectedCharacterUuid()).isEqualTo(actor.toString());
    assertThat(closed.getCanonicalSelectedAssignmentReceipt()).isEmpty();
    assertThat(closed.getTerminalOutcome()).isEqualTo("DENIED");
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isTrue();
  }

  @Test
  void positiveNoCaptureRecoveryAllowsClosureWithoutInventingRosterOrSelection() throws Exception {
    var request = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.markRosterReadStarted(request);
        });
    Thread.sleep(5L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(request, current(request)))
            .getFirst();
    transaction.executeWithoutResult(
        ignored ->
            repository.finishRecovery(
                verifiedRecovery(recovery, recoveryResponse(recovery, null))));
    var result = repository.read(request);
    assertThat(result.getCanonicalRosterReceipt()).isEmpty();
    assertThat(result.getSelectedCharacterUuid()).isEmpty();
    assertThat(result.getTerminalOutcome()).isEqualTo("DENIED");
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isTrue();
  }

  @Test
  void storedReplayHorizonAndExactSupersessionAreRequiredBeforeRecovery() throws Exception {
    repository = repository(policy(6000L, 5000L, 5000L));
    var request = request();
    transaction.executeWithoutResult(ignored -> repository.begin(request));
    // Later lowered configuration must not shorten the already retained target's horizon.
    repository = repository(policy(1L, 1L, 1L));
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(request))))
        .isEmpty();
    Thread.sleep(5500L);
    var oldTarget = CanonicalGameplayRosterReferenceEvidence.target(request);
    var samePointer =
        request.toBuilder()
            .setCanonicalTarget(
                oldTarget.toBuilder()
                    .setPointerVersion(oldTarget.getPointerVersion() - 1L)
                    .build()
                    .toByteString())
            .build();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(samePointer))))
        .isEmpty();
    var wrongNamespace = request.toBuilder().setTargetNamespace("different").build();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(wrongNamespace))))
        .isEmpty();
    var regressedCatalog =
        request.toBuilder()
            .setCanonicalTarget(
                oldTarget.toBuilder()
                    .setCatalogRevision(oldTarget.getCatalogRevision() - 2L)
                    .build()
                    .toByteString())
            .build();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(regressedCatalog))))
        .isEmpty();
    var regressedWorld =
        request.toBuilder()
            .setCanonicalTarget(
                oldTarget.toBuilder()
                    .setActiveWorldEpoch(oldTarget.getActiveWorldEpoch() - 2L)
                    .build()
                    .toByteString())
            .build();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(regressedWorld))))
        .isEmpty();
    assertThat(repository.read(request).getRecoveryUuid()).isEmpty();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(request))))
        .hasSize(1);
  }

  @Test
  void ownerRetriesUnknownEntityOutsideSqlAndRetainsPositiveClosureThroughLocalMinimum()
      throws Exception {
    repository = repository(policy(5000L, 1L, 1L));
    var request = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.markRosterReadStarted(request);
        });
    Thread.sleep(5L);
    var routes =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class);
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var current = current(request);
    var snapshot = currentSnapshot(request);
    org.mockito.Mockito.when(routes.readCurrentTarget(current.canonicalTenantId()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return current;
            });
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return snapshot;
            });
    org.mockito.Mockito.when(admissions.lockExactCurrentOpenSnapshot(snapshot))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              return snapshot;
            });
    var client = org.mockito.Mockito.mock(CanonicalGameplayRosterRecoveryClient.class);
    org.mockito.Mockito.when(client.read(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isSynchronizationActive())
                  .isFalse();
              throw io.grpc.Status.UNAVAILABLE.asRuntimeException();
            });
    var owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            repository,
            transaction,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routes,
            admissions,
            client);
    assertThat(owner.retireTarget(request)).isFalse();
    var marker = repository.read(request).getRecoveryUuid();
    assertThat(marker).isNotEmpty();
    assertThat(repository.read(request).getTerminalOutcome()).isEmpty();
    assertThatThrownBy(
            () -> transaction.execute(ignored -> repository.record(request, receipt(request))))
        .hasMessageContaining("RECOVERY_FENCED");
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isSynchronizationActive())
                  .isFalse();
              CanonicalGameplayRosterRecoveryRequest retry = call.getArgument(0);
              assertThat(retry.getRecoveryUuid()).isEqualTo(marker);
              return verifiedRecovery(retry, recoveryResponse(retry, null));
            })
        .when(client)
        .read(org.mockito.ArgumentMatchers.any());
    assertThat(owner.retireTarget(request)).isFalse();
    assertThat(repository.read(request).getTerminalOutcome()).isEqualTo("DENIED");
    assertThat(repository.read(request).getRetirementUuid()).isEmpty();
    Thread.sleep(5500L);
    assertThat(owner.retireTarget(request)).isTrue();
    org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2))
        .read(org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(() -> transaction.execute(ignored -> owner.retireTarget(request)))
        .hasMessageContaining("NO_AMBIENT_TRANSACTION");
  }

  @Test
  void recoveryPageUsesConfiguredBoundAndNeverReplacesPendingRecoveryMarkers() throws Exception {
    var request = request();
    transaction.executeWithoutResult(
        ignored -> {
          for (int i = 0; i < 9; i++) {
            repository.begin(
                request.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build());
          }
        });
    Thread.sleep(5L);
    var page =
        transaction.execute(ignored -> repository.beginRecoveries(request, current(request)));
    assertThat(page).hasSize(8);
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(request))))
        .containsExactlyElementsOf(page);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_selection WHERE recovery_uuid IS NOT NULL")
                .get(0, Long.class))
        .isEqualTo(8L);
  }

  @Test
  void purgedOriginalCannotReturnReservedWhenFreshSourceOrLockedCurrentProofFails()
      throws Exception {
    var published =
        support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture
            .canonicalPublishedRoute();
    var request = publishedRequest(published);
    var candidate = retireOriginal(request);
    Thread.sleep(5L);
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    var sources =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .class);
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var owner = referenceOwner(sources, admissions);
    org.mockito.Mockito.when(
            sources.readCurrent(
                org.mockito.ArgumentMatchers.any(
                    net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                        .Request.class)))
        .thenThrow(
            new net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .StorageUnavailableException("source unavailable"));
    assertThatThrownBy(() -> owner.begin(request)).hasMessageContaining("source unavailable");
    assertNoOriginalOrFence();
    org.mockito.Mockito.doAnswer(
            call -> {
              var source = sourceEvidence(call.getArgument(0), published);
              return new net.firedevops.firemud.common.world
                  .CanonicalGameplayRosterOwnerReadEvidence(
                  source.request(),
                  "0".repeat(64),
                  source.gameSessionOwnerProof(),
                  source.publishedPolicySetEvidence());
            })
        .when(sources)
        .readCurrent(
            org.mockito.ArgumentMatchers.any(
                net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
                    .class));
    assertThatThrownBy(() -> owner.begin(request)).hasMessageContaining("SOURCE_MISMATCH");
    assertNoOriginalOrFence();
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return sourceEvidence(call.getArgument(0), published);
            })
        .when(sources)
        .readCurrent(
            org.mockito.ArgumentMatchers.any(
                net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
                    .class));
    org.mockito.Mockito.when(
            admissions.lockExactCurrentOpenSnapshot(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              CanonicalInitialAdmissionRepository.CurrentOpenSnapshot qualified =
                  call.getArgument(0);
              assertThat(qualified.admissionPointerSnapshotDigest())
                  .isEqualTo(published.route().admissionPointerSnapshotDigest());
              assertThat(qualified.ownerProof().proofDigest())
                  .isEqualTo(published.route().ownerProofDigest());
              assertThat(qualified.ownerProof().holdId()).isEqualTo(published.route().holdId());
              throw new CanonicalInitialAdmissionRepository
                  .CanonicalInitialAdmissionReconciliationRequiredException(
                  "current changed before reservation commit");
            });
    assertThatThrownBy(() -> owner.begin(request)).hasMessageContaining("current changed");
    assertNoOriginalOrFence();
  }

  @Test
  void freshReservationQualifiesOutsideSqlAndLocksThatExactSourceBeforeCommit() {
    var published =
        support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture
            .canonicalPublishedRoute();
    var request = publishedRequest(published);
    var sources =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .class);
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var observed =
        new java.util.concurrent.atomic.AtomicReference<
            net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence>();
    org.mockito.Mockito.when(
            sources.readCurrent(
                org.mockito.ArgumentMatchers.any(
                    net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                        .Request.class)))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertNoOriginalOrFence();
              var source = sourceEvidence(call.getArgument(0), published);
              observed.set(source);
              return source;
            });
    org.mockito.Mockito.when(
            admissions.lockExactCurrentOpenSnapshot(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              CanonicalInitialAdmissionRepository.CurrentOpenSnapshot qualified =
                  call.getArgument(0);
              assertThat(repository.find(request)).isPresent();
              assertThat(qualified.ownerProof().holdBindingDigest())
                  .isEqualTo(
                      observed.get().gameSessionOwnerProof().holdIdentity().holdBindingDigest());
              assertThat(qualified.ownerProof().initialAdmissionRequestId())
                  .isEqualTo(
                      observed
                          .get()
                          .gameSessionOwnerProof()
                          .holdIdentity()
                          .request()
                          .initialAdmissionRequestId());
              assertThat(qualified.admissionPointerSnapshotDigest())
                  .isEqualTo(observed.get().admissionPointerSnapshotDigest());
              return qualified;
            });
    var owner = referenceOwner(sources, admissions);
    assertThat(owner.begin(request).getCanonicalRosterReceipt()).isEmpty();
    assertThat(repository.find(request)).isPresent();
    org.mockito.Mockito.verify(admissions)
        .lockExactCurrentOpenSnapshot(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void purgeBetweenExactLookupAndLockedReplayNeverReservesReplacementRows() throws Exception {
    var request = request();
    var candidate = retireOriginal(request);
    assertThat(repository.find(request)).isPresent();
    Thread.sleep(5L);
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.replay(request)))
        .hasMessageContaining("UNAVAILABLE_OR_RETIRED");
    assertThat(repository.find(request)).isEmpty();
    assertNoOriginalOrFence();
  }

  @Test
  void historicalReceiptReplaySkipsFreshSourceButUnissuedOwnerReadsRequireIt() throws Exception {
    var sources =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .class);
    var owner =
        referenceOwner(
            sources, org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class));
    var request = request();
    transaction.executeWithoutResult(ignored -> repository.begin(request));
    org.mockito.Mockito.when(
            sources.readCurrent(
                org.mockito.ArgumentMatchers.any(
                    net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                        .Request.class)))
        .thenThrow(
            new net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .StaleAuthorityException("target superseded"));
    assertThatThrownBy(() -> owner.read(request)).hasMessageContaining("target superseded");
    var roster = receipt(request);
    transaction.executeWithoutResult(ignored -> repository.record(request, roster));
    assertThat(owner.begin(request).getCanonicalRosterReceipt()).isEqualTo(roster.toByteString());
    assertThat(owner.read(request).getCanonicalRosterReceipt()).isEqualTo(roster.toByteString());
    org.mockito.Mockito.verify(sources, org.mockito.Mockito.times(1))
        .readCurrent(
            org.mockito.ArgumentMatchers.any(
                net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
                    .class));
    var unissued = request.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
    transaction.executeWithoutResult(ignored -> repository.begin(unissued));
    Thread.sleep(5L);
    transaction.execute(ignored -> repository.beginRecoveries(request, current(request)));
    assertThat(owner.read(request).getRecoveryUuid()).isNotEmpty();
    assertThat(owner.read(unissued).getRecoveryUuid()).isNotEmpty();
    assertThat(owner.read(unissued).getCanonicalRosterReceipt()).isEmpty();
    org.mockito.Mockito.verify(sources, org.mockito.Mockito.times(1))
        .readCurrent(
            org.mockito.ArgumentMatchers.any(
                net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
                    .class));
  }

  @Test
  void deniedDispatchedReadsKeepTheirOriginalDenialAndActualRepositoryRecoveryProtection()
      throws Exception {
    for (boolean selectedRead : List.of(false, true)) {
      var published =
          support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture
              .canonicalPublishedRoute();
      var request = publishedRequest(published);
      var actor = UUID.randomUUID();
      var roster =
          receipt(request).toBuilder()
              .addActors(
                  net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor
                      .newBuilder()
                      .setCharacterUuid(actor.toString())
                      .setDisplayName("Selected")
                      .setActorKind(
                          net.firedevops.firemud.entitymanagement.v1
                              .CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
              .build();
      transaction.executeWithoutResult(
          ignored -> {
            repository.begin(request);
            repository.reserveSelectionInput(request, selectionInput());
            repository.markRosterReadStarted(request);
            if (selectedRead) {
              repository.record(request, roster);
              repository.selectOriginal(request, actor);
            }
          });
      var routes =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class);
      var publications =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                  .class);
      var client =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient.class);
      org.mockito.Mockito.when(routes.readCurrentTarget(published.route().canonicalTenantId()))
          .thenReturn(published.route());
      org.mockito.Mockito.when(publications.readCurrent(published.route())).thenReturn(published);
      if (selectedRead) {
        org.mockito.Mockito.when(
                client.listPreseededRoster(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(
                new net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient
                    .PreseededRosterSnapshot(
                    UUID.fromString(request.getCanonicalAccountUuid()),
                    UUID.fromString(roster.getSnapshotUuid()),
                    roster.getSnapshotDigest(),
                    roster.getTarget(),
                    List.of(
                        new net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient
                            .RosterActor(actor, "Selected"))));
        org.mockito.Mockito.when(
                client.readSelectedPreseededAssignment(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException());
      } else {
        org.mockito.Mockito.when(
                client.listPreseededRoster(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException());
      }
      // The terminal write below executes the real owner SQL and its missing-receipt gate.
      org.mockito.Mockito.doAnswer(
              call -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                transaction.executeWithoutResult(
                    ignored -> repository.completeSelection(request, call.getArgument(2)));
                return null;
              })
          .when(client)
          .completeSelection(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());
      var context =
          net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
              .setAccountId(request.getCanonicalAccountUuid())
              .setSessionId(request.getSessionUuid())
              .setRequestId(request.getOperationUuid())
              .setTenantId(published.route().canonicalTenantId().toString())
              .setRealmId(published.route().realmId().toString())
              .setPlayableStateNamespaceId(published.route().playableStateNamespaceId().toString())
              .setPlayableStateScope("SHARED")
              .setGameInstanceId(published.route().canonicalGameInstanceId().toString())
              .build();
      var service =
          new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService(
              routes, publications, client);
      assertThat(service.select(context, null, null))
          .isEqualTo(
              new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService
                  .Denied(
                  net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService
                      .Denial.ROUTE_CHANGED));
      org.mockito.Mockito.verify(client)
          .completeSelection(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.eq(
                  CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED));
      assertThat(repository.read(request).getTerminalOutcome()).isEmpty();
      assertThat(
              transaction.<Boolean>execute(
                  ignored -> repository.sealTargetRetirement(request, current(request))))
          .isFalse();
    }
  }

  @Test
  void allRequiredObservationsAndStoredHorizonProtectOriginalsAndLostReplyReplaysAfterPurge()
      throws Exception {
    var first = request();
    var second =
        first.toBuilder()
            .setOperationUuid(UUID.randomUUID().toString())
            .setSessionUuid(UUID.randomUUID().toString())
            .build();
    var roster = receipt(first);
    for (var original : List.of(first, second)) {
      transaction.executeWithoutResult(
          ignored -> {
            repository.begin(original);
            repository.reserveSelectionInput(original, selectionInput());
            repository.markRosterReadStarted(original);
            repository.record(original, roster);
            repository.completeSelection(
                original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.MENU);
          });
    }
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(first, current(first))))
        .isTrue();
    var candidate =
        new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
            UUID.fromString(repository.read(first).getTargetFenceUuid()), first, "RETIRED");
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_retirement_transfers")
                .get(0, Long.class))
        .isEqualTo(2L);
    Thread.sleep(5L);
    // Entity may be unavailable indefinitely: a passed target horizon alone releases nothing.
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isFalse();
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_session_canonical_roster_selection"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_session_canonical_roster_target_fences"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM game_session_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    var firstRequest = acknowledgement(first, repository.read(first), UUID.randomUUID(), 250L);
    var secondRequest = acknowledgement(second, repository.read(first), UUID.randomUUID(), 250L);
    var unrecordedConfirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            firstRequest,
            CanonicalGameplayRosterRetirementEvidence.response(
                firstRequest, UUID.randomUUID(), 250L));
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.confirmRetirementAcknowledgement(
                            unrecordedConfirmation, entityPrincipal())))
        .hasMessageContaining("ACKNOWLEDGEMENT_CONFLICT");
    var firstAck =
        transaction.execute(
            ignored -> repository.acknowledgeRetirement(firstRequest, entityPrincipal()));
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isFalse();
    var beforeReplay = transferRow(first);
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementAcknowledgementResponse>execute(
                ignored -> repository.acknowledgeRetirement(firstRequest, entityPrincipal())))
        .isEqualTo(firstAck);
    assertThat(transferRow(first)).isEqualTo(beforeReplay);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.acknowledgeRetirement(
                            firstRequest.toBuilder()
                                .setAdmissionUuid(UUID.randomUUID().toString())
                                .build(),
                            entityPrincipal())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.acknowledgeRetirement(
                            firstRequest,
                            "spiffe://firemud/ns/other/sa/entity-management-service")))
        .isInstanceOf(IllegalArgumentException.class);
    var secondAck =
        transaction.execute(
            ignored -> repository.acknowledgeRetirement(secondRequest, entityPrincipal()));
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    assertNoOriginalOrFence();
    // Crash/lost reply: the original rows are gone, but the actual first response is unchanged.
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementAcknowledgementResponse>execute(
                ignored -> repository.acknowledgeRetirement(firstRequest, entityPrincipal())))
        .isEqualTo(firstAck);
    Thread.sleep(275L);
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(8)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM game_session_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(firstRequest, firstAck);
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementConfirmationResponse>execute(
                ignored ->
                    repository.confirmRetirementAcknowledgement(confirmation, entityPrincipal())))
        .isEqualTo(CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation));
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(8)))
        .isZero();
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.confirmRetirementAcknowledgement(
                            confirmation.toBuilder()
                                .setAcknowledgement(
                                    firstAck.toBuilder()
                                        .setAcknowledgementUuid(UUID.randomUUID().toString()))
                                .build(),
                            entityPrincipal())))
        .isInstanceOf(IllegalArgumentException.class);
    Thread.sleep(275L);
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(1)))
        .isEqualTo(1);
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementConfirmationResponse>execute(
                ignored ->
                    repository.confirmRetirementAcknowledgement(confirmation, entityPrincipal())))
        .isEqualTo(CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation));
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.acknowledgeRetirement(firstRequest, entityPrincipal())))
        .isInstanceOf(
            CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException.class);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_retirement_transfers")
                .get(0, Long.class))
        .isEqualTo(1L);
    transaction.executeWithoutResult(
        ignored ->
            repository.confirmRetirementAcknowledgement(
                CanonicalGameplayRosterRetirementEvidence.confirmation(secondRequest, secondAck),
                entityPrincipal()));
  }

  @Test
  void confirmationWithoutInventoryIsOnlyANoopAndCannotCreateFirstAckOrReleaseTarget()
      throws Exception {
    var original = request();
    var candidate = retireOriginal(original); // Proven never issued; no Entity transfer required.
    var request = acknowledgement(original, repository.read(original), UUID.randomUUID(), 1L);
    var unrecorded =
        CanonicalGameplayRosterRetirementEvidence.response(request, UUID.randomUUID(), 1L);
    var confirmation = CanonicalGameplayRosterRetirementEvidence.confirmation(request, unrecorded);
    transaction.executeWithoutResult(
        ignored -> repository.confirmRetirementAcknowledgement(confirmation, entityPrincipal()));
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_retirement_transfers")
                .get(0, Long.class))
        .isZero();
    assertThat(repository.read(original).getTargetFenceUuid())
        .isEqualTo(candidate.fenceUuid().toString());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.acknowledgeRetirement(request, entityPrincipal())))
        .isInstanceOf(
            CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException.class);
  }

  @Test
  void storedMinimumStillGatesPurgeAndConfirmationBeforePurgeCannotShortenReceiptHorizon()
      throws Exception {
    repository = repository(policy(400L, 1L, 1L));
    var original = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.record(original, receipt(original));
          repository.completeSelection(
              original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.MENU);
        });
    Thread.sleep(450L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(original, current(original))))
        .isTrue();
    repository = repository(policy(1L, 1L, 1L));
    var request = acknowledgement(original, repository.read(original), UUID.randomUUID(), 1L);
    var ack =
        transaction.execute(
            ignored -> repository.acknowledgeRetirement(request, entityPrincipal()));
    assertThat(ack.getMinimumRetentionMillis()).isEqualTo(400L);
    var confirmation = CanonicalGameplayRosterRetirementEvidence.confirmation(request, ack);
    transaction.executeWithoutResult(
        ignored -> repository.confirmRetirementAcknowledgement(confirmation, entityPrincipal()));
    var candidate =
        new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
            UUID.fromString(repository.read(original).getTargetFenceUuid()), original, "RETIRED");
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isFalse();
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_session_canonical_roster_selection"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_retirement_transfers SET target_purged_at = clock_timestamp()"))
        .isInstanceOf(DataAccessException.class);
    Thread.sleep(450L);
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(8)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM game_session_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    Thread.sleep(450L);
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(8)))
        .isEqualTo(1);
  }

  @Test
  void recoveryObservationChecksKnownNonceAndExactRetirementBeforeFirstAck() throws Exception {
    var original = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.markRosterReadStarted(original);
        });
    Thread.sleep(5L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(original, current(original)))
            .getFirst();
    var closed = recoveryResponse(recovery, null);
    transaction.executeWithoutResult(
        ignored -> repository.finishRecovery(verifiedRecovery(recovery, closed)));
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(original, current(original))))
        .isTrue();
    var proof = repository.read(original);
    var wrongNonce = acknowledgement(original, proof, UUID.randomUUID(), 1L);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.acknowledgeRetirement(wrongNonce, entityPrincipal())))
        .hasMessageContaining("ADMISSION_CONFLICT");
    var exact = wrongNonce.toBuilder().setAdmissionUuid(closed.getAdmissionUuid()).build();
    var wrongFenceProof =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            proof.toBuilder().setTargetFenceUuid(UUID.randomUUID().toString()).build());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.acknowledgeRetirement(
                            exact.toBuilder().setObservedRetirement(wrongFenceProof).build(),
                            entityPrincipal())))
        .hasMessageContaining("OBSERVATION_CONFLICT");
    var absent = original.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
    var absentProof =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            proof.toBuilder().setRequest(absent).build());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.acknowledgeRetirement(
                            acknowledgement(absent, absentProof, UUID.randomUUID(), 1L),
                            entityPrincipal())))
        .isInstanceOf(
            CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException.class);
    var ack =
        transaction.execute(ignored -> repository.acknowledgeRetirement(exact, entityPrincipal()));
    assertThat(ack.getMinimumRetentionMillis()).isEqualTo(1L);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_retirement_transfers SET admission_uuid = ?",
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void firstAckSerializesWithPurgeOnTheRetiredFence() throws Exception {
    var original = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.record(original, receipt(original));
          repository.completeSelection(
              original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.MENU);
        });
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(original, current(original))))
        .isTrue();
    Thread.sleep(5L);
    var request = acknowledgement(original, repository.read(original), UUID.randomUUID(), 1L);
    var candidate =
        new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
            UUID.fromString(repository.read(original).getTargetFenceUuid()), original, "RETIRED");
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var purgeStarted = new CountDownLatch(1);
    var acknowledgementPid = new AtomicInteger();
    var purgePid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstAck =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        acknowledgementPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        var ack = repository.acknowledgeRetirement(request, entityPrincipal());
                        locked.countDown();
                        try {
                          assertThat(release.await(15L, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return ack;
                      }));
      try {
        assertThat(locked.await(5L, TimeUnit.SECONDS)).isTrue();
        var purged =
            executor.submit(
                () ->
                    transaction.execute(
                        ignored -> {
                          purgePid.set(
                              java.util.Objects.requireNonNull(
                                  dsl.fetchSingle("SELECT pg_backend_pid()")
                                      .get(0, Integer.class)));
                          purgeStarted.countDown();
                          return repository.purgeRetiredTarget(candidate);
                        }));
        assertThat(purgeStarted.await(5L, TimeUnit.SECONDS)).isTrue();
        awaitBackendBlockedBy(dsl, purgePid.get(), acknowledgementPid.get());
        release.countDown();
        var ack = firstAck.get(5L, TimeUnit.SECONDS);
        assertThat(purged.get(5L, TimeUnit.SECONDS)).isTrue();
        assertThat(
                transaction.<CanonicalGameplayRosterRetirementAcknowledgementResponse>execute(
                    ignored -> repository.acknowledgeRetirement(request, entityPrincipal())))
            .isEqualTo(ack);
      } finally {
        release.countDown();
      }
    }
  }

  private String transferRow(GetCanonicalGameplayRosterReferenceRequest original) {
    return dsl.fetchSingle(
            "SELECT row_to_json(t)::text AS retained, xmin::text FROM game_session_canonical_roster_retirement_transfers t WHERE operation_uuid = ?",
            UUID.fromString(original.getOperationUuid()))
        .intoMap()
        .toString();
  }

  private static String entityPrincipal() {
    return "spiffe://firemud/ns/gameplay/sa/entity-management-service";
  }

  private static CanonicalGameplayRosterRetirementAcknowledgementRequest acknowledgement(
      GetCanonicalGameplayRosterReferenceRequest original,
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse observed,
      UUID admission,
      long minimum) {
    var request =
        CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setObservedRetirement(observed)
            .setObservationUuid(UUID.randomUUID().toString())
            .setAdmissionUuid(admission.toString())
            .setMinimumRetentionMillis(minimum);
    if (!observed.getCanonicalRosterReceipt().isEmpty()) {
      var roster = CanonicalGameplayRosterReferenceEvidence.receipt(observed);
      request
          .setSnapshotUuid(roster.getSnapshotUuid())
          .setSnapshotDigest(roster.getSnapshotDigest());
    }
    return CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(request.build());
  }

  private CanonicalGameplayRosterReferenceRepository.TargetCandidate retireOriginal(
      GetCanonicalGameplayRosterReferenceRequest request) throws Exception {
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.completeUnissuedDenied(request);
        });
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isTrue();
    return new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
        UUID.fromString(repository.read(request).getTargetFenceUuid()), request, "RETIRED");
  }

  private void assertNoOriginalOrFence() {
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_roster_selection")
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_roster_target_fences")
                .get(0, Long.class))
        .isZero();
  }

  private net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner
      referenceOwner(
          net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
              sources,
          CanonicalInitialAdmissionRepository admissions) {
    return new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
        repository,
        transaction,
        sources,
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class),
        admissions,
        org.mockito.Mockito.mock(CanonicalGameplayRosterRecoveryClient.class));
  }

  private static GetCanonicalGameplayRosterReferenceRequest publishedRequest(
      net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute published) {
    return CanonicalGameplayRosterReferenceEvidence.request(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        published.route().targetNamespace(),
        net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient.expectedTarget(
            published));
  }

  private static net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
      sourceEvidence(
          net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
              selectors,
          net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute published) {
    var route = published.route();
    var request =
        new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request(
            route.targetNamespace(),
            route.canonicalTenantId(),
            route.worldSlug(),
            route.realmId(),
            route.playableStateNamespaceId(),
            route.playableStateScope(),
            route.canonicalGameInstanceId(),
            route.canonicalVersionId(),
            route.activeWorldEpoch(),
            route.initialAdmissionRequestId(),
            route.initialAdmissionRequestDigest(),
            net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold
                .InitialAdmissionOrigin.valueOf(route.initialAdmissionOriginKind().name()),
            route.catalogRevision(),
            route.expectedPriorPointerVersion());
    var hold =
        new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
            request, route.holdId(), route.holdFence());
    var proof =
        new net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof
                .Outcome.COMMITTED,
            route.pointerVersion(),
            route.auditEventId(),
            route.ownerProofDigest(),
            false,
            route.ownerProofTerminalAt());
    return new net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence(
        selectors, route.admissionPointerSnapshotDigest(), proof, published.policySetEvidence());
  }

  private static net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput
      selectionInput() {
    return net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
        .setSchemaVersion(1)
        .build();
  }

  private CanonicalGameplayRosterRecoveryResponse recoveryResponse(
      CanonicalGameplayRosterRecoveryRequest recovery, CanonicalGameplayRosterResponse roster) {
    var original = repository.read(recovery.getOriginalSelection());
    return CanonicalGameplayRosterRecoveryEvidence.response(
        recovery,
        UUID.randomUUID(),
        original.getOwnerFence(),
        UUID.fromString(original.getTargetFenceUuid()),
        roster);
  }

  /** Storage proof only: the real authenticated client capability is independently qualified. */
  private static VerifiedRecovery verifiedRecovery(
      CanonicalGameplayRosterRecoveryRequest request,
      CanonicalGameplayRosterRecoveryResponse response) {
    var verified = org.mockito.Mockito.mock(VerifiedRecovery.class);
    org.mockito.Mockito.when(verified.request()).thenReturn(request);
    org.mockito.Mockito.when(verified.response()).thenReturn(response);
    return verified;
  }

  private List<String> history() {
    return dsl.fetch(
            "SELECT version, script, checksum, installed_rank, success FROM flyway_schema_history ORDER BY installed_rank")
        .map(row -> row.intoMap().toString());
  }

  private String retainedRow(GetCanonicalGameplayRosterReferenceRequest request) {
    return dsl.fetchSingle(
            "SELECT encode(original_request, 'hex') AS original_request_hex, owner_fence, encode(canonical_roster_receipt, 'hex') AS roster_receipt_hex, "
                + "recorded_at, receipt_recorded_at, encode(selection_input, 'hex') AS selection_input_hex, selected_character_uuid, encode(canonical_selected_assignment_receipt, 'hex') AS assignment_receipt_hex, xmin::text FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            UUID.fromString(request.getOperationUuid()))
        .intoMap()
        .toString();
  }

  private List<Object> fencePolicy(GetCanonicalGameplayRosterReferenceRequest request) {
    byte[] binding =
        request.toBuilder().clearOperationUuid().clearSessionUuid().build().toByteArray();
    return dsl.fetchSingle(
            "SELECT minimum_retention_millis, replay_horizon_millis, fence_state FROM game_session_canonical_roster_target_fences WHERE target_binding = ?",
            binding)
        .intoList();
  }

  private CanonicalGameplayRosterReferenceRepository repository(
      net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy policy) {
    return new CanonicalGameplayRosterReferenceRepository(dsl, policy);
  }

  private static net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy
      policy(long minimumRetentionMillis, long retryHorizonMillis, long restoreHorizonMillis) {
    return new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy(
        java.time.Duration.ofMillis(minimumRetentionMillis),
        java.time.Duration.ofMillis(retryHorizonMillis),
        java.time.Duration.ofMillis(restoreHorizonMillis),
        java.time.Duration.ofSeconds(1),
        8);
  }

  private void migrate(String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .locations(
                "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath(),
                "filesystem:"
                    + Path.of("../common-saga/src/main/resources/db/migration/saga")
                        .toAbsolutePath())
            .placeholders(java.util.Map.of("serviceSchema", schema))
            .target(target);
    configuration
        .getPluginRegister()
        .getPlugin(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.load().migrate();
  }

  private static CanonicalGameplayRosterResponse receipt(
      GetCanonicalGameplayRosterReferenceRequest request) {
    return CanonicalGameplayRosterResponse.newBuilder()
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setSnapshotUuid(UUID.randomUUID().toString())
        .setSnapshotDigest("d".repeat(64))
        .setTarget(CanonicalGameplayRosterReferenceEvidence.target(request))
        .build();
  }

  private static GetCanonicalGameplayRosterReferenceRequest request() {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setWorldSlug("earth")
            .setRealmSlug("main")
            .setCatalogRevision(7L)
            .setPointerVersion(11L)
            .setActiveWorldEpoch(13L)
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedReleaseBundleRef("release:exact")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return CanonicalGameplayRosterReferenceEvidence.request(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "gameplay", target);
  }
}
