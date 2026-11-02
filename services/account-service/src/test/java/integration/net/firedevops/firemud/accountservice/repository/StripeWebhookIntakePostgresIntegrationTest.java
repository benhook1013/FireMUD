package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stripe.exception.SignatureVerificationException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier.VerifiedEvent;
import net.firedevops.firemud.accountservice.service.StripeWebhookIntakeService;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual Stripe SDK signature verification and Account-owned PostgreSQL inbox proof. */
@Testcontainers(disabledWithoutDocker = true)
class StripeWebhookIntakePostgresIntegrationTest {
  private static final String SECRET = "whsec_local_test_secret";
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void validSdkSignedUnknownEventIsRetainedAsExactPendingEvidence() throws Exception {
    Context context = context();
    MutableClock clock = new MutableClock(NOW);
    StripeWebhookIntakeService service = service(context, clock, context.transactionManager());
    String payload = eventPayload("evt_unmapped_local", "future.unknown_event", "sub_unmapped");

    StripeEventInboxRecord receipt =
        service.accept(payload, signatureHeader(SECRET, payload, NOW.getEpochSecond()));

    assertThat(receipt.providerEventId()).isEqualTo("evt_unmapped_local");
    assertThat(receipt.rawPayload()).isEqualTo(payload);
    assertThat(receipt.payloadSha256()).isEqualTo(sha256(payload));
    assertThat(receipt.eventType()).isEqualTo("future.unknown_event");
    assertThat(receipt.eventCreatedAtEpochSeconds()).isEqualTo(NOW.getEpochSecond());
    assertThat(receipt.providerApiVersion()).isEqualTo("2025-02-24.acacia");
    assertThat(receipt.livemode()).isFalse();
    assertThat(receipt.receivedAt()).isEqualTo(NOW);
    assertThat(receipt.processingStatus()).isEqualTo(StripeEventInboxRecord.PENDING);
    assertThat(countRows(context)).isOne();
    assertThat(
            Objects.requireNonNull(
                    context
                        .setupDsl()
                        .fetchOne(
                            "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'stripe_event_inbox'::regclass "
                                + "AND contype = 'p')"),
                    "Expected primary-key metadata query row")
                .get(0, Boolean.class))
        .isTrue();
  }

  @Test
  void exactDuplicateReturnsTheOriginalReceiptTimestamp() throws Exception {
    Context context = context();
    MutableClock clock = new MutableClock(NOW);
    StripeWebhookIntakeService service = service(context, clock, context.transactionManager());
    String payload = eventPayload("evt_duplicate_local", "unrecognized.event", "cus_unknown");
    String signature = signatureHeader(SECRET, payload, NOW.getEpochSecond());

    StripeEventInboxRecord first = service.accept(payload, signature);
    clock.set(NOW.plusSeconds(17));
    StripeEventInboxRecord retry = service.accept(payload, signature);

    assertThat(retry).isEqualTo(first);
    assertThat(retry.receivedAt()).isEqualTo(NOW);
    assertThat(countRows(context)).isOne();
  }

  @Test
  void changedPayloadWithSameProviderEventIdConflictsWithoutReplacingEvidence() throws Exception {
    Context context = context();
    MutableClock clock = new MutableClock(NOW);
    StripeWebhookIntakeService service = service(context, clock, context.transactionManager());
    String originalPayload = eventPayload("evt_reused_local", "unrecognized.first", "sub_original");
    StripeEventInboxRecord original =
        service.accept(
            originalPayload, signatureHeader(SECRET, originalPayload, NOW.getEpochSecond()));
    String changedPayload = eventPayload("evt_reused_local", "unrecognized.changed", "sub_changed");
    clock.set(NOW.plusSeconds(1));

    StripeEventInboxConflictException conflict =
        assertThrows(
            StripeEventInboxConflictException.class,
            () ->
                service.accept(
                    changedPayload, signatureHeader(SECRET, changedPayload, NOW.getEpochSecond())));
    assertThat(conflict.providerEventId()).isEqualTo("evt_reused_local");

    Record retained = retainedRow(context, "evt_reused_local");
    assertThat(retained.get("raw_payload", byte[].class))
        .containsExactly(originalPayload.getBytes(StandardCharsets.UTF_8));
    assertThat(retained.get("payload_sha256", String.class)).isEqualTo(original.payloadSha256());
    assertThat(retained.get("event_type", String.class)).isEqualTo(original.eventType());
    assertThat(retained.get("received_at", java.time.OffsetDateTime.class).toInstant())
        .isEqualTo(original.receivedAt());
    assertThat(countRows(context)).isOne();
  }

