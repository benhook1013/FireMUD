package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalInitialAdmissionOwnerGrpcCodec;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidenceTestFixture;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerTestFixtures;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.RunOwnedFixtureRuntimeCompletionRepository;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.impl.DatabaseGameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.impl.RunOwnedFixtureInitialAdmissionOwnerReadGrpcService;
import net.firedevops.firemud.gamesession.v1.ReadRunOwnedFixtureInitialAdmissionOwnerRequest;
import net.firedevops.firemud.gamesession.v1.ReadRunOwnedFixtureInitialAdmissionOwnerResponse;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof for the run-owned fixture first OPEN through the Game Session intent, attempt,
 * fixture runtime completion and pointer owners. The World hold identity and ACTIVE proof are
 * controlled test inputs; this does not claim a genuine World or Account producer.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionRunOwnedFixtureFirstOpenPostgresIntegrationTest {
  private static final String NAMESPACE =
      RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_TARGET_NAMESPACE;
  private static final long SOURCE_GAME_ROW_ID = 919L;
  private static final String SOURCE_GAME_TENANT_KEY = "919";
  private static final long GAME_TEMPLATE_ID = 301L;
  private static final long OWNER_ACCOUNT_ID = 401L;
  private static final long ACTIVE_EPOCH = 2L;
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void awaitPostgresJdbcReady() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  void commitsFixtureFirstOpenThroughExactOwnersWithoutAccountProtection() {
    Fixture f = fixture();
    AuthoredWorldSourceEvidence source = source();
    RunOwnedInitialAdmissionLaunchEvidence evidence = launchEvidence(source);
    UUID fixtureOperationId = UUID.fromString(evidence.fixtureCapability().operationId());
    IntakeReceipt receipt =
        Objects.requireNonNull(
            f.transactions.execute(status -> f.sources.register(fixtureOperationId, source)));
    f.transactions.executeWithoutResult(
        status ->
            f.tenantAssociations.associateFresh(
                associationOperationId(source), NAMESPACE, receipt, evidence.creationEvidence()));
    FixtureCanonicalInstanceLaunch launch =
        f.instanceLaunches.createVerifiedFixtureStartingLaunch(evidence);
    var catalog = launch.catalog().snapshot();
    var association =
        f.launches
            .readForInitialAdmission(
                NAMESPACE, catalog.tenantId(), catalog.realmId(), launch.gameInstanceUuid())
            .orElseThrow()
            .association();
    assertThat(association.isRunOwnedFixture()).isTrue();
    String provenance = evidence.fixturePolicySeal().evidenceDigest();
    assertThat(association.attestationDigest()).isEqualTo(provenance);

    String requestId = "run-owned-fixture-" + fixtureOperationId;
    UUID canonicalVersionId = association.canonicalVersionId();
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            NAMESPACE,
            catalog.tenantId(),
            catalog.worldSlug(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            catalog.stateScope(),
            launch.gameInstanceUuid(),
            canonicalVersionId,
            ACTIVE_EPOCH,
            catalog.catalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            requestId);
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            catalog.tenantId(),
            catalog.worldSlug(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            catalog.stateScope(),
            launch.gameInstanceUuid(),
            canonicalVersionId,
            ACTIVE_EPOCH,
            requestId,
            requestDigest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            catalog.catalogRevision(),
            null);
    var lifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            uuid(1201),
            NAMESPACE,
            catalog.tenantId(),
            catalog.worldSlug(),
            launch.gameInstanceUuid(),
            catalog.playableStateNamespaceId(),
            catalog.stateScope(),
            true,
            association.controlPlaneRequestId(),
            canonicalVersionId,
            association.descriptor().requestDigest(),
            association.descriptor().resultDigest(),
            association.attestationDigest());

    var intent =
        f.transactions.execute(
            status -> f.intents.reserveFixtureStarting(holdRequest, lifecycleRequest));
    assertThat(intent.sourceBinding().releaseAttestationDigest()).isEqualTo(provenance);
    var intentReplay =
        f.transactions.execute(
            status -> f.intents.reserveFixtureStarting(holdRequest, lifecycleRequest));
    assertThat(intentReplay.holdRequest().canonicalRequestBytes())
        .isEqualTo(intent.holdRequest().canonicalRequestBytes());
    assertThat(intentReplay.sourceBinding()).isEqualTo(intent.sourceBinding());
    assertThatThrownBy(
            () ->
                f.transactions.execute(
                    status -> f.intents.reserveHumanStarting(holdRequest, lifecycleRequest)))
        .isInstanceOf(IllegalStateException.class);

    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(holdRequest, uuid(1301), uuid(1302));
    var attached =
        f.transactions.execute(status -> f.intents.attachFixtureStarting(holdRequest, hold));
    var attachReplay =
        f.transactions.execute(status -> f.intents.attachFixtureStarting(holdRequest, hold));
    assertThat(attachReplay.holdIdentity().canonicalBytes())
        .isEqualTo(attached.holdIdentity().canonicalBytes());

    CanonicalInitialAdmissionRequest request = request(hold);
    CanonicalInitialAdmissionWorldProof worldProof = worldProof(request);
    assertThat(flag(f, () -> f.admissions.reserveFixtureStarting(request))).isTrue();
    assertThat(flag(f, () -> f.admissions.reserveFixtureStarting(request))).isFalse();
    assertThat(f.admissions.readRunOwnedFixture(NAMESPACE, requestId, provenance))
        .hasValueSatisfying(
            pending ->
                assertThat(pending.outcome())
                    .isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING));
    assertThat(f.admissions.readRunOwnedFixture(NAMESPACE, requestId, "sha256:" + "0".repeat(64)))
        .isEmpty();
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT account_protection_id, fixture_provenance_digest, status "
                        + "FROM game_session_canonical_initial_admission_attempt "
                        + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                    NAMESPACE,
                    requestId)
                .intoMap())
        .containsEntry("account_protection_id", null)
        .containsEntry("fixture_provenance_digest", provenance)
        .containsEntry("status", "PENDING");

    // A raw STARTING -> RUNNING update without the retained fixture completion is refused.
    assertThatThrownBy(
            () ->
                f.transactions.executeWithoutResult(
                    status ->
                        f.dsl.execute(
                            "UPDATE game_instances SET status = 'RUNNING', "
                                + "row_version = row_version + 1 WHERE tenant_id = ? AND id = ?",
                            association.gameSessionTenantId(),
                            launch.gameInstanceId())))
        .hasStackTraceContaining("requires its exact retained runtime completion");
    assertThat(runtimeStatus(f, association.gameSessionTenantId(), launch.gameInstanceId()))
        .isEqualTo("STARTING");

    var completion = f.transactions.execute(status -> f.completions.complete(request, worldProof));
    assertThat(completion.capturedStartingRowVersion()).isZero();
    assertThat(completion.completedRunningRowVersion()).isEqualTo(1L);
    assertThat(completion.fixtureProvenanceDigest()).isEqualTo(provenance);
    assertThat(replayCompletion(f, request, worldProof)).isEqualTo(completion);
    assertThat(runtimeStatus(f, association.gameSessionTenantId(), launch.gameInstanceId()))
        .isEqualTo("RUNNING");

    var numericPointers = new GameplayAdmissionPointerRepository(f.dsl);
    var discovery =
        new GameplayWorldCatalog(
            new DatabaseGameplayAdmissionPointerAuthorityService(
                numericPointers, new GameplayAdmissionPointerEventRepository(f.dsl)));
    assertThat(discovery.publicWorldsFromAuthoritySnapshot()).isEmpty();

    assertThat(flag(f, () -> f.admissions.commitFixture(request, worldProof))).isTrue();
    assertThat(flag(f, () -> f.admissions.commitFixture(request, worldProof))).isFalse();
    assertThat(flag(f, () -> f.admissions.reserveFixtureStarting(request))).isFalse();
    assertThat(replayCompletion(f, request, worldProof)).isEqualTo(completion);
    assertThat(numericPointers.count()).isZero();
    assertThat(discovery.publicWorldsFromAuthoritySnapshot())
        .singleElement()
        .satisfies(
            world -> {
              assertThat(world.slug()).isEqualTo(catalog.worldSlug());
              assertThat(world.realms())
                  .singleElement()
                  .satisfies(
                      realm -> {
                        assertThat(realm.realmId()).isEqualTo(catalog.realmId());
                        assertThat(realm.gameInstanceId()).isEqualTo(launch.gameInstanceId());
                      });
            });

    CanonicalInitialAdmissionOwnerProof proof =
        f.admissions.read(NAMESPACE, requestId).orElseThrow();
    assertThat(proof.outcome()).isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    assertThat(f.admissions.readRunOwnedFixture(NAMESPACE, requestId, provenance)).contains(proof);
    assertFixtureOwnerRead(f, evidence, hold, proof);
    Record attempt =
        f.dsl.fetchSingle(
            "SELECT * FROM game_session_canonical_initial_admission_attempt "
                + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
            NAMESPACE,
            requestId);
    assertThat(attempt.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(attempt.get("fixture_provenance_digest", String.class)).isEqualTo(provenance);
    assertThat(attempt.get("account_protection_evidence_bytes", byte[].class)).isNull();
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT state FROM game_session_canonical_initial_admission_intent "
                        + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                    NAMESPACE,
                    requestId)
                .get(0, String.class))
        .isEqualTo("TERMINAL");
    assertThat(
            f.dsl.fetchCount(
                DSL.table(DSL.name("gameplay_admission_pointer")),
                DSL.field(DSL.name("admission_state")).eq("OPEN")))
        .isEqualTo(1);

    var snapshot =
        f.admissions.readCurrentOpenSnapshotForRealm(
            NAMESPACE, catalog.tenantId(), catalog.realmId());
    assertThat(snapshot.ownerProof()).isEqualTo(proof);
    var targets =
        new CanonicalPlayerRouteReadService(NAMESPACE, f.catalogs, f.admissions, f.launches)
            .readVisiblePublicProductionTargets();
    assertThat(targets).hasSize(1);
    assertThat(targets.get(0).realmId()).isEqualTo(catalog.realmId());
    assertThat(targets.get(0).initialAdmissionRequestId()).isEqualTo(requestId);
    assertThat(targets.get(0).activeWorldEpoch()).isEqualTo(ACTIVE_EPOCH);

    long completedVersion = completion.completedRunningRowVersion();
    String pointerSnapshotDigest = snapshot.admissionPointerSnapshotDigest();
    assertThat(hasFixtureBindingSource(f, requestId, pointerSnapshotDigest, completedVersion, 0L))
        .isTrue();
    // An older candidate runtime cannot use a completion from its future. Only the supplied
    // composite changes here; retained runtime/completion/pointer evidence is not rewritten.
    assertThat(
            hasFixtureBindingSource(f, requestId, pointerSnapshotDigest, completedVersion - 1, 0L))
        .isFalse();
    assertThat(hasFixtureBindingSource(f, requestId, pointerSnapshotDigest, completedVersion, 1L))
        .isFalse();

    // The runtime mutation guard permits a monotonic row-version advance without changing the
    // RUNNING identity. Completion remains historical provenance, not an equality fence.
    f.transactions.executeWithoutResult(
        status ->
            assertThat(
                    f.dsl.execute(
                        "UPDATE game_instances SET row_version = row_version + 1 "
                            + "WHERE tenant_id = ? AND id = ? AND status = 'RUNNING' "
                            + "AND row_version = ?",
                        association.gameSessionTenantId(),
                        launch.gameInstanceId(),
                        completedVersion))
                .isEqualTo(1));
    assertThat(runtimeStatus(f, association.gameSessionTenantId(), launch.gameInstanceId()))
        .isEqualTo("RUNNING");
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT row_version FROM game_instances WHERE tenant_id = ? AND id = ?",
                    association.gameSessionTenantId(),
                    launch.gameInstanceId())
                .get(0, Long.class))
        .isEqualTo(completedVersion + 1);
    assertThat(
            hasFixtureBindingSource(f, requestId, pointerSnapshotDigest, completedVersion + 1, 0L))
        .isFalse();
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        f.dataSource, f.schema, MIGRATION_LOCATION, "1074");
    assertThat(replayCompletion(f, request, worldProof)).isEqualTo(completion);
    assertThat(
            f.admissions.readCurrentOpenSnapshotForRealm(
                NAMESPACE, catalog.tenantId(), catalog.realmId()))
        .isEqualTo(snapshot);
    assertThat(
            hasFixtureBindingSource(f, requestId, pointerSnapshotDigest, completedVersion + 1, 0L))
        .isTrue();
    assertThat(
            hasFixtureBindingSource(f, requestId, pointerSnapshotDigest, completedVersion + 1, 1L))
        .isFalse();
  }

  /**
   * Calls the installed predicate with a complete owner-derived target, without inserting a
   * binding.
   */
  @SuppressWarnings("unchecked")
  private static void assertFixtureOwnerRead(
      Fixture fixture,
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold,
      CanonicalInitialAdmissionOwnerProof retained) {
    var launchOwner = mock(RunOwnedCanonicalLaunchPreparationOwner.class);
    when(launchOwner.loadFixedVerifiedEvidenceForWorldPeer()).thenReturn(evidence);
    when(launchOwner.readFixedFixtureLaunch(evidence))
        .thenReturn(fixture.instanceLaunches.readVerifiedFixtureLaunch(evidence));
    var service =
        new RunOwnedFixtureInitialAdmissionOwnerReadGrpcService(launchOwner, fixture.admissions);
    var request =
        ReadRunOwnedFixtureInitialAdmissionOwnerRequest.newBuilder()
            .setReadRequestId(uuid(1303).toString())
            .setAuthorityKind("RUN_OWNED_FIXTURE")
            .setFixturePolicySealEvidenceDigest(evidence.fixturePolicySeal().evidenceDigest())
            .setHold(CanonicalInitialAdmissionOwnerGrpcCodec.toRequest(hold))
            .build();
    StreamObserver<ReadRunOwnedFixtureInitialAdmissionOwnerResponse> observer =
        mock(StreamObserver.class);
    service.readRunOwnedFixtureInitialAdmissionOwner(request, observer);
    var received = ArgumentCaptor.forClass(ReadRunOwnedFixtureInitialAdmissionOwnerResponse.class);
    verify(observer).onNext(received.capture());
    verify(observer).onCompleted();
    assertThat(received.getValue().getReadRequestId()).isEqualTo(request.getReadRequestId());
    assertThat(received.getValue().getAuthorityKind()).isEqualTo(request.getAuthorityKind());
    assertThat(received.getValue().getFixturePolicySealEvidenceDigest())
        .isEqualTo(request.getFixturePolicySealEvidenceDigest());
    var proof =
        CanonicalInitialAdmissionOwnerGrpcCodec.requireResponse(
            hold, received.getValue().getProof());
    assertThat(proof.committedPointerVersion()).isEqualTo(retained.committedPointerVersion());
    assertThat(proof.auditEventId()).isEqualTo(retained.auditEventId());
    assertThat(proof.proofDigest()).isEqualTo(retained.proofDigest());

    StreamObserver<ReadRunOwnedFixtureInitialAdmissionOwnerResponse> rejected =
        mock(StreamObserver.class);
    service.readRunOwnedFixtureInitialAdmissionOwner(
        request.toBuilder().setFixturePolicySealEvidenceDigest("sha256:" + "0".repeat(64)).build(),
        rejected);
    var error = ArgumentCaptor.forClass(Throwable.class);
    verify(rejected).onError(error.capture());
    assertThat(Status.fromThrowable(error.getValue()).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  private static boolean hasFixtureBindingSource(
      Fixture f,
      String requestId,
      String pointerSnapshotDigest,
      long runtimeRowVersion,
      long pointerVersionOffset) {
    return f.dsl
        .fetchSingle(
            """
            SELECT gs_has_current_run_owned_fixture_binding_source(
                jsonb_populate_record(NULL::game_session_canonical_binding_transition,
                    jsonb_build_object(
                        'admission_target_namespace', attempt.target_namespace,
                        'admission_target_tenant_slug', catalog.tenant_slug,
                        'admission_target_tenant_id', attempt.canonical_tenant_id,
                        'admission_target_world_slug', attempt.world_slug,
                        'admission_target_world_display_name', catalog.source_world_display_name,
                        'admission_target_realm_id', attempt.realm_id,
                        'admission_target_realm_slug', catalog.realm_slug,
                        'admission_target_realm_display_name', catalog.realm_display_name,
                        'admission_target_game_session_tenant_id', attempt.game_session_tenant_id,
                        'admission_target_game_instance_id', attempt.game_instance_id,
                        'admission_target_playable_state_namespace_id', attempt.playable_state_namespace_id,
                        'admission_target_playable_state_scope', attempt.playable_state_scope,
                        'admission_target_canonical_game_instance_id', attempt.canonical_game_instance_id,
                        'admission_target_canonical_version_id', attempt.canonical_version_id,
                        'admission_target_runtime_version_id', attempt.runtime_version_id,
                        'admission_target_catalog_revision', attempt.expected_catalog_revision,
                        'admission_target_pointer_version', attempt.pointer_version + CAST(? AS bigint),
                        'admission_target_pointer_snapshot_digest', CAST(? AS text),
                        'admission_target_active_world_epoch', attempt.active_lifecycle_epoch,
                        'admission_target_initial_admission_request_id', attempt.initial_admission_request_id,
                        'admission_target_initial_admission_request_digest', attempt.request_digest,
                        'admission_target_origin_kind', attempt.origin_kind,
                        'admission_target_expected_prior_pointer_version', attempt.expected_prior_pointer_version,
                        'admission_target_hold_id', attempt.hold_id,
                        'admission_target_hold_fence', attempt.hold_fence,
                        'admission_target_hold_binding_digest', attempt.hold_binding_digest,
                        'admission_target_audit_event_id', attempt.audit_event_id,
                        'admission_target_owner_proof_digest', attempt.commit_proof_digest,
                        'admission_target_owner_proof_outcome', attempt.status,
                        'admission_target_positive_durable_abort', false,
                        'admission_target_terminal_at', attempt.terminal_at,
                        'admission_target_character_creation_policy', pointer.character_creation_policy)),
                launch,
                jsonb_populate_record(NULL::game_instances,
                    to_jsonb(runtime) || jsonb_build_object('row_version', CAST(? AS bigint))))
              FROM game_session_canonical_initial_admission_attempt attempt
              JOIN game_session_run_owned_fixture_runtime_completion completion
                ON completion.target_namespace = attempt.target_namespace
               AND completion.initial_admission_request_id = attempt.initial_admission_request_id
              JOIN gameplay_admission_pointer pointer ON pointer.id = attempt.pointer_id
              JOIN game_session_canonical_instance_launch launch
                ON launch.target_namespace = completion.target_namespace
               AND launch.game_instance_uuid = completion.canonical_game_instance_id
              JOIN game_instances runtime
                ON runtime.tenant_id = completion.game_session_tenant_id
               AND runtime.id = completion.game_instance_id
              JOIN game_session_canonical_realm_catalog catalog
                ON catalog.target_namespace = attempt.target_namespace
               AND catalog.canonical_tenant_id = attempt.canonical_tenant_id
               AND catalog.realm_id = attempt.realm_id
             WHERE attempt.target_namespace = ? AND attempt.initial_admission_request_id = ?
            """,
            pointerVersionOffset,
            pointerSnapshotDigest,
            runtimeRowVersion,
            NAMESPACE,
            requestId)
        .get(0, boolean.class);
  }

  private static boolean flag(Fixture f, java.util.function.Supplier<Boolean> owner) {
    Boolean result = f.transactions.execute(status -> owner.get());
    return Boolean.TRUE.equals(result);
  }

  private static RunOwnedFixtureRuntimeCompletionRepository.Completion replayCompletion(
      Fixture f,
      CanonicalInitialAdmissionRequest request,
      CanonicalInitialAdmissionWorldProof worldProof) {
    return f.transactions.execute(status -> f.completions.complete(request, worldProof));
  }

  private static String runtimeStatus(Fixture f, long tenantId, long gameInstanceId) {
    return f.dsl
        .fetchSingle(
            "SELECT status FROM game_instances WHERE tenant_id = ? AND id = ?",
            tenantId,
            gameInstanceId)
        .get(0, String.class);
  }

  private static CanonicalInitialAdmissionRequest request(
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold) {
    var world = hold.request();
    return new CanonicalInitialAdmissionRequest(
        world.targetNamespace(),
        world.canonicalTenantId(),
        world.worldSlug(),
        world.realmId(),
        world.playableStateNamespaceId(),
        world.playableStateScope(),
        world.canonicalGameInstanceId(),
        world.canonicalVersionId(),
        world.activeLifecycleEpoch(),
        world.expectedCatalogRevision(),
        CanonicalInitialAdmissionRequest.OriginKind.valueOf(world.initialAdmissionOrigin().name()),
        world.expectedPriorPointerVersion(),
        world.initialAdmissionRequestId(),
        world.initialAdmissionRequestDigest(),
        hold.holdId(),
        hold.holdFence(),
        hold.holdBindingDigest());
  }

  /** Controlled World verifier output; no genuine World service is claimed by this test. */
  private static CanonicalInitialAdmissionWorldProof worldProof(
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

  private static AuthoredWorldSourceEvidence source() {
    UUID canonicalTenantId = uuid(1001);
    UUID registrationRequestId =
        UUID.fromString(RunOwnedInitialAdmissionLaunchEvidenceTestFixture.operationId());
    UUID sourceOperationId = uuid(1002);
    String tenantSlug = "run-owned-fixture-tenant";
    String worldSlug = RunOwnedInitialAdmissionFixtureWireCodec.WORLD_SLUG;
    String displayName = RunOwnedInitialAdmissionFixtureWireCodec.WORLD_DISPLAY_NAME;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            displayName,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        sourceOperationId,
        requestDigest,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        displayName,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static RunOwnedInitialAdmissionLaunchEvidence launchEvidence(
      AuthoredWorldSourceEvidence source) {
    StartSessionRequest controlledCapabilityInput =
        new StartSessionRequest(
            Long.parseLong(source.sourceGameTenantKey()),
            GAME_TEMPLATE_ID,
            RunOwnedInitialAdmissionLaunchEvidenceTestFixture.operationId(),
            OWNER_ACCOUNT_ID);
    return RunOwnedInitialAdmissionLaunchEvidenceTestFixture.create(
        controlledCapabilityInput, source);
  }

  private static UUID associationOperationId(AuthoredWorldSourceEvidence source) {
    return UUID.nameUUIDFromBytes(
        ("test-only-gs-fresh-association:" + source.canonicalTenantId())
            .getBytes(StandardCharsets.UTF_8));
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private static Fixture fixture() {
    String schema = "gs_fixture_first_open_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        dataSource, schema, MIGRATION_LOCATION, "1073");

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var catalogs =
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, NAMESPACE);
    var launches = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    var pointers =
        new GameSessionCanonicalAdmissionPointerRepository(
            dsl, catalogs, new GameplayAdmissionPointerEventRepository(dsl));
    return new Fixture(
        dataSource,
        schema,
        dsl,
        transactions,
        new GameSessionAuthoredWorldSourceRepository(dsl),
        new GameSessionFreshTenantAssociationRepository(dsl),
        catalogs,
        new GameSessionCanonicalInstanceLaunchRepository(
            dsl, transactionManager, NAMESPACE, catalogs),
        launches,
        new CanonicalInitialAdmissionIntentRepository(dsl, catalogs, launches),
        new CanonicalInitialAdmissionRepository(dsl, catalogs, pointers),
        new RunOwnedFixtureRuntimeCompletionRepository(dsl, launches));
  }

  private record Fixture(
      DriverManagerDataSource dataSource,
      String schema,
      DSLContext dsl,
      TransactionTemplate transactions,
      GameSessionAuthoredWorldSourceRepository sources,
      GameSessionFreshTenantAssociationRepository tenantAssociations,
      GameSessionCanonicalRealmCatalogRepository catalogs,
      GameSessionCanonicalInstanceLaunchRepository instanceLaunches,
      CanonicalGameInstanceLaunchAssociationRepository launches,
      CanonicalInitialAdmissionIntentRepository intents,
      CanonicalInitialAdmissionRepository admissions,
      RunOwnedFixtureRuntimeCompletionRepository completions) {}
}
