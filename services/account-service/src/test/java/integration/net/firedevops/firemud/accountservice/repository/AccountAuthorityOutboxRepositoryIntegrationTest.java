package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.IdempotencyConflictException;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountAuthorityOutboxRepositoryIntegrationTest {
  private static final String SCHEMA_PREFIX = "authority_outbox_proof";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void appendsContiguousPerStreamAndReplaysOnlyExactRequestEvidence() {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    String stream = membershipStream(UUID.randomUUID(), UUID.randomUUID());
    String unrelated = "account:auth-authority:v1:tenant/" + UUID.randomUUID();

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream))).isEmpty();

    Event first =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream, "request-1", "event-1", "canonical-digest-1", new byte[] {1, 2}));
    Event exactReplay =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream, "request-1", "event-1", "canonical-digest-1", new byte[] {1, 2}));
    assertThat(exactReplay).isEqualTo(first);

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () ->
                        repository.append(
                            stream, "request-1", "event-1", "different-digest", new byte[] {1, 2})))
        .isInstanceOf(IdempotencyConflictException.class);

    Event second =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    stream, "request-2", "event-2", "canonical-digest-2", new byte[] {3, 4}));
    Event otherStreamFirst =
        inTransaction(
            transaction,
            () ->
                repository.append(
                    unrelated, "request-1", "event-1", "canonical-digest-1", new byte[] {1, 2}));
    Optional<Checkpoint> checkpoint =
        inTransaction(transaction, () -> repository.readCheckpoint(stream));

    assertThat(first.outboxSequence()).isEqualTo(1L);
    assertThat(second.outboxSequence()).isEqualTo(2L);
    assertThat(otherStreamFirst.outboxSequence()).isEqualTo(1L);
    assertThat(checkpoint).contains(new Checkpoint(stream, 2L, "event-2", "canonical-digest-2"));
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, 1L))).contains(first);
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, 2L))).contains(second);
  }

  @Test
  void callerRollbackRemovesAppendAndConcurrentExactRetriesShareOneSequence() throws Exception {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    String rollbackStream = "account:auth-authority:v1:account/" + UUID.randomUUID();

    transaction.execute(
        status -> {
          repository.append(
              rollbackStream, "rollback-request", "rollback-event", "digest", new byte[] {9});
          status.setRollbackOnly();
          return null;
        });
    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(rollbackStream)))
        .isEmpty();

    String concurrentStream = "account:auth-authority:v1:issuer/" + UUID.randomUUID();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> concurrentAppend(repository, transaction, concurrentStream, ready, start));
      var second =
          executor.submit(
              () -> concurrentAppend(repository, transaction, concurrentStream, ready, start));
      ready.await();
      start.countDown();
      List<Event> events = List.of(first.get(), second.get());

      assertThat(events).allMatch(event -> event.outboxSequence() == 1L);
      assertThat(events).extracting(Event::eventId).containsOnly("event-1");
      assertThat(inTransaction(transaction, () -> repository.readCheckpoint(concurrentStream)))
          .contains(new Checkpoint(concurrentStream, 1L, "event-1", "digest-1"));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void evidenceFactoryFailureRollsBackStreamCreationAndDoesNotAdvanceHead() {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    String stream = membershipStream(UUID.randomUUID(), UUID.randomUUID());

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () ->
                        repository.append(
                            stream,
                            "factory-failure-request",
                            sequence -> {
                              assertThat(sequence).isEqualTo(1L);
                              throw new IllegalStateException("producer failed");
                            })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("producer failed");

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream))).isEmpty();
  }

  @Test
  void exhaustedSequenceRejectsAppendWithoutChangingEventCheckpointOrCallerSentinel() {
    TestContext context = newTestContext();
    AccountAuthorityOutboxRepository repository = context.repository();
    TransactionTemplate transaction = context.transaction();
    DSLContext dsl = context.dsl();
    String stream = "account:auth-authority:v1:account/" + UUID.randomUUID();
    byte[] originalPayload = new byte[] {7, 8, 9};
    Event originalEvent =
        new Event(
            stream,
            "exhaustion-seed-request",
            Long.MAX_VALUE,
            "exhaustion-seed-event",
            "exhaustion-seed-digest",
            originalPayload);

    dsl.execute(
        "CREATE TABLE authority_overflow_sentinel "
            + "(sentinel_id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
    dsl.execute("INSERT INTO authority_overflow_sentinel (sentinel_id, value) VALUES (1, 0)");
    // Seed a consistent exhausted stream; bypass only its contiguous-insert guard for this setup.
    inTransaction(
        transaction,
        () -> {
          dsl.execute(
              "INSERT INTO account_authority_outbox_streams "
                  + "(outbox_stream_key, last_sequence) VALUES (?, ?)",
              stream,
              Long.MAX_VALUE);
          dsl.execute(
              "ALTER TABLE account_authority_outbox_events "
                  + "DISABLE TRIGGER account_authority_outbox_event_insert");
          try {
            dsl.execute(
                "INSERT INTO account_authority_outbox_events "
                    + "(outbox_stream_key, outbox_sequence, request_id, event_id, "
                    + "event_digest, payload) VALUES (?, ?, ?, ?, ?, ?)",
                stream,
                Long.MAX_VALUE,
                originalEvent.requestId(),
                originalEvent.eventId(),
                originalEvent.eventDigest(),
                originalPayload);
          } finally {
            dsl.execute(
                "ALTER TABLE account_authority_outbox_events "
                    + "ENABLE TRIGGER account_authority_outbox_event_insert");
          }
          return null;
        });

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream)))
        .contains(
            new Checkpoint(
                stream, Long.MAX_VALUE, "exhaustion-seed-event", "exhaustion-seed-digest"));
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, Long.MAX_VALUE)))
        .contains(originalEvent);

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () -> {
                      dsl.execute(
                          "UPDATE authority_overflow_sentinel SET value = value + 1 "
                              + "WHERE sentinel_id = 1");
                      return repository.append(
                          stream,
                          "exhaustion-attempt-request",
                          "exhaustion-attempt-event",
                          "exhaustion-attempt-digest",
                          new byte[] {1, 2, 3});
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account authority outbox sequence is exhausted");

    assertThat(inTransaction(transaction, () -> repository.readCheckpoint(stream)))
        .contains(
            new Checkpoint(
                stream, Long.MAX_VALUE, "exhaustion-seed-event", "exhaustion-seed-digest"));
    assertThat(inTransaction(transaction, () -> repository.findEvent(stream, Long.MAX_VALUE)))
        .contains(originalEvent);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_sequence FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(
            dsl.resultQuery("SELECT value FROM authority_overflow_sentinel WHERE sentinel_id = 1")
                .fetchOne(0, Integer.class))
        .isZero();
  }

  private Event concurrentAppend(
      AccountAuthorityOutboxRepository repository,
      TransactionTemplate transaction,
      String stream,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    await(start);
    return inTransaction(
        transaction,
        () -> repository.append(stream, "same-request", "event-1", "digest-1", new byte[] {1}));
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        new AccountAuthorityOutboxRepository(dsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        dsl);
  }

  private String membershipStream(UUID accountId, UUID tenantId) {
    return "account:auth-authority:v1:membership/" + accountId + "/" + tenantId;
  }

  private <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Authority outbox concurrency proof was interrupted", interrupted);
    }
  }

  private record TestContext(
      AccountAuthorityOutboxRepository repository,
      TransactionTemplate transaction,
      DSLContext dsl) {}
}
