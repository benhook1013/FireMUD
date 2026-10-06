package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.repository.FreshGameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.FreshGameSessionTenantAssociationRepository.FreshTenantAssociationConflictException;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("resource")
class FreshGameSessionTenantAssociationIntegrationTest {
  private static final String NAMESPACE = "fresh-tenant-association-test";
  private static final String POSTGRES_URL_ENV = "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
  private static String postgresJdbcUrl;
  private static String postgresUsername;
  private static String postgresPassword;
  private static boolean externalPostgres;
  private static boolean startedOwnedContainer;

  @BeforeAll
  static void configureDatabase() {
    String externalUrl = System.getenv(POSTGRES_URL_ENV);
    if (externalUrl != null) {
      postgresJdbcUrl = validateExternalLoopbackPostgresUrl(externalUrl);
      postgresUsername = "postgres";
      postgresPassword = "";
      externalPostgres = true;
      return;
    }
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "PostgreSQL proof requires Docker");
    POSTGRES.start();
    startedOwnedContainer = true;
    postgresJdbcUrl = POSTGRES.getJdbcUrl();
    postgresUsername = POSTGRES.getUsername();
    postgresPassword = POSTGRES.getPassword();
  }

  @AfterAll
  static void stopOwnedContainer() {
    if (startedOwnedContainer) {
      POSTGRES.stop();
    }
  }

  @Test
  void migrationFencesExistingScopesAndFreshAssociationIsExactImmutableAndRaceSafe()
      throws Exception {
    try (TestSchema database = new TestSchema("gs_fresh_tenant_")) {
      DriverManagerDataSource dataSource = database.dataSource();
      migrateThroughV14(dataSource, database.schema());
      DSLContext seedDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      seedPreV15ScopesAndRetainedAssociation(seedDsl);
      migrateLatest(dataSource, database.schema());

      DSLContext migrationDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      int tenantScopeTables =
          migrationDsl
              .fetchOne(
                  "SELECT count(DISTINCT table_name) FROM information_schema.columns "
                      + "WHERE table_schema = current_schema() AND column_name = 'tenant_id'")
              .get(0, Integer.class);
      int reservedScopeForeignKeys =
          migrationDsl
              .fetchOne(
                  "SELECT count(*) FROM information_schema.table_constraints "
                      + "WHERE table_schema = current_schema() AND constraint_type = 'FOREIGN KEY' "
                      + "AND constraint_name = 'fk_tenant_scope_reservation'")
              .get(0, Integer.class);
      assertThat(tenantScopeTables).isPositive();
      assertThat(reservedScopeForeignKeys).isEqualTo(tenantScopeTables);
      assertThat(reservationKind(migrationDsl, 701L)).isEqualTo("LEGACY_OCCUPIED");
      assertThat(reservationKind(migrationDsl, 702L)).isEqualTo("LEGACY_OCCUPIED");
      assertThat(reservationKind(migrationDsl, 812L)).isEqualTo("LEGACY_OCCUPIED");
      assertThat(
              migrationDsl
                  .fetchOne(
                      "SELECT association_kind FROM game_session_tenant_canonical_claim "
                          + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                      NAMESPACE,
                      uuid(3))
                  .get(0, String.class))
          .isEqualTo("RETAINED_V8_1");

      DataSourceTransactionManager transactionManager =
          new DataSourceTransactionManager(dataSource);
      DSLContext dsl =
          DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      FreshGameSessionTenantAssociationRepository repository =
          new FreshGameSessionTenantAssociationRepository(dsl, transactionManager, NAMESPACE);

      RuntimeTenantIdentityEvidence evidence = freshEvidence(uuid(1), uuid(2), 9_001L, "9002");
      FreshGameSessionTenantAssociation association = repository.registerAndReadback(evidence);
      assertThat(association.legacyGameSessionTenantId())
          .isNotIn(evidence.sourceGameRowId(), 9_002L);
      assertThat(reservationKind(dsl, association.legacyGameSessionTenantId()))
          .isEqualTo("FRESH_SOURCE_BOUND");
      assertThat(repository.readByRequestCommitted(evidence.requestId())).contains(association);
      assertThat(repository.registerAndReadback(evidence)).isEqualTo(association);

      RuntimeTenantIdentityEvidence changedRetry =
          freshEvidence(evidence.requestId(), evidence.canonicalTenantId(), 9_003L, "other-source");
      assertThatThrownBy(() -> repository.registerAndReadback(changedRetry))
          .isInstanceOf(FreshTenantAssociationConflictException.class);
      RuntimeTenantIdentityEvidence canonicalConflict =
          freshEvidence(uuid(4), evidence.canonicalTenantId(), 9_004L, "different-source");
      assertThatThrownBy(() -> repository.registerAndReadback(canonicalConflict))
          .isInstanceOf(FreshTenantAssociationConflictException.class);

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO feature_flag (id, tenant_id, name, enabled) "
                          + "VALUES (2, ?, 'numeric-fresh-use', true)",
                      association.legacyGameSessionTenantId()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Numeric-only tenant scope cannot use a fresh source-bound key");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE feature_flag SET tenant_id = ? WHERE id = 1",
                      association.legacyGameSessionTenantId()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Numeric-only tenant scope cannot use a fresh source-bound key");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_session_fresh_tenant_association "
                          + "SET source_game_tenant_key = ? WHERE association_operation_id = ?",
                      "changed-source",
                      association.associationOperationId()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("owner evidence is immutable");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_session_tenant_canonical_claim SET association_kind = ? "
                          + "WHERE association_operation_id = ?",
                      "RETAINED_V8_1",
                      association.associationOperationId()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("owner evidence is immutable");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_session_tenant_scope_reservation SET reservation_kind = ? "
                          + "WHERE game_session_tenant_id = ?",
                      "LEGACY_OCCUPIED",
                      association.legacyGameSessionTenantId()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("owner evidence is immutable");

      insertRetainedAssociation(dsl, uuid(5), uuid(6), 820L, uuid(7));
      assertThat(reservationKind(dsl, 820L)).isEqualTo("LEGACY_OCCUPIED");
      assertThat(
              migrationDsl
                  .fetchOne(
                      "SELECT association_kind FROM game_session_tenant_canonical_claim "
                          + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                      NAMESPACE,
                      uuid(7))
                  .get(0, String.class))
          .isEqualTo("RETAINED_V8_1");

      verifyConcurrentExactRetry(repository);
      verifyLegacyScopeWinsConcurrentAllocation(dsl, transactionManager, repository);
    }
  }

  private static void verifyConcurrentExactRetry(
      FreshGameSessionTenantAssociationRepository repository) throws Exception {
    RuntimeTenantIdentityEvidence evidence =
        freshEvidence(uuid(8), uuid(9), 9_005L, "future-source");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<FreshGameSessionTenantAssociation> first =
          executor.submit(
              () -> {
                start.await();
                return repository.registerAndReadback(evidence);
              });
      Future<FreshGameSessionTenantAssociation> second =
          executor.submit(
              () -> {
                start.await();
                return repository.registerAndReadback(evidence);
              });
      start.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  private static void verifyLegacyScopeWinsConcurrentAllocation(
      DSLContext dsl,
      DataSourceTransactionManager transactionManager,
      FreshGameSessionTenantAssociationRepository repository)
      throws Exception {
    String sequence =
        dsl.fetchOne(
                "SELECT pg_get_serial_sequence("
                    + "'game_session_tenant_scope_reservation', 'game_session_tenant_id')")
            .get(0, String.class);
    org.jooq.Record sequenceState = dsl.fetchOne("SELECT last_value, is_called FROM " + sequence);
    long candidate =
        Boolean.TRUE.equals(sequenceState.get("is_called", Boolean.class))
            ? sequenceState.get("last_value", Long.class) + 1L
            : 1L;

    CountDownLatch legacyReserved = new CountDownLatch(1);
    CountDownLatch permitLegacyCommit = new CountDownLatch(1);
    TransactionTemplate legacyTransaction = new TransactionTemplate(transactionManager);
    legacyTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> legacyWriter =
          executor.submit(
              () ->
                  legacyTransaction.execute(
                      status -> {
                        dsl.execute(
                            "INSERT INTO feature_flag (id, tenant_id, name, enabled) "
                                + "VALUES (3, ?, 'concurrent-legacy-scope', true)",
                            candidate);
                        legacyReserved.countDown();
                        await(permitLegacyCommit, "legacy scope commit");
                        return null;
                      }));
      await(legacyReserved, "legacy scope reservation");

      RuntimeTenantIdentityEvidence evidence = freshEvidence(uuid(31), uuid(32), 9_101L, "9102");
      Future<FreshGameSessionTenantAssociation> freshWriter =
          executor.submit(() -> repository.registerAndReadback(evidence));
      assertThatThrownBy(() -> freshWriter.get(100, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      permitLegacyCommit.countDown();
      legacyWriter.get(20, TimeUnit.SECONDS);
      FreshGameSessionTenantAssociation association = freshWriter.get(20, TimeUnit.SECONDS);
      assertThat(association.legacyGameSessionTenantId()).isNotEqualTo(candidate);
      assertThat(reservationKind(dsl, candidate)).isEqualTo("LEGACY_OCCUPIED");
      assertThat(reservationKind(dsl, association.legacyGameSessionTenantId()))
          .isEqualTo("FRESH_SOURCE_BOUND");
    } finally {
      permitLegacyCommit.countDown();
      executor.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch, String label) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for " + label);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for " + label, exception);
    }
  }

  private static void seedPreV15ScopesAndRetainedAssociation(DSLContext dsl) {
    dsl.execute(
        "INSERT INTO feature_flag (id, tenant_id, name, enabled) "
            + "VALUES (1, 701, 'legacy-feature', false)");
    dsl.execute(
        "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id, status) "
            + "VALUES (1, 702, 'legacy-runtime', 9007, 'STOPPED')");
    insertRetainedAssociation(dsl, uuid(10), uuid(11), 812L, uuid(3));
  }

  private static void insertRetainedAssociation(
      DSLContext dsl, UUID operationId, UUID requestId, long tenantId, UUID canonicalTenantId) {
    dsl.execute(
        "INSERT INTO game_session_retained_tenant_association ("
            + "operation_id, approval_schema_version, target_namespace, association_request_id, "
            + "request_digest, approval_operation_id, signer_key_id, approved_by, "
            + "approval_reference, signed_at, legacy_game_session_tenant_id, "
            + "canonical_tenant_id, source_game_row_id, source_game_tenant_key, provenance_kind, "
            + "game_session_evidence_digest, approval_manifest_digest, approval_signature, "
            + "snapshot_canonical_json, snapshot_evidence_digest, receipt_digest) "
            + "VALUES (?, 1, ?, ?, ?, ?, 'test-key', 'test-reviewer', 'fixture', "
            + "'2026-01-01T00:00:00Z', ?, ?, 501, 'retained-source-key', 'RETAINED_GAME_V30', "
            + "?, ?, ?, '{}', ?, ?)",
        operationId,
        NAMESPACE,
        requestId,
        digest('a'),
        uuid(12),
        tenantId,
        canonicalTenantId,
        digest('b'),
        digest('c'),
        "A".repeat(86) + "==",
        digest('d'),
        digest('e'),
        digest('f'));
  }

  private static RuntimeTenantIdentityEvidence freshEvidence(
      UUID requestId, UUID tenantId, long sourceRowId, String sourceTenantKey) {
    return new RuntimeTenantIdentityEvidence(
        1, NAMESPACE, requestId, tenantId, sourceRowId, sourceTenantKey, "NEW_GAME_ROW");
  }

  private static String reservationKind(DSLContext dsl, long tenantId) {
    return dsl.fetchOne(
            "SELECT reservation_kind FROM game_session_tenant_scope_reservation "
                + "WHERE game_session_tenant_id = ?",
            tenantId)
        .get(0, String.class);
  }

  private static String digest(char character) {
    return "sha256:" + String.valueOf(character).repeat(64);
  }

  private static void migrateThroughV14(DriverManagerDataSource dataSource, String schema) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("14"))
        .load()
        .migrate();
  }

  private static void migrateLatest(DriverManagerDataSource dataSource, String schema) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
  }

  private static String validateExternalLoopbackPostgresUrl(String jdbcUrl) {
    final URI uri;
    try {
      if (!jdbcUrl.startsWith("jdbc:")) {
        throw new IllegalArgumentException("not a JDBC URL");
      }
      uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (RuntimeException malformed) {
      throw new IllegalStateException(
          POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres", malformed);
    }
    if (!"postgresql".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getPort() > 65_535
        || !"/postgres".equals(uri.getPath())
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalStateException(
          POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres");
    }
    return jdbcUrl;
  }

  private static UUID uuid(long value) {
    return new UUID(0x123e4567e89b12d3L, value);
  }

  private static final class TestSchema implements AutoCloseable {
    private final String schema;

    private TestSchema(String prefix) {
      schema = prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private String schema() {
      return schema;
    }

    private DriverManagerDataSource dataSource() {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.postgresql.Driver");
      String separator = postgresJdbcUrl.contains("?") ? "&" : "?";
      dataSource.setUrl(postgresJdbcUrl + separator + "currentSchema=" + schema);
      dataSource.setUsername(postgresUsername);
      dataSource.setPassword(postgresPassword);
      configureExternalConnectionProperties(dataSource);
      return dataSource;
    }

    @Override
    public void close() {
      DriverManagerDataSource adminDataSource = new DriverManagerDataSource();
      adminDataSource.setDriverClassName("org.postgresql.Driver");
      adminDataSource.setUrl(postgresJdbcUrl);
      adminDataSource.setUsername(postgresUsername);
      adminDataSource.setPassword(postgresPassword);
      configureExternalConnectionProperties(adminDataSource);
      try (Connection connection = adminDataSource.getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      } catch (SQLException failure) {
        throw new IllegalStateException("Failed to drop isolated test schema " + schema, failure);
      }
    }
  }

  private static void configureExternalConnectionProperties(DriverManagerDataSource dataSource) {
    if (!externalPostgres) {
      return;
    }
    Properties properties = new Properties();
    properties.setProperty("connectTimeout", "10");
    properties.setProperty("socketTimeout", "60");
    properties.setProperty("sslmode", "disable");
    properties.setProperty("gssEncMode", "disable");
    dataSource.setConnectionProperties(properties);
  }
}
