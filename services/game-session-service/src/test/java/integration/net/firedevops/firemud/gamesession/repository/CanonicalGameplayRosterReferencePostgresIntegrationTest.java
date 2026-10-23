package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
            admissions);
    assertThat(owner.retireTarget(request)).isFalse();
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenReturn(
            new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(
                snapshot.ownerProof(), "e".repeat(64)));
    assertThatThrownBy(() -> owner.retireTarget(request))
        .hasMessageContaining("CURRENT_SOURCE_CONFLICT");
    org.mockito.Mockito.verify(admissions, org.mockito.Mockito.times(1))
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
    try (var executor = Executors.newFixedThreadPool(2)) {
      var retirement =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        boolean result = repository.sealTargetRetirement(request, current(request));
                        sealed.countDown();
                        try {
                          if (!commit.await(5L, TimeUnit.SECONDS)) {
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
                      ignored ->
                          repository.begin(
                              request.toBuilder()
                                  .setOperationUuid(UUID.randomUUID().toString())
                                  .build())));
      commit.countDown();
      assertThat(retirement.get(5L, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> recovery.get(5L, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
    } finally {
      commit.countDown();
    }
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
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        var value = repository.begin(request);
                        reserved.countDown();
                        try {
                          if (!finish.await(5L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("fixture timeout");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return value;
                      }));
      assertThat(reserved.await(5L, TimeUnit.SECONDS)).isTrue();
      var replay = executor.submit(() -> transaction.execute(ignored -> repository.begin(request)));
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
