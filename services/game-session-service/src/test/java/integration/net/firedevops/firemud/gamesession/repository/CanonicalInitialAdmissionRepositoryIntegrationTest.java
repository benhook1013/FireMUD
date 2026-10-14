package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
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
  void reservesExactHumanIntentButDeniesCommitAndAbortBeforePointerOrAuditEffects() {
    Fixture fixture = fixture();
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
    CompleteLaunchBindingEvidence completeBinding =
        preparationService.readCompleteBinding(preparation);

    FreshGameSessionTenantAssociation tenantAssociation =
        fixture.createTestOnlyFreshMappingAndRunningLaunch(source, completeBinding);
    CanonicalInitialAdmissionRequest request =
        initialAdmissionRequest(
            catalog,
            completeBinding,
            fixture
                .launchRepository
                .read(CONTROL_PLANE_REQUEST_ID)
                .orElseThrow()
                .gameInstanceUuid());
    assertThat(tenantAssociation.legacyGameSessionTenantId())
        .isNotEqualTo(source.source().sourceGameRowId());
    assertThat(request.playableStateNamespaceId()).isEqualTo(catalog.playableStateNamespaceId());
    CanonicalInitialAdmissionWorldProof worldProof = testWorldProof(request);

    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.reserve(request);
          return null;
        });
    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.reserve(request);
          return null;
        });
    assertThat(
            fixture.initialAdmissionRepository.read(NAMESPACE, request.initialAdmissionRequestId()))
        .map(CanonicalInitialAdmissionOwnerProof::outcome)
        .contains(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_initial_admission_attempt"))))
        .isEqualTo(1);

    CanonicalInitialAdmissionWorldProof changedProof =
        new CanonicalInitialAdmissionWorldProof(
            worldProof.initialAdmissionRequestId(),
            worldProof.requestDigest(),
            worldProof.targetNamespace(),
            worldProof.canonicalTenantId(),
            worldProof.worldSlug(),
            worldProof.realmId(),
            worldProof.playableStateNamespaceId(),
            worldProof.playableStateScope(),
            worldProof.canonicalGameInstanceId(),
            worldProof.canonicalVersionId(),
            worldProof.lifecycleState(),
            worldProof.activeLifecycleEpoch(),
            worldProof.originKind(),
            worldProof.expectedCatalogRevision(),
            worldProof.expectedPriorPointerVersion(),
            worldProof.holdId(),
            uuid(111),
            worldProof.holdBindingDigest());
    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status -> {
                      fixture.initialAdmissionRepository.commit(request, changedProof, null);
                      return null;
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match the exact initial admission request");
    assertThat(
            fixture.initialAdmissionRepository.read(NAMESPACE, request.initialAdmissionRequestId()))
        .map(CanonicalInitialAdmissionOwnerProof::outcome)
        .contains(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    assertThat(
            Objects.requireNonNull(
                fixture
                    .dsl
                    .fetchSingle(
                        "SELECT count(*) FROM gameplay_admission_pointer "
                            + "WHERE representation_version = 3")
                    .get(0, Long.class)))
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

    CanonicalInitialAdmissionOwnerProof pending =
        fixture
            .initialAdmissionRepository
            .read(NAMESPACE, request.initialAdmissionRequestId())
            .orElseThrow();
    String before = attemptProjection(fixture, request);
    for (int retry = 0; retry < 2; retry++) {
      assertHumanProtectionUnavailable(
          () ->
              fixture.transactions.executeWithoutResult(
                  status -> fixture.initialAdmissionRepository.commit(request, worldProof, null)));
      assertHumanProtectionUnavailable(
          () ->
              fixture.transactions.executeWithoutResult(
                  status ->
                      fixture.initialAdmissionRepository.abort(
                          request, null, "Missing protection")));
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
  void retainsAndReadsBack128CharacterRequestIdentityFromOwnerAttempt() {
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

    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.reserve(request);
          return null;
        });

    CanonicalInitialAdmissionOwnerProof retained =
        fixture.initialAdmissionRepository.read(NAMESPACE, requestId).orElseThrow();
    assertThat(retained.outcome()).isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    assertThat(retained.initialAdmissionRequestId()).isEqualTo(requestId);
    assertThat(retained.holdId()).isEqualTo(request.holdId());
    assertThat(retained.holdFence()).isEqualTo(request.holdFence());
  }

  @Test
  void competingNoPriorPointerReservationCannotReplacePendingUnprotectedHumanIntent() {
    Fixture fixture = fixture();
    PreparedAdmission prepared = prepareAdmission(fixture);
    CanonicalInitialAdmissionRequest first =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    fixture.transactions.executeWithoutResult(
        status -> fixture.initialAdmissionRepository.reserve(first));
    assertHumanProtectionUnavailable(
        () -> fixture.initialAdmissionRepository.commit(first, testWorldProof(first), null));
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
    assertThatThrownBy(
            () ->
                fixture.transactions.executeWithoutResult(
                    status -> fixture.initialAdmissionRepository.reserve(competing)))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException.class)
        .hasMessageContaining("pending realm attempt");
    assertThat(
            fixture.initialAdmissionRepository.read(
                NAMESPACE, competing.initialAdmissionRequestId()))
        .isEmpty();
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_initial_admission_attempt")),
                DSL.field(DSL.name("status"), String.class).eq("PENDING")))
        .isEqualTo(1);
    assertThat(
            fixture.initialAdmissionRepository.read(NAMESPACE, first.initialAdmissionRequestId()))
        .map(CanonicalInitialAdmissionOwnerProof::outcome)
        .contains(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
        .isZero();
  }

  @Test
  void expectedClosedReservationRequiresExactCurrentOriginalClosedEvidence() {
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
    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status -> {
                      fixture.initialAdmissionRepository.reserve(unsupportedPriorVersion);
                      return null;
                    }))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("versions do not match");
    assertNoAttemptAndNoPending(fixture, unsupportedPriorVersion);

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
    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.reserve(firstOpenRequest);
          return null;
        });
    assertThat(
            fixture.initialAdmissionRepository.read(
                NAMESPACE, firstOpenRequest.initialAdmissionRequestId()))
        .map(CanonicalInitialAdmissionOwnerProof::outcome)
        .contains(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    var preparedExpectedClosed =
        fixture.pointerRepository.prepareExpectedClosedForRealm(
            NAMESPACE, TENANT, catalog.realmId(), 1L, catalog.catalogRevision());
    String closedBefore = pointerAndAuditProjection(fixture);
    String pendingBefore = attemptProjection(fixture, firstOpenRequest);
    assertHumanProtectionUnavailable(
        () ->
            fixture.transactions.executeWithoutResult(
                status ->
                    fixture.initialAdmissionRepository.commit(
                        firstOpenRequest,
                        testWorldProof(firstOpenRequest),
                        preparedExpectedClosed)));
    assertHumanProtectionUnavailable(
        () ->
            fixture.transactions.executeWithoutResult(
                status ->
                    fixture.initialAdmissionRepository.abort(
                        firstOpenRequest, preparedExpectedClosed, "Missing protection")));
    assertThat(pointerAndAuditProjection(fixture)).isEqualTo(closedBefore);
    assertThat(attemptProjection(fixture, firstOpenRequest)).isEqualTo(pendingBefore);
    assertThat(
            fixture.initialAdmissionRepository.read(
                NAMESPACE, firstOpenRequest.initialAdmissionRequestId()))
        .map(CanonicalInitialAdmissionOwnerProof::outcome)
        .contains(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
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
  void unprotectedHumanAbortAndCommitRetryLeaveTheExactPendingOwnerUnchanged() {
    Fixture fixture = fixture();
    PreparedAdmission prepared = prepareAdmission(fixture);
    var request =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    fixture.transactions.executeWithoutResult(
        status -> fixture.initialAdmissionRepository.reserve(request));
    var pending =
        fixture
            .initialAdmissionRepository
            .read(NAMESPACE, request.initialAdmissionRequestId())
            .orElseThrow();
    String before = attemptProjection(fixture, request);
    for (int retry = 0; retry < 2; retry++) {
      assertHumanProtectionUnavailable(
          () -> fixture.initialAdmissionRepository.abort(request, null, "World hold expired"));
      assertHumanProtectionUnavailable(
          () -> fixture.initialAdmissionRepository.commit(request, testWorldProof(request), null));
      fixture.transactions.executeWithoutResult(
          status -> fixture.initialAdmissionRepository.reserve(request));
      assertThat(attemptProjection(fixture, request)).isEqualTo(before);
      assertThat(
              fixture.initialAdmissionRepository.read(
                  NAMESPACE, request.initialAdmissionRequestId()))
          .contains(pending);
    }
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
        .isZero();
  }

  @Test
  void terminalProtectionConstraintRejectsNewAndUpdatedUnprotectedTerminalRows() {
    Fixture fixture = fixture();
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT count(*) FROM pg_trigger WHERE tgrelid = "
                        + "'game_session_canonical_instance_launch'::regclass "
                        + "AND tgname = 'gs_canonical_instance_launch_complete_catalog_identity'")
                .get(0, Long.class))
        .isEqualTo(1L);
    PreparedAdmission prepared = prepareAdmission(fixture);
    var request =
        initialAdmissionRequest(
            prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
    fixture.transactions.executeWithoutResult(
        status -> fixture.initialAdmissionRepository.reserve(request));
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

    // Isolate the declarative constraint in this disposable schema. All other triggers,
    // source bindings and constraints stay enabled; each failed write rolls back separately.
    fixture.dsl.execute(
        "ALTER TABLE game_session_canonical_initial_admission_attempt DISABLE TRIGGER "
            + "game_session_canonical_initial_admission_v44_terminal_evidence");
    try {
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
                        "violates check constraint \"chk_gs_initial_admission_terminal_requires_protection\"");
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
              exception -> {
                assertThat(exception.sqlState()).isEqualTo("23514");
                assertThat(exception)
                    .hasStackTraceContaining(
                        "violates check constraint \"chk_gs_initial_admission_terminal_requires_protection\"");
              });
    } finally {
      fixture.dsl.execute(
          "ALTER TABLE game_session_canonical_initial_admission_attempt ENABLE TRIGGER "
              + "game_session_canonical_initial_admission_v44_terminal_evidence");
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
  void pre1006CommittedAndAbortedRowsSurviveForwardProtectionMigration() throws Exception {
    for (boolean committed : List.of(true, false)) {
      Fixture predecessor = fixture("1005");
      PredecessorAdmission predecessorSources = preparePre1006Admission(predecessor);
      PreparedAdmission prepared = predecessorSources.prepared();
      CanonicalInitialAdmissionRequest request =
          initialAdmissionRequest(
              prepared.catalog(), prepared.binding(), prepared.gameInstanceUuid());
      reservePre1006Attempt(predecessor, predecessorSources.launch(), request);
      // This is the real target1005 schema. Current terminal writers remain denied, and current
      // typed catalog/terminal reads need columns introduced by1010. Only explicit predecessor SQL
      // supplies the old row shape here; current owner readback is qualified after the upgrade.
      seedPre1006Terminal(predecessor, prepared, predecessorSources.launch(), request, committed);
      String originalSources = predecessorSourceProjection(predecessor);
      String originalAttempt = predecessorAttemptProjection(predecessor, request);
      String originalPointerAndAudit = pointerAndAuditProjection(predecessor);
      String schema = predecessor.dsl.fetchSingle("SELECT current_schema()").get(0, String.class);
      var migration =
          Flyway.configure()
              .dataSource(Objects.requireNonNull(predecessor.transactionManager.getDataSource()))
              .schemas(schema)
              .defaultSchema(schema)
              .table("flyway_schema_history")
              .locations(MIGRATION_LOCATION)
              .load();
      migration.migrate();
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
          () ->
              predecessor.initialAdmissionRepository.commit(
                  request, testWorldProof(request), null));
      assertHumanProtectionUnavailable(
          () ->
              predecessor.initialAdmissionRepository.abort(
                  request, null, "Current Human retry remains unprotected"));
      assertThat(predecessorAttemptProjection(predecessor, request)).isEqualTo(originalAttempt);
      assertThat(pointerAndAuditProjection(predecessor)).isEqualTo(originalPointerAndAudit);
      assertThat(predecessorSourceProjection(predecessor)).isEqualTo(originalSources);
    }
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

  private static String pointerAndAuditProjection(Fixture fixture) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT jsonb_build_array("
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::text, to_jsonb(pointer)) ORDER BY id) FROM gameplay_admission_pointer pointer),"
                + "(SELECT jsonb_agg(jsonb_build_array(xmin::text, to_jsonb(event)) ORDER BY id) FROM gameplay_admission_pointer_event event))::text AS projection")
        .get("projection", String.class);
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
        new CanonicalInitialAdmissionRepository(
            dsl, catalogRepository, pointerRepository, launchRepository, eventRepository);
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
