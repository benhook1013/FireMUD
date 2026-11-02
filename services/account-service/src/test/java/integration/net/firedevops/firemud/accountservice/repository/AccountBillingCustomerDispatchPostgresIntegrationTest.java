package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.StripeBillingCustomerProvisioner;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository.DispatchClaimResult;
import net.firedevops.firemud.accountservice.service.AccountBillingCustomerProvisioningService;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real SQL owners and Stripe SDK over controlled transport. This does not qualify live Stripe,
 * authenticated caller authority, provider credentials, custody, or production wiring.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountBillingCustomerDispatchPostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void forwardUpgradePreservesUnresolvedIntentAndHistoryWithoutGrantingDispatch() {
    Context c = context(false);
    var owners =
        c.sql().fetch("SELECT row_to_json(c)::text AS value FROM billing_customer c").intoMaps();
    var history = c.sql().fetch("SELECT * FROM account_billing_customer_observations").intoMaps();
    migrate(c, "1027.1");
    assertThat(
            c.sql()
                .fetch("SELECT row_to_json(c)::text AS value FROM billing_customer c")
                .intoMaps())
        .isEqualTo(owners);
    assertThat(c.sql().fetch("SELECT * FROM account_billing_customer_observations").intoMaps())
        .isEqualTo(history);
    assertThat(c.sql().fetchCount(DSL.table("account_billing_customer_dispatch_claim"))).isZero();
    assertThat(c.repository().findByAccount(c.intent().accountUuid()).orElseThrow())
        .isEqualTo(c.intent());
  }

  @Test
  void competingClaimsHaveOneInitialCallerAndExactReplayNeverReissuesPermission() throws Exception {
    Context c = context(true);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return claim(c, UUID.randomUUID());
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return claim(c, UUID.randomUUID());
              });
      start.countDown();
      List<DispatchClaimResult> results =
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results)
          .extracting(DispatchClaimResult::inserted)
          .containsExactlyInAnyOrder(true, false);
      assertThat(results.getFirst().claim()).isEqualTo(results.getLast().claim());
      var original = results.getFirst().claim();
      var replay = claim(c, original.claimId());
      assertThat(replay.inserted()).isFalse();
      assertThat(replay.claim()).isEqualTo(original);
      var reread =
          c.tx()
              .execute(
                  s -> c.repository().findDispatchClaim(c.intent().accountUuid()).orElseThrow());
      assertThat(reread).isEqualTo(original);
      assertThat(c.repository().findByAccount(c.intent().accountUuid()).orElseThrow())
          .isEqualTo(c.intent());
    }
  }

  @Test
  void claimIdentityBindingTimeAndRetentionCannotBeRewrittenOrErased() {
    Context c = context(true);
    var retained = claim(c, UUID.randomUUID()).claim();
    var before = c.sql().fetch("SELECT * FROM account_billing_customer_dispatch_claim").intoMaps();
    for (String command :
        List.of(
            "UPDATE account_billing_customer_dispatch_claim SET claim_id = '"
                + UUID.randomUUID()
                + "'",
            "UPDATE account_billing_customer_dispatch_claim SET claimed_at = claimed_at + interval '1 second'",
            "UPDATE account_billing_customer_dispatch_claim SET provider_idempotency_id = '"
                + UUID.randomUUID()
                + "'",
            "DELETE FROM account_billing_customer_dispatch_claim",
            "TRUNCATE account_billing_customer_dispatch_claim")) {
      assertThatThrownBy(() -> c.sql().execute(command))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
    }
    assertThat(c.sql().fetch("SELECT * FROM account_billing_customer_dispatch_claim").intoMaps())
        .isEqualTo(before);
    assertThat(claim(c, retained.claimId()).inserted()).isFalse();
  }

  @Test
  void mismatchedDirectInsertAndConfirmedOwnerCannotAcquireFirstDispatch() {
    Context c = context(true);
    assertThatThrownBy(
            () ->
                c.sql()
                    .execute(
                        "INSERT INTO account_billing_customer_dispatch_claim "
                            + "(account_uuid, customer_provisioning_request_id, provider_idempotency_id, claim_id) VALUES (?, ?, ?, ?)",
                        c.intent().accountUuid(),
                        c.intent().customerProvisioningRequestId(),
                        UUID.randomUUID(),
                        UUID.randomUUID()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    c.tx()
        .executeWithoutResult(
            s ->
                c.repository()
                    .recordObservation(
                        c.intent().accountUuid(),
                        c.intent().customerProvisioningRequestId(),
                        1,
                        UUID.randomUUID(),
                        AccountBillingCustomerRecord.Observation.CONFIRMED,
                        "cus_preexisting",
                        "sha256:" + "a".repeat(64)));
    assertThatThrownBy(() -> claim(c, UUID.randomUUID()))
        .isInstanceOf(AccountBillingCustomerRepository.ConflictException.class);
    assertThat(c.sql().fetchCount(DSL.table("account_billing_customer_dispatch_claim"))).isZero();
  }

  @Test
  void rolledBackClaimDoesNotSurviveAndOnlyFreshCommittedInsertGetsPermission() {
    Context c = context(true);
    c.tx()
        .executeWithoutResult(
            s -> {
              assertThat(
                      c.repository()
                          .claimFirstProviderDispatch(c.intent(), UUID.randomUUID())
                          .inserted())
                  .isTrue();
              s.setRollbackOnly();
            });
    assertThat(c.sql().fetchCount(DSL.table("account_billing_customer_dispatch_claim"))).isZero();
    assertThat(claim(c, UUID.randomUUID()).inserted()).isTrue();
    assertThat(claim(c, UUID.randomUUID()).inserted()).isFalse();
  }

  @Test
  void composedOwnerCommitsClaimAndReadbackBeforeSdkThenIndependentlyReadsConfirmation() {
    Context c = context(true);
    var transactions = new ObservedTransactions(c.source());
    var transport = new ControlledCustomerTransport(c, transactions);
    var service = customerService(c, transactions, transport);

    var result =
        service.provision(c.intent().accountUuid(), c.intent().customerProvisioningRequestId());
    assertThat(result)
        .isInstanceOf(AccountBillingCustomerProvisioningService.ConfirmedCustomer.class);
    var confirmed = (AccountBillingCustomerProvisioningService.ConfirmedCustomer) result;
    assertThat(transport.creates).hasValue(1);
    assertThat(transport.searches).hasValue(1);
    assertThat(transport.retrieves).hasValue(1);
    assertThat(transport.commitsBeforeIo).containsExactly(2, 2, 2);
    // Claim write, independent claim readback, confirmation write, independent receipt readback.
    assertThat(transactions.commits).hasValue(4);
    assertThat(confirmed.customer().rowVersion()).isEqualTo(2L);
    assertThat(confirmed.customer().state())
        .isEqualTo(AccountBillingCustomerRecord.State.CONFIRMED);
    assertThat(confirmed.customer().evidenceDigest())
        .isEqualTo(confirmed.evidence().evidenceDigest());
    assertThat(confirmed.customer().providerCustomerId()).isEqualTo("cus_composed");
    assertCommittedReceipt(c, confirmed.customer());

    var rows =
        c.sql()
            .fetch("SELECT * FROM account_billing_customer_observations ORDER BY row_version")
            .intoMaps();
    var claims = claimRows(c);
    var replay =
        service.provision(c.intent().accountUuid(), c.intent().customerProvisioningRequestId());
    assertThat(replay)
        .isInstanceOf(AccountBillingCustomerProvisioningService.RetainedCustomer.class);
    assertThat(((AccountBillingCustomerProvisioningService.RetainedCustomer) replay).customer())
        .isEqualTo(confirmed.customer());
    assertThat(transport.commitsBeforeIo).hasSize(3);
    assertThat(claimRows(c)).isEqualTo(claims);
    assertThat(
            c.sql()
                .fetch("SELECT * FROM account_billing_customer_observations ORDER BY row_version")
                .intoMaps())
        .isEqualTo(rows);
  }

  @Test
  void lostFirstCreateAndLaterEmptyOrFailedSearchNeverGrantAnotherCreate() {
    Context c = context(true);
    var transactions = new ObservedTransactions(c.source());
    var transport = new ControlledCustomerTransport(c, transactions);
    transport.loseCreateReply = true;
    var service = customerService(c, transactions, transport);

    assertThat(
            service.provision(c.intent().accountUuid(), c.intent().customerProvisioningRequestId()))
        .isInstanceOf(AccountBillingCustomerProvisioningService.PendingCustomer.class);
    var originalClaim = claimRows(c);
    assertThat(transport.creates).hasValue(1);
    assertThat(transport.retrieves).hasValue(0);
    assertThat(transport.commitsBeforeIo).containsExactly(2, 2);
    assertThat(c.repository().findByAccount(c.intent().accountUuid()).orElseThrow())
        .isEqualTo(c.intent());

    for (SearchMode mode : List.of(SearchMode.EMPTY, SearchMode.FAILURE)) {
      transport.searchMode = mode;
      assertThat(
              service.provision(
                  c.intent().accountUuid(), c.intent().customerProvisioningRequestId()))
          .isInstanceOf(AccountBillingCustomerProvisioningService.PendingCustomer.class);
      assertThat(transport.creates).hasValue(1);
      assertThat(claimRows(c)).isEqualTo(originalClaim);
      assertThat(c.repository().findByAccount(c.intent().accountUuid()).orElseThrow())
          .isEqualTo(c.intent());
    }

    // The simulated provider effect becomes independently discoverable; reconcile that original.
    transport.searchMode = SearchMode.AUTOMATIC;
    var confirmed =
        (AccountBillingCustomerProvisioningService.ConfirmedCustomer)
            service.provision(c.intent().accountUuid(), c.intent().customerProvisioningRequestId());
    assertThat(transport.creates).hasValue(1);
    assertThat(transport.retrieves).hasValue(1);
    assertThat(claimRows(c)).isEqualTo(originalClaim);
    assertCommittedReceipt(c, confirmed.customer());
    assertThat(c.sql().fetchCount(DSL.table("account_billing_customer_observations"))).isEqualTo(2);
  }

  @Test
  void concurrentMatchingSdkResultsKeepOneConfirmationAndExactHistoricalRetry() throws Exception {
    Context c = context(true);
    var transactions = new ObservedTransactions(c.source());
    var transport = new ControlledCustomerTransport(c, transactions);
    transport.waitForBothRetrieves = true;
    var service = customerService(c, transactions, transport);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () ->
                  service.provision(
                      c.intent().accountUuid(), c.intent().customerProvisioningRequestId()));
      assertThat(transport.firstRetrieve.await(10, TimeUnit.SECONDS)).isTrue();
      var second =
          executor.submit(
              () ->
                  service.provision(
                      c.intent().accountUuid(), c.intent().customerProvisioningRequestId()));
      var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(
              results.stream()
                  .filter(
                      AccountBillingCustomerProvisioningService.ConfirmedCustomer.class::isInstance)
                  .count())
          .isEqualTo(1L);
      assertThat(
              results.stream()
                  .filter(
                      AccountBillingCustomerProvisioningService.RetainedCustomer.class::isInstance)
                  .count())
          .isEqualTo(1L);
      var confirmed =
          (AccountBillingCustomerProvisioningService.ConfirmedCustomer)
              results.stream()
                  .filter(
                      AccountBillingCustomerProvisioningService.ConfirmedCustomer.class::isInstance)
                  .findFirst()
                  .orElseThrow();
      var retained =
          (AccountBillingCustomerProvisioningService.RetainedCustomer)
              results.stream()
                  .filter(
                      AccountBillingCustomerProvisioningService.RetainedCustomer.class::isInstance)
                  .findFirst()
                  .orElseThrow();
      assertThat(retained.customer()).isEqualTo(confirmed.customer());
      assertThat(transport.creates).hasValue(1);
      assertThat(transport.retrieves).hasValue(2);
      assertCommittedReceipt(c, confirmed.customer());
      assertThat(c.sql().fetchCount(DSL.table("account_billing_customer_observations")))
          .isEqualTo(2);
      var claims = claimRows(c);
      int calls = transport.commitsBeforeIo.size();
      var replay =
          (AccountBillingCustomerProvisioningService.RetainedCustomer)
              service.provision(
                  c.intent().accountUuid(), c.intent().customerProvisioningRequestId());
      assertThat(replay.customer()).isEqualTo(confirmed.customer());
      assertThat(transport.commitsBeforeIo).hasSize(calls);
      assertThat(claimRows(c)).isEqualTo(claims);
    }
  }

  private static AccountBillingCustomerProvisioningService customerService(
      Context c, ObservedTransactions transactions, ControlledCustomerTransport transport) {
    var sdk = new com.stripe.StripeClient(transport);
    return new AccountBillingCustomerProvisioningService(
        c.repository(),
        new StripeBillingCustomerProvisioner(
            sdk, Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC), false),
        transactions);
  }

  private static void assertCommittedReceipt(Context c, AccountBillingCustomerRecord expected) {
    c.tx()
        .executeWithoutResult(
            status -> {
              assertThat(c.repository().findByAccountForUpdate(expected.accountUuid()))
                  .contains(expected);
              assertThat(c.repository().findObservationByRequestId(expected.changeRequestId()))
                  .contains(expected);
            });
    var currentRow =
        Objects.requireNonNull(
            c.sql()
                .fetchOne(
                    "SELECT state, row_version, change_request_id FROM billing_customer WHERE account_uuid = ?",
                    expected.accountUuid()),
            "Committed customer row must remain available");
    assertThat(currentRow.intoMap())
        .containsEntry("state", "CONFIRMED")
        .containsEntry("row_version", expected.rowVersion())
        .containsEntry("change_request_id", expected.changeRequestId());
  }

  private static List<Map<String, Object>> claimRows(Context c) {
    return c.sql()
        .fetch(
            "SELECT row_to_json(claim)::text AS row, xmin::text AS xmin FROM account_billing_customer_dispatch_claim claim")
        .intoMaps();
  }

  /**
   * Counts successful commits while retaining the actual datasource-backed transaction behavior.
   */
  private static final class ObservedTransactions extends DataSourceTransactionManager {
    private static final long serialVersionUID = 1L;
    private final AtomicInteger commits = new AtomicInteger();

    private ObservedTransactions(DriverManagerDataSource source) {
      super(source);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      super.doCommit(status);
      commits.incrementAndGet();
    }
  }

  private enum SearchMode {
    AUTOMATIC,
    EMPTY,
    FAILURE
  }

  /**
   * Only provider responses are controlled; SQL, guards, repositories, service and SDK are real.
   */
  private static final class ControlledCustomerTransport implements StripeResponseGetter {
    private final Context context;
    private final ObservedTransactions transactions;
    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger retrieves = new AtomicInteger();
    private final AtomicBoolean existsAtProvider = new AtomicBoolean();
    private final AtomicReference<List<Map<String, Object>>> originalClaim =
        new AtomicReference<>();
    private final List<Integer> commitsBeforeIo = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstRetrieve = new CountDownLatch(1);
    private final CountDownLatch bothRetrieves = new CountDownLatch(2);
    private volatile SearchMode searchMode = SearchMode.AUTOMATIC;
    private boolean loseCreateReply;
    private boolean waitForBothRetrieves;

    private ControlledCustomerTransport(Context context, ObservedTransactions transactions) {
      this.context = context;
      this.transactions = transactions;
    }

    @Override
    public <T extends StripeObject> T request(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode mode)
        throws StripeException {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
      assertThat(transactions.commits.get()).isGreaterThanOrEqualTo(2);
      commitsBeforeIo.add(transactions.commits.get());
      var committed = claimRows(context);
      assertThat(committed).hasSize(1);
      originalClaim.compareAndSet(null, committed);
      assertThat(committed).isEqualTo(originalClaim.get());
      // A separate autocommit connection sees the actual durable claim and acquires its lock.
      var claim =
          context
              .sql()
              .fetchOne(
                  "SELECT * FROM account_billing_customer_dispatch_claim WHERE account_uuid = ? FOR SHARE NOWAIT",
                  context.intent().accountUuid());
      assertThat(claim).isNotNull();
      assertThat(claim.get("customer_provisioning_request_id", UUID.class))
          .isEqualTo(context.intent().customerProvisioningRequestId());
      assertThat(claim.get("provider_idempotency_id", UUID.class))
          .isEqualTo(context.intent().providerIdempotencyId());

      String response;
      if (method == RequestMethod.GET && path.equals("/v1/customers/search")) {
        searches.incrementAndGet();
        assertThat(params.get("limit")).isEqualTo(2L);
        assertThat(params.get("query").toString())
            .contains(
                context.intent().accountUuid().toString(),
                context.intent().customerProvisioningRequestId().toString());
        if (searchMode == SearchMode.FAILURE)
          throw new ApiConnectionException("Controlled search failure");
        boolean found = searchMode == SearchMode.AUTOMATIC && existsAtProvider.get();
        response =
            "{\"object\":\"search_result\",\"has_more\":false,\"data\":["
                + (found ? customer() : "")
                + "]}";
      } else if (method == RequestMethod.POST && path.equals("/v1/customers")) {
        assertThat(creates.incrementAndGet()).isEqualTo(1);
        assertThat(options.getIdempotencyKey())
            .isEqualTo(context.intent().providerIdempotencyId().toString());
        assertThat(options.getMaxNetworkRetries()).isZero();
        existsAtProvider.set(true);
        if (loseCreateReply) throw new ApiConnectionException("Controlled lost create response");
        response = customer();
      } else if (method == RequestMethod.GET && path.equals("/v1/customers/cus_composed")) {
        retrieves.incrementAndGet();
        firstRetrieve.countDown();
        if (waitForBothRetrieves) {
          bothRetrieves.countDown();
          try {
            if (!bothRetrieves.await(10, TimeUnit.SECONDS))
              throw new AssertionError("Both actual SDK reads must reach the controlled barrier");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted at controlled SDK read barrier", interrupted);
          }
        }
        response = customer();
      } else {
        throw new AssertionError("Unexpected customer SDK operation: " + method + " " + path);
      }
      return com.stripe.net.ApiResource.GSON.fromJson(response, type);
    }

    private String customer() {
      return "{\"id\":\"cus_composed\",\"object\":\"customer\",\"livemode\":false,\"metadata\":{"
          + "\"firemud_metadata_schema_version\":\"1\",\"firemud_operation_type\":\"customer_provisioning\","
          + "\"firemud_operation_id\":\""
          + context.intent().customerProvisioningRequestId()
          + "\",\"firemud_account_id\":\""
          + context.intent().accountUuid()
          + "\"}}";
    }

    @Override
    public InputStream requestStream(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode mode) {
      throw new AssertionError("No customer streaming operation is permitted");
    }
  }

  private static DispatchClaimResult claim(Context c, UUID claim) {
    return Objects.requireNonNull(
        c.tx().execute(s -> c.repository().claimFirstProviderDispatch(c.intent(), claim)));
  }

  private static Context context(boolean forward) {
    String schema = "customer_dispatch_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    DSLContext sql = DSL.using(source, SQLDialect.POSTGRES);
    var repository =
        new AccountBillingCustomerRepository(
            DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.setTimeout(10);
    Context empty = new Context(source, schema, sql, repository, tx, null);
    migrate(empty, "1027");
    UUID account =
        Objects.requireNonNull(
                sql.fetchOne(
                    "INSERT INTO accounts(username,email,password_hash) VALUES ('dispatch','dispatch@example.test','hash') RETURNING account_uuid"))
            .get(0, UUID.class);
    var intent =
        Objects.requireNonNull(
            tx.execute(
                s ->
                    repository
                        .retainIntent(account, UUID.randomUUID(), UUID.randomUUID())
                        .customer()));
    Context result = new Context(source, schema, sql, repository, tx, intent);
    if (forward) {
      migrate(result, "1027.1");
    }
    return result;
  }

  private static void migrate(Context c, String target) {
    Flyway.configure()
        .dataSource(c.source())
        .schemas(c.schema())
        .defaultSchema(c.schema())
        .placeholders(Map.of("serviceSchema", c.schema()))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .target(target)
        .load()
        .migrate();
  }

  private record Context(
      DriverManagerDataSource source,
      String schema,
      DSLContext sql,
      AccountBillingCustomerRepository repository,
      TransactionTemplate tx,
      AccountBillingCustomerRecord intent) {}
}