  @Test
  void concurrentExactRetriesConvergeOnOneDatabaseUniqueReceipt() throws Exception {
    Context context = context();
    MutableClock clock = new MutableClock(NOW);
    StripeWebhookIntakeService service = service(context, clock, context.transactionManager());
    String payload = eventPayload("evt_concurrent_local", "other.unknown", "cus_unmapped");
    String signature = signatureHeader(SECRET, payload, NOW.getEpochSecond());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<StripeEventInboxRecord> left =
          executor.submit(() -> acceptAfterBarrier(service, payload, signature, ready, start));
      Future<StripeEventInboxRecord> right =
          executor.submit(() -> acceptAfterBarrier(service, payload, signature, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      StripeEventInboxRecord first = result(left);
      StripeEventInboxRecord second = result(right);
      assertThat(second).isEqualTo(first);
      assertThat(countRows(context)).isOne();
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void invalidSignatureStartsNoAccountTransactionAndWritesNoSql() throws Exception {
    Context context = context();
    CountingTransactionManager transactions = new CountingTransactionManager(context.dataSource());
    StripeWebhookIntakeService service = service(context, new MutableClock(NOW), transactions);
    String payload = eventPayload("evt_invalid_signature_local", "unknown.event", "sub_unknown");

    assertThatThrownBy(() -> service.accept(payload, "t=" + NOW.getEpochSecond() + ",v1=invalid"))
        .isInstanceOf(SignatureVerificationException.class);

    assertThat(transactions.requestCount()).isZero();
    assertThat(countRows(context)).isZero();
  }

  @Test
  void ambientTransactionIsRejectedBeforeVerificationOrInboxStorage() throws Exception {
    Context context = context();
    CountingTransactionManager serviceTransactions =
        new CountingTransactionManager(context.dataSource());
    StripeWebhookIntakeService service =
        service(context, new MutableClock(NOW), serviceTransactions);
    String payload = eventPayload("evt_ambient_local", "unknown.event", "sub_unknown");
    TransactionTemplate ambient = new TransactionTemplate(context.transactionManager());

    ambient.executeWithoutResult(
        status ->
            assertThatThrownBy(() -> service.accept(payload, "invalid-signature"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambient transaction or synchronization"));

    assertThat(serviceTransactions.requestCount()).isZero();
    assertThat(countRows(context)).isZero();
  }

  @Test
  void ownerTransactionRollbackRemovesInsertAndSqlPreventsMutationDeleteAndTruncate()
      throws Exception {
    Context context = context();
    MutableClock clock = new MutableClock(NOW);
    StripeHostingWebhookVerifier verifier = new StripeHostingWebhookVerifier(SECRET, clock);
    StripeEventInboxRepository repository = new StripeEventInboxRepository(context.dsl());
    String payload = eventPayload("evt_immutable_local", "unknown.event", "provider_unknown");
    VerifiedEvent verified =
        verifier.verify(payload, signatureHeader(SECRET, payload, NOW.getEpochSecond()));

    context
        .transaction()
        .executeWithoutResult(
            status -> {
              repository.retainOrReplay(verified);
              status.setRollbackOnly();
            });
    assertThat(countRows(context)).isZero();

    StripeWebhookIntakeService service =
        new StripeWebhookIntakeService(verifier, repository, context.transactionManager());
    StripeEventInboxRecord committed =
        service.accept(payload, signatureHeader(SECRET, payload, NOW.getEpochSecond()));
    assertThat(committed.rawPayload()).isEqualTo(payload);
    assertThat(committed.processingStatus()).isEqualTo(StripeEventInboxRecord.PENDING);

    assertAppendOnlyRejected(
        () ->
            context
                .setupDsl()
                .execute(
                    "UPDATE stripe_event_inbox SET event_type = 'changed' "
                        + "WHERE provider_event_id = ?",
                    committed.providerEventId()));
    assertAppendOnlyRejected(
        () ->
            context
                .setupDsl()
                .execute(
                    "DELETE FROM stripe_event_inbox WHERE provider_event_id = ?",
                    committed.providerEventId()));
    assertAppendOnlyRejected(() -> context.setupDsl().execute("TRUNCATE stripe_event_inbox"));

    assertThat(service.accept(payload, signatureHeader(SECRET, payload, NOW.getEpochSecond())))
        .isEqualTo(committed);
    assertThat(countRows(context)).isOne();
  }

  private static StripeWebhookIntakeService service(
      Context context, Clock clock, PlatformTransactionManager transactions) {
    return new StripeWebhookIntakeService(
        new StripeHostingWebhookVerifier(SECRET, clock),
        new StripeEventInboxRepository(context.dsl()),
        transactions);
  }

  private static StripeEventInboxRecord acceptAfterBarrier(
      StripeWebhookIntakeService service,
      String payload,
      String signature,
      CountDownLatch ready,
      CountDownLatch start)
      throws SignatureVerificationException {
    ready.countDown();
    await(start);
    return service.accept(payload, signature);
  }

  private static StripeEventInboxRecord result(Future<StripeEventInboxRecord> future)
      throws Exception {
    try {
      return future.get(15, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      throw new AssertionError("Concurrent Stripe inbox worker failed", failure.getCause());
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent Stripe inbox proof was not released");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent Stripe inbox proof was interrupted", interrupted);
    }
  }

  private static Record retainedRow(Context context, String providerEventId) {
    Record row =
        context
            .setupDsl()
            .fetchOne(
                "SELECT raw_payload, payload_sha256, event_type, received_at "
                    + "FROM stripe_event_inbox WHERE provider_event_id = ?",
                providerEventId);
    if (row == null) {
      throw new AssertionError("Expected retained Stripe event inbox row");
    }
    return row;
  }

  private static long countRows(Context context) {
    Long count =
        Objects.requireNonNull(
                context.setupDsl().fetchOne("SELECT count(*) FROM stripe_event_inbox"),
                "Expected inbox count query row")
            .get(0, Long.class);
    if (count == null) {
      throw new AssertionError("Stripe inbox row count query returned no value");
    }
    return count;
  }

  private static void assertAppendOnlyRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .satisfies(
            failure -> {
              java.sql.SQLException sqlFailure = findSqlException(failure);
              assertThat(sqlFailure.getSQLState()).isEqualTo("23514");
              assertThat(sqlFailure.getMessage())
                  .contains("Stripe event inbox evidence is append-only");
            });
  }

  private static java.sql.SQLException findSqlException(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sqlFailure) {
        return sqlFailure;
      }
    }
    throw new AssertionError("Expected PostgreSQL immutable-evidence refusal", failure);
  }

  private static Context context() {
    String schema = "stripe_inbox_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target("1026")
        .load()
        .migrate();
    DSLContext setupDsl = DSL.using(source, SQLDialect.POSTGRES);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    PlatformTransactionManager transactions = new DataSourceTransactionManager(source);
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(source, setupDsl, dsl, transactions, transaction);
  }

  private static String eventPayload(String id, String type, String providerObjectId) {
    return "{\"id\":\""
        + id
        + "\",\"object\":\"event\",\"api_version\":\"2025-02-24.acacia\","
        + "\"created\":"
        + NOW.getEpochSecond()
        + ",\"data\":{\"object\":{\"id\":\""
        + providerObjectId
        + "\"}},\"livemode\":false,\"type\":\""
        + type
        + "\"}";
  }

  private static String signatureHeader(String secret, String payload, long timestamp)
      throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] signed = (timestamp + "." + payload).getBytes(StandardCharsets.UTF_8);
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(signed));
  }

  private static String sha256(String payload) throws Exception {
    return "sha256:"
        + HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
  }

  private record Context(
      DriverManagerDataSource dataSource,
      DSLContext setupDsl,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transaction) {}

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;

    private MutableClock(Instant initial) {
      this.current = new AtomicReference<>(initial);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
      return current.get();
    }

    private void set(Instant instant) {
      current.set(instant);
    }
  }

  private static final class CountingTransactionManager implements PlatformTransactionManager {
    private final AtomicInteger requests = new AtomicInteger();
    private final DataSourceTransactionManager delegate;

    private CountingTransactionManager(DriverManagerDataSource source) {
      delegate = new DataSourceTransactionManager(source);
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      requests.incrementAndGet();
      return delegate.getTransaction(definition);
    }

    @Override
    public void commit(TransactionStatus status) {
      delegate.commit(status);
    }

    @Override
    public void rollback(TransactionStatus status) {
      delegate.rollback(status);
    }

    private int requestCount() {
      return requests.get();
    }
  }
}
