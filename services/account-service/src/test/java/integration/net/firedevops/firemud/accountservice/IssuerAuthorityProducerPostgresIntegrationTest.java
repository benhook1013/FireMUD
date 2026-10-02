package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IssuerAuthorityProducerPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "issuer_authority_source_proof";
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String STREAM_KEY = "account:auth-authority:v1:issuer/" + ISSUER_ID;
  private static final String EVENT_ID_PREFIX = "account-issuer-authority-event-v1:";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void atomicallyAdvancesSourceAndEventAndReadsBackExactCheckpoint() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);

    AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot baseline =
        fixture.producer().readCurrent(ISSUER_ID);
    assertThat(baseline.issuerAuthGeneration()).isEqualTo(1L);
    assertThat(baseline.sourceVersion()).isEqualTo(1L);
    assertThat(baseline.outboxSequence()).isZero();
    assertThat(baseline.latestEvent()).isEmpty();

    UUID requestId = UUID.randomUUID();
    IssuerGenerationAuthorityEvent event = fixture.producer().advance(ISSUER_ID, requestId, 1L, 1L);
    AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot current =
        fixture.producer().readCurrent(ISSUER_ID);

    assertThat(event.eventId()).isEqualTo(EVENT_ID_PREFIX + requestId);
    assertThat(event.requestId()).isEqualTo(requestId.toString());
    assertThat(event.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(event.sourceScope()).isEqualTo("issuer/" + ISSUER_ID);
    assertThat(event.outboxStreamKey()).isEqualTo(STREAM_KEY);
    assertThat(event.outboxSequence()).isEqualTo("1");
    assertThat(event.issuerAuthGeneration()).isEqualTo("2");
    assertThat(event.sourceVersion()).isEqualTo("2");
    assertThat(event.eventDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(current.issuerAuthGeneration()).isEqualTo(2L);
    assertThat(current.sourceVersion()).isEqualTo(2L);
    assertThat(current.outboxSequence()).isEqualTo(1L);
    assertThat(current.latestEvent()).isPresent();
    assertSameEvent(event, current.latestEvent().orElseThrow());
    assertThat(countEvents(fixture, STREAM_KEY)).isEqualTo(1L);
  }

  @Test
  void exactRetryRecoversLostResponseAndOriginalEventAfterLaterAdvance() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    UUID firstRequest = UUID.randomUUID();
    IssuerGenerationAuthorityEvent first =
        fixture.producer().advance(ISSUER_ID, firstRequest, 1L, 1L);
    IssuerGenerationAuthorityEvent exactRetry =
        fixture.producer().advance(ISSUER_ID, firstRequest, 1L, 1L);
    assertSameEvent(first, exactRetry);

    UUID secondRequest = UUID.randomUUID();
    IssuerGenerationAuthorityEvent second =
        fixture.producer().advance(ISSUER_ID, secondRequest, 2L, 2L);
    IssuerGenerationAuthorityEvent historicalRetry =
        fixture.producer().advance(ISSUER_ID, firstRequest, 1L, 1L);
    assertSameEvent(first, historicalRetry);
    assertThat(second.outboxSequence()).isEqualTo("2");
    assertThat(fixture.producer().readCurrent(ISSUER_ID).latestEvent()).isPresent();
    assertSameEvent(second, fixture.producer().readCurrent(ISSUER_ID).latestEvent().orElseThrow());
    assertThat(countEvents(fixture, STREAM_KEY)).isEqualTo(2L);
  }

  @Test
  void concurrentExactRequestSerializesAndCommitsOneSourceEvent() throws Exception {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    UUID requestId = UUID.randomUUID();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> concurrentAdvance(fixture, requestId, ready, start));
      var second = executor.submit(() -> concurrentAdvance(fixture, requestId, ready, start));
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      IssuerGenerationAuthorityEvent firstEvent = first.get(45, TimeUnit.SECONDS);
      IssuerGenerationAuthorityEvent secondEvent = second.get(45, TimeUnit.SECONDS);
      assertSameEvent(firstEvent, secondEvent);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    assertThat(readAuthority(fixture).generation()).isEqualTo(2L);
    assertThat(readAuthority(fixture).sourceVersion()).isEqualTo(2L);
    assertThat(countEvents(fixture, STREAM_KEY)).isEqualTo(1L);
    assertThat(countStreams(fixture)).isEqualTo(1L);
  }

  @Test
  void changedExpectedStateForSameRequestConflictsWithoutMutation() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    UUID requestId = UUID.randomUUID();
    fixture.producer().advance(ISSUER_ID, requestId, 1L, 1L);
    StoredState committed = snapshot(fixture);

    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, requestId, 2L, 2L))
        .isInstanceOf(AccountAuthorityOutboxRepository.IdempotencyConflictException.class);

    assertThat(snapshot(fixture)).isEqualTo(committed);
  }

  @Test
  void wrongIssuerStaleRequestAndMissingSourceFailWithoutEnrollmentOrMutation() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    StoredState baseline = snapshot(fixture);
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                fixture.producer().advance("https://other.example.test/issuer", requestId, 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, new UUID(0L, 0L), 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, requestId, 2L, 2L))
        .isInstanceOf(IllegalStateException.class);
    assertThat(snapshot(fixture)).isEqualTo(baseline);

    Fixture missing = newFixture();
    assertThatThrownBy(() -> missing.producer().readCurrent(ISSUER_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("generation is missing");
    assertThatThrownBy(() -> missing.producer().advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L))
        .isInstanceOf(IllegalStateException.class);
    assertThat(countStreams(missing)).isZero();
    assertThat(countEvents(missing, STREAM_KEY)).isZero();
  }

  @Test
  void sequenceZeroRequiresOriginalPositiveBaselineAndNoContradictoryStreamOrHistory() {
    Fixture baseline = newFixture();
    seedIssuer(baseline);
    AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot zero =
        baseline.producer().readCurrent(ISSUER_ID);
    assertThat(zero.issuerAuthGeneration()).isEqualTo(1L);
    assertThat(zero.sourceVersion()).isEqualTo(1L);
    assertThat(zero.outboxSequence()).isZero();
    assertThat(zero.latestEvent()).isEmpty();

    Fixture advancedWithoutEvents = newFixture();
    seedIssuer(advancedWithoutEvents);
    forceIssuerCounters(advancedWithoutEvents, 2L, 2L);
    assertThatThrownBy(() -> advancedWithoutEvents.producer().readCurrent(ISSUER_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("original positive 1/1");

    Fixture contradictoryHead = newFixture();
    seedIssuer(contradictoryHead);
    contradictoryHead
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_streams "
                + "DISABLE TRIGGER account_authority_outbox_stream_consistent");
    contradictoryHead
        .setupDsl()
        .execute(
            "INSERT INTO account_authority_outbox_streams (outbox_stream_key, last_sequence) "
                + "VALUES (?, 1)",
            STREAM_KEY);
    contradictoryHead
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_streams "
                + "ENABLE TRIGGER account_authority_outbox_stream_consistent");
    assertThatThrownBy(() -> contradictoryHead.producer().readCurrent(ISSUER_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("checkpoint has no matching event evidence");

    Fixture committedZeroHead = newFixture();
    seedIssuer(committedZeroHead);
    committedZeroHead
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_streams "
                + "DISABLE TRIGGER account_authority_outbox_stream_consistent");
    committedZeroHead
        .setupDsl()
        .execute(
            "INSERT INTO account_authority_outbox_streams (outbox_stream_key, last_sequence) "
                + "VALUES (?, 0)",
            STREAM_KEY);
    committedZeroHead
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_streams "
                + "ENABLE TRIGGER account_authority_outbox_stream_consistent");
    assertThatThrownBy(() -> committedZeroHead.producer().readCurrent(ISSUER_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Committed zero-sequence issuer stream head");
  }

  @Test
  void positiveReadbackRejectsSourceCountersThatDifferFromLatestEvent() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    fixture.producer().advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L);
    forceIssuerCounters(fixture, 3L, 3L);

    assertThatThrownBy(() -> fixture.producer().readCurrent(ISSUER_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Latest issuer source event differs");
  }

  @Test
  void positiveReadbackUsesExplicitLatestEventCountersInsteadOfDerivingThemFromSequence() {
    Fixture fixture = newFixture();
    // Seed an already-positive V31 source row as fixture state; this is not migration provenance
    // proof.
    fixture
        .setupDsl()
        .execute(
            "INSERT INTO account_authority_generations "
                + "(scope_kind, issuer_id, account_uuid, tenant_uuid, generation, source_version) "
                + "VALUES ('ISSUER', ?, NULL, NULL, 3, 7)",
            ISSUER_ID);
    transaction(
        fixture.transaction(),
        () -> {
          ScopeState baseline = fixture.generations().read(AuthorityScope.issuer(ISSUER_ID));
          ScopeState advanced = fixture.generations().advance(baseline, null);
          assertThat(advanced.generation()).isEqualTo(4L);
          assertThat(advanced.sourceVersion()).isEqualTo(8L);
          appendSeedEvent(fixture, UUID.randomUUID(), 4L, 8L);
          return null;
        });

    AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot snapshot =
        fixture.producer().readCurrent(ISSUER_ID);
    assertThat(snapshot.issuerAuthGeneration()).isEqualTo(4L);
    assertThat(snapshot.sourceVersion()).isEqualTo(8L);
    assertThat(snapshot.outboxSequence()).isEqualTo(1L);
    assertThat(snapshot.latestEvent()).isPresent();
    IssuerGenerationAuthorityEvent latest = snapshot.latestEvent().orElseThrow();
    assertThat(latest.outboxSequence()).isEqualTo("1");
    assertThat(latest.issuerAuthGeneration()).isEqualTo("4");
    assertThat(latest.sourceVersion()).isEqualTo("8");
  }

  @Test
  void appendFailureRollsBackSourceAndOutboxTogether() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "ADD CONSTRAINT reject_issuer_source_event "
                + "CHECK (event_id NOT LIKE 'account-issuer-authority-event-v1:%')");

    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L))
        .isInstanceOf(RuntimeException.class);

    assertThat(readAuthority(fixture).generation()).isEqualTo(1L);
    assertThat(readAuthority(fixture).sourceVersion()).isEqualTo(1L);
    assertThat(countEvents(fixture, STREAM_KEY)).isZero();
    assertThat(countStreams(fixture)).isZero();
  }

  @Test
  void transactionLocalEventReadbackFailureRollsBackTheAdvance() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    AccountAuthorityOutboxRepository hideSecondRequestRead =
        new AccountAuthorityOutboxRepository(fixture.transactionDsl()) {
          private final AtomicInteger requestLookups = new AtomicInteger();

          @Override
          public Optional<Event> findEvent(String streamKey, String requestId) {
            if (requestLookups.incrementAndGet() >= 2) {
              return Optional.empty();
            }
            return super.findEvent(streamKey, requestId);
          }
        };
    AccountIssuerAuthorityEventProducer producer = fixture.producer(hideSecondRequestRead);

    assertThatThrownBy(() -> producer.advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("request event readback is missing");

    assertThat(readAuthority(fixture).generation()).isEqualTo(1L);
    assertThat(readAuthority(fixture).sourceVersion()).isEqualTo(1L);
    assertThat(countEvents(fixture, STREAM_KEY)).isZero();
    assertThat(countStreams(fixture)).isZero();
  }

  @Test
  void unavailablePostCommitReadDoesNotUndoAndExactRetryRecoversWithoutAdvancingAgain() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    AtomicInteger requestLookups = new AtomicInteger();
    AccountAuthorityOutboxRepository hidePostCommitRead =
        new AccountAuthorityOutboxRepository(fixture.transactionDsl()) {
          @Override
          public Optional<Event> findEvent(String streamKey, String requestId) {
            if (requestLookups.incrementAndGet() == 3) {
              return Optional.empty();
            }
            return super.findEvent(streamKey, requestId);
          }
        };
    UUID requestId = UUID.randomUUID();
    AccountIssuerAuthorityEventProducer firstAttempt = fixture.producer(hidePostCommitRead);

    assertThatThrownBy(() -> firstAttempt.advance(ISSUER_ID, requestId, 1L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Post-commit issuer authority request event is missing");

    assertThat(readAuthority(fixture).generation()).isEqualTo(2L);
    assertThat(readAuthority(fixture).sourceVersion()).isEqualTo(2L);
    assertThat(countEvents(fixture, STREAM_KEY)).isEqualTo(1L);
    IssuerGenerationAuthorityEvent recovered =
        fixture.producer().advance(ISSUER_ID, requestId, 1L, 1L);
    assertThat(recovered.eventId()).isEqualTo(EVENT_ID_PREFIX + requestId);
    assertThat(readAuthority(fixture).generation()).isEqualTo(2L);
    assertThat(readAuthority(fixture).sourceVersion()).isEqualTo(2L);
    assertThat(countEvents(fixture, STREAM_KEY)).isEqualTo(1L);
  }

  @Test
  void generationAndSourceVersionOverflowFailBeforeAnyMutation() {
    Fixture generationOverflow = newFixture();
    seedIssuer(generationOverflow);
    forceIssuerCounters(generationOverflow, Long.MAX_VALUE, 1L);
    assertThatThrownBy(
            () ->
                generationOverflow
                    .producer()
                    .advance(ISSUER_ID, UUID.randomUUID(), Long.MAX_VALUE, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuer generation is exhausted");
    assertCountersAndOutbox(generationOverflow, Long.MAX_VALUE, 1L);

    Fixture sourceVersionOverflow = newFixture();
    seedIssuer(sourceVersionOverflow);
    forceIssuerCounters(sourceVersionOverflow, 1L, Long.MAX_VALUE);
    assertThatThrownBy(
            () ->
                sourceVersionOverflow
                    .producer()
                    .advance(ISSUER_ID, UUID.randomUUID(), 1L, Long.MAX_VALUE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuer source version is exhausted");
    assertCountersAndOutbox(sourceVersionOverflow, 1L, Long.MAX_VALUE);
  }

  private IssuerGenerationAuthorityEvent concurrentAdvance(
      Fixture fixture, UUID requestId, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    return fixture.producer().advance(ISSUER_ID, requestId, 1L, 1L);
  }

  private Fixture newFixture() {
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

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(transactionDsl);
    return new Fixture(
        setupDsl, transactionDsl, transactionManager, transaction, generations, outbox);
  }

  private void seedIssuer(Fixture fixture) {
    transaction(
        fixture.transaction(),
        () -> {
          fixture.generations().initializeIssuerIfAbsent(ISSUER_ID);
          return null;
        });
  }

  private void appendSeedEvent(
      Fixture fixture, UUID requestId, long issuerGeneration, long sourceVersion) {
    String requestText = requestId.toString();
    String eventId = EVENT_ID_PREFIX + requestText;
    fixture
        .outbox()
        .append(
            STREAM_KEY,
            requestText,
            sequence -> {
              IssuerGenerationAuthorityEvent event =
                  IssuerGenerationAuthorityEventV1Codec.seal(
                      Map.of(
                          "schemaVersion",
                          IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                          "eventType",
                          IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
                          "eventId",
                          eventId,
                          "requestId",
                          requestText,
                          "issuerId",
                          ISSUER_ID,
                          "sourceScope",
                          "issuer/" + ISSUER_ID,
                          "outboxStreamKey",
                          STREAM_KEY,
                          "outboxSequence",
                          Long.toString(sequence),
                          "issuerAuthGeneration",
                          Long.toString(issuerGeneration),
                          "sourceVersion",
                          Long.toString(sourceVersion)));
              return new AccountAuthorityOutboxRepository.EventEvidence(
                  event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
            });
  }

  private void forceIssuerCounters(Fixture fixture, long generation, long sourceVersion) {
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_generations "
                + "DISABLE TRIGGER account_authority_generations_monotonic");
    fixture
        .setupDsl()
        .execute(
            "UPDATE account_authority_generations SET generation = ?, source_version = ? "
                + "WHERE scope_kind = 'ISSUER' AND issuer_id = ?",
            generation,
            sourceVersion,
            ISSUER_ID);
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_generations "
                + "ENABLE TRIGGER account_authority_generations_monotonic");
  }

  private void assertCountersAndOutbox(Fixture fixture, long generation, long sourceVersion) {
    ScopeState authority = readAuthority(fixture);
    assertThat(authority.generation()).isEqualTo(generation);
    assertThat(authority.sourceVersion()).isEqualTo(sourceVersion);
    assertThat(countEvents(fixture, STREAM_KEY)).isZero();
    assertThat(countStreams(fixture)).isZero();
  }

  private ScopeState readAuthority(Fixture fixture) {
    return transaction(
        fixture.transaction(), () -> fixture.generations().read(AuthorityScope.issuer(ISSUER_ID)));
  }

  private StoredState snapshot(Fixture fixture) {
    ScopeState authority = readAuthority(fixture);
    return new StoredState(
        authority.generation(),
        authority.sourceVersion(),
        countEvents(fixture, STREAM_KEY),
        countStreams(fixture));
  }

  private long countEvents(Fixture fixture, String streamKey) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class),
        "Issuer event count readback is missing");
  }

  private long countStreams(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery("SELECT COUNT(*) FROM account_authority_outbox_streams")
            .fetchOne(0, Long.class),
        "Issuer stream count readback is missing");
  }

  private void assertSameEvent(
      IssuerGenerationAuthorityEvent expected, IssuerGenerationAuthorityEvent actual) {
    assertThat(actual.eventId()).isEqualTo(expected.eventId());
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.issuerId()).isEqualTo(expected.issuerId());
    assertThat(actual.sourceScope()).isEqualTo(expected.sourceScope());
    assertThat(actual.outboxStreamKey()).isEqualTo(expected.outboxStreamKey());
    assertThat(actual.outboxSequence()).isEqualTo(expected.outboxSequence());
    assertThat(actual.issuerAuthGeneration()).isEqualTo(expected.issuerAuthGeneration());
    assertThat(actual.sourceVersion()).isEqualTo(expected.sourceVersion());
    assertThat(actual.eventDigest()).isEqualTo(expected.eventDigest());
    assertThat(actual.canonicalJsonUtf8()).containsExactly(expected.canonicalJsonUtf8());
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent issuer authority barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Concurrent issuer authority proof was interrupted", interrupted);
    }
  }

  private <T> T transaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record Fixture(
      DSLContext setupDsl,
      DSLContext transactionDsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transaction,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox) {
    private AccountIssuerAuthorityEventProducer producer() {
      return producer(outbox);
    }

    private AccountIssuerAuthorityEventProducer producer(
        AccountAuthorityOutboxRepository outboxOverride) {
      return new AccountIssuerAuthorityEventProducer(
          ISSUER_ID, generations, outboxOverride, transactionDsl, transactionManager);
    }
  }

  private record StoredState(
      long generation, long sourceVersion, long eventCount, long streamCount) {}
}
