package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.dto.CanonicalLaunchPreparationSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalClosedAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import net.firedevops.firemud.gamesession.service.GameSessionCanonicalLaunchPreparationService;
import net.firedevops.firemud.gamesession.service.impl.DatabaseCanonicalInitialAdmissionService;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof for the canonical first-OPEN owner transaction.
 *
 * <p>Fresh association, catalog, and launch pins use their actual Game Session owners with
 * controlled upstream creation, publication, Account tuple/projection, and World inputs. These
 * inputs do not execute genuine upstream producers or establish Account terminal protection. Human
 * terminal positives remain an implementation obligation until the protected owner boundary is
 * composed.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalInitialAdmissionRepositoryIntegrationTest {
  private static final String NAMESPACE = "canonical-initial-admission-it";
  private static final UUID TENANT = uuid(101);
  private static final UUID SOURCE_OPERATION = uuid(102);
  private static final UUID INTAKE_REQUEST = uuid(103);
  private static final UUID CATALOG_REQUEST = uuid(104);
  private static final UUID ACTOR = uuid(105);
  private static final UUID CANONICAL_VERSION = uuid(109);
  private static final UUID ASSOCIATION_OPERATION = uuid(110);
  private static final String CONTROL_PLANE_REQUEST_ID = "canonical-start-instance-1";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void rosterRetirementUsesRealCommittedAdmissionReaderAndLocksCurrentPointerUnderRosterFence()
      throws Exception {
    // Retained pre1006 source is deliberately controlled original owner history, not a fresh
    // protected Human admission or genuine upstream producer. All current route/owner reads and
    // row locks below are real, with every current SQL guard enabled.
    Fixture fixture = fixture("1005");
    var predecessor = preparePre1006Admission(fixture);
    var prepared = predecessor.prepared();
    var admission =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    reservePre1006Attempt(fixture, predecessor.launch(), admission);
    seedPre1006Terminal(fixture, prepared, predecessor.launch(), admission, true);
    migration(fixture, "1015").migrate();
    var routes =
        new net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService(
            NAMESPACE,
            fixture.catalogRepository,
            fixture.initialAdmissionRepository,
            fixture.launchRepository);
    var current = routes.readCurrentTarget(TENANT);
    var target =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(TENANT.toString())
            .setRealmUuid(current.realmId().toString())
            .setWorldSlug(current.worldSlug())
            .setRealmSlug(current.realmSlug())
            .setGameInstanceUuid(current.canonicalGameInstanceId().toString())
            .setCanonicalVersionUuid(current.canonicalVersionId().toString())
            .setPlayableStateNamespaceUuid(current.playableStateNamespaceId().toString())
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                    .CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .setCatalogRevision(current.catalogRevision())
            .setPointerVersion(current.pointerVersion())
            .setActiveWorldEpoch(current.activeWorldEpoch())
            .setAdmissionPointerSnapshotDigest(current.admissionPointerSnapshotDigest())
            .setPublishedOwnerProofDigest(current.ownerProofDigest().substring("sha256:".length()))
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedReleaseBundleRef("controlled:retained-fixture")
            .build();
    var request =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.request(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NAMESPACE, target);
    var references =
        new net.firedevops.firemud.gamesession.repository
            .CanonicalGameplayRosterReferenceRepository(
            fixture.dsl,
            new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy(
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofSeconds(1),
                8));
    fixture.transactions.executeWithoutResult(
        status -> {
          references.begin(request);
          references.completeUnissuedDenied(request);
        });
    String retained =
        fixture
            .dsl
            .fetchSingle(
                "SELECT original_request, xmin::text FROM game_session_canonical_roster_selection")
            .formatJSON();
    String originalOwnerRows = pointerAndAuditProjection(fixture);
    var lockAttempted = new java.util.concurrent.CountDownLatch(1);
    var recoveryStarted = new java.util.concurrent.CountDownLatch(1);
    var retirementPid = new java.util.concurrent.atomic.AtomicInteger();
    var recoveryPid = new java.util.concurrent.atomic.AtomicInteger();
    var admissions = org.mockito.Mockito.spy(fixture.initialAdmissionRepository);
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              retirementPid.set(
                  Objects.requireNonNull(
                      fixture.dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
              lockAttempted.countDown();
              return call.callRealMethod();
            })
        .when(admissions)
        .lockExactCurrentOpenSnapshot(any());
    var owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            references,
            fixture.transactions,
            mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routes,
            admissions,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient
                    .class));
    // This is the actual guard hidden by the old whole-route mock.
    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status ->
                        fixture.initialAdmissionRepository.readCurrentOpenSnapshotForRealm(
                            NAMESPACE, TENANT, current.realmId())))
        .isInstanceOf(IllegalStateException.class);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var retirement =
          new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
      var recovery =
          new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
      fixture.transactions.executeWithoutResult(
          status -> {
            fixture.dsl.fetchSingle(
                "SELECT id FROM gameplay_admission_pointer WHERE target_namespace = ? "
                    + "AND canonical_tenant_id = ? AND realm_id = ? FOR UPDATE",
                NAMESPACE,
                TENANT,
                current.realmId());
            int pointerHolderPid =
                Objects.requireNonNull(
                    fixture.dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
            retirement.set(executor.submit(() -> owner.retireTarget(request)));
            try {
              assertThat(lockAttempted.await(5L, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              awaitBackendBlockedBy(fixture.dsl, retirementPid.get(), pointerHolderPid);
              recovery.set(
                  executor.submit(
                      () ->
                          fixture.transactions.execute(
                              ignored -> {
                                recoveryPid.set(
                                    Objects.requireNonNull(
                                        fixture
                                            .dsl
                                            .fetchSingle("SELECT pg_backend_pid()")
                                            .get(0, Integer.class)));
                                recoveryStarted.countDown();
                                return references.begin(request);
                              })));
              assertThat(recoveryStarted.await(5L, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              awaitBackendBlockedBy(fixture.dsl, recoveryPid.get(), retirementPid.get());
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
          });
      // The exact current pointer has not superseded this original target, so it cannot retire.
      assertThat(retirement.get().get(5L, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
      assertThatThrownBy(() -> recovery.get().get(5L, java.util.concurrent.TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("CANONICAL_ROSTER_ORIGINAL_REPLAY_WINDOW_CLOSED");
    }
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT original_request, xmin::text FROM game_session_canonical_roster_selection")
                .formatJSON())
        .isEqualTo(retained);
    assertThat(pointerAndAuditProjection(fixture)).isEqualTo(originalOwnerRows);
    assertThat(references.read(request).getState())
        .isEqualTo(
            net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_REPLAY_CLOSED);
    assertDistinctAccountSourceReadersShareAndExcludeWriters(
        fixture, current, CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER);
  }

  @Test
  void recoveryUsesRealCommittedV2PointerWithSyntheticRetainedV1Originals() throws Exception {
    // Controlled retained pre1006 CLOSED-v1 -> first-OPEN-v2 owner history. Original roster-v1
    // selections below are synthetic retained evidence: no genuine OPEN-v1 -> OPEN-v2 writer or
    // Human admission is activated by this proof. All current readers and row-lock guards are real.
    Fixture fixture = fixture("1005");
    var predecessor = preparePre1006Admission(fixture);
    var prepared = predecessor.prepared();
    var closeRequest =
        new CreateCanonicalClosedAdmissionPointerRequest(
            uuid(115),
            NAMESPACE,
            TENANT,
            prepared.catalog().realmId(),
            CATALOG_REQUEST,
            prepared.catalog().catalogRevision(),
            "canonical-roster-recovery-test",
            "Controlled retained CLOSED origin");
    // Current catalog mapping expects later fixture-policy columns absent at migration 1005.
    // Only this historical catalog boundary is controlled; the real CLOSED writer, database
    // catalog constraints, append-only event and original receipt readback remain in use.
    var historicalCatalog = mock(GameSessionCanonicalRealmCatalogRepository.class);
    when(historicalCatalog.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .thenReturn(java.util.Optional.of(prepared.catalog()));
    when(historicalCatalog.lockExactInitialPublicProduction(
            NAMESPACE,
            TENANT,
            prepared.catalog().realmId(),
            CATALOG_REQUEST,
            prepared.catalog().catalogRevision()))
        .thenReturn(prepared.catalog());
    var historicalPointers =
        new GameSessionCanonicalAdmissionPointerRepository(
            fixture.dsl,
            historicalCatalog,
            new GameplayAdmissionPointerEventRepository(fixture.dsl));
    var closed = historicalPointers.prepareInitialClosed(closeRequest);
    fixture.transactions.executeWithoutResult(
        status -> historicalPointers.createInitialClosed(closed));
    var origin = historicalPointers.readOriginalClosedOrigin(NAMESPACE, uuid(115)).orElseThrow();
    var admission =
        initialAdmissionRequest(
            prepared.catalog(),
            prepared.binding(),
            prepared.gameInstanceUuid(),
            "controlled-recovery-open-v2",
            CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED,
            1L,
            uuid(121),
            uuid(122));
    reservePre1006Attempt(fixture, predecessor.launch(), admission);
    seedPre1006ExpectedClosedTerminal(fixture, prepared, predecessor.launch(), admission, origin);
    migration(fixture, "1015").migrate();
    var routes =
        new net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService(
            NAMESPACE,
            fixture.catalogRepository,
            fixture.initialAdmissionRepository,
            fixture.launchRepository);
    var current = routes.readCurrentTarget(TENANT);
    assertThat(current.pointerVersion()).isEqualTo(2L);
    var currentSnapshot =
        fixture.initialAdmissionRepository.readCurrentOpenSnapshotForRealm(
            NAMESPACE, TENANT, current.realmId());
    assertThat(currentSnapshot.admissionPointerSnapshotDigest())
        .isEqualTo(current.admissionPointerSnapshotDigest());
    assertThatThrownBy(
            () -> fixture.pointerRepository.lockOriginalClosedOrigin(NAMESPACE, origin.requestId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    var readOnly = new TransactionTemplate(fixture.transactionManager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () ->
                readOnly.execute(
                    status ->
                        fixture.pointerRepository.lockOriginalClosedOrigin(
                            NAMESPACE, origin.requestId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("read-write transaction");
    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status ->
                        fixture.pointerRepository.readOriginalClosedOrigin(
                            NAMESPACE, origin.requestId())))
        .isInstanceOf(IllegalStateException.class);
    fixture.transactions.executeWithoutResult(
        status -> {
          var lockedOrigin =
              fixture
                  .pointerRepository
                  .lockOriginalClosedOrigin(NAMESPACE, origin.requestId())
                  .orElseThrow();
          assertThat(lockedOrigin.requestId()).isEqualTo(origin.requestId());
          assertThat(lockedOrigin.requestDigest()).isEqualTo(origin.requestDigest());
          assertThat(lockedOrigin.receiptDigest()).isEqualTo(origin.receiptDigest());
          assertThat(lockedOrigin.auditEventId()).isEqualTo(origin.auditEventId());
          assertThat(
                  fixture.pointerRepository.lockOriginalClosedOrigin(NAMESPACE, UUID.randomUUID()))
              .isEmpty();
          assertThat(
                  fixture.pointerRepository.lockOriginalClosedOrigin(
                      "substituted-namespace", origin.requestId()))
              .isEmpty();
        });
    String originalOwnerRows = pointerAndAuditProjection(fixture);
    var references =
        new CanonicalGameplayRosterReferenceRepository(
            fixture.dsl,
            new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy(
                java.time.Duration.ofMillis(500),
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofSeconds(1),
                8));
    var target =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(TENANT.toString())
            .setRealmUuid(current.realmId().toString())
            .setWorldSlug(current.worldSlug())
            .setRealmSlug(current.realmSlug())
            .setGameInstanceUuid(current.canonicalGameInstanceId().toString())
            .setCanonicalVersionUuid(current.canonicalVersionId().toString())
            .setPlayableStateNamespaceUuid(current.playableStateNamespaceId().toString())
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                    .CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .setCatalogRevision(current.catalogRevision())
            .setPointerVersion(1L)
            .setActiveWorldEpoch(current.activeWorldEpoch())
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedReleaseBundleRef("controlled:retained-original-v1")
            .build();
    var missingRoster =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.request(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NAMESPACE, target);
    var missingAssignment =
        missingRoster.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
    var actor = UUID.randomUUID();
    var roster =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(missingRoster.getCanonicalAccountUuid())
            .setTarget(target)
            .setSnapshotUuid(UUID.randomUUID().toString())
            .setSnapshotDigest("d".repeat(64))
            .addActors(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
                    .setCharacterUuid(actor.toString())
                    .setDisplayName("Selected")
                    .setActorKind(
                        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                            .CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
            .build();
    var input =
        net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
            .setSchemaVersion(1)
            .build();
    fixture.transactions.executeWithoutResult(
        status -> {
          references.begin(missingRoster);
          references.reserveSelectionInput(missingRoster, input);
          references.markRosterReadStarted(missingRoster);
          references.begin(missingAssignment);
          references.reserveSelectionInput(missingAssignment, input);
          references.markRosterReadStarted(missingAssignment);
          references.record(missingAssignment, roster);
          references.selectOriginal(missingAssignment, actor);
        });
    Thread.sleep(5L);
    var recoveryClient =
        mock(net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient.class);
    org.mockito.Mockito.when(recoveryClient.read(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              throw io.grpc.Status.UNAVAILABLE.asRuntimeException();
            });
    var admissions = org.mockito.Mockito.spy(fixture.initialAdmissionRepository);
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return call.callRealMethod();
            })
        .when(admissions)
        .lockExactCurrentOpenSnapshot(any());
    var owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            references,
            fixture.transactions,
            mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routes,
            admissions,
            recoveryClient);
    assertThat(owner.retireTarget(missingRoster)).isFalse();
    assertThat(references.read(missingRoster).getRecoveryUuid()).isNotEmpty();
    assertThat(references.read(missingAssignment).getRecoveryUuid()).isNotEmpty();
    assertThat(references.read(missingRoster).getTerminalOutcome()).isEmpty();
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest
                  recovery = call.getArgument(0);
              var original = references.read(recovery.getOriginalSelection());
              var response =
                  net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence
                      .response(
                          recovery,
                          UUID.randomUUID(),
                          original.getOwnerFence(),
                          UUID.fromString(original.getTargetFenceUuid()),
                          roster);
              // Storage composition double only; actual peer-authenticated capability proof is
              // separate.
              var verified =
                  mock(
                      net.firedevops.firemud.gamesession.client
                          .CanonicalGameplayRosterRecoveryClient.VerifiedRecovery.class);
              org.mockito.Mockito.when(verified.request()).thenReturn(recovery);
              org.mockito.Mockito.when(verified.response()).thenReturn(response);
              return verified;
            })
        .when(recoveryClient)
        .read(any());
    assertThat(owner.retireTarget(missingRoster)).isFalse();
    assertThat(references.read(missingRoster).getCanonicalRosterReceipt())
        .isEqualTo(roster.toByteString());
    assertThat(references.read(missingAssignment).getSelectedCharacterUuid())
        .isEqualTo(actor.toString());
    assertThat(references.read(missingAssignment).getCanonicalSelectedAssignmentReceipt())
        .isEmpty();
    assertThat(references.read(missingAssignment).getTerminalOutcome()).isEqualTo("DENIED");
    Thread.sleep(550L);
    assertThat(owner.retireTarget(missingRoster)).isTrue();
    assertThat(
            net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                .requireRetirement(references.read(missingRoster)))
        .isEqualTo(references.read(missingRoster));
    org.mockito.Mockito.verify(admissions, org.mockito.Mockito.times(6))
        .lockExactCurrentOpenSnapshot(any());
    assertThat(pointerAndAuditProjection(fixture)).isEqualTo(originalOwnerRows);
    // The existing same-current-pointer and actual-row-lock race proof remains separate above.
    // Fresh Human allocation still refuses missing Account protection after the retained upgrade.
    var fresh =
        initialAdmissionRequest(
            prepared.catalog(),
            prepared.binding(),
            prepared.gameInstanceUuid(),
            "recovery-must-not-open-human",
            CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED,
            1L,
            uuid(123),
            uuid(124));
    assertHumanProtectionUnavailable(() -> fixture.initialAdmissionRepository.reserve(fresh));
    assertDistinctAccountSourceReadersShareAndExcludeWriters(
        fixture, current, CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED);
  }

  @Test
  void freshHumanReservationDeniesBeforeEffectsAndRetainedPendingCannotCommit() throws Exception {
    Fixture fixture = fixture("1005");
    PredecessorAdmission predecessor = preparePre1006Admission(fixture);
    PreparedAdmission prepared = predecessor.prepared();
    CanonicalInitialAdmissionRequest request =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    reservePre1006Attempt(fixture, predecessor.launch(), request);
    migration(fixture, null).migrate();
    migration(fixture, null).validate();

    CanonicalInitialAdmissionOwnerProof pending =
        fixture
            .initialAdmissionRepository
            .read(NAMESPACE, request.initialAdmissionRequestId())
            .orElseThrow();
    assertThat(pending.outcome()).isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    String before = attemptProjection(fixture, request);
    CanonicalInitialAdmissionRequest freshRequest =
        initialAdmissionRequest(
            prepared.catalog(),
            prepared.binding(),
            prepared.gameInstanceUuid(),
            "fresh-human-reserve-denied-" + prepared.catalog().realmId(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            uuid(115),
            uuid(116));
    assertHumanProtectionUnavailable(
        () -> fixture.initialAdmissionRepository.reserve(freshRequest));
    assertThat(
            fixture.initialAdmissionRepository.read(
                NAMESPACE, freshRequest.initialAdmissionRequestId()))
        .isEmpty();
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_initial_admission_attempt"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
        .isZero();

    UUID mismatchedGameInstance = uuid(998);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "INSERT INTO gameplay_admission_pointer_event ("
                        + "world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
                        + "game_instance_id, pointer_version, visible, requires_character_selection, "
                        + "state_scope, character_creation_policy, actor_principal, reason, "
                        + "control_plane_request_id, occurred_at, prepared_version_upgrade_id, "
                        + "public_production_realm, catalog_revision, realm_id, "
                        + "playable_state_namespace_id, representation_version, target_namespace, "
                        + "canonical_tenant_id, admission_state, canonical_game_instance_id, "
                        + "canonical_version_id, runtime_version_id, initial_admission_request_id, "
                        + "initial_admission_request_digest, initial_admission_origin_kind, "
                        + "initial_admission_prior_pointer_version, initial_admission_active_epoch, "
                        + "initial_admission_hold_id, initial_admission_hold_fence, "
                        + "initial_admission_hold_binding_digest) "
                        + "SELECT attempt.world_slug, catalog.realm_slug, catalog.source_world_display_name, "
                        + "catalog.realm_display_name, attempt.game_session_tenant_id, attempt.game_instance_id, "
                        + "CASE WHEN attempt.origin_kind = 'NO_PRIOR_POINTER' THEN 1 "
                        + "ELSE attempt.expected_prior_pointer_version + 1 END, catalog.visible, NULL, "
                        + "attempt.playable_state_scope, catalog.character_creation_policy, "
                        + "'game-session-canonical-initial-admission', 'World-held initial admission', "
                        + "attempt.initial_admission_request_id, CURRENT_TIMESTAMP, NULL, "
                        + "catalog.public_production, attempt.expected_catalog_revision, attempt.realm_id, "
                        + "attempt.playable_state_namespace_id, 3, attempt.target_namespace, "
                        + "attempt.canonical_tenant_id, 'OPEN', ?, attempt.canonical_version_id, "
                        + "attempt.runtime_version_id, attempt.initial_admission_request_id, "
                        + "attempt.request_digest, attempt.origin_kind, attempt.expected_prior_pointer_version, "
                        + "attempt.active_lifecycle_epoch, attempt.hold_id, attempt.hold_fence, "
                        + "attempt.hold_binding_digest "
                        + "FROM game_session_canonical_initial_admission_attempt attempt "
                        + "JOIN game_session_canonical_realm_catalog catalog "
                        + "ON catalog.target_namespace = attempt.target_namespace "
                        + "AND catalog.canonical_tenant_id = attempt.canonical_tenant_id "
                        + "AND catalog.realm_id = attempt.realm_id "
                        + "AND catalog.catalog_revision = attempt.expected_catalog_revision "
                        + "JOIN game_session_canonical_instance_launch launch "
                        + "ON launch.target_namespace = attempt.target_namespace "
                        + "AND launch.canonical_tenant_id = attempt.canonical_tenant_id "
                        + "AND launch.canonical_realm_id = attempt.realm_id "
                        + "AND launch.game_session_tenant_id = attempt.game_session_tenant_id "
                        + "AND launch.game_instance_id = attempt.game_instance_id "
                        + "AND launch.game_instance_uuid = attempt.canonical_game_instance_id "
                        + "AND launch.version_id = attempt.runtime_version_id "
                        + "WHERE attempt.target_namespace = ? "
                        + "AND attempt.initial_admission_request_id = ? AND attempt.status = 'PENDING'",
                    mismatchedGameInstance,
                    NAMESPACE,
                    request.initialAdmissionRequestId()))
        .hasMessageContaining("Numeric-only tenant scope cannot use a fresh source-bound key");
    assertThat(
            fixture.initialAdmissionRepository.read(NAMESPACE, request.initialAdmissionRequestId()))
        .map(CanonicalInitialAdmissionOwnerProof::outcome)
        .contains(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("gameplay_admission_pointer_event")),
                DSL.field(DSL.name("representation_version"), Integer.class).eq(3)))
        .isZero();

    for (int retry = 0; retry < 2; retry++) {
      assertHumanProtectionUnavailable(
          () ->
              fixture.transactions.executeWithoutResult(
                  status ->
                      fixture.initialAdmissionRepository.commit(request, testWorldProof(request))));
      assertHumanProtectionUnavailable(
          () ->
              fixture.transactions.executeWithoutResult(
                  status ->
                      fixture.initialAdmissionRepository.abort(request, "Missing protection")));
      assertThat(attemptProjection(fixture, request)).isEqualTo(before);
      assertThat(
              fixture.initialAdmissionRepository.read(
                  NAMESPACE, request.initialAdmissionRequestId()))
          .contains(pending);
      assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer"))))
          .isZero();
      assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
          .isZero();
    }
  }

  @Test
  void denies128CharacterFreshHumanIntentBeforeReservation() {
    Fixture fixture = fixture();
    PreparedAdmission prepared = prepareAdmission(fixture);
    String requestId = "r".repeat(128);
    CanonicalInitialAdmissionRequest request =
        initialAdmissionRequest(
            prepared.catalog(),
            prepared.binding(),
            prepared.gameInstanceUuid(),
            requestId,
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            uuid(115),
            uuid(116));

    assertHumanProtectionUnavailable(() -> fixture.initialAdmissionRepository.reserve(request));
    assertNoAttemptAndNoPending(fixture, request);
    assertThat(fixture.initialAdmissionRepository.read(NAMESPACE, requestId)).isEmpty();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
        .isZero();
  }

  @Test
  void competingFreshHumanReservationsDenyBeforeIntentOrPointerEffects() {
    Fixture fixture = fixture();
    PreparedAdmission prepared = prepareAdmission(fixture);
    CanonicalInitialAdmissionRequest first =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    CanonicalInitialAdmissionRequest competing =
        initialAdmissionRequest(
            prepared.catalog(),
            prepared.binding(),
            prepared.gameInstanceUuid(),
            "competing-no-prior-" + prepared.catalog().realmId(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            uuid(117),
            uuid(118));

    assertHumanProtectionUnavailable(() -> fixture.initialAdmissionRepository.reserve(first));
    assertHumanProtectionUnavailable(() -> fixture.initialAdmissionRepository.reserve(competing));
    assertNoAttemptAndNoPending(fixture, first);
    assertNoAttemptAndNoPending(fixture, competing);
    assertThat(
            fixture.initialAdmissionRepository.read(NAMESPACE, first.initialAdmissionRequestId()))
        .isEmpty();
    assertThat(
            fixture.initialAdmissionRepository.read(
                NAMESPACE, competing.initialAdmissionRequestId()))
        .isEmpty();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
        .isZero();
  }

  @Test
  void freshExpectedClosedHumanIntentDeniesBeforeEffects() {
    Fixture fixture = fixture();
    PreparedAdmission preparedAdmission = prepareAdmission(fixture);
    CanonicalRealmCatalogSnapshot catalog = preparedAdmission.catalog();
    CompleteLaunchBindingEvidence binding = preparedAdmission.binding();
    CreateCanonicalClosedAdmissionPointerRequest closeRequest =
        new CreateCanonicalClosedAdmissionPointerRequest(
            uuid(115),
            NAMESPACE,
            TENANT,
            catalog.realmId(),
            CATALOG_REQUEST,
            catalog.catalogRevision(),
            "canonical-initial-admission-test",
            "Create exact CLOSED origin");
    var preparedClosed = fixture.pointerRepository.prepareInitialClosed(closeRequest);
    fixture.transactions.execute(
        status -> {
          fixture.pointerRepository.createInitialClosed(preparedClosed);
          return null;
        });

    CanonicalInitialAdmissionRequest unsupportedPriorVersion =
        initialAdmissionRequest(
            catalog,
            binding,
            preparedAdmission.gameInstanceUuid(),
            "canonical-expected-closed-v129-" + catalog.realmId(),
            CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED,
            129L,
            uuid(119),
            uuid(120));
    String closedBefore = pointerAndAuditProjection(fixture);
    assertHumanProtectionUnavailable(
        () -> fixture.initialAdmissionRepository.reserve(unsupportedPriorVersion));
    assertNoAttemptAndNoPending(fixture, unsupportedPriorVersion);
    assertThat(pointerAndAuditProjection(fixture)).isEqualTo(closedBefore);

    CanonicalInitialAdmissionRequest firstOpenRequest =
        initialAdmissionRequest(
            catalog,
            binding,
            preparedAdmission.gameInstanceUuid(),
            "canonical-expected-closed-v1-" + catalog.realmId(),
            CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED,
            1L,
            uuid(121),
            uuid(122));
    assertHumanProtectionUnavailable(
        () -> fixture.initialAdmissionRepository.reserve(firstOpenRequest));
    assertNoAttemptAndNoPending(fixture, firstOpenRequest);
    assertHumanProtectionUnavailable(
        () ->
            fixture.initialAdmissionRepository.commit(
                firstOpenRequest, testWorldProof(firstOpenRequest)));
    assertHumanProtectionUnavailable(
        () -> fixture.initialAdmissionRepository.abort(firstOpenRequest, "Missing protection"));
    assertThat(pointerAndAuditProjection(fixture)).isEqualTo(closedBefore);
    assertNoAttemptAndNoPending(fixture, firstOpenRequest);
    assertThat(
            fixture.initialAdmissionRepository.read(
                NAMESPACE, firstOpenRequest.initialAdmissionRequestId()))
        .isEmpty();
  }

  @Test
  void directlyConstructedHumanServiceDeniesBeforeReservationOrWorldVerification() {
    Fixture fixture = fixture();
    PreparedAdmission prepared = prepareAdmission(fixture);
    var request =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    CanonicalInitialAdmissionWorldVerifier verifier =
        mock(CanonicalInitialAdmissionWorldVerifier.class);
    var service =
        new DatabaseCanonicalInitialAdmissionService(
            fixture.initialAdmissionRepository,
            fixture.pointerRepository,
            verifier,
            fixture.transactionManager);
    assertHumanProtectionUnavailable(() -> service.bind(request));
    assertHumanProtectionUnavailable(() -> service.abort(request, "Missing protection"));
    verifyNoInteractions(verifier);
    assertNoAttemptAndNoPending(fixture, request);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
        .isZero();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @Test
  void freshHumanReserveCommitAndAbortRetriesLeaveNoOwnerEffects() {
    Fixture fixture = fixture();
    PreparedAdmission prepared = prepareAdmission(fixture);
    var request =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    for (int retry = 0; retry < 2; retry++) {
      assertHumanProtectionUnavailable(() -> fixture.initialAdmissionRepository.reserve(request));
      assertHumanProtectionUnavailable(
          () -> fixture.initialAdmissionRepository.abort(request, "World hold expired"));
      assertHumanProtectionUnavailable(
          () -> fixture.initialAdmissionRepository.commit(request, testWorldProof(request)));
      assertNoAttemptAndNoPending(fixture, request);
      assertThat(
              fixture.initialAdmissionRepository.read(
                  NAMESPACE, request.initialAdmissionRequestId()))
          .isEmpty();
      assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer"))))
          .isZero();
      assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
          .isZero();
    }
  }

  @Test
  void retainedPendingRowsPreserveUnprotectedTerminalWriteGuards() throws Exception {
    Fixture fixture = fixture("1005");
    PredecessorAdmission predecessor = preparePre1006Admission(fixture);
    PreparedAdmission prepared = predecessor.prepared();
    CanonicalInitialAdmissionRequest request =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    reservePre1006Attempt(fixture, predecessor.launch(), request);
    migration(fixture, null).migrate();
    migration(fixture, null).validate();
    launchGuardProjection(fixture, true);
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '1011' AND success")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT convalidated FROM pg_constraint WHERE conrelid = "
                        + "'game_session_canonical_initial_admission_attempt'::regclass "
                        + "AND conname = 'chk_gs_initial_admission_terminal_requires_protection'")
                .get(0, Boolean.class))
        .isFalse();
    String pendingBefore = attemptProjection(fixture, request);
    var terminalColumns =
        new HashMap<>(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT * FROM game_session_canonical_initial_admission_attempt "
                        + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                    NAMESPACE,
                    request.initialAdmissionRequestId())
                .intoMap());
    terminalColumns.put("initial_admission_request_id", "unprotected-terminal-insert");
    terminalColumns.put("hold_id", UUID.randomUUID());
    terminalColumns.put("status", "ABORTED");
    terminalColumns.put("abort_reason", "Unprotected terminal constraint fixture");
    terminalColumns.put("abort_proof_digest", "sha256:" + "f".repeat(64));
    terminalColumns.put("terminal_at", terminalColumns.get("updated_at"));

    assertThatThrownBy(
            () ->
                fixture.transactions.executeWithoutResult(
                    status ->
                        fixture
                            .dsl
                            .insertInto(
                                DSL.table(
                                    DSL.name("game_session_canonical_initial_admission_attempt")))
                            .set(terminalColumns)
                            .execute()))
        .isInstanceOfSatisfying(
            DataAccessException.class,
            exception -> {
              assertThat(exception.sqlState()).isEqualTo("23514");
              assertThat(exception)
                  .hasStackTraceContaining(
                      "New canonical initial-admission attempts must begin PENDING");
            });
    assertThatThrownBy(
            () ->
                fixture.transactions.executeWithoutResult(
                    status ->
                        fixture.dsl.execute(
                            "UPDATE game_session_canonical_initial_admission_attempt SET "
                                + "status = 'ABORTED', abort_reason = ?, abort_proof_digest = ?, "
                                + "updated_at = updated_at + INTERVAL '1 second', "
                                + "terminal_at = updated_at + INTERVAL '1 second' "
                                + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                            terminalColumns.get("abort_reason"),
                            terminalColumns.get("abort_proof_digest"),
                            NAMESPACE,
                            request.initialAdmissionRequestId())))
        .isInstanceOfSatisfying(
            DataAccessException.class,
            exception ->
                assertPostgresConstraintFailure(
                    exception,
                    "chk_gs_initial_admission_terminal_requires_protection",
                    "New canonical initial-admission terminal requires complete retained Account protection evidence"));
    // The enabled v44 row trigger runs before declarative CHECK evaluation. INSERTs must begin
    // PENDING; terminal UPDATEs reject each incomplete evidence shape with exact attribution.
    for (String missing :
        List.of(
            "account_protection_evidence_version",
            "account_protection_fence",
            "original_owner_fence")) {
      var partial = new HashMap<>(terminalColumns);
      putControlledPartialProtection(fixture, partial, missing);
      assertThatThrownBy(
              () ->
                  fixture.transactions.executeWithoutResult(
                      status ->
                          fixture
                              .dsl
                              .insertInto(
                                  DSL.table(
                                      DSL.name("game_session_canonical_initial_admission_attempt")))
                              .set(partial)
                              .execute()))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              exception -> {
                assertThat(exception.sqlState()).isEqualTo("23514");
                assertThat(exception)
                    .hasStackTraceContaining(
                        "New canonical initial-admission attempts must begin PENDING");
              });
      assertThatThrownBy(
              () ->
                  fixture.transactions.executeWithoutResult(
                      status ->
                          fixture.dsl.execute(
                              "UPDATE game_session_canonical_initial_admission_attempt SET status = 'ABORTED', "
                                  + "abort_reason = ?, abort_proof_digest = ?, updated_at = updated_at + INTERVAL '1 second', "
                                  + "terminal_at = updated_at + INTERVAL '1 second', account_protection_evidence_version = ?, "
                                  + "account_protection_id = ?, account_protection_fence = ?, original_owner_attempt_id = ?, "
                                  + "original_owner_mutation_id = ?, original_owner_fence = ?, account_protection_evidence_bytes = ? "
                                  + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                              partial.get("abort_reason"),
                              partial.get("abort_proof_digest"),
                              partial.get("account_protection_evidence_version"),
                              partial.get("account_protection_id"),
                              partial.get("account_protection_fence"),
                              partial.get("original_owner_attempt_id"),
                              partial.get("original_owner_mutation_id"),
                              partial.get("original_owner_fence"),
                              partial.get("account_protection_evidence_bytes"),
                              NAMESPACE,
                              request.initialAdmissionRequestId())))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              exception ->
                  assertPostgresConstraintFailure(
                      exception,
                      "chk_gs_initial_admission_terminal_requires_protection",
                      "New canonical initial-admission terminal requires complete retained Account protection evidence"));
    }
    assertThat(attemptProjection(fixture, request)).isEqualTo(pendingBefore);
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_initial_admission_attempt "
                        + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                    NAMESPACE,
                    "unprotected-terminal-insert")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void originalProtectionMigrationBytesRemainUnchanged() throws Exception {
    assertOriginalMigrationBlob(
        "V1005__canonical_launch_exact_catalog_identity.sql",
        "368bdf18472699e82f1bccd8b956b62f058b2a12");
    assertOriginalMigrationBlob(
        "V1006__canonical_initial_admission_terminal_protection.sql",
        "ef093cb7262929d9e962f717220088e378f21e6d");
  }

  private static void assertOriginalMigrationBlob(String filename, String expected)
      throws Exception {
    byte[] bytes = Files.readAllBytes(Path.of("src/main/resources/db/migration", filename));
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
    assertThat(HexFormat.of().formatHex(digest.digest(bytes))).isEqualTo(expected);
  }

  @Test
  void pre1006CommittedAndAbortedRowsSurviveForwardProtectionMigration() throws Exception {
    for (boolean stagedOriginalHistory : List.of(true, false)) {
      for (boolean committed : List.of(true, false)) {
        Fixture predecessor = fixture("1005");
        PredecessorAdmission predecessorSources = preparePre1006Admission(predecessor);
        PreparedAdmission prepared = predecessorSources.prepared();
        CanonicalInitialAdmissionRequest request =
            initialAdmissionRequest(
                prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
        reservePre1006Attempt(predecessor, predecessorSources.launch(), request);
        // This is the real target1005 schema. Current terminal writers remain denied, and current
        // typed catalog/terminal reads need columns introduced by1010. Only explicit predecessor
        // SQL
        // supplies the old row shape here; current owner readback is qualified after the upgrade.
        seedPre1006Terminal(predecessor, prepared, predecessorSources.launch(), request, committed);
        String originalSources = predecessorSourceProjection(predecessor);
        String originalAttempt = predecessorAttemptProjection(predecessor, request);
        String originalPointerAndAudit = pointerAndAuditProjection(predecessor);
        String originalHistory = migrationHistoryProjection(predecessor, "1005");
        String originalGuard = null;
        String otherGuards = null;
        String originalFullAttempt = null;
        if (stagedOriginalHistory) {
          migration(predecessor, "1010").migrate();
          migration(predecessor, "1010").validate();
          originalHistory = migrationHistoryProjection(predecessor, "1010");
          originalGuard = launchGuardProjection(predecessor, false);
          otherGuards = otherGuardProjection(predecessor);
          originalFullAttempt = attemptProjection(predecessor, request);
          assertThat(predecessorAttemptProjection(predecessor, request)).isEqualTo(originalAttempt);
          assertThat(pointerAndAuditProjection(predecessor)).isEqualTo(originalPointerAndAudit);
          assertThat(predecessorSourceProjection(predecessor)).isEqualTo(originalSources);
        }
        migration(predecessor, "1011").migrate();
        migration(predecessor, "1011").validate();
        assertThat(migrationHistoryProjection(predecessor, stagedOriginalHistory ? "1010" : "1005"))
            .isEqualTo(originalHistory);
        assertThat(
                predecessor
                    .dsl
                    .fetchSingle(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '1011' AND success")
                    .get(0, Long.class))
            .isEqualTo(1L);
        if (stagedOriginalHistory) {
          assertThat(launchGuardProjection(predecessor, true)).isEqualTo(originalGuard);
          assertThat(otherGuardProjection(predecessor)).isEqualTo(otherGuards);
          assertThat(attemptProjection(predecessor, request)).isEqualTo(originalFullAttempt);
        } else {
          launchGuardProjection(predecessor, true);
        }
        String v1011TriggerIdentity =
            predecessor
                .dsl
                .fetchSingle(
                    "SELECT oid, tgfoid, tgtype, tgenabled FROM pg_trigger "
                        + "WHERE tgrelid = 'game_session_canonical_instance_launch'::regclass "
                        + "AND tgname = 'gs_canonical_instance_launch_complete_catalog_identity'")
                .formatJSON();
        String v1011Guard = launchGuardProjection(predecessor, true);
        migration(predecessor, null).migrate();
        migration(predecessor, null).validate();
        assertV1013RetentionLaunchForeignKey(predecessor);
        assertThat(
                predecessor
                    .dsl
                    .fetchSingle(
                        "SELECT oid, tgfoid, tgtype, tgenabled FROM pg_trigger "
                            + "WHERE tgrelid = 'game_session_canonical_instance_launch'::regclass "
                            + "AND tgname = 'gs_canonical_instance_launch_complete_catalog_identity'")
                    .formatJSON())
            .isEqualTo(v1011TriggerIdentity);
        assertThat(launchGuardProjection(predecessor, true)).isNotEqualTo(v1011Guard);
        assertThat(
                predecessor
                    .dsl
                    .fetchSingle(
                        "SELECT pg_get_functiondef('require_complete_canonical_launch_catalog_identity()'::regprocedure)")
                    .get(0, String.class))
            .contains(
                "catalog_row.complete_policy_set_evidence IS NULL",
                "octet_length(catalog_row.complete_policy_set_evidence) = 0");
        if (stagedOriginalHistory) {
          assertThat(otherGuardProjection(predecessor)).isEqualTo(otherGuards);
          assertThat(attemptProjection(predecessor, request)).isEqualTo(originalFullAttempt);
        }
        assertThat(
                predecessor
                    .dsl
                    .fetchSingle(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '1006' AND success")
                    .get(0, Long.class))
            .isEqualTo(1L);
        assertThat(
                predecessor
                    .dsl
                    .fetchSingle(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '1010' AND success")
                    .get(0, Long.class))
            .isEqualTo(1L);
        assertThat(
                predecessor
                    .dsl
                    .fetchSingle(
                        "SELECT convalidated FROM pg_constraint WHERE conrelid = "
                            + "'game_session_canonical_initial_admission_attempt'::regclass "
                            + "AND conname = 'chk_gs_initial_admission_terminal_requires_protection'")
                    .get(0, Boolean.class))
            .isFalse();
        assertThat(predecessorAttemptProjection(predecessor, request)).isEqualTo(originalAttempt);
        assertThat(pointerAndAuditProjection(predecessor)).isEqualTo(originalPointerAndAudit);
        assertThat(predecessorSourceProjection(predecessor)).isEqualTo(originalSources);
        CanonicalInitialAdmissionOwnerProof retained =
            predecessor
                .initialAdmissionRepository
                .read(NAMESPACE, request.initialAdmissionRequestId())
                .orElseThrow();
        assertThat(retained.outcome())
            .isEqualTo(
                committed
                    ? CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
                    : CanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
        assertThat(retained.requestDigest()).isEqualTo(request.requestDigest());
        assertThat(retained.holdId()).isEqualTo(request.holdId());
        if (committed) {
          assertThat(retained.committedPointerVersion()).isEqualTo(1L);
          assertThat(retained.auditEventId()).isPositive();
        } else {
          assertThat(retained.provesAbort()).isTrue();
          assertThat(retained.committedPointerVersion()).isNull();
          assertThat(retained.auditEventId()).isNull();
        }
        assertThat(
                predecessor
                    .dsl
                    .fetchSingle(
                        "SELECT account_protection_evidence_version, account_protection_id, "
                            + "account_protection_fence, original_owner_attempt_id, original_owner_mutation_id, "
                            + "original_owner_fence, account_protection_evidence_bytes "
                            + "FROM game_session_canonical_initial_admission_attempt "
                            + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                        NAMESPACE,
                        request.initialAdmissionRequestId())
                    .intoArray())
            .containsOnlyNulls();
        assertHumanProtectionUnavailable(
            () -> predecessor.initialAdmissionRepository.commit(request, testWorldProof(request)));
        assertHumanProtectionUnavailable(
            () ->
                predecessor.initialAdmissionRepository.abort(
                    request, "Current Human retry remains unprotected"));
        assertThatThrownBy(
                () ->
                    predecessor.transactions.executeWithoutResult(
                        status ->
                            predecessor.dsl.execute(
                                "UPDATE game_session_canonical_initial_admission_attempt SET updated_at = updated_at + INTERVAL '1 second' "
                                    + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                                NAMESPACE,
                                request.initialAdmissionRequestId())))
            .isInstanceOf(DataAccessException.class);
        assertThat(predecessorAttemptProjection(predecessor, request)).isEqualTo(originalAttempt);
        assertThat(pointerAndAuditProjection(predecessor)).isEqualTo(originalPointerAndAudit);
        assertThat(predecessorSourceProjection(predecessor)).isEqualTo(originalSources);
      }
    }
  }

  @Test
  void malformedProtectedOriginalHistoryFailsCorrectionWithoutChangingRowsGuardsOrHistory()
      throws Exception {
    Fixture fixture = fixture("1005");
    PredecessorAdmission predecessor = preparePre1006Admission(fixture);
    var prepared = predecessor.prepared();
    var request =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    reservePre1006Attempt(fixture, predecessor.launch(), request);
    seedPre1006Terminal(fixture, prepared, predecessor.launch(), request, false);
    migration(fixture, "1010").migrate();
    migration(fixture, "1010").validate();
    var partial =
        new HashMap<>(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT * FROM game_session_canonical_initial_admission_attempt WHERE target_namespace = ? "
                        + "AND initial_admission_request_id = ?",
                    NAMESPACE,
                    request.initialAdmissionRequestId())
                .intoMap());
    partial.put("initial_admission_request_id", "malformed-retained-protected-attempt");
    partial.put("hold_id", UUID.randomUUID());
    partial.put("status", "PENDING");
    partial.put("abort_reason", null);
    partial.put("abort_proof_digest", null);
    partial.put("terminal_at", null);
    putControlledPartialProtection(fixture, partial, "account_protection_fence");
    // Simulate historical corruption admitted by the original SQL's UNKNOWN CHECK branch.
    // Only the original v44 protection trigger is isolated for this one pending INSERT; V28,
    // source/owner FK, pointer/audit, launch and truncate guards remain active. This is not
    // Account-produced terminal protection or a supported owner write.
    fixture.dsl.execute(
        "ALTER TABLE game_session_canonical_initial_admission_attempt DISABLE TRIGGER "
            + "game_session_canonical_initial_admission_v44_terminal_evidence");
    try {
      fixture.transactions.executeWithoutResult(
          status ->
              fixture
                  .dsl
                  .insertInto(
                      DSL.table(DSL.name("game_session_canonical_initial_admission_attempt")))
                  .set(partial)
                  .execute());
    } finally {
      fixture.dsl.execute(
          "ALTER TABLE game_session_canonical_initial_admission_attempt ENABLE TRIGGER "
              + "game_session_canonical_initial_admission_v44_terminal_evidence");
    }
    String rows = allAttemptProjection(fixture);
    String sources = predecessorSourceProjection(fixture);
    String pointers = pointerAndAuditProjection(fixture);
    String history = migrationHistoryProjection(fixture, "1010");
    String checks = constraintProjection(fixture);
    String guards = otherGuardProjection(fixture);
    String launchGuard = launchGuardProjection(fixture, false);
    assertThatThrownBy(() -> migration(fixture, null).migrate())
        .hasStackTraceContaining("23514")
        .hasStackTraceContaining(
            "Retained canonical initial-admission protection evidence is malformed or partial");
    assertThat(allAttemptProjection(fixture)).isEqualTo(rows);
    assertThat(predecessorSourceProjection(fixture)).isEqualTo(sources);
    assertThat(pointerAndAuditProjection(fixture)).isEqualTo(pointers);
    assertThat(migrationHistoryProjection(fixture, "1010")).isEqualTo(history);
    assertThat(
            fixture
                .dsl
                .fetchSingle("SELECT count(*) FROM flyway_schema_history WHERE version = '1011'")
                .get(0, Long.class))
        .isZero();
    assertThat(constraintProjection(fixture)).isEqualTo(checks);
    assertThat(otherGuardProjection(fixture)).isEqualTo(guards);
    assertThat(launchGuardProjection(fixture, false)).isEqualTo(launchGuard);
    migration(fixture, "1010").validate();
  }

  /**
   * Deliberately incomplete synthetic bytes exercise SQL null semantics, never Account authority.
   */
  private static void putControlledPartialProtection(
      Fixture fixture, HashMap<String, Object> row, String missing) {
    row.put("account_protection_evidence_version", 1);
    row.put("account_protection_id", UUID.randomUUID());
    row.put("account_protection_fence", 1L);
    row.put(
        "original_owner_attempt_id",
        fixture
            .dsl
            .fetchSingle(
                "SELECT owner_attempt_id FROM game_session_start_session_operator_attempt "
                    + "WHERE target_namespace = ? ORDER BY owner_attempt_id LIMIT 1",
                NAMESPACE)
            .get(0, UUID.class));
    row.put("original_owner_mutation_id", UUID.randomUUID());
    row.put("original_owner_fence", 1L);
    row.put("account_protection_evidence_bytes", new byte[] {1, 2, 3});
    row.put(missing, null);
  }

  private static String allAttemptProjection(Fixture fixture) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(attempt)) "
                + "ORDER BY initial_admission_request_id)::TEXT FROM game_session_canonical_initial_admission_attempt attempt")
        .get(0, String.class);
  }

  private static String constraintProjection(Fixture fixture) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT jsonb_agg(to_jsonb(guard) ORDER BY oid)::TEXT FROM ("
                + "SELECT oid, conname, convalidated, pg_get_constraintdef(oid) AS definition FROM pg_constraint "
                + "WHERE conrelid IN ('game_session_canonical_initial_admission_attempt'::regclass, "
                + "'game_session_canonical_instance_launch'::regclass)) guard")
        .get(0, String.class);
  }

  /** Old-schema SQL is confined to rows whose current owners require unavailable1010 columns. */
  private static PredecessorAdmission preparePre1006Admission(Fixture fixture) throws Exception {
    IntakeReceipt source = fixture.registerSource();
    var association =
        Objects.requireNonNull(
            fixture.transactions.execute(
                status ->
                    new GameSessionFreshTenantAssociationRepository(fixture.dsl)
                        .associateFresh(
                            ASSOCIATION_OPERATION,
                            NAMESPACE,
                            source,
                            CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(
                                source.source()))));
    var request = catalogRequest(source);
    var policy = publishedPolicy(source);
    // This preparation is compatible with1005 and validates the complete closed policy/source
    // evidence through the current owner; only its subsequent SQL writer needs1010 columns.
    assertThat(fixture.catalogRepository.prepareInitialPublicProduction(request, policy))
        .isNotNull();
    byte[] policyBytes = RealmPolicyPublishedEvidenceCodec.encode(policy);
    var authored = source.source();
    UUID realm = UUID.randomUUID();
    UUID namespace = UUID.randomUUID();
    String baseDigest =
        predecessorCatalogFrame(
            fixture,
            "game-session-canonical-realm-catalog-request/v1",
            "1",
            request.requestId().toString(),
            NAMESPACE,
            TENANT.toString(),
            source.operationId().toString(),
            source.intakeRequestId().toString(),
            source.requestDigest(),
            source.receiptDigest(),
            Integer.toString(authored.schemaVersion()),
            authored.registrationRequestId().toString(),
            authored.operationId().toString(),
            authored.requestDigest(),
            TENANT.toString(),
            authored.tenantSlug(),
            authored.worldSlug(),
            authored.worldDisplayName(),
            Long.toString(authored.sourceGameRowId()),
            authored.sourceGameTenantKey(),
            authored.provenanceKind(),
            authored.evidenceDigest(),
            request.realmSlug(),
            request.realmDisplayName(),
            "true",
            "true",
            "SHARED",
            request.characterCreationPolicy(),
            "ABSENT",
            "ABSENT");
    String catalogDigest =
        predecessorCatalogFrame(
            fixture,
            "game-session-complete-catalog-request/v1",
            baseDigest,
            HexFormat.of().formatHex(policyBytes));
    String receiptDigest =
        predecessorCatalogFrame(
            fixture,
            "game-session-canonical-realm-catalog-receipt/v1",
            "1",
            NAMESPACE,
            TENANT.toString(),
            request.requestId().toString(),
            catalogDigest,
            realm.toString(),
            namespace.toString(),
            "1",
            source.operationId().toString(),
            source.receiptDigest());
    var catalog =
        new CanonicalRealmCatalogSnapshot(
            NAMESPACE,
            TENANT,
            authored.tenantSlug(),
            authored.worldSlug(),
            realm,
            request.realmSlug(),
            request.realmDisplayName(),
            true,
            true,
            "SHARED",
            namespace,
            request.characterCreationPolicy(),
            1L,
            request.requestId(),
            catalogDigest,
            receiptDigest,
            source);
    fixture.transactions.executeWithoutResult(
        status -> {
          fixture.dsl.execute(
              "INSERT INTO gameplay_tenant_shared_playable_state_namespace (tenant_id, "
                  + "playable_state_namespace_id, canonical_tenant_id, target_namespace, "
                  + "source_intake_operation_id, source_intake_schema_version, source_intake_request_id, "
                  + "source_intake_request_digest, source_intake_receipt_digest, source_schema_version, "
                  + "source_registration_request_id, source_operation_id, source_request_digest, "
                  + "source_game_row_id, source_game_tenant_key, source_provenance_kind, source_evidence_digest) "
                  + "VALUES (NULL, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              namespace,
              TENANT,
              NAMESPACE,
              source.operationId(),
              source.intakeRequestId(),
              source.requestDigest(),
              source.receiptDigest(),
              authored.schemaVersion(),
              authored.registrationRequestId(),
              authored.operationId(),
              authored.requestDigest(),
              authored.sourceGameRowId(),
              authored.sourceGameTenantKey(),
              authored.provenanceKind(),
              authored.evidenceDigest());
          fixture.dsl.execute(
              "INSERT INTO game_session_canonical_realm_catalog (target_namespace, canonical_tenant_id, "
                  + "tenant_slug, world_slug, realm_id, realm_slug, realm_display_name, visible, "
                  + "public_production, state_scope, playable_state_namespace_id, character_creation_policy, "
                  + "catalog_revision, creation_request_id, request_digest, receipt_digest, "
                  + "source_intake_operation_id, source_intake_schema_version, source_intake_request_id, "
                  + "source_intake_request_digest, source_intake_receipt_digest, source_schema_version, "
                  + "source_registration_request_id, source_operation_id, source_request_digest, "
                  + "source_world_display_name, source_game_row_id, source_game_tenant_key, "
                  + "source_provenance_kind, source_evidence_digest, complete_policy_set_evidence) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, 'SHARED', ?, ?, 1, ?, ?, ?, ?, 1, "
                  + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              NAMESPACE,
              TENANT,
              authored.tenantSlug(),
              authored.worldSlug(),
              realm,
              request.realmSlug(),
              request.realmDisplayName(),
              namespace,
              request.characterCreationPolicy(),
              request.requestId(),
              catalogDigest,
              receiptDigest,
              source.operationId(),
              source.intakeRequestId(),
              source.requestDigest(),
              source.receiptDigest(),
              authored.schemaVersion(),
              authored.registrationRequestId(),
              authored.operationId(),
              authored.requestDigest(),
              authored.worldDisplayName(),
              authored.sourceGameRowId(),
              authored.sourceGameTenantKey(),
              authored.provenanceKind(),
              authored.evidenceDigest(),
              policyBytes);
        });
    var preparationRequest = preparationRequest(source, catalog);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            preparationRequest.descriptorRequest(catalog, authored),
            "launch-descriptor-canonical-admission",
            72,
            false,
            null,
            "{}",
            "generation-1",
            3,
            73,
            "published-bundle-73",
            false,
            null);
    var binding = CanonicalHumanPublicationOwnerTestFixtures.completeBinding(descriptor, policy);
    var tuple =
        CanonicalHumanPublicationOwnerTestFixtures.tuple(
            NAMESPACE,
            TENANT,
            ACTOR,
            descriptor.gameTemplateId(),
            descriptor.controlPlaneRequestId());
    CanonicalHumanPublicationOwnerTestFixtures.pinLaunch(
        fixture.dsl, fixture.transactions, tuple, authored, policy, binding);
    UUID instanceUuid = UUID.randomUUID();
    String bindingJson =
        tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(binding);
    long tenantId = association.legacyGameSessionTenantId();
    var sourceIdentity = association.sourceEvidence();
    Long instanceId =
        fixture.transactions.execute(
            status -> {
              long allocated =
                  fixture
                      .dsl
                      .fetchSingle(
                          "INSERT INTO game_instances (tenant_id, runtime_version, owner_account_uuid, status, "
                              + "row_version, game_template_id, launch_descriptor_id, version_id, release_bundle_id, "
                              + "version_state_epoch, generation_config_revision, run_owned_start_request_id, "
                              + "run_owned_start_request_digest, run_owned_start_published_release_bundle_ref, "
                              + "game_instance_uuid) VALUES (?, ?, ?, 'STARTING', 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                              + "RETURNING id",
                          tenantId,
                          Long.toString(descriptor.versionId()),
                          ACTOR,
                          descriptor.gameTemplateId(),
                          descriptor.launchDescriptorId(),
                          descriptor.versionId(),
                          descriptor.releaseBundleId(),
                          descriptor.versionStateEpoch(),
                          descriptor.generationConfigRevision(),
                          tuple.controlPlaneRequestId(),
                          tuple.mutationDigest(),
                          descriptor.publishedReleaseBundleRef(),
                          instanceUuid)
                      .get("id", Long.class);
              fixture.dsl.execute(
                  "INSERT INTO game_session_canonical_instance_launch (target_namespace, control_plane_request_id, "
                      + "game_session_tenant_id, canonical_tenant_id, tenant_association_operation_id, "
                      + "tenant_association_request_id, tenant_source_schema_version, tenant_source_target_namespace, "
                      + "tenant_source_request_id, tenant_source_canonical_tenant_id, tenant_source_game_row_id, "
                      + "tenant_source_game_tenant_key, tenant_source_provenance_kind, tenant_association_kind, "
                      + "game_instance_id, game_instance_uuid, canonical_realm_id, world_slug, "
                      + "playable_state_namespace_id, playable_state_scope, public_production, "
                      + "captured_starting_row_version, game_template_id, launch_descriptor_id, version_id, "
                      + "release_bundle_id, generation_config_revision, version_state_epoch, complete_launch_binding_evidence, "
                      + "original_start_session_tuple, catalog_creation_request_id, catalog_revision, "
                      + "catalog_source_intake_operation_id, catalog_source_intake_request_id) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FRESH_SOURCE_BOUND', ?, ?, ?, ?, ?, "
                      + "'SHARED', TRUE, 0, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, 1, ?, ?)",
                  NAMESPACE,
                  tuple.controlPlaneRequestId(),
                  tenantId,
                  TENANT,
                  ASSOCIATION_OPERATION,
                  sourceIdentity.requestId(),
                  sourceIdentity.schemaVersion(),
                  NAMESPACE,
                  sourceIdentity.requestId(),
                  TENANT,
                  sourceIdentity.sourceGameRowId(),
                  sourceIdentity.sourceGameTenantKey(),
                  sourceIdentity.provenanceKind(),
                  allocated,
                  instanceUuid,
                  realm,
                  authored.worldSlug(),
                  namespace,
                  descriptor.gameTemplateId(),
                  descriptor.launchDescriptorId(),
                  descriptor.versionId(),
                  descriptor.releaseBundleId(),
                  descriptor.generationConfigRevision(),
                  descriptor.versionStateEpoch(),
                  bindingJson,
                  tuple.canonicalBytes(),
                  CATALOG_REQUEST,
                  source.operationId(),
                  source.intakeRequestId());
              fixture.dsl.execute(
                  "UPDATE game_instances SET status = 'RUNNING' WHERE tenant_id = ? AND id = ?",
                  tenantId,
                  allocated);
              return allocated;
            });
    var captured =
        new CanonicalGameInstanceLaunchAssociation(
            NAMESPACE,
            tenantId,
            ASSOCIATION_OPERATION,
            TENANT,
            instanceUuid,
            authored.worldSlug(),
            namespace,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            tuple.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            0L,
            binding,
            CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING,
            0L);
    var launch =
        new CanonicalInitialAdmissionLaunchTarget(
            captured, realm, Objects.requireNonNull(instanceId), descriptor.versionId());
    return new PredecessorAdmission(new PreparedAdmission(catalog, binding, instanceUuid), launch);
  }

  private static void reservePre1006Attempt(
      Fixture fixture,
      CanonicalInitialAdmissionLaunchTarget launch,
      CanonicalInitialAdmissionRequest request) {
    fixture.transactions.executeWithoutResult(
        status ->
            fixture.dsl.execute(
                "INSERT INTO game_session_canonical_initial_admission_attempt (target_namespace, "
                    + "initial_admission_request_id, request_digest, canonical_tenant_id, world_slug, realm_id, "
                    + "playable_state_namespace_id, playable_state_scope, canonical_game_instance_id, "
                    + "game_session_tenant_id, game_instance_id, canonical_version_id, runtime_version_id, "
                    + "expected_catalog_revision, origin_kind, expected_prior_pointer_version, active_lifecycle_epoch, "
                    + "hold_id, hold_fence, hold_binding_digest, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', "
                    + "clock_timestamp(), clock_timestamp())",
                NAMESPACE,
                request.initialAdmissionRequestId(),
                request.requestDigest(),
                TENANT,
                request.worldSlug(),
                request.realmId(),
                request.playableStateNamespaceId(),
                request.playableStateScope(),
                request.canonicalGameInstanceId(),
                launch.association().gameSessionTenantId(),
                launch.gameInstanceId(),
                request.canonicalVersionId(),
                launch.runtimeVersionId(),
                request.expectedCatalogRevision(),
                request.originKind().name(),
                request.expectedPriorPointerVersion(),
                request.activeLifecycleEpoch(),
                request.holdId(),
                request.holdFence(),
                request.holdBindingDigest()));
  }

  /** Independent SQL encoding of the predecessor catalog's decimal-length UTF-8 framing. */
  private static String predecessorCatalogFrame(Fixture fixture, String... fields) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT 'sha256:' || encode(sha256(string_agg(convert_to(octet_length(convert_to(field, 'UTF8'))::TEXT "
                + "|| ':', 'UTF8') || convert_to(field, 'UTF8'), ''::BYTEA ORDER BY ordinal)), 'hex') AS digest "
                + "FROM unnest(?::TEXT[]) WITH ORDINALITY framed(field, ordinal)",
            (Object) fields)
        .get("digest", String.class);
  }

  private record PredecessorAdmission(
      PreparedAdmission prepared, CanonicalInitialAdmissionLaunchTarget launch) {}

  /** Entire original source, namespace, catalog, runtime, launch and original-pin heap evidence. */
  private static String predecessorSourceProjection(Fixture fixture) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT jsonb_build_array("
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY operation_id) FROM game_session_authored_world_source_intake source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY canonical_tenant_id) FROM game_session_authored_world_tenant_source_binding source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY association_operation_id) FROM game_session_fresh_tenant_association source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY canonical_tenant_id) FROM game_session_tenant_canonical_claim source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY to_jsonb(source)::TEXT) FROM game_session_tenant_scope_reservation source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY canonical_tenant_id) FROM gameplay_tenant_shared_playable_state_namespace source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source) - ARRAY['fixture_policy_request_bytes', 'fixture_policy_seal_operation_id', 'fixture_policy_seal_request_digest', 'fixture_policy_seal_evidence_digest']) ORDER BY realm_id) FROM game_session_canonical_realm_catalog source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY id) FROM game_instances source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source) - ARRAY['fixture_policy_request_bytes', 'fixture_policy_seal_operation_id', 'fixture_policy_seal_request_digest', 'fixture_policy_seal_evidence_digest']) ORDER BY game_instance_id) FROM game_session_canonical_instance_launch source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY owner_attempt_id) FROM game_session_start_session_operator_attempt source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY owner_attempt_id) FROM game_session_start_session_template_association_pin source),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::TEXT, to_jsonb(source)) ORDER BY owner_attempt_id) FROM game_session_start_session_launch_descriptor_pin source))::TEXT AS projection")
        .get("projection", String.class);
  }

  private static void seedPre1006ExpectedClosedTerminal(
      Fixture fixture,
      PreparedAdmission prepared,
      CanonicalInitialAdmissionLaunchTarget launch,
      CanonicalInitialAdmissionRequest request,
      net.firedevops.firemud.gamesession.dto.CanonicalClosedAdmissionPointerSnapshot origin) {
    Instant terminalAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    fixture.transactions.executeWithoutResult(
        status -> {
          long audit =
              new GameplayAdmissionPointerEventRepository(fixture.dsl)
                  .appendCanonicalInitialAdmissionOpen(
                      request,
                      prepared.catalog(),
                      launch,
                      2L,
                      prepared.catalog().catalogRevision(),
                      terminalAt);
          long pointer =
              fixture
                  .dsl
                  .fetchSingle(
                      "UPDATE gameplay_admission_pointer SET (world_slug, realm_slug, world_display_name, realm_display_name, "
                          + "tenant_id, game_instance_id, pointer_version, visible, requires_character_selection, state_scope, "
                          + "character_creation_policy, last_updated_by, last_update_reason, updated_at, public_production_realm, "
                          + "catalog_revision, realm_id, playable_state_namespace_id, representation_version, target_namespace, "
                          + "canonical_tenant_id, admission_state, canonical_game_instance_id, canonical_version_id, runtime_version_id, "
                          + "initial_admission_request_id, initial_admission_request_digest, initial_admission_origin_kind, "
                          + "initial_admission_prior_pointer_version, initial_admission_active_epoch, initial_admission_hold_id, "
                          + "initial_admission_hold_fence, initial_admission_hold_binding_digest) = "
                          + "(SELECT world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, game_instance_id, "
                          + "pointer_version, visible, requires_character_selection, state_scope, character_creation_policy, "
                          + "actor_principal, reason, occurred_at, public_production_realm, catalog_revision, realm_id, "
                          + "playable_state_namespace_id, representation_version, target_namespace, canonical_tenant_id, admission_state, "
                          + "canonical_game_instance_id, canonical_version_id, runtime_version_id, initial_admission_request_id, "
                          + "initial_admission_request_digest, initial_admission_origin_kind, initial_admission_prior_pointer_version, "
                          + "initial_admission_active_epoch, initial_admission_hold_id, initial_admission_hold_fence, initial_admission_hold_binding_digest "
                          + "FROM gameplay_admission_pointer_event WHERE id = ?) WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? RETURNING id",
                      audit,
                      NAMESPACE,
                      TENANT,
                      request.realmId())
                  .get("id", Long.class);
          String proof =
              fixture
                  .dsl
                  .fetchSingle(
                      "SELECT 'sha256:' || encode(sha256(string_agg(int4send(octet_length(convert_to(field, 'UTF8'))) "
                          + "|| convert_to(field, 'UTF8'), ''::BYTEA ORDER BY ordinal)), 'hex') AS proof "
                          + "FROM game_session_canonical_initial_admission_attempt attempt CROSS JOIN LATERAL unnest(ARRAY["
                          + "'gs-canonical-initial-admission-commit-proof/v1', request_digest, initial_admission_request_id, "
                          + "target_namespace, canonical_tenant_id::TEXT, world_slug, realm_id::TEXT, playable_state_namespace_id::TEXT, "
                          + "playable_state_scope, canonical_game_instance_id::TEXT, canonical_version_id::TEXT, runtime_version_id::TEXT, "
                          + "active_lifecycle_epoch::TEXT, expected_catalog_revision::TEXT, origin_kind, expected_prior_pointer_version::TEXT, "
                          + "hold_id::TEXT, hold_fence::TEXT, hold_binding_digest, '2', ?::TEXT, ?::TEXT, ?::TEXT, ?::TEXT, ?::TEXT]) "
                          + "WITH ORDINALITY fields(field, ordinal) WHERE target_namespace = ? AND initial_admission_request_id = ?",
                      Long.toString(audit),
                      origin.requestId().toString(),
                      origin.requestDigest(),
                      Long.toString(origin.auditEventId()),
                      terminalAt.toString(),
                      NAMESPACE,
                      request.initialAdmissionRequestId())
                  .get("proof", String.class);
          fixture.dsl.execute(
              "UPDATE game_session_canonical_initial_admission_attempt SET status = 'COMMITTED', pointer_id = ?, "
                  + "pointer_version = 2, audit_event_id = ?, prior_pointer_request_id = ?, prior_pointer_request_digest = ?, "
                  + "prior_pointer_audit_event_id = ?, commit_proof_digest = ?, updated_at = CAST(? AS TIMESTAMPTZ), "
                  + "terminal_at = CAST(? AS TIMESTAMPTZ) WHERE target_namespace = ? AND initial_admission_request_id = ?",
              pointer,
              audit,
              origin.requestId(),
              origin.requestDigest(),
              origin.auditEventId(),
              proof,
              terminalAt.atOffset(ZoneOffset.UTC),
              terminalAt.atOffset(ZoneOffset.UTC),
              NAMESPACE,
              request.initialAdmissionRequestId());
        });
  }

  private static void seedPre1006Terminal(
      Fixture fixture,
      PreparedAdmission prepared,
      CanonicalInitialAdmissionLaunchTarget launch,
      CanonicalInitialAdmissionRequest request,
      boolean committed) {
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                        + "AND table_name = 'game_session_canonical_initial_admission_attempt' "
                        + "AND column_name = 'account_protection_evidence_bytes'")
                .get(0, Long.class))
        .isZero();
    Instant terminalAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    fixture.transactions.executeWithoutResult(
        status -> {
          Long pointerId = null;
          Long auditId = null;
          String reason = "Explicit pre1006 owner abort fixture";
          if (committed) {
            auditId =
                new GameplayAdmissionPointerEventRepository(fixture.dsl)
                    .appendCanonicalInitialAdmissionOpen(
                        request,
                        prepared.catalog(),
                        launch,
                        1L,
                        prepared.catalog().catalogRevision(),
                        terminalAt);
            pointerId =
                fixture
                    .dsl
                    .fetchSingle(
                        "INSERT INTO gameplay_admission_pointer (world_slug, realm_slug, world_display_name, "
                            + "realm_display_name, tenant_id, game_instance_id, pointer_version, visible, "
                            + "requires_character_selection, state_scope, character_creation_policy, "
                            + "last_updated_by, last_update_reason, created_at, updated_at, "
                            + "public_production_realm, catalog_revision, realm_id, playable_state_namespace_id, "
                            + "representation_version, target_namespace, canonical_tenant_id, admission_state, "
                            + "canonical_game_instance_id, canonical_version_id, runtime_version_id, "
                            + "initial_admission_request_id, initial_admission_request_digest, "
                            + "initial_admission_origin_kind, initial_admission_prior_pointer_version, "
                            + "initial_admission_active_epoch, initial_admission_hold_id, "
                            + "initial_admission_hold_fence, initial_admission_hold_binding_digest) "
                            + "SELECT world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
                            + "game_instance_id, pointer_version, visible, requires_character_selection, "
                            + "state_scope, character_creation_policy, actor_principal, reason, occurred_at, "
                            + "occurred_at, public_production_realm, catalog_revision, realm_id, "
                            + "playable_state_namespace_id, representation_version, target_namespace, "
                            + "canonical_tenant_id, admission_state, canonical_game_instance_id, "
                            + "canonical_version_id, runtime_version_id, initial_admission_request_id, "
                            + "initial_admission_request_digest, initial_admission_origin_kind, "
                            + "initial_admission_prior_pointer_version, initial_admission_active_epoch, "
                            + "initial_admission_hold_id, initial_admission_hold_fence, "
                            + "initial_admission_hold_binding_digest FROM gameplay_admission_pointer_event "
                            + "WHERE id = ? RETURNING id",
                        auditId)
                    .get("id", Long.class);
          }
          String proof =
              predecessorTerminalProofDigest(
                  fixture, request, committed, auditId, reason, terminalAt);
          if (committed) {
            fixture.dsl.execute(
                "UPDATE game_session_canonical_initial_admission_attempt SET "
                    + "status = 'COMMITTED', pointer_id = ?, pointer_version = 1, audit_event_id = ?, "
                    + "commit_proof_digest = ?, updated_at = CAST(? AS TIMESTAMPTZ), "
                    + "terminal_at = CAST(? AS TIMESTAMPTZ) "
                    + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                pointerId,
                auditId,
                proof,
                terminalAt.atOffset(ZoneOffset.UTC),
                terminalAt.atOffset(ZoneOffset.UTC),
                NAMESPACE,
                request.initialAdmissionRequestId());
          } else {
            fixture.dsl.execute(
                "UPDATE game_session_canonical_initial_admission_attempt SET "
                    + "status = 'ABORTED', abort_reason = ?, abort_proof_digest = ?, "
                    + "updated_at = CAST(? AS TIMESTAMPTZ), terminal_at = CAST(? AS TIMESTAMPTZ) "
                    + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                reason,
                proof,
                terminalAt.atOffset(ZoneOffset.UTC),
                terminalAt.atOffset(ZoneOffset.UTC),
                NAMESPACE,
                request.initialAdmissionRequestId());
          }
        });
  }

  /** Independent PostgreSQL encoding of the retained v1 length-prefixed terminal frame. */
  private static String predecessorTerminalProofDigest(
      Fixture fixture,
      CanonicalInitialAdmissionRequest request,
      boolean committed,
      Long auditId,
      String reason,
      Instant terminalAt) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT 'sha256:' || encode(sha256(string_agg(int4send(octet_length(convert_to(field, 'UTF8'))) "
                + "|| convert_to(field, 'UTF8'), ''::BYTEA ORDER BY ordinal)), 'hex') AS proof "
                + "FROM game_session_canonical_initial_admission_attempt attempt "
                + "CROSS JOIN LATERAL unnest(ARRAY[?::TEXT, request_digest, "
                + "initial_admission_request_id, target_namespace, canonical_tenant_id::TEXT, world_slug, "
                + "realm_id::TEXT, playable_state_namespace_id::TEXT, playable_state_scope, "
                + "canonical_game_instance_id::TEXT, canonical_version_id::TEXT, runtime_version_id::TEXT, "
                + "active_lifecycle_epoch::TEXT, expected_catalog_revision::TEXT, origin_kind, "
                + "COALESCE(expected_prior_pointer_version::TEXT, ''), hold_id::TEXT, hold_fence::TEXT, "
                + "hold_binding_digest] || CASE WHEN ? THEN ARRAY['1', ?::TEXT] ELSE ARRAY[?::TEXT] END "
                + "|| ARRAY['', '', '', ?::TEXT]) WITH ORDINALITY fields(field, ordinal) "
                + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
            committed
                ? "gs-canonical-initial-admission-commit-proof/v1"
                : "gs-canonical-initial-admission-abort-proof/v1",
            committed,
            auditId == null ? null : auditId.toString(),
            reason,
            terminalAt.toString(),
            NAMESPACE,
            request.initialAdmissionRequestId())
        .get("proof", String.class);
  }

  /** Every pre1006 attempt column, including byte values, and the original heap xmin. */
  private static String predecessorAttemptProjection(
      Fixture fixture, CanonicalInitialAdmissionRequest request) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT jsonb_build_array(xmin::TEXT, to_jsonb(attempt) - ARRAY["
                + "'account_protection_evidence_version', 'account_protection_id', "
                + "'account_protection_fence', 'original_owner_attempt_id', 'original_owner_mutation_id', "
                + "'original_owner_fence', 'account_protection_evidence_bytes'])::TEXT AS projection "
                + "FROM game_session_canonical_initial_admission_attempt attempt "
                + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
            NAMESPACE,
            request.initialAdmissionRequestId())
        .get("projection", String.class);
  }

  private static void assertHumanProtectionUnavailable(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalHumanAdmissionProtectionUnavailableException.class)
        .hasMessageContaining("Account terminal protection");
  }

  private static void assertPostgresConstraintFailure(
      Throwable failure, String expectedConstraint, String expectedMessage) {
    PSQLException postgresFailure = postgresException(failure);
    assertThat(postgresFailure.getSQLState()).isEqualTo("23514");
    assertThat(postgresFailure.getServerErrorMessage()).isNotNull();
    assertThat(postgresFailure.getServerErrorMessage().getConstraint())
        .isEqualTo(expectedConstraint);
    assertThat(postgresFailure.getServerErrorMessage().getMessage()).isEqualTo(expectedMessage);
  }

  private static PSQLException postgresException(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
      if (current instanceof PSQLException postgresFailure) {
        return postgresFailure;
      }
    }
    throw new AssertionError("PostgreSQL exception was not present in the cause chain", failure);
  }

  private static String attemptProjection(
      Fixture fixture, CanonicalInitialAdmissionRequest request) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT xmin::text || '/' || to_jsonb(attempt)::text AS projection "
                + "FROM game_session_canonical_initial_admission_attempt attempt WHERE target_namespace = ? "
                + "AND initial_admission_request_id = ?",
            request.targetNamespace(),
            request.initialAdmissionRequestId())
        .get("projection", String.class);
  }

  private static void assertDistinctAccountSourceReadersShareAndExcludeWriters(
      Fixture fixture,
      net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget current,
      CanonicalInitialAdmissionRequest.OriginKind expectedOrigin)
      throws Exception {
    var qualified =
        fixture.initialAdmissionRepository.readCurrentOpenSnapshotForRealm(
            NAMESPACE, TENANT, current.realmId());
    assertThat(qualified.ownerProof().originKind()).isEqualTo(expectedOrigin);
    var target =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(TENANT.toString())
            .setRealmUuid(current.realmId().toString())
            .setWorldSlug(current.worldSlug())
            .setRealmSlug(current.realmSlug())
            .setGameInstanceUuid(current.canonicalGameInstanceId().toString())
            .setCanonicalVersionUuid(current.canonicalVersionId().toString())
            .setPlayableStateNamespaceUuid(current.playableStateNamespaceId().toString())
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                    .CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .setCatalogRevision(current.catalogRevision())
            .setPointerVersion(current.pointerVersion())
            .setActiveWorldEpoch(current.activeWorldEpoch())
            .setAdmissionPointerSnapshotDigest(current.admissionPointerSnapshotDigest())
            .setPublishedOwnerProofDigest(current.ownerProofDigest().substring("sha256:".length()))
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedReleaseBundleRef("controlled:retained-fixture")
            .build();
    var requests =
        List.of(
            net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.request(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NAMESPACE, target),
            net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.request(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NAMESPACE, target));
    assertThat(requests.getFirst().getCanonicalAccountUuid())
        .isNotEqualTo(requests.get(1).getCanonicalAccountUuid());
    var references =
        new CanonicalGameplayRosterReferenceRepository(
            fixture.dsl,
            new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy(
                java.time.Duration.ofMinutes(1),
                java.time.Duration.ofMinutes(1),
                java.time.Duration.ofMinutes(1),
                java.time.Duration.ofSeconds(1),
                8));
    var readerPids =
        List.of(
            new java.util.concurrent.atomic.AtomicInteger(),
            new java.util.concurrent.atomic.AtomicInteger());
    var acquired =
        List.of(
            new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(1));
    var release =
        List.of(
            new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(1));
    String ownerBefore = pointerAndAuditProjection(fixture);
    String sourcesBefore = predecessorSourceProjection(fixture);
    long originalsBefore =
        fixture
            .dsl
            .fetchSingle("SELECT count(*) FROM game_session_canonical_roster_selection")
            .get(0, Long.class);
    long fencesBefore =
        fixture
            .dsl
            .fetchSingle("SELECT count(*) FROM game_session_canonical_roster_target_fences")
            .get(0, Long.class);
    var writerSql =
        List.of(
            "SELECT id FROM gameplay_admission_pointer WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? FOR UPDATE",
            "SELECT initial_admission_request_id FROM game_session_canonical_initial_admission_attempt WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? AND status = 'COMMITTED' FOR UPDATE",
            "SELECT realm_id FROM game_session_canonical_realm_catalog WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? FOR UPDATE");
    var writerPids =
        List.of(
            new java.util.concurrent.atomic.AtomicInteger(),
            new java.util.concurrent.atomic.AtomicInteger(),
            new java.util.concurrent.atomic.AtomicInteger());
    var readers = new java.util.ArrayList<java.util.concurrent.Future<String>>();
    var writers = new java.util.ArrayList<java.util.concurrent.Future<?>>();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(5)) {
      try {
        for (int index = 0; index < requests.size(); index++) {
          int slot = index;
          readers.add(
              executor.submit(
                  () ->
                      fixture.transactions.execute(
                          status -> {
                            readerPids
                                .get(slot)
                                .set(
                                    Objects.requireNonNull(
                                        fixture
                                            .dsl
                                            .fetchSingle("SELECT pg_backend_pid()")
                                            .get(0, Integer.class)));
                            var original = references.begin(requests.get(slot));
                            assertThat(
                                    fixture.initialAdmissionRepository.lockExactCurrentOpenSnapshot(
                                        qualified))
                                .isEqualTo(qualified);
                            assertThat(references.find(requests.get(slot))).contains(original);
                            acquired.get(slot).countDown();
                            try {
                              if (!release
                                  .get(slot)
                                  .await(30L, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                    "Current-source reader was not released");
                              }
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(interrupted);
                            } finally {
                              // Retained owner histories and existing selection assertions remain
                              // unchanged.
                              status.setRollbackOnly();
                            }
                            return original.getTargetFenceUuid();
                          })));
          // Both complete revalidations must acquire their shared locks while the first stays open.
          assertThat(acquired.get(slot).await(5L, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(readerPids.getFirst().get()).isNotEqualTo(readerPids.get(1).get());
        int firstReleasedReader = -1;
        for (int index = 0; index < writerSql.size(); index++) {
          int slot = index;
          var started = new java.util.concurrent.CountDownLatch(1);
          writers.add(
              executor.submit(
                  () ->
                      fixture.transactions.execute(
                          status -> {
                            writerPids
                                .get(slot)
                                .set(
                                    Objects.requireNonNull(
                                        fixture
                                            .dsl
                                            .fetchSingle("SELECT pg_backend_pid()")
                                            .get(0, Integer.class)));
                            started.countDown();
                            var locked =
                                fixture.dsl.fetchSingle(
                                    writerSql.get(slot), NAMESPACE, TENANT, current.realmId());
                            status.setRollbackOnly();
                            return locked.get(0);
                          })));
          assertThat(started.await(5L, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
          int holder =
              awaitBackendBlockedByCurrentSourceReader(
                  fixture.dsl,
                  writerPids.get(slot).get(),
                  readerPids.getFirst().get(),
                  readerPids.get(1).get());
          if (slot == 0) firstReleasedReader = holder == readerPids.getFirst().get() ? 0 : 1;
        }
        assertThat(firstReleasedReader).isNotNegative();
        int remainingReader = 1 - firstReleasedReader;
        release.get(firstReleasedReader).countDown();
        String firstFence =
            readers.get(firstReleasedReader).get(5L, java.util.concurrent.TimeUnit.SECONDS);
        // PostgreSQL can wait on SHARE members in turn; each writer must still await the other
        // exact reader after its first observed blocker releases its transaction.
        for (var writerPid : writerPids) {
          awaitBackendBlockedBy(
              fixture.dsl, writerPid.get(), readerPids.get(remainingReader).get());
        }
        release.get(remainingReader).countDown();
        String secondFence =
            readers.get(remainingReader).get(5L, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(firstFence).isNotEqualTo(secondFence);
        for (var writer : writers) {
          assertThat(writer.get(5L, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        }
      } finally {
        release.forEach(java.util.concurrent.CountDownLatch::countDown);
      }
    }
    assertThat(pointerAndAuditProjection(fixture)).isEqualTo(ownerBefore);
    assertThat(predecessorSourceProjection(fixture)).isEqualTo(sourcesBefore);
    assertThat(
            fixture
                .dsl
                .fetchSingle("SELECT count(*) FROM game_session_canonical_roster_selection")
                .get(0, Long.class))
        .isEqualTo(originalsBefore);
    assertThat(
            fixture
                .dsl
                .fetchSingle("SELECT count(*) FROM game_session_canonical_roster_target_fences")
                .get(0, Long.class))
        .isEqualTo(fencesBefore);
  }

  private static int awaitBackendBlockedByCurrentSourceReader(
      DSLContext observer, int waitingPid, int firstReaderPid, int secondReaderPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    Integer holder = null;
    while (System.nanoTime() < deadline) {
      holder =
          observer
              .fetchSingle(
                  "SELECT CASE WHEN ? = ANY(pg_blocking_pids(?)) THEN ? WHEN ? = ANY(pg_blocking_pids(?)) THEN ? END",
                  firstReaderPid,
                  waitingPid,
                  firstReaderPid,
                  secondReaderPid,
                  waitingPid,
                  secondReaderPid)
              .get(0, Integer.class);
      if (holder != null) break;
      java.util.concurrent.TimeUnit.MILLISECONDS.sleep(10L);
    }
    assertThat(holder)
        .as("Writer backend %s waits on an exact current-source reader", waitingPid)
        .isIn(firstReaderPid, secondReaderPid);
    return Objects.requireNonNull(holder);
  }

  private static void awaitBackendBlockedBy(DSLContext observer, int waitingPid, int holderPid)
      throws InterruptedException {
    assertThat(waitingPid).isPositive().isNotEqualTo(holderPid);
    assertThat(holderPid).isPositive();
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    boolean blocked = false;
    while (System.nanoTime() < deadline) {
      blocked =
          Boolean.TRUE.equals(
              observer
                  .fetchSingle("SELECT ? = ANY(pg_blocking_pids(?))", holderPid, waitingPid)
                  .get(0, Boolean.class));
      if (blocked) break;
      java.util.concurrent.TimeUnit.MILLISECONDS.sleep(10L);
    }
    assertThat(blocked)
        .as("PostgreSQL backend %s waits on exact holder %s", waitingPid, holderPid)
        .isTrue();
  }

  private static String pointerAndAuditProjection(Fixture fixture) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT jsonb_build_array("
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::text, to_jsonb(pointer)) ORDER BY id) FROM gameplay_admission_pointer pointer),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::text, to_jsonb(event)) ORDER BY id) FROM gameplay_admission_pointer_event event))::text AS projection")
        .get("projection", String.class);
  }

  private static Flyway migration(Fixture fixture, String target) {
    String schema = fixture.dsl.fetchSingle("SELECT current_schema()").get(0, String.class);
    var configuration =
        Flyway.configure()
            .dataSource(Objects.requireNonNull(fixture.transactionManager.getDataSource()))
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION);
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    return configuration.load();
  }

  private static String migrationHistoryProjection(Fixture fixture, String frontier) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT jsonb_agg(to_jsonb(history) ORDER BY installed_rank)::TEXT FROM flyway_schema_history history "
                + "WHERE version::NUMERIC <= ?::NUMERIC",
            frontier)
        .get(0, String.class);
  }

  /** Rename must preserve the actual trigger OID, function OID/body and INSERT event mask. */
  private static String launchGuardProjection(Fixture fixture, boolean corrected) {
    String name =
        corrected
            ? "gs_canonical_instance_launch_complete_catalog_identity"
            : "game_session_canonical_instance_launch_complete_catalog_identit";
    var guards =
        fixture.dsl.fetch(
            "SELECT trigger.oid::TEXT, tgfoid::TEXT, tgtype, tgenabled::TEXT, pg_get_functiondef(tgfoid) "
                + "FROM pg_trigger trigger WHERE tgrelid = 'game_session_canonical_instance_launch'::regclass "
                + "AND NOT tgisinternal AND tgfoid = 'require_complete_canonical_launch_catalog_identity()'::regprocedure");
    assertThat(guards).hasSize(1);
    assertThat(guards.get(0).get("tgtype", Short.class)).isEqualTo((short) 7);
    assertThat(guards.get(0).get("tgenabled", String.class)).isEqualTo("O");
    assertThat(guards.get(0).get(4, String.class))
        .contains("fixture_policy", "complete_policy_set_evidence");
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT tgname::TEXT FROM pg_trigger WHERE oid = ?::OID",
                    guards.get(0).get(0, String.class))
                .get(0, String.class))
        .isEqualTo(name);
    return guards.get(0).formatJSON();
  }

  private static void assertV1013RetentionLaunchForeignKey(Fixture fixture) {
    var foreignKey =
        fixture.dsl.fetchSingle(
            "SELECT fk.conname::TEXT, child_namespace.nspname::TEXT AS child_schema, "
                + "child.relname::TEXT AS child_table, parent_namespace.nspname::TEXT AS parent_schema, "
                + "parent.relname::TEXT AS parent_table, pg_get_constraintdef(fk.oid) AS definition, "
                + "fk.convalidated, fk.condeferrable FROM pg_constraint fk "
                + "JOIN pg_class child ON child.oid = fk.conrelid "
                + "JOIN pg_namespace child_namespace ON child_namespace.oid = child.relnamespace "
                + "JOIN pg_class parent ON parent.oid = fk.confrelid "
                + "JOIN pg_namespace parent_namespace ON parent_namespace.oid = parent.relnamespace "
                + "WHERE fk.conname = 'fk_gs_original_start_session_actor_core_launch' "
                + "AND child.relname = 'gs_original_start_session_authored_actor_core_retention' "
                + "AND child_namespace.nspname = current_schema() "
                + "AND parent.relname = 'game_session_canonical_instance_launch' "
                + "AND parent_namespace.nspname = current_schema()");
    assertThat(foreignKey.get("child_schema", String.class))
        .isEqualTo(foreignKey.get("parent_schema", String.class));
    assertThat(foreignKey.get("child_table", String.class))
        .isEqualTo("gs_original_start_session_authored_actor_core_retention");
    assertThat(foreignKey.get("parent_table", String.class))
        .isEqualTo("game_session_canonical_instance_launch");
    assertThat(foreignKey.get("definition", String.class))
        .contains(
            "FOREIGN KEY (target_namespace, control_plane_request_id)",
            "REFERENCES game_session_canonical_instance_launch(target_namespace, control_plane_request_id)",
            "ON DELETE RESTRICT");
    assertThat(foreignKey.get("convalidated", Boolean.class)).isTrue();
    assertThat(foreignKey.get("condeferrable", Boolean.class)).isFalse();

    var launchTriggers =
        fixture.dsl.fetch(
            "SELECT launch_trigger.tgtype, launch_trigger.tgenabled::TEXT, "
                + "launch_trigger.tgisinternal FROM pg_trigger launch_trigger "
                + "JOIN pg_constraint fk ON fk.oid = launch_trigger.tgconstraint "
                + "JOIN pg_class child ON child.oid = fk.conrelid "
                + "JOIN pg_namespace child_namespace ON child_namespace.oid = child.relnamespace "
                + "JOIN pg_class parent ON parent.oid = fk.confrelid "
                + "JOIN pg_namespace parent_namespace ON parent_namespace.oid = parent.relnamespace "
                + "WHERE fk.conname = 'fk_gs_original_start_session_actor_core_launch' "
                + "AND child.relname = 'gs_original_start_session_authored_actor_core_retention' "
                + "AND child_namespace.nspname = current_schema() "
                + "AND parent.relname = 'game_session_canonical_instance_launch' "
                + "AND parent_namespace.nspname = current_schema() "
                + "AND launch_trigger.tgrelid = parent.oid ORDER BY launch_trigger.tgtype");
    assertThat(launchTriggers).hasSize(2);
    assertThat(launchTriggers)
        .extracting(row -> row.get("tgtype", Integer.class))
        .containsExactly(9, 17);
    assertThat(launchTriggers)
        .allSatisfy(
            trigger -> {
              assertThat(trigger.get("tgenabled", String.class)).isEqualTo("O");
              assertThat(trigger.get("tgisinternal", Boolean.class)).isTrue();
            });
  }

  private static String otherGuardProjection(Fixture fixture) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT jsonb_agg(to_jsonb(guard) ORDER BY guard.oid)::TEXT FROM ("
                + "SELECT trigger.oid, trigger.tgrelid, trigger.tgname, trigger.tgfoid, "
                + "trigger.tgtype, trigger.tgenabled FROM pg_trigger trigger "
                + "WHERE trigger.tgrelid IN ('game_session_canonical_instance_launch'::regclass, "
                + "'game_session_canonical_initial_admission_attempt'::regclass, "
                + "'game_session_canonical_initial_admission_intent'::regclass) "
                + "AND trigger.tgfoid <> 'require_complete_canonical_launch_catalog_identity()'::regprocedure "
                + "AND NOT EXISTS (SELECT 1 FROM pg_constraint fk "
                + "JOIN pg_class retention_child ON retention_child.oid = fk.conrelid "
                + "JOIN pg_namespace retention_namespace ON retention_namespace.oid = retention_child.relnamespace "
                + "JOIN pg_class launch_parent ON launch_parent.oid = fk.confrelid "
                + "JOIN pg_namespace launch_namespace ON launch_namespace.oid = launch_parent.relnamespace "
                + "WHERE fk.oid = trigger.tgconstraint "
                + "AND fk.conname = 'fk_gs_original_start_session_actor_core_launch' "
                + "AND retention_child.relname = 'gs_original_start_session_authored_actor_core_retention' "
                + "AND retention_namespace.nspname = current_schema() "
                + "AND launch_parent.relname = 'game_session_canonical_instance_launch' "
                + "AND launch_namespace.nspname = current_schema())) guard")
        .get(0, String.class);
  }

  private Fixture fixture() {
    return fixture(null);
  }

  private Fixture fixture(String targetVersion) {
    String schema = "gs_canonical_admission_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    var migration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION);
    if (targetVersion != null) {
      migration.target(MigrationVersion.fromVersion(targetVersion));
    }
    migration.load().migrate();

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameSessionAuthoredWorldSourceRepository sourceRepository =
        new GameSessionAuthoredWorldSourceRepository(dsl);
    GameSessionCanonicalRealmCatalogRepository catalogRepository =
        new GameSessionCanonicalRealmCatalogRepository(dsl);
    GameSessionCanonicalLaunchPreparationRepository preparationRepository =
        new GameSessionCanonicalLaunchPreparationRepository(dsl, catalogRepository);
    GameplayAdmissionPointerEventRepository eventRepository =
        new GameplayAdmissionPointerEventRepository(dsl);
    GameSessionCanonicalAdmissionPointerRepository pointerRepository =
        new GameSessionCanonicalAdmissionPointerRepository(dsl, catalogRepository, eventRepository);
    CanonicalGameInstanceLaunchAssociationRepository launchRepository =
        new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    CanonicalInitialAdmissionRepository initialAdmissionRepository =
        new CanonicalInitialAdmissionRepository(dsl, catalogRepository, pointerRepository);
    return new Fixture(
        dsl,
        transactionManager,
        transactions,
        sourceRepository,
        catalogRepository,
        preparationRepository,
        pointerRepository,
        launchRepository,
        initialAdmissionRepository);
  }

  private static AuthoredWorldSourceEvidence freshSource() {
    String worldSlug = "violet-wilds";
    String tenantSlug = "tenant-canonical-admission";
    String displayName = "Violet Wilds";
    UUID registrationRequestId = uuid(112);
    long sourceGameRowId = 821;
    String sourceGameTenantKey = "gds-tenant-821";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, TENANT, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            SOURCE_OPERATION,
            requestDigest,
            TENANT,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        SOURCE_OPERATION,
        requestDigest,
        TENANT,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static CreateCanonicalLaunchPreparationRequest preparationRequest(
      IntakeReceipt source, CanonicalRealmCatalogSnapshot catalog) {
    return new CreateCanonicalLaunchPreparationRequest(
        CONTROL_PLANE_REQUEST_ID,
        ACTOR,
        NAMESPACE,
        TENANT,
        catalog.realmId(),
        CATALOG_REQUEST,
        catalog.catalogRevision(),
        source.operationId(),
        404,
        false,
        null,
        false,
        null,
        false,
        null,
        true,
        "{}");
  }

  private static AuthoredWorldLaunchDescriptorClient syntheticGameDesignClient(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source) {
    AuthoredWorldLaunchDescriptorClient client = mock(AuthoredWorldLaunchDescriptorClient.class);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request.descriptorRequest(catalog, source.source()),
            "launch-descriptor-canonical-admission",
            72,
            false,
            null,
            "{}",
            "generation-1",
            3,
            73,
            "published-bundle-73",
            false,
            null);
    CompleteLaunchBindingEvidence binding =
        CanonicalHumanPublicationOwnerTestFixtures.completeBinding(
            descriptor, publishedPolicy(source));
    when(client.resolve(any(AuthoredWorldLaunchDescriptorEvidence.Request.class)))
        .thenReturn(descriptor);
    when(client.get(any(GetRequest.class))).thenReturn(descriptor);
    when(client.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(binding);
    return client;
  }

  private static net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence.PublishedSet
      publishedPolicy(IntakeReceipt source) {
    return CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
        catalogRequest(source),
        source.source(),
        CANONICAL_VERSION,
        72L,
        "publish-canonical-admission",
        "published-bundle-73",
        "generation-1",
        3L);
  }

  private static CanonicalInitialAdmissionRequest initialAdmissionRequest(
      CanonicalRealmCatalogSnapshot catalog,
      CompleteLaunchBindingEvidence binding,
      UUID gameInstanceUuid) {
    return initialAdmissionRequest(
        catalog,
        binding,
        gameInstanceUuid,
        "canonical-first-open-" + catalog.realmId(),
        CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
        null,
        uuid(113),
        uuid(114));
  }

  private static CanonicalInitialAdmissionRequest initialAdmissionRequest(
      CanonicalRealmCatalogSnapshot catalog,
      CompleteLaunchBindingEvidence binding,
      UUID gameInstanceUuid,
      String requestId,
      CanonicalInitialAdmissionRequest.OriginKind origin,
      Long expectedPriorPointerVersion,
      UUID holdId,
      UUID holdFence) {
    long activeEpoch = 2L;
    UUID canonicalVersionId = binding.releaseAttestation().canonicalVersionId();
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            NAMESPACE,
            TENANT,
            catalog.worldSlug(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            "SHARED",
            gameInstanceUuid,
            canonicalVersionId,
            activeEpoch,
            catalog.catalogRevision(),
            origin,
            expectedPriorPointerVersion,
            requestId);
    return new CanonicalInitialAdmissionRequest(
        NAMESPACE,
        TENANT,
        catalog.worldSlug(),
        catalog.realmId(),
        catalog.playableStateNamespaceId(),
        "SHARED",
        gameInstanceUuid,
        canonicalVersionId,
        activeEpoch,
        catalog.catalogRevision(),
        origin,
        expectedPriorPointerVersion,
        requestId,
        requestDigest,
        holdId,
        holdFence,
        digest("e"));
  }

  private static void assertNoAttemptAndNoPending(
      Fixture fixture, CanonicalInitialAdmissionRequest request) {
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_initial_admission_attempt")),
                DSL.field(DSL.name("initial_admission_request_id"), String.class)
                    .eq(request.initialAdmissionRequestId())))
        .isZero();
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_initial_admission_attempt")),
                DSL.field(DSL.name("status"), String.class).eq("PENDING")))
        .isZero();
  }

  private static PreparedAdmission prepareAdmission(Fixture fixture) {
    IntakeReceipt source = fixture.registerSource();
    CanonicalRealmCatalogSnapshot catalog = fixture.createCatalog(source);
    CreateCanonicalLaunchPreparationRequest preparationRequest =
        preparationRequest(source, catalog);
    AuthoredWorldLaunchDescriptorClient syntheticGameDesign =
        syntheticGameDesignClient(preparationRequest, catalog, source);
    GameSessionCanonicalLaunchPreparationService preparationService =
        new GameSessionCanonicalLaunchPreparationService(
            syntheticGameDesign,
            fixture.catalogRepository,
            fixture.sourceRepository,
            fixture.preparationRepository,
            fixture.transactionManager,
            NAMESPACE);
    CanonicalLaunchPreparationSnapshot preparation = preparationService.prepare(preparationRequest);
    CompleteLaunchBindingEvidence binding = preparationService.readCompleteBinding(preparation);
    fixture.createTestOnlyFreshMappingAndRunningLaunch(source, binding);
    UUID allocatedInstanceUuid =
        fixture.launchRepository.read(CONTROL_PLANE_REQUEST_ID).orElseThrow().gameInstanceUuid();
    return new PreparedAdmission(catalog, binding, allocatedInstanceUuid);
  }

  private static CanonicalInitialAdmissionWorldProof testWorldProof(
      CanonicalInitialAdmissionRequest request) {
    return new CanonicalInitialAdmissionWorldProof(
        request.initialAdmissionRequestId(),
        request.requestDigest(),
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        "ACTIVE",
        request.activeLifecycleEpoch(),
        request.originKind(),
        request.expectedCatalogRevision(),
        request.expectedPriorPointerVersion(),
        request.holdId(),
        request.holdFence(),
        request.holdBindingDigest());
  }

  private static CreateCanonicalRealmCatalogRequest catalogRequest(IntakeReceipt source) {
    return new CreateCanonicalRealmCatalogRequest(
        CATALOG_REQUEST,
        NAMESPACE,
        TENANT,
        source.operationId(),
        "violet-realm",
        "Violet Realm",
        true,
        true,
        "SHARED",
        "PRESEEDED_ONLY",
        null,
        null);
  }

  private static String digest(String letter) {
    return "sha256:" + letter.repeat(64);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private record PreparedAdmission(
      CanonicalRealmCatalogSnapshot catalog,
      CompleteLaunchBindingEvidence binding,
      UUID gameInstanceUuid) {}

  private record Fixture(
      DSLContext dsl,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionCanonicalLaunchPreparationRepository preparationRepository,
      GameSessionCanonicalAdmissionPointerRepository pointerRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository,
      CanonicalInitialAdmissionRepository initialAdmissionRepository) {
    IntakeReceipt registerSource() {
      return Objects.requireNonNull(
          transactions.execute(status -> sourceRepository.register(INTAKE_REQUEST, freshSource())));
    }

    CanonicalRealmCatalogSnapshot createCatalog(IntakeReceipt source) {
      transactions.execute(
          status ->
              new GameSessionFreshTenantAssociationRepository(dsl)
                  .associateFresh(
                      ASSOCIATION_OPERATION,
                      NAMESPACE,
                      source,
                      CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(
                          source.source())));
      var prepared =
          catalogRepository.prepareInitialPublicProduction(
              catalogRequest(source), publishedPolicy(source));
      return Objects.requireNonNull(
          transactions.execute(
              status -> catalogRepository.createInitialPublicProduction(prepared)));
    }

    FreshGameSessionTenantAssociation createTestOnlyFreshMappingAndRunningLaunch(
        IntakeReceipt source, CompleteLaunchBindingEvidence binding) {
      var tuple =
          CanonicalHumanPublicationOwnerTestFixtures.tuple(
              NAMESPACE,
              TENANT,
              ACTOR,
              binding.descriptor().gameTemplateId(),
              binding.descriptor().controlPlaneRequestId());
      CanonicalHumanPublicationOwnerTestFixtures.pinLaunch(
          dsl, transactions, tuple, source.source(), publishedPolicy(source), binding);
      return Objects.requireNonNull(
          transactions.execute(
              status -> {
                var association =
                    new GameSessionFreshTenantAssociationRepository(dsl)
                        .associateFresh(
                            ASSOCIATION_OPERATION,
                            NAMESPACE,
                            source,
                            CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(
                                source.source()));
                var starting =
                    new GameSessionCanonicalStartingInstanceRepository(dsl, NAMESPACE)
                        .createStarting(
                            association,
                            binding,
                            ACTOR,
                            tuple.controlPlaneRequestId(),
                            tuple.mutationDigest(),
                            tuple);
                dsl.execute(
                    "UPDATE game_instances SET status = 'RUNNING' WHERE tenant_id = ? AND id = ?",
                    association.legacyGameSessionTenantId(),
                    starting.gameInstanceId());
                return association;
              }),
          "Controlled actual GS tenant association");
    }
  }
}
