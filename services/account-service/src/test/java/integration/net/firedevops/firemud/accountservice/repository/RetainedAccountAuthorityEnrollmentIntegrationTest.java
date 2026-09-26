package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RetainedAccountAuthorityEnrollmentIntegrationTest {
  private static final String MIGRATION_LOCATION = "classpath:db/migration";
  private static final String ACCOUNT_SCOPE = "ACCOUNT";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void enrollsRetainedExactAccountUuidPairWithoutInventingTenantOrMembershipScopes() {
    TestDatabase database = createDatabaseAtVersion28();
    long retainedNumericId =
        insertRetainedAccount(database.dsl(), "retained-account-enrollment", 73L);
    migrateToVersion33(database);

    UUID accountUuid = accountUuid(database.dsl(), retainedNumericId);
    migrateToLatest(database);

    assertThat(
            fetchString(
                database.dsl(),
                "SELECT account_uuid_provenance FROM accounts WHERE id = ?",
                retainedNumericId))
        .isEqualTo("ACCOUNT_V29_MIGRATION");
    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT account_uuid_source_numeric_id FROM accounts WHERE id = ?",
                retainedNumericId))
        .isEqualTo(retainedNumericId);
    assertThat(accountGenerationCount(database.dsl(), accountUuid)).isEqualTo(1L);
    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT generation FROM account_authority_generations "
                    + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                accountUuid))
        .isEqualTo(1L);
    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT source_version FROM account_authority_generations "
                    + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                accountUuid))
        .isEqualTo(1L);
    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT issuance_fence FROM account_authority_issuance_fences WHERE account_uuid = ?",
                accountUuid))
        .isEqualTo(1L);
    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT source_version FROM account_authority_issuance_fences WHERE account_uuid = ?",
                accountUuid))
        .isEqualTo(1L);
    assertThat(tenantOrMembershipGenerationCount(database.dsl())).isZero();
    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT count(*) FROM account_tenant_membership WHERE account_id = ?",
                retainedNumericId))
        .isZero();
  }

  @Test
  void preservesBothExistingAdvancedAccountAuthorityRows() {
    TestDatabase database = createDatabaseAtVersion28();
    long retainedNumericId =
        insertRetainedAccount(database.dsl(), "advanced-retained-account", 91L);
    migrateToVersion33(database);
    UUID accountUuid = accountUuid(database.dsl(), retainedNumericId);
    database
        .dsl()
        .execute(
            "INSERT INTO account_authority_generations "
                + "(scope_kind, account_uuid, generation, source_version) "
                + "VALUES ('ACCOUNT', ?, 7, 13)",
            accountUuid);
    database
        .dsl()
        .execute(
            "INSERT INTO account_authority_issuance_fences "
                + "(account_uuid, issuance_fence, source_version) VALUES (?, 17, 29)",
            accountUuid);

    migrateToLatest(database);

    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT generation FROM account_authority_generations "
                    + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                accountUuid))
        .isEqualTo(7L);
    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT source_version FROM account_authority_generations "
                    + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                accountUuid))
        .isEqualTo(13L);
    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT issuance_fence FROM account_authority_issuance_fences WHERE account_uuid = ?",
                accountUuid))
        .isEqualTo(17L);
    assertThat(
            fetchLong(
                database.dsl(),
                "SELECT source_version FROM account_authority_issuance_fences WHERE account_uuid = ?",
                accountUuid))
        .isEqualTo(29L);
  }

  @Test
  void failsClosedOnEitherHalfPairWithoutPartiallyEnrollingOtherRetainedAccounts() {
    TestDatabase database = createDatabaseAtVersion28();
    long generationOnlyNumericId =
        insertRetainedAccount(database.dsl(), "generation-only-account", 101L);
    long fenceOnlyNumericId = insertRetainedAccount(database.dsl(), "fence-only-account", 102L);
    long unenrolledNumericId =
        insertRetainedAccount(database.dsl(), "unenrolled-retained-account", 103L);
    migrateToVersion33(database);

    UUID generationOnlyUuid = accountUuid(database.dsl(), generationOnlyNumericId);
    UUID fenceOnlyUuid = accountUuid(database.dsl(), fenceOnlyNumericId);
    UUID unenrolledUuid = accountUuid(database.dsl(), unenrolledNumericId);
    database
        .dsl()
        .execute(
            "INSERT INTO account_authority_generations "
                + "(scope_kind, account_uuid, generation, source_version) "
                + "VALUES ('ACCOUNT', ?, 3, 5)",
            generationOnlyUuid);
    database
        .dsl()
        .execute(
            "INSERT INTO account_authority_issuance_fences "
                + "(account_uuid, issuance_fence, source_version) VALUES (?, 4, 8)",
            fenceOnlyUuid);

    assertThatThrownBy(() -> migrateToLatest(database))
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("has incomplete authority generation and issuance-fence state");

    assertThat(accountGenerationCount(database.dsl(), generationOnlyUuid)).isEqualTo(1L);
    assertThat(issuanceFenceCount(database.dsl(), generationOnlyUuid)).isZero();
    assertThat(accountGenerationCount(database.dsl(), fenceOnlyUuid)).isZero();
    assertThat(issuanceFenceCount(database.dsl(), fenceOnlyUuid)).isEqualTo(1L);
    assertThat(accountGenerationCount(database.dsl(), unenrolledUuid)).isZero();
    assertThat(issuanceFenceCount(database.dsl(), unenrolledUuid)).isZero();
  }

  private TestDatabase createDatabaseAtVersion28() {
    String schema = "retained_account_enrollment_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    TestDatabase database =
        new TestDatabase(schema, dataSource, DSL.using(dataSource, SQLDialect.POSTGRES));
    flyway(database, "28").migrate();
    return database;
  }

  private void migrateToVersion33(TestDatabase database) {
    flyway(database, "33").migrate();
  }

  private void migrateToLatest(TestDatabase database) {
    flyway(database, null).migrate();
  }

  private Flyway flyway(TestDatabase database, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(database.dataSource())
            .schemas(database.schema())
            .defaultSchema(database.schema())
            .placeholders(Map.of("serviceSchema", database.schema()))
            .locations(MIGRATION_LOCATION);
    if (target != null) {
      configuration.target(target);
    }
    return configuration.load();
  }

  private long insertRetainedAccount(DSLContext dsl, String username, long legacyTenantId) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                    + "VALUES (?, ?, ?, ?) RETURNING id",
                username,
                username + "@example.test",
                "hash",
                legacyTenantId)
            .fetchOne(0, Long.class));
  }

  private UUID accountUuid(DSLContext dsl, long numericId) {
    return Objects.requireNonNull(
        dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", numericId)
            .fetchOne(0, UUID.class));
  }

  private long accountGenerationCount(DSLContext dsl, UUID accountUuid) {
    return fetchLong(
        dsl,
        "SELECT count(*) FROM account_authority_generations "
            + "WHERE scope_kind = ? AND account_uuid = ?",
        ACCOUNT_SCOPE,
        accountUuid);
  }

  private long issuanceFenceCount(DSLContext dsl, UUID accountUuid) {
    return fetchLong(
        dsl,
        "SELECT count(*) FROM account_authority_issuance_fences WHERE account_uuid = ?",
        accountUuid);
  }

  private long tenantOrMembershipGenerationCount(DSLContext dsl) {
    return fetchLong(
        dsl,
        "SELECT count(*) FROM account_authority_generations "
            + "WHERE scope_kind IN ('TENANT', 'MEMBERSHIP')");
  }

  private long fetchLong(DSLContext dsl, String query, Object... bindings) {
    return Objects.requireNonNull(dsl.resultQuery(query, bindings).fetchOne(0, Long.class));
  }

  private String fetchString(DSLContext dsl, String query, Object... bindings) {
    return dsl.resultQuery(query, bindings).fetchOne(0, String.class);
  }

  private record TestDatabase(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}
}
