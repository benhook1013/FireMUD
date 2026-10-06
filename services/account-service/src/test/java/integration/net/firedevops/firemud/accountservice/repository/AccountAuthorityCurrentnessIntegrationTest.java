package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    assertThat(revoked.getId()).isEqualTo(original.getId());
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

  @Test
  void concurrentRealmGrantWritersCannotOverwriteAnObservedGeneration() throws Exception {
    String schema = "account_grant_cas_" + UUID.randomUUID().toString().replace("-", "");
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
                "grant-cas-" + UUID.randomUUID(),
                "grant-cas-" + UUID.randomUUID() + "@example.test",
                "test-hash"));
    Account account = new Account();
    account.setId(accountId);
    AccountRealmAccessGrantRepository grants = new AccountRealmAccessGrantRepository(dsl);
    AccountRealmAccessGrant initial = new AccountRealmAccessGrant();
    initial.setAccount(account);
    initial.setTenantId(7_654_329L);
    initial.setWorldSlug("world");
    initial.setRealmSlug("cas");
    initial.setGrantVersion(1L);
    initial.setGranted(true);
    initial.setGrantedBy("initial");
    initial.setGrantReason("initial grant");
    initial.setCreatedAt(Instant.now());
    initial.setUpdatedAt(Instant.now());
    grants.save(initial);
    UUID originalGeneration = initial.getGrantAuthorityGeneration();

    AccountRealmAccessGrant firstWriter =
        grants
            .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, 7_654_329L, "world", "cas")
            .orElseThrow();
    AccountRealmAccessGrant secondWriter =
        grants
            .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, 7_654_329L, "world", "cas")
            .orElseThrow();
    assertThat(firstWriter.getGrantAuthorityGeneration()).isEqualTo(originalGeneration);
    assertThat(secondWriter.getGrantAuthorityGeneration()).isEqualTo(originalGeneration);
    firstWriter.setGrantVersion(2L);
    firstWriter.setGranted(false);
    firstWriter.setGrantedBy("writer-a");
    firstWriter.setGrantReason("writer A update");
    secondWriter.setGrantVersion(2L);
    secondWriter.setGranted(false);
    secondWriter.setGrantedBy("writer-b");
    secondWriter.setGrantReason("writer B update");

    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> first =
          executor.submit(() -> updateGrant(transaction, grants, firstWriter, ready, release));
      Future<String> second =
          executor.submit(() -> updateGrant(transaction, grants, secondWriter, ready, release));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("committed", "stale");
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    AccountRealmAccessGrant persisted =
        grants
            .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, 7_654_329L, "world", "cas")
            .orElseThrow();
    assertThat(persisted.getGrantAuthorityGeneration()).isNotEqualTo(originalGeneration);
    assertThat(persisted.getGrantVersion()).isEqualTo(2L);
    assertThat(persisted.isGranted()).isFalse();
    assertThat(persisted.getGrantedBy()).isIn("writer-a", "writer-b");
    assertThat(persisted.getGrantReason())
        .isEqualTo(
            "writer-a".equals(persisted.getGrantedBy()) ? "writer A update" : "writer B update");
  }

  private static String updateGrant(
      TransactionTemplate transaction,
      AccountRealmAccessGrantRepository grants,
      AccountRealmAccessGrant grant,
      CountDownLatch ready,
      CountDownLatch release)
      throws InterruptedException {
    ready.countDown();
    if (!release.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting to race realm-grant writes");
    }
    try {
      transaction.executeWithoutResult(status -> grants.save(grant));
      return "committed";
    } catch (IllegalStateException failure) {
      if (("Failed to update account_realm_access_grant id=" + grant.getId())
          .equals(failure.getMessage())) {
        return "stale";
      }
      throw failure;
    }
  }
}
