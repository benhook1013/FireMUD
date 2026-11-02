package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.State;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository.AvailabilityObservation;
import net.firedevops.firemud.accountservice.service.AccountPaymentInstrumentAttachmentService.HistoricalAttachment;
import net.firedevops.firemud.accountservice.service.AccountPaymentInstrumentAttachmentService.ObservedAttachment;
import net.firedevops.firemud.accountservice.service.AccountPaymentInstrumentAttachmentService.PendingObservation;
import net.firedevops.firemud.accountservice.service.AccountPaymentInstrumentAttachmentService.Request;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Physical retention with controlled customer enrollment and SDK transport; no actor/production
 * proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountPaymentInstrumentAttachmentPostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void sdkObservationCommitsExactHistoryAndRetryRetainsLaterAvailability() {
    Fixture f = fixture();
    var observed = (ObservedAttachment) f.service().retainSavedMethod(f.request());
    assertThat(f.transport().paths)
        .containsExactly("/v1/customers/cus_saved", "/v1/payment_methods/pm_saved");
    assertThat(f.instruments().readHistory(f.request().accountUuid(), f.request().instrumentUuid()))
        .containsExactly(observed.receipt());
    assertThat(observed.receipt().evidenceDigest()).isEqualTo(observed.evidence().evidenceDigest());
    UUID laterRequest = UUID.randomUUID();
    var unavailable =
        f.tx()
            .execute(
                s ->
                    f.instruments()
                        .recordAvailability(
                            new AvailabilityObservation(
                                laterRequest,
                                f.request().instrumentUuid(),
                                f.request().accountUuid(),
                                1,
                                State.UNAVAILABLE,
                                observed.receipt().display(),
                                "sha256:" + "c".repeat(64))));
    f.transport().paths.clear();
    var replay = (HistoricalAttachment) f.service().retainSavedMethod(f.request());
    assertThat(replay.receipt()).isEqualTo(observed.receipt());
    assertThat(replay.current()).isEqualTo(unavailable);
    assertThat(f.instruments().readHistory(f.request().accountUuid(), f.request().instrumentUuid()))
        .containsExactly(observed.receipt(), unavailable);
    assertThat(f.transport().paths).isEmpty();
    assertThatThrownBy(
            () ->
                f.service()
                    .retainSavedMethod(
                        new Request(
                            f.request().requestId(),
                            f.request().instrumentUuid(),
                            f.request().accountUuid(),
                            "pm_changed")))
        .hasMessageContaining("changed binding");
    assertThat(f.instruments().readHistory(f.request().accountUuid(), f.request().instrumentUuid()))
        .containsExactly(observed.receipt(), unavailable);
    int methodReadsBeforeReusedInstrument = f.transport().methodReads.get();
    Request differentRequestForOccupiedInstrument =
        new Request(
            UUID.randomUUID(),
            f.request().instrumentUuid(),
            f.request().accountUuid(),
            f.request().providerPaymentMethodId());
    assertThatThrownBy(() -> f.service().retainSavedMethod(differentRequestForOccupiedInstrument))
        .hasMessageContaining("Explicit instrument UUID already has a retained owner row");
    assertThat(f.transport().paths).isEmpty();
    assertThat(f.transport().methodReads.get()).isEqualTo(methodReadsBeforeReusedInstrument);
    assertThat(f.instruments().readHistory(f.request().accountUuid(), f.request().instrumentUuid()))
        .containsExactly(observed.receipt(), unavailable);
    assertThat(f.sql().fetchCount(DSL.table("payment_instrument"))).isEqualTo(1);
  }

  @Test
  void concurrentExactRequestsReturnOneOriginalObservationAndOneHistoricalReplay()
      throws Exception {
    Fixture f = fixture();
    f.transport().methodBarrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> f.service().retainSavedMethod(f.request()));
      var second = executor.submit(() -> f.service().retainSavedMethod(f.request()));
      var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results.stream().filter(ObservedAttachment.class::isInstance).count())
          .isEqualTo(1);
      assertThat(results.stream().filter(HistoricalAttachment.class::isInstance).count())
          .isEqualTo(1);
      var observed =
          (ObservedAttachment)
              results.stream()
                  .filter(ObservedAttachment.class::isInstance)
                  .findFirst()
                  .orElseThrow();
      var historical =
          (HistoricalAttachment)
              results.stream()
                  .filter(HistoricalAttachment.class::isInstance)
                  .findFirst()
                  .orElseThrow();
      assertThat(historical.receipt()).isEqualTo(observed.receipt());
      assertThat(historical.current()).isEqualTo(observed.current());
      assertThat(observed.receipt().evidenceDigest())
          .isEqualTo(observed.evidence().evidenceDigest());
      assertThat(
              f.instruments().readHistory(f.request().accountUuid(), f.request().instrumentUuid()))
          .containsExactly(observed.receipt());
      assertThat(f.transport().methodReads.get()).isEqualTo(2);
    }
  }

  @Test
  void mismatchedSdkOwnerCannotCommitAnyAttachment() {
    Fixture f = fixture();
    f.transport().methodCustomer = "cus_other";
    assertThat(f.service().retainSavedMethod(f.request())).isInstanceOf(PendingObservation.class);
    assertThat(f.instruments().readHistory(f.request().accountUuid(), f.request().instrumentUuid()))
        .isEmpty();
    assertThat(f.sql().fetchCount(DSL.table("payment_instrument"))).isZero();
  }

  private static Fixture fixture() {
    String schema = "instrument_attachment_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .target("1027.1")
        .load()
        .migrate();
    DSLContext sql = DSL.using(source, SQLDialect.POSTGRES);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(source);
    var tx = new TransactionTemplate(manager);
    var customers = new AccountBillingCustomerRepository(dsl);
    var instruments = new AccountPaymentInstrumentRepository(dsl);
    UUID account =
        Objects.requireNonNull(
                sql.fetchOne(
                    "INSERT INTO accounts(username,email,password_hash) VALUES ('wallet','wallet@example.test','hash') RETURNING account_uuid"))
            .get(0, UUID.class);
    UUID operation = UUID.randomUUID();
    var intent =
        Objects.requireNonNull(
            tx.execute(
                s -> customers.retainIntent(account, operation, UUID.randomUUID()).customer()));
    // Controlled local customer setup only; the observer must independently match SDK metadata.
    tx.executeWithoutResult(
        s ->
            customers.recordObservation(
                account,
                operation,
                intent.rowVersion(),
                UUID.randomUUID(),
                AccountBillingCustomerRecord.Observation.CONFIRMED,
                "cus_saved",
                "sha256:" + "a".repeat(64)));
    var transport = new Transport(account, operation, sql);
    Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
    var service =
        new AccountPaymentInstrumentAttachmentService(
            customers,
            instruments,
            new StripeBillingPrerequisiteObserver(
                new com.stripe.StripeClient(transport), clock, false),
            manager,
            clock,
            Duration.ofSeconds(10));
    return new Fixture(
        sql,
        tx,
        instruments,
        transport,
        service,
        new Request(UUID.randomUUID(), UUID.randomUUID(), account, "pm_saved"));
  }

  private record Fixture(
      DSLContext sql,
      TransactionTemplate tx,
      AccountPaymentInstrumentRepository instruments,
      Transport transport,
      AccountPaymentInstrumentAttachmentService service,
      Request request) {}

  private static final class Transport implements StripeResponseGetter {
    private final UUID account;
    private final UUID operation;
    private final DSLContext independentSql;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final AtomicInteger methodReads = new AtomicInteger();
    private CyclicBarrier methodBarrier;
    private String methodCustomer = "cus_saved";

    private Transport(UUID account, UUID operation, DSLContext independentSql) {
      this.account = account;
      this.operation = operation;
      this.independentSql = independentSql;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode apiMode)
        throws StripeException {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
      assertThat(method).isEqualTo(RequestMethod.GET);
      assertThat(params == null || params.isEmpty()).isTrue();
      // Independent SQL connection observes no wallet row during either provider retrieval.
      assertThat(independentSql.fetchCount(DSL.table("payment_instrument"))).isZero();
      paths.add(path);
      String response;
      if (path.equals("/v1/customers/cus_saved")) {
        response =
            ("{\"id\":\"cus_saved\",\"object\":\"customer\",\"livemode\":false,\"deleted\":false,%n"
                    + " \"metadata\":{\"firemud_metadata_schema_version\":\"1\",\"firemud_operation_type\":\"customer_provisioning\",%n"
                    + "  \"firemud_operation_id\":\"%s\",\"firemud_account_id\":\"%s\"}}%n")
                .formatted(operation, account);
      } else if (path.equals("/v1/payment_methods/pm_saved")) {
        int read = methodReads.incrementAndGet();
        if (methodBarrier != null) {
          try {
            // Both requests must finish preparation before either may retain. Distinct display
            // observations make their digests differ while the stable request binding is equal.
            methodBarrier.await(10, TimeUnit.SECONDS);
          } catch (Exception failure) {
            throw new AssertionError("Concurrent SDK observations did not rendezvous", failure);
          }
        }
        response =
            ("{\"id\":\"pm_saved\",\"object\":\"payment_method\",\"livemode\":false,\"type\":\"card\",%n"
                    + " \"customer\":\"%s\",\"card\":{\"brand\":\"visa\",\"last4\":\"%s\",\"exp_month\":12,\"exp_year\":2030}}%n")
                .formatted(methodCustomer, read == 1 ? "4242" : "4444");
      } else {
        throw new AssertionError("Unexpected SDK request " + path);
      }
      return (T) com.stripe.net.ApiResource.GSON.fromJson(response, type);
    }

    @Override
    public InputStream requestStream(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode apiMode) {
      throw new AssertionError("No stream or mutation allowed");
    }
  }
}
