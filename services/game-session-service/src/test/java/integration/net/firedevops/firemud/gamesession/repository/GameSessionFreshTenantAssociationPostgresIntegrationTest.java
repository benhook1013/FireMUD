package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.client.GameDesignFreshTenantCreationClient;
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
 * Game Design responses are explicit mocks here. Client unit tests prove configured peer pinning
 * and unauthenticated-response rejection, not a genuine authenticated Game Design-to-SQL flow.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionFreshTenantAssociationPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String NAMESPACE = "test";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void commitsCompleteOwnerReceiptAndExactRetryReadsTheOriginalAllocation() throws Exception {
    try (Fixture fixture = newFixture(null)) {
      FreshTenantCreationEvidence evidence = evidence(UUID.randomUUID(), REQUEST_DIGEST_A);
      GameDesignFreshTenantCreationClient client = recordingClient(evidence);
      GameSessionFreshTenantAssociationRepository repository = fixture.repository(client);

      var created =
          repository.associateFreshTenant(evidence.creationRequestId(), evidence.requestDigest());
      var independentlyRead =
          repository.readByCanonicalTenantId(evidence.canonicalTenantId()).orElseThrow();
      var retry =
          repository.associateFreshTenant(evidence.creationRequestId(), evidence.requestDigest());

      assertThat(created.gameSessionTenantId()).isPositive();
      assertThat(created.associationOperationId()).isNotEqualTo(evidence.operationId());
      assertThat(created.sourceEvidence()).isEqualTo(evidence);
      assertThat(independentlyRead).isEqualTo(created);
      assertThat(retry).isEqualTo(created);
      assertThat(count(fixture.dsl(), "game_session_fresh_tenant_association")).isEqualTo(1L);
      assertThat(count(fixture.dsl(), "game_session_tenant_canonical_claim")).isEqualTo(1L);
      assertThat(count(fixture.dsl(), "game_session_tenant_scope_reservation")).isEqualTo(1L);
      verify(client, times(2))
          .resolveCreation(evidence.creationRequestId(), evidence.requestDigest());
    }
  }

  @Test
  void rejectsChangedSourceReceiptAndMismatchedRequestWithoutCreatingAnotherAssociation()
      throws Exception {
    try (Fixture fixture = newFixture(null)) {
      FreshTenantCreationEvidence original = evidence(UUID.randomUUID(), REQUEST_DIGEST_A);
      FreshTenantCreationEvidence changedSource =
          evidence(original.creationRequestId(), original.requestDigest(), UUID.randomUUID());
      GameDesignFreshTenantCreationClient client = mock(GameDesignFreshTenantCreationClient.class);
      when(client.resolveCreation(any(), anyString())).thenReturn(original, changedSource);
      GameSessionFreshTenantAssociationRepository repository = fixture.repository(client);

      var created = repository.associateFreshTenant(original.creationRequestId(), REQUEST_DIGEST_A);
      assertThatThrownBy(
              () -> repository.associateFreshTenant(original.creationRequestId(), REQUEST_DIGEST_A))
          .isInstanceOf(
              GameSessionFreshTenantAssociationRepository.InvalidFreshTenantAssociationException
                  .class)
          .hasMessageContaining("complete original Game Design receipt");
      assertThat(repository.readByCanonicalTenantId(original.canonicalTenantId()).orElseThrow())
          .isEqualTo(created);
      assertThat(count(fixture.dsl(), "game_session_fresh_tenant_association")).isEqualTo(1L);
      assertThat(count(fixture.dsl(), "game_session_tenant_scope_reservation")).isEqualTo(1L);

      when(client.resolveCreation(original.creationRequestId(), REQUEST_DIGEST_B))
          .thenReturn(original);
      assertThatThrownBy(
              () -> repository.associateFreshTenant(original.creationRequestId(), REQUEST_DIGEST_B))
          .isInstanceOf(
              GameSessionFreshTenantAssociationRepository.InvalidFreshTenantAssociationException
                  .class)
          .hasMessageContaining("exact Game Session request");
      assertThat(count(fixture.dsl(), "game_session_fresh_tenant_association")).isEqualTo(1L);
    }
  }

  @Test
  void refusesToAdoptRetainedCanonicalClaimOrNumericScope() throws Exception {
    try (Fixture fixture = newFixture(null)) {
      FreshTenantCreationEvidence evidence = evidence(UUID.randomUUID(), REQUEST_DIGEST_A);
      fixture
          .dsl()
          .execute(
              "INSERT INTO game_session_tenant_scope_reservation"
                  + " (game_session_tenant_id, reservation_kind) VALUES (1, 'LEGACY_OCCUPIED')");
      fixture
          .dsl()
          .execute(
              "INSERT INTO game_session_tenant_canonical_claim"
                  + " (target_namespace, canonical_tenant_id, legacy_game_session_tenant_id,"
                  + " association_kind, reservation_kind, association_operation_id,"
                  + " association_request_id) VALUES (?, ?, 1, 'RETAINED_V8_1',"
                  + " 'LEGACY_OCCUPIED', ?, ?)",
              NAMESPACE,
              evidence.canonicalTenantId(),
              UUID.randomUUID(),
              evidence.creationRequestId());
      GameDesignFreshTenantCreationClient client = recordingClient(evidence);
      GameSessionFreshTenantAssociationRepository repository = fixture.repository(client);

      assertThatThrownBy(
              () ->
                  repository.associateFreshTenant(
                      evidence.creationRequestId(), evidence.requestDigest()))
          .isInstanceOf(
              GameSessionFreshTenantAssociationRepository.InvalidFreshTenantAssociationException
                  .class)
          .hasMessageContaining("retained or partial identity cannot be adopted");
      assertThat(count(fixture.dsl(), "game_session_fresh_tenant_association")).isZero();
      assertThat(count(fixture.dsl(), "game_session_tenant_scope_reservation")).isEqualTo(1L);
      assertThat(count(fixture.dsl(), "game_session_tenant_canonical_claim")).isEqualTo(1L);
    }
  }

  @Test
  void concurrentExactDuplicateRequestsReturnOneCommittedAssociation() throws Exception {
    try (Fixture fixture = newFixture(null)) {
      FreshTenantCreationEvidence evidence = evidence(UUID.randomUUID(), REQUEST_DIGEST_A);
      CyclicBarrier bothOwnerReads = new CyclicBarrier(2);
      GameDesignFreshTenantCreationClient client = mock(GameDesignFreshTenantCreationClient.class);
      when(client.resolveCreation(evidence.creationRequestId(), evidence.requestDigest()))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                bothOwnerReads.await(10, TimeUnit.SECONDS);
                return evidence;
              });
      GameSessionFreshTenantAssociationRepository repository = fixture.repository(client);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<GameSessionFreshTenantAssociationRepository.FreshTenantAssociation> first =
            executor.submit(
                () ->
                    repository.associateFreshTenant(
                        evidence.creationRequestId(), evidence.requestDigest()));
        Future<GameSessionFreshTenantAssociationRepository.FreshTenantAssociation> second =
            executor.submit(
                () ->
                    repository.associateFreshTenant(
                        evidence.creationRequestId(), evidence.requestDigest()));

        var firstResult = first.get(20, TimeUnit.SECONDS);
        var secondResult = second.get(20, TimeUnit.SECONDS);
        assertThat(firstResult).isEqualTo(secondResult);
        assertThat(count(fixture.dsl(), "game_session_fresh_tenant_association")).isEqualTo(1L);
        assertThat(count(fixture.dsl(), "game_session_tenant_canonical_claim")).isEqualTo(1L);
        assertThat(count(fixture.dsl(), "game_session_tenant_scope_reservation")).isEqualTo(1L);
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  void rollsBackReservationAndCanonicalClaimWhenAssociationInsertFails() throws Exception {
    try (Fixture fixture = newFixture(null)) {
      fixture
          .dsl()
          .execute(
              "ALTER TABLE game_session_fresh_tenant_association"
                  + " ADD CONSTRAINT chk_test_reject_fresh_association CHECK (FALSE)");
      FreshTenantCreationEvidence evidence = evidence(UUID.randomUUID(), REQUEST_DIGEST_A);
      GameSessionFreshTenantAssociationRepository repository =
          fixture.repository(recordingClient(evidence));

      assertThatThrownBy(
              () ->
                  repository.associateFreshTenant(
                      evidence.creationRequestId(), evidence.requestDigest()))
          .isInstanceOf(RuntimeException.class);
      assertThat(count(fixture.dsl(), "game_session_fresh_tenant_association")).isZero();
      assertThat(count(fixture.dsl(), "game_session_tenant_canonical_claim")).isZero();
      assertThat(count(fixture.dsl(), "game_session_tenant_scope_reservation")).isZero();
    }
  }

  @Test
  void deniesHistoricalV25AssociationWithoutReconstructingMissingSourceReceipt() throws Exception {
    try (Fixture fixture = newFixture(MigrationVersion.fromVersion("25"))) {
      UUID requestId = UUID.randomUUID();
      UUID canonicalTenantId = UUID.randomUUID();
      UUID associationOperationId = UUID.randomUUID();
      Record reservation =
          Objects.requireNonNull(
              fixture
                  .dsl()
                  .fetchOne(
                      "INSERT INTO game_session_tenant_scope_reservation (reservation_kind)"
                          + " VALUES ('FRESH_SOURCE_BOUND') RETURNING game_session_tenant_id"),
              "V25 reservation insert returned no generated tenant ID");
      Long tenantId = reservation.get("game_session_tenant_id", Long.class);
      fixture
          .dsl()
          .execute(
              "INSERT INTO game_session_tenant_canonical_claim"
                  + " (target_namespace, canonical_tenant_id, legacy_game_session_tenant_id,"
                  + " association_kind, reservation_kind, association_operation_id,"
                  + " association_request_id) VALUES (?, ?, ?, 'FRESH_SOURCE_BOUND',"
                  + " 'FRESH_SOURCE_BOUND', ?, ?)",
              NAMESPACE,
              canonicalTenantId,
              tenantId,
              associationOperationId,
              requestId);
      fixture
          .dsl()
          .execute(
              "INSERT INTO game_session_fresh_tenant_association"
                  + " (association_operation_id, target_namespace, association_request_id,"
                  + " source_schema_version, source_target_namespace, source_request_id,"
                  + " source_canonical_tenant_id, canonical_tenant_id,"
                  + " legacy_game_session_tenant_id, source_game_row_id, source_game_tenant_key,"
                  + " provenance_kind, association_kind)"
                  + " VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, 9, 'legacy-source-key',"
                  + " 'NEW_GAME_ROW', 'FRESH_SOURCE_BOUND')",
              associationOperationId,
              NAMESPACE,
              requestId,
              NAMESPACE,
              requestId,
              canonicalTenantId,
              canonicalTenantId,
              tenantId);
      fixture.migrateLatest();

      GameDesignFreshTenantCreationClient client = mock(GameDesignFreshTenantCreationClient.class);
      GameSessionFreshTenantAssociationRepository repository = fixture.repository(client);
      assertThatThrownBy(() -> repository.readByCanonicalTenantId(canonicalTenantId))
          .isInstanceOf(
              GameSessionFreshTenantAssociationRepository.InvalidFreshTenantAssociationException
                  .class)
          .hasMessageContaining("missing required stored evidence: complete_source_evidence");
      verifyNoInteractions(client);
      assertThat(count(fixture.dsl(), "game_session_fresh_tenant_association")).isEqualTo(1L);
    }
  }

  private static GameDesignFreshTenantCreationClient recordingClient(
      FreshTenantCreationEvidence evidence) {
    GameDesignFreshTenantCreationClient client = mock(GameDesignFreshTenantCreationClient.class);
    when(client.resolveCreation(evidence.creationRequestId(), evidence.requestDigest()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return evidence;
            });
    return client;
  }

  private static FreshTenantCreationEvidence evidence(UUID requestId, String requestDigest) {
    return evidence(requestId, requestDigest, UUID.randomUUID());
  }

  private static FreshTenantCreationEvidence evidence(
      UUID requestId, String requestDigest, UUID sourceOperationId) {
    String sourceKey = "owner-key-" + requestId.toString().substring(0, 8);
    long sourceRowId = 91L;
    UUID canonicalTenantId = UUID.randomUUID();
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        requestId,
        sourceOperationId,
        requestDigest,
        canonicalTenantId,
        sourceRowId,
        sourceKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            requestId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            sourceRowId,
            sourceKey,
            "NEW_GAME_ROW"));
  }

  private static long count(DSLContext dsl, String tableName) {
    Record row =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT COUNT(*) AS row_count FROM " + tableName),
            "Count query returned no row for table " + tableName);
    return row.get("row_count", Long.class);
  }

  private static Fixture newFixture(MigrationVersion target) {
    String schema = "gs_fresh_tenant_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION)
            .target(target == null ? MigrationVersion.LATEST : target)
            .load();
    flyway.migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(
        schema, dataSource, flyway, dsl, new DataSourceTransactionManager(dataSource));
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      Flyway flyway,
      DSLContext dsl,
      DataSourceTransactionManager transactionManager)
      implements AutoCloseable {
    GameSessionFreshTenantAssociationRepository repository(
        GameDesignFreshTenantCreationClient client) {
      return new GameSessionFreshTenantAssociationRepository(
          dsl, transactionManager, client, NAMESPACE);
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

  private static final String REQUEST_DIGEST_A = "sha256:" + "a".repeat(64);
  private static final String REQUEST_DIGEST_B = "sha256:" + "b".repeat(64);
}
