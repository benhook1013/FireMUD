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
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.client.GameDesignFreshTenantCreationClient;
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
 * PostgreSQL owner-storage proof only. Game Design owner responses and the complete synthetic
 * source/publication carriers are stipulated fixtures; this suite does not prove GD transport
 * authentication or producer-side persisted selection.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionCanonicalRealmCatalogPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String NAMESPACE = "test";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void commitsCompleteSourceAndPolicyEvidenceAndExactRetryReturnsActualStoredRevision()
      throws Exception {
    try (Fixture fixture = newFixture(null)) {
      FreshTenantAssociation association = createFreshAssociation(fixture);
      GameSessionCanonicalRealmCatalogProofFixtures.Proof proof =
          GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(association);
      UUID intakeRequestId = UUID.randomUUID();
      UUID catalogRequestId = UUID.randomUUID();
      var repository = fixture.catalogRepository();

      var created =
          repository.establishFreshPublicRealm(
              association, proof.source(), proof.publishedSet(), intakeRequestId, catalogRequestId);
      var independent =
          repository.readByCanonicalTenantId(association.canonicalTenantId()).orElseThrow();
      var retry =
          repository.establishFreshPublicRealm(
              association, proof.source(), proof.publishedSet(), intakeRequestId, catalogRequestId);

      assertThat(created.canonicalTenantId()).isEqualTo(association.canonicalTenantId());
      assertThat(created.gameSessionTenantId()).isEqualTo(association.gameSessionTenantId());
      assertThat(created.tenantAssociationOperationId())
          .isEqualTo(association.associationOperationId());
      assertThat(created.sourceEvidence()).isEqualTo(proof.source());
      assertThat(created.associationSourceEvidence()).isEqualTo(association.sourceEvidence());
      assertThat(created.entryPolicy()).isEqualTo("PRESEEDED_ONLY");
      assertThat(created.realmSlug()).isEqualTo("main");
      assertThat(created.catalogRevision()).isPositive();
      assertThat(created.completePolicySetEvidence())
          .containsExactly(proof.completePolicySetBytes());
      assertThat(independent.realmId()).isEqualTo(created.realmId());
      assertThat(independent.catalogRevision()).isEqualTo(created.catalogRevision());
      assertThat(independent.completePolicySetEvidence())
          .containsExactly(created.completePolicySetEvidence());
      assertThat(retry.realmId()).isEqualTo(created.realmId());
      assertThat(retry.playableStateNamespaceId()).isEqualTo(created.playableStateNamespaceId());
      assertThat(retry.catalogRevision()).isEqualTo(created.catalogRevision());
      assertThat(count(fixture.dsl(), "game_session_authored_world_tenant_source_binding"))
          .isEqualTo(1L);
      assertThat(count(fixture.dsl(), "game_session_authored_world_source_intake")).isEqualTo(1L);
      assertThat(count(fixture.dsl(), "gameplay_tenant_shared_playable_state_namespace"))
          .isEqualTo(1L);
      assertThat(count(fixture.dsl(), "game_session_canonical_realm_catalog")).isEqualTo(1L);

      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "INSERT INTO game_session_canonical_realm_catalog"
                              + " (target_namespace, canonical_tenant_id, tenant_slug, world_slug, realm_id,"
                              + " realm_slug, realm_display_name, visible, public_production, state_scope,"
                              + " playable_state_namespace_id, character_creation_policy, catalog_revision,"
                              + " creation_request_id, request_digest, receipt_digest, source_intake_operation_id,"
                              + " source_intake_schema_version, source_intake_request_id, source_intake_request_digest,"
                              + " source_intake_receipt_digest, source_schema_version, source_registration_request_id,"
                              + " source_operation_id, source_request_digest, source_world_display_name,"
                              + " source_game_row_id, source_game_tenant_key, source_provenance_kind, source_evidence_digest)"
                              + " SELECT target_namespace, canonical_tenant_id, tenant_slug, world_slug, realm_id,"
                              + " realm_slug, realm_display_name, visible, public_production, state_scope,"
                              + " playable_state_namespace_id, character_creation_policy, catalog_revision,"
                              + " creation_request_id, request_digest, receipt_digest, source_intake_operation_id,"
                              + " source_intake_schema_version, source_intake_request_id, source_intake_request_digest,"
                              + " source_intake_receipt_digest, source_schema_version, source_registration_request_id,"
                              + " source_operation_id, source_request_digest, source_world_display_name,"
                              + " source_game_row_id, source_game_tenant_key, source_provenance_kind, source_evidence_digest"
                              + " FROM game_session_canonical_realm_catalog"
                              + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
                          NAMESPACE,
                          association.canonicalTenantId()))
          .hasMessageContaining("requires complete published policy evidence");
    }
  }

  @Test
  void rejectsChangedSourceOnStableRequestAndDoesNotCreateAnotherCatalog() throws Exception {
    try (Fixture fixture = newFixture(null)) {
      FreshTenantAssociation association = createFreshAssociation(fixture);
      var firstProof =
          GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(association);
      var changedProof =
          GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(association);
      UUID intakeRequestId = UUID.randomUUID();
      UUID catalogRequestId = UUID.randomUUID();
      var repository = fixture.catalogRepository();
      var original =
          repository.establishFreshPublicRealm(
              association,
              firstProof.source(),
              firstProof.publishedSet(),
              intakeRequestId,
              catalogRequestId);

      assertThatThrownBy(
              () ->
                  repository.establishFreshPublicRealm(
                      association,
                      changedProof.source(),
                      changedProof.publishedSet(),
                      intakeRequestId,
                      catalogRequestId))
          .isInstanceOf(
              GameSessionCanonicalRealmCatalogRepository.InvalidCanonicalRealmCatalogException
                  .class)
          .hasMessageContaining("retry differs from the complete original source");
      assertThat(
              repository
                  .readByCanonicalTenantId(association.canonicalTenantId())
                  .orElseThrow()
                  .realmId())
          .isEqualTo(original.realmId());
      assertThat(count(fixture.dsl(), "game_session_authored_world_source_intake")).isEqualTo(1L);
      assertThat(count(fixture.dsl(), "game_session_canonical_realm_catalog")).isEqualTo(1L);
    }
  }

  @Test
  void rejectsIsolatedPolicyBeforeOpeningTheOwnerTransaction() throws Exception {
    try (Fixture fixture = newFixture(null)) {
      FreshTenantAssociation association = createFreshAssociation(fixture);
      var shared = GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(association);
      var isolated =
          GameSessionCanonicalRealmCatalogProofFixtures.isolatedForSource(shared.source());
      var repository = fixture.catalogRepository();

      assertThatThrownBy(
              () ->
                  repository.establishFreshPublicRealm(
                      association,
                      isolated.source(),
                      isolated.publishedSet(),
                      UUID.randomUUID(),
                      UUID.randomUUID()))
          .isInstanceOf(
              GameSessionCanonicalRealmCatalogRepository.InvalidCanonicalRealmCatalogException
                  .class)
          .hasMessageContaining("use SHARED state scope");
      assertThat(count(fixture.dsl(), "game_session_authored_world_tenant_source_binding"))
          .isZero();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
  }

  @Test
  void rejectsPublicationOperationFromAnotherNamespaceBeforeOpeningTheOwnerTransaction()
      throws Exception {
    try (Fixture fixture = newFixture(null)) {
      String otherNamespace = "other";
      UUID canonicalTenantId = UUID.randomUUID();
      FreshTenantCreationEvidence creation =
          freshCreationEvidence(otherNamespace, UUID.randomUUID(), canonicalTenantId);
      FreshTenantAssociation association =
          new FreshTenantAssociation(
              otherNamespace, canonicalTenantId, 73L, UUID.randomUUID(), creation);
      var proof = GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(association);
      var repository =
          new GameSessionCanonicalRealmCatalogRepository(
              fixture.dsl(), fixture.transactionManager(), otherNamespace);

      assertThatThrownBy(
              () ->
                  repository.establishFreshPublicRealm(
                      association,
                      proof.source(),
                      proof.publishedSet(),
                      UUID.randomUUID(),
                      UUID.randomUUID()))
          .isInstanceOf(
              GameSessionCanonicalRealmCatalogRepository.InvalidCanonicalRealmCatalogException
                  .class)
          .hasMessageContaining("operation namespace");
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(count(fixture.dsl(), "game_session_authored_world_tenant_source_binding"))
          .isZero();
    }
  }

  @Test
  void rollsBackBindingIntakeNamespaceAndCatalogTogetherWhenCatalogInsertFails() throws Exception {
    try (Fixture fixture = newFixture(null)) {
      FreshTenantAssociation association = createFreshAssociation(fixture);
      var proof = GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(association);
      fixture
          .dsl()
          .execute(
              "ALTER TABLE game_session_canonical_realm_catalog"
                  + " ADD CONSTRAINT chk_test_reject_canonical_catalog CHECK (FALSE)");

      assertThatThrownBy(
              () ->
                  fixture
                      .catalogRepository()
                      .establishFreshPublicRealm(
                          association,
                          proof.source(),
                          proof.publishedSet(),
                          UUID.randomUUID(),
                          UUID.randomUUID()))
          .isInstanceOf(RuntimeException.class);
      assertThat(count(fixture.dsl(), "game_session_authored_world_tenant_source_binding"))
          .isZero();
      assertThat(count(fixture.dsl(), "game_session_authored_world_source_intake")).isZero();
      assertThat(count(fixture.dsl(), "gameplay_tenant_shared_playable_state_namespace")).isZero();
      assertThat(count(fixture.dsl(), "game_session_canonical_realm_catalog")).isZero();
      assertThat(count(fixture.dsl(), "game_session_fresh_tenant_association")).isEqualTo(1L);
    }
  }

  @Test
  void deniesHistoricalV23CatalogWithoutReconstructingMissingPublishedSetEvidence()
      throws Exception {
    try (Fixture fixture = newFixture(MigrationVersion.fromVersion("41"))) {
      FreshTenantAssociation association = createFreshAssociation(fixture);
      var proof = GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(association);
      seedHistoricalV23CatalogWithoutPolicyEvidence(fixture.dsl(), proof);
      fixture.migrateLatest();

      assertThatThrownBy(
              () ->
                  fixture
                      .catalogRepository()
                      .readByCanonicalTenantId(association.canonicalTenantId()))
          .isInstanceOf(
              GameSessionCanonicalRealmCatalogRepository.InvalidCanonicalRealmCatalogException
                  .class)
          .hasMessageContaining("complete_policy_set_evidence");
      assertThat(count(fixture.dsl(), "game_session_canonical_realm_catalog")).isEqualTo(1L);
      Record retainedCatalog =
          Objects.requireNonNull(
              fixture
                  .dsl()
                  .fetchOne(
                      "SELECT complete_policy_set_evidence IS NULL AS missing FROM"
                          + " game_session_canonical_realm_catalog"
                          + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
                      NAMESPACE,
                      association.canonicalTenantId()),
              "Historical catalog must survive migration");
      assertThat(retainedCatalog.get("missing", Boolean.class)).isTrue();
    }
  }

  private static FreshTenantAssociation createFreshAssociation(Fixture fixture) {
    UUID requestId = UUID.randomUUID();
    FreshTenantCreationEvidence evidence = freshCreationEvidence(requestId, UUID.randomUUID());
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
    return freshCreationEvidence(NAMESPACE, creationRequestId, canonicalTenantId);
  }

  private static FreshTenantCreationEvidence freshCreationEvidence(
      String namespace, UUID creationRequestId, UUID canonicalTenantId) {
    String sourceKey = "owner-key-" + creationRequestId.toString().substring(0, 8);
    long sourceRowId = Math.abs(creationRequestId.getMostSignificantBits() % 1_000_000L) + 1L;
    UUID sourceOperationId = UUID.randomUUID();
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            namespace, creationRequestId, sourceKey, "Fixture game", null);
    return new FreshTenantCreationEvidence(
        1,
        namespace,
        creationRequestId,
        sourceOperationId,
        requestDigest,
        canonicalTenantId,
        sourceRowId,
        sourceKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            namespace,
            creationRequestId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            sourceRowId,
            sourceKey,
            "NEW_GAME_ROW"));
  }

  private static void seedHistoricalV23CatalogWithoutPolicyEvidence(
      DSLContext dsl, GameSessionCanonicalRealmCatalogProofFixtures.Proof proof) {
    AuthoredWorldSourceEvidence source = proof.source();
    UUID intakeOperationId = UUID.randomUUID();
    UUID intakeRequestId = UUID.randomUUID();
    UUID realmId = UUID.randomUUID();
    UUID sharedNamespaceId = UUID.randomUUID();
    UUID catalogRequestId = UUID.randomUUID();
    String digest = "sha256:" + "a".repeat(64);
    var selected = proof.publishedSet().policies().getFirst().source().policy();
    dsl.transaction(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          tx.execute(
              "INSERT INTO game_session_authored_world_tenant_source_binding"
                  + " (target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id,"
                  + " source_game_tenant_key, provenance_kind) VALUES (?, ?, ?, ?, ?, ?)",
              source.targetNamespace(),
              source.canonicalTenantId(),
              source.tenantSlug(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind());
          tx.execute(
              "INSERT INTO game_session_authored_world_source_intake"
                  + " (operation_id, schema_version, target_namespace, intake_request_id, request_digest,"
                  + " source_schema_version, source_registration_request_id, source_operation_id,"
                  + " source_request_digest, canonical_tenant_id, tenant_slug, world_slug, world_display_name,"
                  + " source_game_row_id, source_game_tenant_key, source_provenance_kind,"
                  + " source_evidence_digest, receipt_digest)"
                  + " VALUES (?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              intakeOperationId,
              source.targetNamespace(),
              intakeRequestId,
              digest,
              source.registrationRequestId(),
              source.operationId(),
              source.requestDigest(),
              source.canonicalTenantId(),
              source.tenantSlug(),
              source.worldSlug(),
              source.worldDisplayName(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind(),
              source.evidenceDigest(),
              digest);
          tx.execute(
              "INSERT INTO gameplay_tenant_shared_playable_state_namespace"
                  + " (tenant_id, playable_state_namespace_id, canonical_tenant_id, target_namespace,"
                  + " source_intake_operation_id, source_intake_schema_version, source_intake_request_id,"
                  + " source_intake_request_digest, source_intake_receipt_digest, source_schema_version,"
                  + " source_registration_request_id, source_operation_id, source_request_digest,"
                  + " source_game_row_id, source_game_tenant_key, source_provenance_kind, source_evidence_digest)"
                  + " VALUES (NULL, ?, ?, ?, ?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)",
              sharedNamespaceId,
              source.canonicalTenantId(),
              source.targetNamespace(),
              intakeOperationId,
              intakeRequestId,
              digest,
              digest,
              source.registrationRequestId(),
              source.operationId(),
              source.requestDigest(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind(),
              source.evidenceDigest());
          tx.execute(
              "INSERT INTO game_session_canonical_realm_catalog"
                  + " (target_namespace, canonical_tenant_id, tenant_slug, world_slug, realm_id,"
                  + " realm_slug, realm_display_name, visible, public_production, state_scope,"
                  + " playable_state_namespace_id, character_creation_policy, catalog_revision,"
                  + " creation_request_id, request_digest, receipt_digest, source_intake_operation_id,"
                  + " source_intake_schema_version, source_intake_request_id, source_intake_request_digest,"
                  + " source_intake_receipt_digest, source_schema_version, source_registration_request_id,"
                  + " source_operation_id, source_request_digest, source_world_display_name,"
                  + " source_game_row_id, source_game_tenant_key, source_provenance_kind, source_evidence_digest)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, 'SHARED', ?, ?, 1, ?, ?, ?, ?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)",
              source.targetNamespace(),
              source.canonicalTenantId(),
              source.tenantSlug(),
              source.worldSlug(),
              realmId,
              selected.realmSlug(),
              selected.realmDisplayName(),
              sharedNamespaceId,
              selected.entryPolicy().name(),
              catalogRequestId,
              digest,
              digest,
              intakeOperationId,
              intakeRequestId,
              digest,
              digest,
              source.registrationRequestId(),
              source.operationId(),
              source.requestDigest(),
              source.worldDisplayName(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind(),
              source.evidenceDigest());
        });
  }

  private static long count(DSLContext dsl, String tableName) {
    Record row =
        Objects.requireNonNull(dsl.fetchOne("SELECT COUNT(*) AS row_count FROM " + tableName));
    return Objects.requireNonNull(row.get("row_count", Long.class));
  }

  private static Fixture newFixture(MigrationVersion migrationVersion) {
    String schema = "gs_canonical_realm_" + UUID.randomUUID().toString().replace("-", "");
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
