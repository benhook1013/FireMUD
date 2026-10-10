package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.dto.CanonicalLaunchPreparationSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import net.firedevops.firemud.gamesession.service.GameSessionCanonicalLaunchPreparationService;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof for the canonical first-OPEN owner transaction.
 *
 * <p>The direct V25 reservation/claim/association inserts below are test-only owner fixtures for
 * exercising the migrated constraints. They are not a runtime fresh-tenant mapping producer. The
 * World proof is also test data; production remains disabled until the real verifier and Account
 * authorization redemption are composed.
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
  private static final UUID GAME_INSTANCE_UUID = uuid(108);
  private static final UUID CANONICAL_VERSION = uuid(109);
  private static final UUID ASSOCIATION_OPERATION = uuid(110);
  private static final String CONTROL_PLANE_REQUEST_ID = "canonical-start-instance-1";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void reservesAndCommitsExactWorldHeldOpenWithPointerAuditAndOwnerReadback() {
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
    CanonicalInitialAdmissionRequest request = initialAdmissionRequest(catalog, completeBinding);
    assertThat(tenantAssociation.legacyGameSessionTenantId())
        .isNotEqualTo(source.source().sourceGameRowId());
    assertThat(request.playableStateNamespaceId()).isEqualTo(catalog.playableStateNamespaceId());
    CanonicalInitialAdmissionWorldProof worldProof = testWorldProof(request);

    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.reserve(request);
          return null;
        });

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

    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.commit(request, worldProof, null);
          return null;
        });

    CanonicalInitialAdmissionOwnerProof committed =
        fixture
            .initialAdmissionRepository
            .read(NAMESPACE, request.initialAdmissionRequestId())
            .orElseThrow();
    assertThat(committed.outcome())
        .isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    assertThat(committed.canonicalTenantId()).isEqualTo(TENANT);
    assertThat(committed.canonicalGameInstanceId()).isEqualTo(GAME_INSTANCE_UUID);
    assertThat(committed.canonicalVersionId()).isEqualTo(CANONICAL_VERSION);
    assertThat(committed.committedPointerVersion()).isEqualTo(1L);
    assertThat(committed.auditEventId()).isPositive();
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_initial_admission_attempt"))))
        .isEqualTo(1);

    Record pointer =
        Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT id, pointer_version, tenant_id, game_instance_id, "
                    + "canonical_tenant_id, realm_id, "
                    + "canonical_game_instance_id, canonical_version_id, initial_admission_request_id, "
                    + "admission_state, representation_version FROM gameplay_admission_pointer "
                    + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ?",
                NAMESPACE,
                TENANT,
                request.realmId()));
    assertThat(pointer.get("tenant_id", Long.class))
        .isEqualTo(tenantAssociation.legacyGameSessionTenantId());
    assertThat(pointer.get("canonical_tenant_id", UUID.class)).isEqualTo(TENANT);
    assertThat(pointer.get("realm_id", UUID.class)).isEqualTo(request.realmId());
    assertThat(pointer.get("canonical_game_instance_id", UUID.class)).isEqualTo(GAME_INSTANCE_UUID);
    assertThat(pointer.get("canonical_version_id", UUID.class)).isEqualTo(CANONICAL_VERSION);
    assertThat(pointer.get("initial_admission_request_id", String.class))
        .isEqualTo(request.initialAdmissionRequestId());
    assertThat(pointer.get("admission_state", String.class)).isEqualTo("OPEN");
    assertThat(pointer.get("representation_version", Integer.class)).isEqualTo(3);

    Record audit =
        Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT canonical_tenant_id, realm_id, canonical_game_instance_id, "
                    + "canonical_version_id, initial_admission_request_id, admission_state "
                    + "FROM gameplay_admission_pointer_event WHERE id = ?",
                committed.auditEventId()));
    assertThat(audit.get("canonical_tenant_id", UUID.class)).isEqualTo(TENANT);
    assertThat(audit.get("realm_id", UUID.class)).isEqualTo(request.realmId());
    assertThat(audit.get("canonical_game_instance_id", UUID.class)).isEqualTo(GAME_INSTANCE_UUID);
    assertThat(audit.get("canonical_version_id", UUID.class)).isEqualTo(CANONICAL_VERSION);
    assertThat(audit.get("initial_admission_request_id", String.class))
        .isEqualTo(request.initialAdmissionRequestId());
    assertThat(audit.get("admission_state", String.class)).isEqualTo("OPEN");

    CanonicalInitialAdmissionRequest replayRequest =
        initialAdmissionRequest(catalog, completeBinding);
    CanonicalInitialAdmissionWorldProof replayWorldProof = testWorldProof(replayRequest);
    assertThat(replayRequest).isEqualTo(request);
    assertThat(replayWorldProof).isEqualTo(worldProof);
    int pointerRowsBeforeReplay =
        fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")));
    int auditRowsBeforeReplay =
        fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event")));

    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.reserve(replayRequest);
          return null;
        });
    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.commit(replayRequest, replayWorldProof, null);
          return null;
        });

    CanonicalInitialAdmissionOwnerProof replayed =
        fixture
            .initialAdmissionRepository
            .read(NAMESPACE, replayRequest.initialAdmissionRequestId())
            .orElseThrow();
    assertThat(replayed).isEqualTo(committed);
    Record pointerAfterReplay =
        Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT id, pointer_version, tenant_id, game_instance_id, canonical_tenant_id, "
                    + "realm_id, canonical_game_instance_id, canonical_version_id, "
                    + "initial_admission_request_id, admission_state, representation_version "
                    + "FROM gameplay_admission_pointer WHERE target_namespace = ? "
                    + "AND canonical_tenant_id = ? AND realm_id = ?",
                NAMESPACE,
                TENANT,
                replayRequest.realmId()));
    assertThat(pointerAfterReplay.get("id", Long.class)).isEqualTo(pointer.get("id", Long.class));
    assertThat(pointerAfterReplay.get("pointer_version", Long.class))
        .isEqualTo(pointer.get("pointer_version", Long.class));
    assertThat(pointerAfterReplay.get("tenant_id", Long.class))
        .isEqualTo(pointer.get("tenant_id", Long.class));
    assertThat(pointerAfterReplay.get("game_instance_id", Long.class))
        .isEqualTo(pointer.get("game_instance_id", Long.class));
    assertThat(pointerAfterReplay.get("canonical_tenant_id", UUID.class))
        .isEqualTo(pointer.get("canonical_tenant_id", UUID.class));
    assertThat(pointerAfterReplay.get("realm_id", UUID.class))
        .isEqualTo(pointer.get("realm_id", UUID.class));
    assertThat(pointerAfterReplay.get("canonical_game_instance_id", UUID.class))
        .isEqualTo(pointer.get("canonical_game_instance_id", UUID.class));
    assertThat(pointerAfterReplay.get("canonical_version_id", UUID.class))
        .isEqualTo(pointer.get("canonical_version_id", UUID.class));
    assertThat(pointerAfterReplay.get("initial_admission_request_id", String.class))
        .isEqualTo(pointer.get("initial_admission_request_id", String.class));
    assertThat(pointerAfterReplay.get("admission_state", String.class))
        .isEqualTo(pointer.get("admission_state", String.class));
    assertThat(pointerAfterReplay.get("representation_version", Integer.class))
        .isEqualTo(pointer.get("representation_version", Integer.class));
    Record auditAfterReplay =
        Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT canonical_tenant_id, realm_id, canonical_game_instance_id, "
                    + "canonical_version_id, initial_admission_request_id, admission_state "
                    + "FROM gameplay_admission_pointer_event WHERE id = ?",
                replayed.auditEventId()));
    assertThat(auditAfterReplay.get("canonical_tenant_id", UUID.class))
        .isEqualTo(audit.get("canonical_tenant_id", UUID.class));
    assertThat(auditAfterReplay.get("realm_id", UUID.class))
        .isEqualTo(audit.get("realm_id", UUID.class));
    assertThat(auditAfterReplay.get("canonical_game_instance_id", UUID.class))
        .isEqualTo(audit.get("canonical_game_instance_id", UUID.class));
    assertThat(auditAfterReplay.get("canonical_version_id", UUID.class))
        .isEqualTo(audit.get("canonical_version_id", UUID.class));
    assertThat(auditAfterReplay.get("initial_admission_request_id", String.class))
        .isEqualTo(audit.get("initial_admission_request_id", String.class));
    assertThat(auditAfterReplay.get("admission_state", String.class))
        .isEqualTo(audit.get("admission_state", String.class));
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_initial_admission_attempt"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer"))))
        .isEqualTo(pointerRowsBeforeReplay);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
        .isEqualTo(auditRowsBeforeReplay);

    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "INSERT INTO gameplay_admission_pointer ("
                        + "world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
                        + "game_instance_id, pointer_version, visible, requires_character_selection, "
                        + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
                        + "public_production_realm, catalog_revision) "
                        + "VALUES ('numeric-scope-guard-test', 'numeric-scope-guard-test', "
                        + "'Numeric Scope Guard', 'Numeric Scope Guard', ?, 1, 1, TRUE, FALSE, "
                        + "'SHARED', 'owner-mode', 'scope-guard-test', 'numeric-only guard', TRUE, 1)",
                    tenantAssociation.legacyGameSessionTenantId()))
        .hasMessageContaining("Numeric-only tenant scope cannot use a fresh source-bound key");
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("gameplay_admission_pointer")),
                DSL.field(DSL.name("world_slug"), String.class).eq("numeric-scope-guard-test")))
        .isZero();

    UUID mismatchedTenant = uuid(997);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "INSERT INTO gameplay_admission_pointer ("
                        + "world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
                        + "game_instance_id, pointer_version, visible, requires_character_selection, "
                        + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
                        + "created_at, updated_at, public_production_realm, catalog_revision, realm_id, "
                        + "playable_state_namespace_id, representation_version, target_namespace, "
                        + "canonical_tenant_id, admission_state, canonical_game_instance_id, "
                        + "canonical_version_id, runtime_version_id, initial_admission_request_id, "
                        + "initial_admission_request_digest, initial_admission_origin_kind, "
                        + "initial_admission_prior_pointer_version, initial_admission_active_epoch, "
                        + "initial_admission_hold_id, initial_admission_hold_fence, "
                        + "initial_admission_hold_binding_digest) "
                        + "SELECT world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
                        + "game_instance_id, pointer_version + 1, visible, requires_character_selection, "
                        + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
                        + "created_at, updated_at, public_production_realm, catalog_revision, realm_id, "
                        + "playable_state_namespace_id, representation_version, target_namespace, ?, "
                        + "admission_state, canonical_game_instance_id, canonical_version_id, "
                        + "runtime_version_id, initial_admission_request_id, initial_admission_request_digest, "
                        + "initial_admission_origin_kind, initial_admission_prior_pointer_version, "
                        + "initial_admission_active_epoch, initial_admission_hold_id, "
                        + "initial_admission_hold_fence, initial_admission_hold_binding_digest "
                        + "FROM gameplay_admission_pointer WHERE id = ?",
                    mismatchedTenant,
                    pointer.get("id", Long.class)))
        .hasMessageContaining(
            "Canonical OPEN pointer requires exact catalog and launch owner evidence");
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("gameplay_admission_pointer")),
                DSL.field(DSL.name("representation_version"), Integer.class).eq(3)))
        .isEqualTo(1);
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("gameplay_admission_pointer")),
                DSL.field(DSL.name("canonical_tenant_id"), UUID.class).eq(mismatchedTenant)))
        .isZero();
  }

  @Test
  void exactDefinitivelyAbortedAttemptCannotBeReopenedByRetry() {
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
    fixture.createTestOnlyFreshMappingAndRunningLaunch(source, completeBinding);
    CanonicalInitialAdmissionRequest request = initialAdmissionRequest(catalog, completeBinding);
    // Synthetic World proof input exercises the GS owner fence; it is not live World evidence.
    CanonicalInitialAdmissionWorldProof worldProof = testWorldProof(request);

    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.reserve(request);
          return null;
        });
    assertThat(
            fixture.initialAdmissionRepository.read(NAMESPACE, request.initialAdmissionRequestId()))
        .map(CanonicalInitialAdmissionOwnerProof::outcome)
        .contains(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    int pointerRowsBeforeAbort =
        fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")));
    int auditRowsBeforeAbort =
        fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event")));
    assertThat(pointerRowsBeforeAbort).isZero();
    assertThat(auditRowsBeforeAbort).isZero();

    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.abort(request, null, "World hold expired");
          return null;
        });

    CanonicalInitialAdmissionOwnerProof aborted =
        fixture
            .initialAdmissionRepository
            .read(NAMESPACE, request.initialAdmissionRequestId())
            .orElseThrow();
    assertThat(aborted.provesAbort()).isTrue();
    assertThat(aborted.outcome()).isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
    assertThat(aborted.committedPointerVersion()).isNull();
    assertThat(aborted.auditEventId()).isNull();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer"))))
        .isEqualTo(pointerRowsBeforeAbort);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
        .isEqualTo(auditRowsBeforeAbort);

    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.reserve(request);
          return null;
        });
    fixture.transactions.execute(
        status -> {
          fixture.initialAdmissionRepository.commit(request, worldProof, null);
          return null;
        });

    CanonicalInitialAdmissionOwnerProof afterRetry =
        fixture
            .initialAdmissionRepository
            .read(NAMESPACE, request.initialAdmissionRequestId())
            .orElseThrow();
    assertThat(afterRetry).isEqualTo(aborted);
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_initial_admission_attempt"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer"))))
        .isEqualTo(pointerRowsBeforeAbort);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer_event"))))
        .isEqualTo(auditRowsBeforeAbort);
  }

  private Fixture fixture() {
    String schema = "gs_canonical_admission_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();

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
        false,
        null);
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
    CompleteLaunchBindingEvidence binding = completeBinding(descriptor);
    when(client.resolve(any(AuthoredWorldLaunchDescriptorEvidence.Request.class)))
        .thenReturn(descriptor);
    when(client.get(any(GetRequest.class))).thenReturn(descriptor);
    when(client.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(binding);
    return client;
  }

  private static CompleteLaunchBindingEvidence completeBinding(
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    String commitId = "publish-commit-canonical-admission";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        commitId,
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? digest("d") : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence attestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            CANONICAL_VERSION,
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish-canonical-admission",
            commitId,
            participants,
            digest("b"),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, attestation);
  }

  private static CanonicalInitialAdmissionRequest initialAdmissionRequest(
      CanonicalRealmCatalogSnapshot catalog, CompleteLaunchBindingEvidence binding) {
    String requestId = "canonical-first-open-" + catalog.realmId();
    long activeEpoch = 2L;
    UUID canonicalVersionId = binding.releaseAttestation().canonicalVersionId();
    CanonicalInitialAdmissionRequest.OriginKind origin =
        CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER;
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            NAMESPACE,
            TENANT,
            catalog.worldSlug(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            "SHARED",
            GAME_INSTANCE_UUID,
            canonicalVersionId,
            activeEpoch,
            catalog.catalogRevision(),
            origin,
            null,
            requestId);
    return new CanonicalInitialAdmissionRequest(
        NAMESPACE,
        TENANT,
        catalog.worldSlug(),
        catalog.realmId(),
        catalog.playableStateNamespaceId(),
        "SHARED",
        GAME_INSTANCE_UUID,
        canonicalVersionId,
        activeEpoch,
        catalog.catalogRevision(),
        origin,
        null,
        requestId,
        requestDigest,
        uuid(113),
        uuid(114),
        digest("e"));
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
        "explicit-policy-v1",
        null,
        null);
  }

  private static String digest(String letter) {
    return "sha256:" + letter.repeat(64);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private record Fixture(
      DSLContext dsl,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionCanonicalLaunchPreparationRepository preparationRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository,
      CanonicalInitialAdmissionRepository initialAdmissionRepository) {
    IntakeReceipt registerSource() {
      return Objects.requireNonNull(
          transactions.execute(status -> sourceRepository.register(INTAKE_REQUEST, freshSource())));
    }

    CanonicalRealmCatalogSnapshot createCatalog(IntakeReceipt source) {
      var prepared = catalogRepository.prepareInitialPublicProduction(catalogRequest(source));
      return Objects.requireNonNull(
          transactions.execute(
              status -> catalogRepository.createInitialPublicProduction(prepared)));
    }

    FreshGameSessionTenantAssociation createTestOnlyFreshMappingAndRunningLaunch(
        IntakeReceipt source, CompleteLaunchBindingEvidence binding) {
      RuntimeTenantIdentityEvidence sourceIdentity =
          new RuntimeTenantIdentityEvidence(
              1,
              NAMESPACE,
              source.source().registrationRequestId(),
              TENANT,
              source.source().sourceGameRowId(),
              source.source().sourceGameTenantKey(),
              source.source().provenanceKind());
      FreshGameSessionTenantAssociation association =
          transactions.execute(
              status -> {
                Record reservation =
                    Objects.requireNonNull(
                        dsl.fetchOne(
                            "INSERT INTO game_session_tenant_scope_reservation "
                                + "(reservation_kind) VALUES ('FRESH_SOURCE_BOUND') "
                                + "RETURNING game_session_tenant_id"));
                long allocatedTenantId = reservation.get("game_session_tenant_id", Long.class);
                FreshGameSessionTenantAssociation allocatedAssociation =
                    new FreshGameSessionTenantAssociation(
                        ASSOCIATION_OPERATION, allocatedTenantId, sourceIdentity);
                dsl.execute(
                    "INSERT INTO game_session_tenant_canonical_claim "
                        + "(target_namespace, canonical_tenant_id, legacy_game_session_tenant_id, "
                        + "association_kind, reservation_kind, association_operation_id, "
                        + "association_request_id) "
                        + "VALUES (?, ?, ?, 'FRESH_SOURCE_BOUND', 'FRESH_SOURCE_BOUND', ?, ?)",
                    NAMESPACE,
                    TENANT,
                    allocatedTenantId,
                    ASSOCIATION_OPERATION,
                    source.source().registrationRequestId());
                dsl.execute(
                    "INSERT INTO game_session_fresh_tenant_association "
                        + "(association_operation_id, target_namespace, association_request_id, "
                        + "source_schema_version, source_target_namespace, source_request_id, "
                        + "source_canonical_tenant_id, canonical_tenant_id, "
                        + "legacy_game_session_tenant_id, source_game_row_id, "
                        + "source_game_tenant_key, provenance_kind, association_kind) "
                        + "VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', "
                        + "'FRESH_SOURCE_BOUND')",
                    ASSOCIATION_OPERATION,
                    NAMESPACE,
                    source.source().registrationRequestId(),
                    NAMESPACE,
                    source.source().registrationRequestId(),
                    TENANT,
                    TENANT,
                    allocatedTenantId,
                    source.source().sourceGameRowId(),
                    source.source().sourceGameTenantKey());
                Record instance =
                    Objects.requireNonNull(
                        dsl.fetchOne(
                            "INSERT INTO game_instances "
                                + "(tenant_id, runtime_version, game_template_id, "
                                + "launch_descriptor_id, version_id, release_bundle_id, "
                                + "generation_config_revision, version_state_epoch, "
                                + "owner_account_id, status, row_version, game_instance_uuid, "
                                + "run_owned_start_request_id, run_owned_start_request_digest, "
                                + "run_owned_start_published_release_bundle_ref, "
                                + "run_owned_start_preparing_epoch, run_owned_start_active_epoch) "
                                + "VALUES (?, '72', 404, ?, 72, 73, 'generation-1', 3, 77, "
                                + "'STARTING', 1, ?, ?, ?, ?, 1, 2) RETURNING id",
                            allocatedTenantId,
                            binding.descriptor().launchDescriptorId(),
                            GAME_INSTANCE_UUID,
                            CONTROL_PLANE_REQUEST_ID,
                            "c".repeat(64),
                            binding.descriptor().publishedReleaseBundleRef()));
                long gameInstanceId = instance.get("id", Long.class);
                launchRepository.capture(allocatedAssociation, gameInstanceId, binding);
                dsl.execute(
                    "UPDATE game_instances SET status = 'RUNNING' "
                        + "WHERE tenant_id = ? AND id = ?",
                    allocatedTenantId,
                    gameInstanceId);
                return allocatedAssociation;
              });
      return Objects.requireNonNull(association, "test fixture tenant association");
    }
  }
}
