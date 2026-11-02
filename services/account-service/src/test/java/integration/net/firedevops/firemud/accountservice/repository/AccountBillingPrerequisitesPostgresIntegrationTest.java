package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.Observation;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository.IntentDisposition;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.DisplayMetadata;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.State;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository.Attachment;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository.AvailabilityObservation;
import net.firedevops.firemud.test.TestContainerImages;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL storage proof only: no provider, authority, reservation or detach simulation.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountBillingPrerequisitesPostgresIntegrationTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String OTHER_DIGEST = "sha256:" + "b".repeat(64);
  private static final DisplayMetadata DISPLAY = new DisplayMetadata("visa", "4242", 12, 2030);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void forwardUpgradePreservesExistingAccountsAndDoesNotCreateBillingOwners() {
    Context c = predecessor();
    var before =
        c.setup()
            .fetch(
                "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM accounts a ORDER BY id")
            .intoMaps();
    var history =
        c.setup().fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();

    configuration(c).target("1027").load().migrate();

    assertThat(
            c.setup()
                .fetch(
                    "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM accounts a ORDER BY id")
                .intoMaps())
        .isEqualTo(before);
    var afterHistory =
        c.setup().fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
    assertThat(afterHistory).hasSize(history.size() + 1);
    assertThat(afterHistory.subList(0, history.size())).containsExactlyElementsOf(history);
    assertThat(count(c, "billing_customer")).isZero();
    assertThat(count(c, "payment_instrument")).isZero();
    assertThat(count(c, "account_billing_customer_observations")).isZero();
    assertThat(count(c, "account_payment_instrument_observations")).isZero();
    configuration(c).target("1027").load().validate();
  }

  @Test
  void concurrentCandidatesShareOneDurableCustomerIntentAndExactRetryUsesItsRetainedKey()
      throws Exception {
    Context c = context();
    var results =
        race(
            () ->
                c.tx()
                    .execute(
                        s ->
                            c.customers()
                                .retainIntent(c.account(), UUID.randomUUID(), UUID.randomUUID())),
            () ->
                c.tx()
                    .execute(
                        s ->
                            c.customers()
                                .retainIntent(c.account(), UUID.randomUUID(), UUID.randomUUID())));
    assertThat(results)
        .extracting(AccountBillingCustomerRepository.IntentResult::disposition)
        .containsExactlyInAnyOrder(IntentDisposition.CREATED, IntentDisposition.REUSED);
    var retained = results.getFirst().customer();
    assertThat(results.getLast().customer()).isEqualTo(retained);
    assertThat(retained.state()).isEqualTo(AccountBillingCustomerRecord.State.UNCONFIRMED);
    assertThat(retained.providerCustomerId()).isNull();
    assertThat(retained.customerProvisioningRequestId())
        .isNotEqualTo(retained.providerIdempotencyId());
    var retry =
        c.tx()
            .execute(
                s ->
                    c.customers()
                        .retainIntent(
                            c.account(),
                            retained.customerProvisioningRequestId(),
                            retained.providerIdempotencyId()));
    assertThat(Objects.requireNonNull(retry).disposition()).isEqualTo(IntentDisposition.REPLAYED);
    assertThat(retry.customer()).isEqualTo(retained);
    assertThat(count(c, "billing_customer")).isOne();
    assertThat(c.customers().readHistory(c.account())).containsExactly(retained);

    assertThatThrownBy(
            () ->
                c.tx()
                    .execute(
                        s ->
                            c.customers()
                                .retainIntent(
                                    c.otherAccount(),
                                    retained.customerProvisioningRequestId(),
                                    retained.providerIdempotencyId())))
        .isInstanceOf(AccountBillingCustomerRepository.ConflictException.class);
    assertThatThrownBy(
            () ->
                c.tx()
                    .execute(
                        s ->
                            c.customers()
                                .retainIntent(
                                    c.account(),
                                    retained.customerProvisioningRequestId(),
                                    UUID.randomUUID())))
        .isInstanceOf(AccountBillingCustomerRepository.ConflictException.class);
  }

  @Test
  void ambiguousAndAbsentEvidenceStayNonterminalAndExactObservationRetriesNeverRebindCustomer() {
    Context c = context();
    var intent = intent(c, c.account());
    UUID ambiguousRequest = UUID.randomUUID();
    var ambiguous = observe(c, intent, 1, ambiguousRequest, Observation.AMBIGUOUS, null, DIGEST);
    var absent =
        observe(c, intent, 2, UUID.randomUUID(), Observation.PROVIDER_ABSENT, null, OTHER_DIGEST);
    assertThat(absent.state()).isEqualTo(AccountBillingCustomerRecord.State.RECONCILING);
    assertThat(absent.providerCustomerId()).isNull();
    var reused =
        c.tx()
            .execute(
                s -> c.customers().retainIntent(c.account(), UUID.randomUUID(), UUID.randomUUID()));
    assertThat(Objects.requireNonNull(reused).disposition()).isEqualTo(IntentDisposition.REUSED);
    assertThat(reused.customer().customerProvisioningRequestId())
        .isEqualTo(intent.customerProvisioningRequestId());
    assertThat(reused.customer().providerIdempotencyId()).isEqualTo(intent.providerIdempotencyId());

    var confirmed =
        observe(c, intent, 3, UUID.randomUUID(), Observation.CONFIRMED, "cus_primary", DIGEST);
    assertThat(confirmed.state()).isEqualTo(AccountBillingCustomerRecord.State.CONFIRMED);
    assertThat(observe(c, intent, 1, ambiguousRequest, Observation.AMBIGUOUS, null, DIGEST))
        .isEqualTo(ambiguous);
    assertThat(c.customers().findByAccount(c.account())).contains(confirmed);
    assertThat(c.customers().readHistory(c.account()))
        .containsExactly(intent, ambiguous, absent, confirmed);
    assertThatThrownBy(
            () ->
                observe(c, intent, 1, ambiguousRequest, Observation.PROVIDER_ABSENT, null, DIGEST))
        .isInstanceOf(AccountBillingCustomerRepository.ConflictException.class);
    assertThatThrownBy(
            () -> observe(c, intent, 2, UUID.randomUUID(), Observation.AMBIGUOUS, null, DIGEST))
        .isInstanceOf(AccountBillingCustomerRepository.StaleVersionException.class);
    assertThatThrownBy(
            () ->
                observe(
                    c, intent, 4, UUID.randomUUID(), Observation.CONFIRMED, "cus_changed", DIGEST))
        .isInstanceOf(AccountBillingCustomerRepository.ConflictException.class);
    var other = intent(c, c.otherAccount());
    assertThatThrownBy(
            () ->
                observe(
                    c, other, 1, UUID.randomUUID(), Observation.CONFIRMED, "cus_primary", DIGEST))
        .isInstanceOf(AccountBillingCustomerRepository.ConflictException.class);
    assertThat(c.customers().findByAccount(c.otherAccount())).contains(other);
    assertThat(c.customers().readHistory(c.otherAccount())).containsExactly(other);
  }

  @Test
  void attachmentRequiresExactConfirmedAccountCustomerAndImmutableProviderBinding() {
    Context c = context();
    var pending = intent(c, c.account());
    var request = attachment(pending, "cus_primary", "pm_primary");
    assertThatThrownBy(() -> c.tx().execute(s -> c.instruments().retainAttachment(request)))
        .isInstanceOf(AccountPaymentInstrumentRepository.ConflictException.class);
    var confirmed =
        observe(c, pending, 1, UUID.randomUUID(), Observation.CONFIRMED, "cus_primary", DIGEST);
    var other = confirm(c, c.otherAccount(), "cus_other");
    assertThatThrownBy(
            () ->
                c.tx()
                    .execute(
                        s ->
                            c.instruments()
                                .retainAttachment(
                                    attachment(confirmed, "cus_other", "pm_wrongCustomer"))))
        .isInstanceOf(AccountPaymentInstrumentRepository.ConflictException.class);
    Attachment wrongOperation =
        new Attachment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            c.account(),
            other.customerProvisioningRequestId(),
            "cus_primary",
            "pm_wrongOperation",
            State.ATTACHABLE,
            DISPLAY,
            DIGEST);
    assertThatThrownBy(() -> c.tx().execute(s -> c.instruments().retainAttachment(wrongOperation)))
        .isInstanceOf(AccountPaymentInstrumentRepository.ConflictException.class);
    var instrument =
        Objects.requireNonNull(c.tx().execute(s -> c.instruments().retainAttachment(request)));
    assertThat(instrument.accountUuid()).isEqualTo(c.account());
    assertThat(instrument.customerProvisioningRequestId())
        .isEqualTo(confirmed.customerProvisioningRequestId());
    assertThat(
            c.tx()
                .<AccountPaymentInstrumentRecord>execute(
                    s -> c.instruments().retainAttachment(request)))
        .isEqualTo(instrument);
    assertThat(c.instruments().findByOwner(c.otherAccount(), instrument.instrumentUuid()))
        .isEmpty();
    assertThatThrownBy(
            () ->
                c.tx()
                    .execute(
                        s ->
                            c.instruments()
                                .retainAttachment(attachment(other, "cus_other", "pm_primary"))))
        .isInstanceOf(AccountPaymentInstrumentRepository.ConflictException.class);
    Attachment changedInput =
        new Attachment(
            request.requestId(),
            request.instrumentUuid(),
            request.accountUuid(),
            request.customerProvisioningRequestId(),
            request.providerCustomerId(),
            request.providerPaymentMethodId(),
            State.UNAVAILABLE,
            DISPLAY,
            OTHER_DIGEST);
    assertThatThrownBy(() -> c.tx().execute(s -> c.instruments().retainAttachment(changedInput)))
        .isInstanceOf(AccountPaymentInstrumentRepository.ConflictException.class);
    assertThat(count(c, "payment_instrument")).isOne();
    assertThat(c.instruments().readHistory(c.account(), instrument.instrumentUuid()))
        .containsExactly(instrument);
  }

  @Test
  void concurrentInstrumentCasHasOneWinnerAndRetainsReplayReceiptAfterLaterObservations()
      throws Exception {
    Context c = context();
    var customer = confirm(c, c.account(), "cus_primary");
    Attachment attachment = attachment(customer, "cus_primary", "pm_primary");
    var instrument =
        Objects.requireNonNull(c.tx().execute(s -> c.instruments().retainAttachment(attachment)));
    var left = availability(instrument, 1, State.UNAVAILABLE, DIGEST);
    var right = availability(instrument, 1, State.ATTACHABLE, OTHER_DIGEST);
    var results = race(() -> attemptCas(c, left), () -> attemptCas(c, right));
    assertThat(results).containsExactlyInAnyOrder("APPLIED", "STALE");
    var winner =
        c.instruments().findByOwner(c.account(), instrument.instrumentUuid()).orElseThrow();
    AvailabilityObservation winningRequest =
        winner.changeRequestId().equals(left.requestId()) ? left : right;
    var later = availability(instrument, 2, State.ATTACHABLE, DIGEST);
    var current = c.tx().execute(s -> c.instruments().recordAvailability(later));
    assertThat(
            c.tx()
                .<AccountPaymentInstrumentRecord>execute(
                    s -> c.instruments().recordAvailability(winningRequest)))
        .isEqualTo(winner);
    assertThat(
            c.tx()
                .<AccountPaymentInstrumentRecord>execute(
                    s -> c.instruments().retainAttachment(attachment)))
        .isEqualTo(instrument);
    assertThat(c.instruments().findByOwner(c.account(), instrument.instrumentUuid()))
        .contains(current);
    assertThat(c.instruments().readHistory(c.account(), instrument.instrumentUuid()))
        .containsExactly(instrument, winner, current);
    var changedRetry =
        new AvailabilityObservation(
            winningRequest.requestId(),
            instrument.instrumentUuid(),
            c.account(),
            1,
            winningRequest.state(),
            DISPLAY,
            "sha256:" + "c".repeat(64));
    assertThatThrownBy(() -> c.tx().execute(s -> c.instruments().recordAvailability(changedRetry)))
        .isInstanceOf(AccountPaymentInstrumentRepository.ConflictException.class);
  }

  @Test
  void rollbackRemovesOwnerChangesAndTheirAutomaticAuditRows() {
    Context c = context();
    c.tx()
        .executeWithoutResult(
            s -> {
              c.customers().retainIntent(c.account(), UUID.randomUUID(), UUID.randomUUID());
              s.setRollbackOnly();
            });
    assertThat(c.customers().findByAccount(c.account())).isEmpty();
    assertThat(c.customers().readHistory(c.account())).isEmpty();
    var intent = intent(c, c.account());
    c.tx()
        .executeWithoutResult(
            s -> {
              c.customers()
                  .recordObservation(
                      c.account(),
                      intent.customerProvisioningRequestId(),
                      1,
                      UUID.randomUUID(),
                      Observation.CONFIRMED,
                      "cus_rolledBack",
                      DIGEST);
              s.setRollbackOnly();
            });
    assertThat(c.customers().findByAccount(c.account())).contains(intent);
    assertThat(c.customers().readHistory(c.account())).containsExactly(intent);
    var confirmed =
        observe(c, intent, 1, UUID.randomUUID(), Observation.CONFIRMED, "cus_primary", DIGEST);
    var request = attachment(confirmed, "cus_primary", "pm_primary");
    c.tx()
        .executeWithoutResult(
            s -> {
              c.instruments().retainAttachment(request);
              s.setRollbackOnly();
            });
    assertThat(count(c, "payment_instrument")).isZero();
    assertThat(count(c, "account_payment_instrument_observations")).isZero();
    var instrument =
        Objects.requireNonNull(c.tx().execute(s -> c.instruments().retainAttachment(request)));
    c.tx()
        .executeWithoutResult(
            s -> {
              c.instruments()
                  .recordAvailability(availability(instrument, 1, State.UNAVAILABLE, OTHER_DIGEST));
              s.setRollbackOnly();
            });
    assertThat(c.instruments().findByOwner(c.account(), instrument.instrumentUuid()))
        .contains(instrument);
    assertThat(c.instruments().readHistory(c.account(), instrument.instrumentUuid()))
        .containsExactly(instrument);
  }

  @Test
  void databaseRejectsAbsentOwnersCrossCustomerLinksRebindingUnversionedMutationAndErasure() {
    Context c = context();
    rejectSql(
        "23503",
        () ->
            c.setup()
                .execute(
                    "INSERT INTO billing_customer(account_uuid,customer_provisioning_request_id,provider_idempotency_id,row_version,state,change_request_id,observation) "
                        + "VALUES (?, ?, ?, 1, 'UNCONFIRMED', ?, 'NONE')",
                    UUID.randomUUID(),
                    new UUID(1L, 1L),
                    UUID.randomUUID(),
                    new UUID(1L, 1L)));
    var customer = confirm(c, c.account(), "cus_primary");
    confirm(c, c.otherAccount(), "cus_other");
    var request = attachment(customer, "cus_primary", "pm_primary");
    var instrument =
        Objects.requireNonNull(c.tx().execute(s -> c.instruments().retainAttachment(request)));
    UUID forgedAttachment = UUID.randomUUID();
    rejectSql(
        "23503",
        () ->
            c.setup()
                .execute(
                    "INSERT INTO payment_instrument(instrument_uuid,account_uuid,customer_provisioning_request_id,provider_customer_id,provider_payment_method_id,attachment_request_id,row_version,state,change_request_id,evidence_digest) "
                        + "VALUES (?, ?, ?, 'cus_other', 'pm_crossCustomer', ?, 1, 'ATTACHABLE', ?, ?)",
                    UUID.randomUUID(),
                    c.account(),
                    customer.customerProvisioningRequestId(),
                    forgedAttachment,
                    forgedAttachment,
                    DIGEST));
    for (String assignment :
        List.of(
            "instrument_uuid = '11111111-1111-4111-8111-111111111111'::uuid",
            "account_uuid = '22222222-2222-4222-8222-222222222222'::uuid",
            "customer_provisioning_request_id = '33333333-3333-4333-8333-333333333333'::uuid",
            "provider_customer_id = 'cus_other'",
            "provider_payment_method_id = 'pm_changed'",
            "attachment_request_id = '44444444-4444-4444-8444-444444444444'::uuid")) {
      rejectSql(
          "23514",
          () ->
              c.setup()
                  .execute(
                      "UPDATE payment_instrument SET "
                          + assignment
                          + ", row_version = row_version + 1, change_request_id = ? WHERE instrument_uuid = ?",
                      UUID.randomUUID(),
                      instrument.instrumentUuid()));
    }
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE payment_instrument SET state = 'UNAVAILABLE' WHERE instrument_uuid = ?",
                    instrument.instrumentUuid()));
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE billing_customer SET row_version = row_version + 1, change_request_id = ?, provider_customer_id = 'cus_rebound' WHERE account_uuid = ?",
                    UUID.randomUUID(),
                    c.account()));
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE payment_instrument SET row_version = row_version + 1, change_request_id = ?, state = 'DETACHED' WHERE instrument_uuid = ?",
                    UUID.randomUUID(),
                    instrument.instrumentUuid()));
    for (String table :
        List.of(
            "billing_customer",
            "payment_instrument",
            "account_billing_customer_observations",
            "account_payment_instrument_observations")) {
      rejectSql("23514", () -> c.setup().execute("DELETE FROM " + table));
      rejectSql("23514", () -> c.setup().execute("TRUNCATE " + table + " CASCADE"));
    }
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE account_billing_customer_observations SET observation = observation"));
    rejectSql(
        "23514",
        () ->
            c.setup().execute("UPDATE account_payment_instrument_observations SET state = state"));
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "INSERT INTO account_billing_customer_observations SELECT account_uuid, row_version + 1, ?, state, provider_customer_id, observation, evidence_digest, recorded_at FROM account_billing_customer_observations WHERE row_version = 1",
                    UUID.randomUUID()));
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "INSERT INTO account_payment_instrument_observations SELECT instrument_uuid, row_version + 1, ?, state, display_brand, display_last_four, display_expiry_month, display_expiry_year, evidence_digest, recorded_at FROM account_payment_instrument_observations",
                    UUID.randomUUID()));
    // ON DELETE RESTRICT reports restrict_violation, preserving the referenced owner.
    rejectSql(
        "23001",
        () -> c.setup().execute("DELETE FROM accounts WHERE account_uuid = ?", c.account()));
    assertThat(c.customers().findByAccount(c.account())).contains(customer);
    assertThat(c.instruments().findByOwner(c.account(), instrument.instrumentUuid()))
        .contains(instrument);
  }

  private static String attemptCas(Context c, AvailabilityObservation request) {
    try {
      c.tx().execute(s -> c.instruments().recordAvailability(request));
      return "APPLIED";
    } catch (AccountPaymentInstrumentRepository.StaleVersionException stale) {
      return "STALE";
    }
  }

  private static AvailabilityObservation availability(
      AccountPaymentInstrumentRecord instrument,
      long expectedVersion,
      State state,
      String evidence) {
    return new AvailabilityObservation(
        UUID.randomUUID(),
        instrument.instrumentUuid(),
        instrument.accountUuid(),
        expectedVersion,
        state,
        DISPLAY,
        evidence);
  }

  private static Attachment attachment(
      AccountBillingCustomerRecord customer, String providerCustomer, String method) {
    return new Attachment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        customer.accountUuid(),
        customer.customerProvisioningRequestId(),
        providerCustomer,
        method,
        State.ATTACHABLE,
        DISPLAY,
        DIGEST);
  }

  private static AccountBillingCustomerRecord intent(Context c, UUID account) {
    return Objects.requireNonNull(
            c.tx()
                .execute(
                    s -> c.customers().retainIntent(account, UUID.randomUUID(), UUID.randomUUID())))
        .customer();
  }

  private static AccountBillingCustomerRecord confirm(
      Context c, UUID account, String providerCustomer) {
    var intent = intent(c, account);
    return observe(
        c, intent, 1, UUID.randomUUID(), Observation.CONFIRMED, providerCustomer, DIGEST);
  }

  private static AccountBillingCustomerRecord observe(
      Context c,
      AccountBillingCustomerRecord intent,
      long version,
      UUID request,
      Observation observation,
      String customer,
      String digest) {
    return Objects.requireNonNull(
        c.tx()
            .execute(
                s ->
                    c.customers()
                        .recordObservation(
                            intent.accountUuid(),
                            intent.customerProvisioningRequestId(),
                            version,
                            request,
                            observation,
                            customer,
                            digest)));
  }

  private static <T> List<T> race(Supplier<T> left, Supplier<T> right) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> afterBarrier(left, ready, start));
      var second = executor.submit(() -> afterBarrier(right, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static <T> T afterBarrier(Supplier<T> action, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent storage proof was not released");
    }
    return action.get();
  }

  private static void rejectSql(String sqlState, ThrowingCallable action) {
    assertThatThrownBy(action)
        .satisfies(
            failure -> {
              for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                  assertThat(sql.getSQLState()).isEqualTo(sqlState);
                  return;
                }
              }
              throw new AssertionError("Expected PostgreSQL storage refusal", failure);
            });
  }

  private static long count(Context c, String table) {
    return Objects.requireNonNull(c.setup().fetchOne("SELECT count(*) FROM " + table))
        .get(0, Long.class);
  }

  private static FluentConfiguration configuration(Context c) {
    return Flyway.configure()
        .dataSource(c.source())
        .schemas(c.schema())
        .defaultSchema(c.schema())
        .placeholders(Map.of("serviceSchema", c.schema()))
        .locations("classpath:db/migration", "classpath:db/migration/saga");
  }

  private static Context context() {
    Context c = predecessor();
    configuration(c).target("1027.1").load().migrate();
    return c;
  }

  private static Context predecessor() {
    String schema = "billing_prerequisites_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    DSLContext setup = DSL.using(source, SQLDialect.POSTGRES);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
    Context empty =
        new Context(
            source,
            schema,
            setup,
            tx,
            new AccountBillingCustomerRepository(dsl),
            new AccountPaymentInstrumentRepository(dsl),
            null,
            null);
    configuration(empty).target("1026").load().migrate();
    UUID account =
        Objects.requireNonNull(
                setup.fetchOne(
                    "INSERT INTO accounts(username,email,password_hash) VALUES ('billing','billing@example.test','hash') RETURNING account_uuid"))
            .get(0, UUID.class);
    UUID other =
        Objects.requireNonNull(
                setup.fetchOne(
                    "INSERT INTO accounts(username,email,password_hash) VALUES ('other','other@example.test','hash') RETURNING account_uuid"))
            .get(0, UUID.class);
    return new Context(
        source, schema, setup, tx, empty.customers(), empty.instruments(), account, other);
  }

  private record Context(
      DriverManagerDataSource source,
      String schema,
      DSLContext setup,
      TransactionTemplate tx,
      AccountBillingCustomerRepository customers,
      AccountPaymentInstrumentRepository instruments,
      UUID account,
      UUID otherAccount) {}
}
