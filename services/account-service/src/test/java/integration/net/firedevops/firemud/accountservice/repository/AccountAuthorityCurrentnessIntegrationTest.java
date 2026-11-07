package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository;
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

/** Canonical hosting lifecycle/source currentness and separate realm-grant tombstone/CAS proof. */
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
    var fixture = CanonicalHostingSubscriptionTestFixture.create(dataSource);
    var claim =
        fixture
            .service()
            .claimProvisioning(fixture.creation().requestId(), 1, UUID.randomUUID())
            .claim();
    UUID initialRequest = UUID.randomUUID();
    var initialCommit =
        fixture
            .service()
            .reconcile(
                initialRequest,
                fixture.creation().requestId(),
                claim.subscriptionRowVersion(),
                fixture.providerId(),
                claim.attemptId());
    assertThat(initialCommit.outcome()).isEqualTo(Outcome.COMMITTED);
    var initial = fixture.service().readCurrent(fixture.tenantId());
    assertThat(initial.entitlementVersion()).isOne();
    assertCanonicalCheckpoint(fixture, initial);
    int initialReads = fixture.transport().reads();
    assertThat(
            fixture
                .service()
                .reconcile(
                    initialRequest,
                    fixture.creation().requestId(),
                    claim.subscriptionRowVersion(),
                    fixture.providerId(),
                    claim.attemptId()))
        .isEqualTo(initialCommit);
    assertThat(fixture.transport().reads()).isEqualTo(initialReads);
    var initialRetry = fixture.service().readCurrent(fixture.tenantId());
    assertThat(initialRetry.receiptRequestId()).isEqualTo(initial.receiptRequestId());
    assertThat(initialRetry.tenantAuthorityEvent().payload())
        .isEqualTo(initial.tenantAuthorityEvent().payload());

    fixture.clock().advance(Duration.ofSeconds(1));
    fixture.providerStatus("canceled");
    UUID changedRequest = UUID.randomUUID();
    var changedCommit =
        fixture
            .service()
            .reconcile(
                changedRequest,
                fixture.creation().requestId(),
                initial.current().rowVersion(),
                fixture.providerId(),
                null);
    assertThat(changedCommit.outcome()).isEqualTo(Outcome.COMMITTED);
    var changed = fixture.service().readCurrent(fixture.tenantId());
    assertThat(changed.rawProviderStatus()).isEqualTo("canceled");
    assertThat(changed.entitlementVersion()).isGreaterThan(initial.entitlementVersion());
    assertThat(changed.tenantBillingSequence()).isGreaterThan(initial.tenantBillingSequence());
    assertThat(changed.tenantAuthorityEvent().tenantAuthorityGeneration())
        .isGreaterThan(initial.tenantAuthorityEvent().tenantAuthorityGeneration());
    assertThat(changed.tenantAuthorityEvent().outboxSequence())
        .isGreaterThan(initial.tenantAuthorityEvent().outboxSequence());
    assertThat(changed.tenantAuthorityEvent().eventId())
        .isNotEqualTo(initial.tenantAuthorityEvent().eventId());
    assertCanonicalCheckpoint(fixture, changed);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE subscription SET status = 'active', row_version = row_version + 1 WHERE subscription_id = ?",
                    fixture.creation().subscriptionId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    int changedReads = fixture.transport().reads();
    assertThat(
            fixture
                .service()
                .reconcile(
                    changedRequest,
                    fixture.creation().requestId(),
                    initial.current().rowVersion(),
                    fixture.providerId(),
                    null))
        .isEqualTo(changedCommit);
    assertThat(fixture.transport().reads()).isEqualTo(changedReads);
    var changedRetry = fixture.service().readCurrent(fixture.tenantId());
    assertThat(changedRetry.current()).isEqualTo(changed.current());
    assertThat(changedRetry.receiptRequestId()).isEqualTo(changed.receiptRequestId());
    assertThat(changedRetry.tenantAuthorityEvent().payload())
        .isEqualTo(changed.tenantAuthorityEvent().payload());
    assertCanonicalCheckpoint(fixture, changedRetry);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload FROM account_authority_outbox_events WHERE outbox_stream_key = ? AND outbox_sequence = ?",
                byte[].class,
                initial.tenantAuthorityEvent().outboxStreamKey(),
                initial.tenantAuthorityEvent().outboxSequence()))
        .isEqualTo(initial.tenantAuthorityEvent().payload());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                Long.class,
                changed.tenantAuthorityEvent().outboxStreamKey()))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ?",
                Long.class,
                fixture.tenantId()))
        .isEqualTo(2L);

    // Realm grants retain their separate authority and numeric legacy scope in this existing proof.
    long accountId = fixture.accountId();
    long tenantId = 7_654_322L;

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

  private static void assertCanonicalCheckpoint(
      CanonicalHostingSubscriptionTestFixture.Fixture fixture,
      AccountHostingSubscriptionCurrentReceipt receipt) {
    var event = receipt.tenantAuthorityEvent();
    assertThat(event.tenantId()).isEqualTo(fixture.tenantId());
    assertThat(event.tenantBillingSequence()).isEqualTo(receipt.tenantBillingSequence());
    assertThat(event.tenantBillingEventId()).isEqualTo(receipt.billingEventId());
    assertThat(event.tenantBillingEventDigest()).isEqualTo(receipt.billingEventDigest());
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              var currentGeneration =
                  new AccountAuthorityGenerationRepository(fixture.dsl())
                      .read(AuthorityScope.tenant(fixture.tenantId()));
              assertThat(currentGeneration.generation())
                  .isEqualTo(event.tenantAuthorityGeneration());
              assertThat(currentGeneration.sourceVersion())
                  .isEqualTo(event.tenantAuthoritySourceVersion());
              var checkpoint =
                  new AccountAuthorityOutboxRepository(fixture.dsl())
                      .readCheckpoint(event.outboxStreamKey())
                      .orElseThrow();
              assertThat(checkpoint.outboxSequence()).isEqualTo(event.outboxSequence());
              assertThat(checkpoint.sourceEventId()).isEqualTo(event.eventId().toString());
              assertThat(checkpoint.sourceEventDigest()).isEqualTo(event.eventDigest());
              assertThat(
                      fixture
                          .jdbc()
                          .queryForObject(
                              "SELECT event_digest FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ? AND tenant_billing_sequence = ? AND event_id = ?",
                              String.class,
                              fixture.tenantId(),
                              receipt.tenantBillingSequence(),
                              receipt.billingEventId()))
                  .isEqualTo(receipt.billingEventDigest());
              assertThat(
                      fixture
                          .jdbc()
                          .queryForMap(
                              "SELECT current_generation, tenant_billing_sequence, last_outbox_sequence, "
                                  + "last_event_id, last_event_digest FROM account_tenant_authority_source_records WHERE tenant_uuid = ?",
                              fixture.tenantId()))
                  .containsEntry("current_generation", event.tenantAuthorityGeneration())
                  .containsEntry("tenant_billing_sequence", receipt.tenantBillingSequence())
                  .containsEntry("last_outbox_sequence", checkpoint.outboxSequence())
                  .containsEntry("last_event_id", event.eventId().toString())
                  .containsEntry("last_event_digest", event.eventDigest());
            });
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
