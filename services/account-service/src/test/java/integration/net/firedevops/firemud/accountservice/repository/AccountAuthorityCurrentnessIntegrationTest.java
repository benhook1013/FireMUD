package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import net.firedevops.firemud.accountservice.entity.Subscription;
import net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Physical V31/V32 currentness proof, isolated from Account lifecycle/source writers. */
class AccountAuthorityCurrentnessIntegrationTest {
  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void subscriptionAndRealmGrantMutationsAdvanceCurrentnessAndKeepRevocationTombstone() {
    String schema = "account_currentness_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    Long accountId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "currentness-" + UUID.randomUUID(),
                "currentness-" + UUID.randomUUID() + "@example.test",
                "test-hash"));

    long tenantId = 7_654_322L;
    jdbc.update(
        "INSERT INTO subscription (account_id, plan_id, status, tenant_id, entitlement_version) "
            + "VALUES (?, 'test-plan', 'active', ?, 1)",
        accountId,
        tenantId);
    SubscriptionRepository subscriptions = new SubscriptionRepository(dsl);
    Subscription subscription = subscriptions.findByTenantId(tenantId).getFirst();
    UUID initialTenantGeneration = subscription.getTenantAuthorityGeneration();
    assertThat(initialTenantGeneration).isNotNull();
    assertThat(subscription.getEntitlementVersion()).isEqualTo(1L);

    subscription.setStatus("past_due");
    subscriptions.save(subscription);
    Subscription repositoryUpdated = subscriptions.findByTenantId(tenantId).getFirst();
    assertThat(repositoryUpdated.getTenantAuthorityGeneration())
        .isNotEqualTo(initialTenantGeneration);
    assertThat(repositoryUpdated.getEntitlementVersion()).isEqualTo(2L);

    jdbc.update("UPDATE subscription SET status = 'active' WHERE tenant_id = ?", tenantId);
    Subscription directlyUpdated = subscriptions.findByTenantId(tenantId).getFirst();
    assertThat(directlyUpdated.getTenantAuthorityGeneration())
        .isNotEqualTo(repositoryUpdated.getTenantAuthorityGeneration());
    assertThat(directlyUpdated.getEntitlementVersion()).isEqualTo(3L);

    Account account = new Account();
    account.setId(accountId);
    AccountRealmAccessGrant grant = new AccountRealmAccessGrant();
    grant.setAccount(account);
    grant.setTenantId(tenantId);
    grant.setWorldSlug("world");
    grant.setRealmSlug("private");
    grant.setGrantVersion(1L);
    grant.setGrantedBy("test");
    grant.setGrantReason("test grant");
    grant.setCreatedAt(Instant.now());
    grant.setUpdatedAt(Instant.now());
    AccountRealmAccessGrantRepository grants = new AccountRealmAccessGrantRepository(dsl);
    grants.save(grant);
    AccountRealmAccessGrant original =
        grants
            .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, tenantId, "world", "private")
            .orElseThrow();
    UUID originalGrantGeneration = original.getGrantAuthorityGeneration();

    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.executeWithoutResult(
        status ->
            grants.revokeByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, tenantId, "world", "private"));
    AccountRealmAccessGrant revoked =
        grants
            .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, tenantId, "world", "private")
            .orElseThrow();
    assertThat(revoked.isGranted()).isFalse();
    assertThat(revoked.getGrantVersion()).isEqualTo(2L);
    assertThat(revoked.getGrantAuthorityGeneration()).isNotEqualTo(originalGrantGeneration);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_realm_access_grant WHERE id = ?",
                Long.class,
                revoked.getId()))
        .isEqualTo(1L);
    assertThat(
            grants.existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, tenantId, "world", "private"))
        .isFalse();
  }
}
