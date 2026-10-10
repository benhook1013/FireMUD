package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.client.GameDesignFreshTenantCreationClient;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchProofFixtures.SyntheticLaunchProof;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.CanonicalInstanceLaunch;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.InvalidCanonicalInstanceLaunchException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.CanonicalRealmCatalog;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociation;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof of atomic original StartSession owner allocation and exact retained readback.
 * Synthetic Account tuple, descriptor/release, and Game Design source/catalog values are storage
 * inputs only; this suite proves neither authenticated producer transport nor current
 * authorization.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionCanonicalInstanceLaunchPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String NAMESPACE = "test";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void atomicallyCreatesStartingPairAndExactRetryKeepsAllocationAfterLifecycleAdvance()
      throws Exception {
    try (Fixture fixture = newFixture(null)) {
      LaunchInputs inputs = prepareLaunch(fixture);
      CanonicalInstanceLaunch created =
          inputs
              .repository()
              .createStartingLaunch(
                  inputs.proof().originalTuple(),
                  inputs.proof().completeLaunchBindingEvidence(),
                  inputs.catalog());

      assertThat(created.currentGameInstanceStatus())
          .isEqualTo(
              CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                  .STARTING);
      assertThat(created.currentRowVersion()).isZero();
      assertThat(created.capturedStartingRowVersion()).isZero();
      assertThat(created.originalStartSessionTuple())
          .containsExactly(inputs.proof().originalTuple().canonicalBytes());
      assertThat(created.catalogRevision()).isEqualTo(inputs.catalog().catalogRevision());
      assertThat(created.catalogCreationRequestId()).isEqualTo(inputs.catalog().catalogRequestId());
      assertThat(created.catalogSourceIntakeOperationId())
          .isEqualTo(inputs.catalog().intakeOperationId());
      assertThat(
              countByRequest(
                  fixture.dsl(),
                  "game_instances",
                  "run_owned_start_request_id",
                  created.controlPlaneRequestId()))
          .isEqualTo(1L);
      assertThat(
              countByRequest(
                  fixture.dsl(),
                  "game_session_canonical_instance_launch",
                  "control_plane_request_id",
                  created.controlPlaneRequestId()))
          .isEqualTo(1L);

      var independent =
          inputs
              .repository()
              .readByLaunchIdentity(
                  selector(inputs.proof(), inputs.catalog(), created.gameInstanceUuid()))
              .orElseThrow();
      assertThat(independent.gameInstanceUuid()).isEqualTo(created.gameInstanceUuid());
      assertThat(independent.catalogRevision()).isEqualTo(inputs.catalog().catalogRevision());
      assertThat(independent.launchBindingEvidence())
          .isEqualTo(inputs.proof().completeLaunchBindingEvidence());

      assertThat(
              fixture
                  .dsl()
                  .execute(
                      "UPDATE game_instances SET status = 'RUNNING', row_version = row_version + 1"
                          + " WHERE tenant_id = ? AND id = ?",
                      created.gameSessionTenantId(),
                      created.gameInstanceId()))
          .isEqualTo(1);
      CanonicalInstanceLaunch retry =
          inputs
              .repository()
              .createStartingLaunch(
                  inputs.proof().originalTuple(),
                  inputs.proof().completeLaunchBindingEvidence(),
                  inputs.catalog());
      assertThat(retry.gameInstanceUuid()).isEqualTo(created.gameInstanceUuid());
      assertThat(retry.currentGameInstanceStatus())
          .isEqualTo(
              CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.RUNNING);
      assertThat(retry.currentRowVersion()).isEqualTo(created.capturedStartingRowVersion() + 1L);
      assertThat(retry.capturedStartingRowVersion())
          .isEqualTo(created.capturedStartingRowVersion());

      var changedTuple =
          GameSessionCanonicalInstanceLaunchProofFixtures.withAuditReason(
              inputs.proof().originalTuple(), "substituted retry tuple");
      assertThatThrownBy(
              () ->
                  inputs
                      .repository()
                      .createStartingLaunch(
                          changedTuple,
                          inputs.proof().completeLaunchBindingEvidence(),
                          inputs.catalog()))
          .isInstanceOf(InvalidCanonicalInstanceLaunchException.class)
          .hasMessageContaining("already bound to different complete launch evidence");
      assertThat(
              countByRequest(
                  fixture.dsl(),
                  "game_instances",
                  "run_owned_start_request_id",
                  created.controlPlaneRequestId()))
          .isEqualTo(1L);
    }
  }

  @Test
  void rollsBackRuntimeAllocationWhenAtomicLaunchAssociationInsertFails() throws Exception {
    try (Fixture fixture = newFixture(null)) {
      LaunchInputs inputs = prepareLaunch(fixture);
      fixture
          .dsl()
          .execute(
              "ALTER TABLE game_session_canonical_instance_launch"
                  + " ADD CONSTRAINT chk_test_reject_canonical_launch CHECK (FALSE)");

      assertThatThrownBy(
              () ->
                  inputs
                      .repository()
                      .createStartingLaunch(
                          inputs.proof().originalTuple(),
                          inputs.proof().completeLaunchBindingEvidence(),
                          inputs.catalog()))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              countByRequest(
                  fixture.dsl(),
                  "game_instances",
                  "run_owned_start_request_id",
                  inputs.proof().originalTuple().controlPlaneRequestId()))
          .isZero();
      assertThat(
              countByRequest(
                  fixture.dsl(),
                  "game_session_canonical_instance_launch",
                  "control_plane_request_id",
                  inputs.proof().originalTuple().controlPlaneRequestId()))
          .isZero();
    }
  }

  @Test
  void retainedV42LaunchWithoutCapturedTupleRemainsNullAndCannotBeUpgradedByRetry()
      throws Exception {
    try (Fixture fixture = newFixture(MigrationVersion.fromVersion("42"))) {
      LaunchInputs inputs = prepareLaunch(fixture);
      UUID retainedUuid = UUID.randomUUID();
      long retainedInstanceId = insertHistoricalV26Pair(fixture.dsl(), inputs, retainedUuid);
      fixture.migrateLatest();

      var currentCatalog =
          fixture
              .catalogRepository()
              .readByCanonicalTenantId(inputs.catalog().canonicalTenantId())
              .orElseThrow();
      var repository = fixture.launchRepository();
      assertThatThrownBy(
              () ->
                  repository.createStartingLaunch(
                      inputs.proof().originalTuple(),
                      inputs.proof().completeLaunchBindingEvidence(),
                      currentCatalog))
          .isInstanceOf(InvalidCanonicalInstanceLaunchException.class)
          .hasMessageContaining("original_start_session_tuple");

      Record retained =
          Objects.requireNonNull(
              fixture
                  .dsl()
                  .fetchOne(
                      "SELECT original_start_session_tuple IS NULL AS tuple_missing,"
                          + " catalog_revision IS NULL AS revision_missing"
                          + " FROM game_session_canonical_instance_launch"
                          + " WHERE target_namespace = ? AND control_plane_request_id = ?",
                      NAMESPACE,
                      inputs.proof().originalTuple().controlPlaneRequestId()));
      assertThat(retained.get("tuple_missing", Boolean.class)).isTrue();
      assertThat(retained.get("revision_missing", Boolean.class)).isTrue();
      assertThat(
              countByRequest(
                  fixture.dsl(),
                  "game_instances",
                  "run_owned_start_request_id",
                  inputs.proof().originalTuple().controlPlaneRequestId()))
          .isEqualTo(1L);
      assertThat(retainedInstanceId).isPositive();
    }
  }

  private static LaunchInputs prepareLaunch(Fixture fixture) {
    FreshTenantAssociation association = createFreshAssociation(fixture);
    var catalogProof =
        GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(association);
    CanonicalRealmCatalog catalog =
        fixture
            .catalogRepository()
            .establishFreshPublicRealm(
                association,
                catalogProof.source(),
                catalogProof.publishedSet(),
                UUID.randomUUID(),
                UUID.randomUUID());
    String operationId = "original-start-session-" + UUID.randomUUID();
    long gameTemplateId = 91L;
    String descriptorId = "launch-" + UUID.randomUUID();
    String releaseRef = "fixture-release/" + UUID.randomUUID();
    SyntheticLaunchProof proof =
        GameSessionCanonicalInstanceLaunchProofFixtures.create(
            NAMESPACE,
            operationId,
            association.canonicalTenantId(),
            catalog.worldSlug(),
            catalog.sourceEvidence(),
            gameTemplateId,
            13L,
            17L,
            descriptorId,
            "generation-fixture",
            2L,
            releaseRef);
    return new LaunchInputs(catalog, proof, fixture.launchRepository());
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector(
      SyntheticLaunchProof proof, CanonicalRealmCatalog catalog, UUID gameInstanceUuid) {
    var descriptor = proof.completeLaunchBindingEvidence().descriptor();
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        UUID.randomUUID(),
        catalog.targetNamespace(),
        catalog.canonicalTenantId(),
        catalog.worldSlug(),
        gameInstanceUuid,
        descriptor.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        proof.completeLaunchBindingEvidence().releaseAttestation().evidenceDigest());
  }

  private static long insertHistoricalV26Pair(
      DSLContext dsl, LaunchInputs inputs, UUID gameInstanceUuid) {
    var tuple = inputs.proof().originalTuple();
    var descriptor = inputs.proof().completeLaunchBindingEvidence().descriptor();
    CanonicalRealmCatalog catalog = inputs.catalog();
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Record runtime =
              tx.fetchOne(
                  "INSERT INTO game_instances"
                      + " (tenant_id, runtime_version, owner_account_id, owner_account_uuid, status,"
                      + " row_version, game_instance_uuid, game_template_id, launch_descriptor_id, version_id,"
                      + " release_bundle_id, generation_config_revision, version_state_epoch,"
                      + " run_owned_start_request_id, run_owned_start_request_digest,"
                      + " run_owned_start_published_release_bundle_ref)"
                      + " VALUES (?, ?, NULL, ?, 'STARTING', 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                  catalog.gameSessionTenantId(),
                  Long.toString(descriptor.versionId()),
                  tuple.preAuthorizationTuple().action().target().ownerAccountId(),
                  gameInstanceUuid,
                  descriptor.gameTemplateId(),
                  descriptor.launchDescriptorId(),
                  descriptor.versionId(),
                  descriptor.releaseBundleId(),
                  descriptor.generationConfigRevision(),
                  descriptor.versionStateEpoch(),
                  tuple.controlPlaneRequestId(),
                  tuple.mutationDigest(),
                  descriptor.publishedReleaseBundleRef());
          long instanceId = Objects.requireNonNull(runtime).get("id", Long.class);
          FreshTenantCreationEvidence fresh = catalog.associationSourceEvidence();
          var binding =
              GameSessionCanonicalInstanceLaunchProofFixtures.completeLaunchBindingJson(
                  inputs.proof().completeLaunchBindingEvidence());
          tx.execute(
              "INSERT INTO game_session_canonical_instance_launch"
                  + " (target_namespace, control_plane_request_id, game_session_tenant_id, canonical_tenant_id,"
                  + " tenant_association_operation_id, tenant_association_request_id,"
                  + " tenant_source_schema_version, tenant_source_target_namespace, tenant_source_request_id,"
                  + " tenant_source_canonical_tenant_id, tenant_source_game_row_id, tenant_source_game_tenant_key,"
                  + " tenant_source_provenance_kind, tenant_association_kind, game_instance_id, game_instance_uuid,"
                  + " canonical_realm_id, world_slug, playable_state_namespace_id, playable_state_scope,"
                  + " public_production, captured_starting_row_version, game_template_id, launch_descriptor_id,"
                  + " version_id, release_bundle_id, generation_config_revision, version_state_epoch,"
                  + " complete_launch_binding_evidence)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FRESH_SOURCE_BOUND',"
                  + " ?, ?, ?, ?, ?, 'SHARED', TRUE, 0, ?, ?, ?, ?, ?, ?, ?::jsonb)",
              NAMESPACE,
              tuple.controlPlaneRequestId(),
              catalog.gameSessionTenantId(),
              catalog.canonicalTenantId(),
              catalog.tenantAssociationOperationId(),
              fresh.creationRequestId(),
              fresh.schemaVersion(),
              fresh.targetNamespace(),
              fresh.creationRequestId(),
              fresh.canonicalTenantId(),
              fresh.sourceGameRowId(),
              fresh.sourceGameTenantKey(),
              fresh.provenanceKind(),
              instanceId,
              gameInstanceUuid,
              catalog.realmId(),
              catalog.worldSlug(),
              catalog.playableStateNamespaceId(),
              descriptor.gameTemplateId(),
              descriptor.launchDescriptorId(),
              descriptor.versionId(),
              descriptor.releaseBundleId(),
              descriptor.generationConfigRevision(),
              descriptor.versionStateEpoch(),
              binding);
          return instanceId;
        });
  }

  private static FreshTenantAssociation createFreshAssociation(Fixture fixture) {
    UUID requestId = UUID.randomUUID();
    UUID canonicalTenantId = UUID.randomUUID();
    FreshTenantCreationEvidence evidence = freshCreationEvidence(requestId, canonicalTenantId);
    GameDesignFreshTenantCreationClient client = mock(GameDesignFreshTenantCreationClient.class);
    when(client.resolveCreation(requestId, evidence.requestDigest()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return evidence;
            });
    return fixture
        .associationRepository(client)
        .associateFreshTenant(requestId, evidence.requestDigest());
  }

  private static FreshTenantCreationEvidence freshCreationEvidence(
      UUID creationRequestId, UUID canonicalTenantId) {
    String sourceKey = "launch-owner-key-" + creationRequestId.toString().substring(0, 8);
    long sourceRowId = Math.abs(creationRequestId.getMostSignificantBits() % 1_000_000L) + 1L;
    UUID sourceOperationId = UUID.randomUUID();
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            NAMESPACE, creationRequestId, sourceKey, "Launch fixture game", null);
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        creationRequestId,
        sourceOperationId,
        requestDigest,
        canonicalTenantId,
        sourceRowId,
        sourceKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            creationRequestId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            sourceRowId,
            sourceKey,
            "NEW_GAME_ROW"));
  }

  private static long countByRequest(
      DSLContext dsl, String table, String requestColumn, String requestId) {
    Record row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) AS row_count FROM " + table + " WHERE " + requestColumn + " = ?",
                requestId));
    return Objects.requireNonNull(row.get("row_count", Long.class));
  }

  private static Fixture newFixture(MigrationVersion migrationVersion) {
    String schema = "gs_canonical_launch_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
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
        .target(migrationVersion == null ? MigrationVersion.LATEST : migrationVersion)
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(schema, dataSource, dsl, new DataSourceTransactionManager(dataSource));
  }

  private record LaunchInputs(
      CanonicalRealmCatalog catalog,
      SyntheticLaunchProof proof,
      GameSessionCanonicalInstanceLaunchRepository repository) {}

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      DataSourceTransactionManager transactionManager)
      implements AutoCloseable {
    GameSessionFreshTenantAssociationRepository associationRepository(
        GameDesignFreshTenantCreationClient client) {
      return new GameSessionFreshTenantAssociationRepository(
          dsl, transactionManager, client, NAMESPACE);
    }

    GameSessionCanonicalRealmCatalogRepository catalogRepository() {
      return new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, NAMESPACE);
    }

    GameSessionCanonicalInstanceLaunchRepository launchRepository() {
      return new GameSessionCanonicalInstanceLaunchRepository(
          dsl, transactionManager, NAMESPACE, catalogRepository());
    }

    void migrateLatest() {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
    }

    @Override
    public void close() throws Exception {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
