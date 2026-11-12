package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Real forward migration with retained tenant provenance. No existing usage is guessed. */
class AccountTenantCapacityMigrationPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void existingTenantStaysUninitializedAndAPlainNewImportCannotInitializeCapacity() {
    String schema = "capacity_migration_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .target("1038")
          .load()
          .migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      var repository = new FreshTenantIdentityAssociationRepository(dsl, "prod");
      var existing = source();
      transaction.executeWithoutResult(status -> repository.importVerified(existing));
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      var retained = transaction.execute(status -> repository.read(existing.canonicalTenantId()));
      assertThat(retained).contains(existing);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT capacity_birth_transaction FROM account_fresh_tenant_identity_associations WHERE canonical_tenant_id = ?",
                          existing.canonicalTenantId()))
                  .get("capacity_birth_transaction"))
          .isNull();
      var later = source();
      transaction.executeWithoutResult(status -> repository.importVerified(later));
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT capacity_birth_transaction FROM account_fresh_tenant_identity_associations WHERE canonical_tenant_id = ?",
                          later.canonicalTenantId()))
                  .get("capacity_birth_transaction"))
          .isNotNull();
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_usage"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_reservations"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_decisions"))).isZero();
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private static FreshTenantCreationEvidence source() {
    UUID request = UUID.randomUUID(), operation = UUID.randomUUID(), tenant = UUID.randomUUID();
    String slug = "capacity-" + UUID.randomUUID().toString().substring(0, 8);
    String digest = GameTenantCreationDigest.requestDigest("prod", request, slug, "Capacity", null);
    long row = Math.abs((long) tenant.hashCode()) + 1;
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        request,
        operation,
        digest,
        tenant,
        row,
        slug,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "prod", request, operation, digest, tenant, row, slug, "NEW_GAME_ROW"));
  }
}
