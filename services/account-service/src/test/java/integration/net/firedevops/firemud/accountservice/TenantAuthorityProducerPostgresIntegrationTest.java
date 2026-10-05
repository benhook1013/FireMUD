package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountTenantAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TenantAuthorityProducerPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "tenant_authority_source_proof";
  private static final String TEST_NAMESPACE = "account-service";
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);
  private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID UNKNOWN_TENANT_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000002");
  private static final String SOURCE_SCOPE = "tenant/" + TENANT_ID;
  private static final String STREAM_KEY = "account:auth-authority:v1:" + SOURCE_SCOPE;
  private static final String EVENT_ID_PREFIX = "account-tenant-generation-event-v1:";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void
      atomicallyAdvancesEnrolledTenantSourceAndReadsBackExactCheckpointWithoutAccountFenceChange() {
    Fixture fixture = newFixture();
    seed(fixture);
    ScopeState accountBefore = readAccountState(fixture);
    AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot baseline =
        fixture.producer().readCurrent(TENANT_ID);
    assertThat(baseline.tenantAuthorityGeneration()).isEqualTo(1L);
    assertThat(baseline.sourceVersion()).isEqualTo(1L);
    assertThat(baseline.outboxSequence()).isZero();
    assertThat(baseline.latestEvent()).isEmpty();

    UUID requestId = UUID.randomUUID();
    TenantGenerationAuthorityEvent event = fixture.producer().advance(TENANT_ID, requestId, 1L, 1L);
    AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot current =
        fixture.producer().readCurrent(TENANT_ID);

    assertThat(event.eventId()).isEqualTo(EVENT_ID_PREFIX + requestId);
    assertThat(event.requestId()).isEqualTo(requestId.toString());
    assertThat(event.tenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(event.sourceScope()).isEqualTo(SOURCE_SCOPE);
    assertThat(event.outboxStreamKey()).isEqualTo(STREAM_KEY);
    assertThat(event.outboxSequence()).isEqualTo("1");
    assertThat(event.tenantAuthorityGeneration()).isEqualTo("2");
    assertThat(event.sourceVersion()).isEqualTo("2");
    assertThat(event.eventDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(event.canonicalJson())
        .doesNotContain("issuanceFence", "recipientAccount", "billing");
    assertThat(current.tenantAuthorityGeneration()).isEqualTo(2L);
    assertThat(current.sourceVersion()).isEqualTo(2L);
    assertThat(current.outboxSequence()).isEqualTo(1L);
    assertThat(current.latestEvent()).isPresent();
    assertSameEvent(event, current.latestEvent().orElseThrow());
    assertThat(countEvents(fixture, STREAM_KEY)).isEqualTo(1L);
    assertThat(readAccountState(fixture)).isEqualTo(accountBefore);
  }

  @Test
  void exactRetryRecoversOriginalEventAfterLaterTenantAdvances() {
    Fixture fixture = newFixture();
    seed(fixture);
    UUID firstRequest = UUID.randomUUID();
    TenantGenerationAuthorityEvent first =
        fixture.producer().advance(TENANT_ID, firstRequest, 1L, 1L);
    TenantGenerationAuthorityEvent exactRetry =
        fixture.producer().advance(TENANT_ID, firstRequest, 1L, 1L);
    assertSameEvent(first, exactRetry);

    TenantGenerationAuthorityEvent second =
        fixture.producer().advance(TENANT_ID, UUID.randomUUID(), 2L, 2L);
    TenantGenerationAuthorityEvent historicalRetry =
        fixture.producer().advance(TENANT_ID, firstRequest, 1L, 1L);

    assertSameEvent(first, historicalRetry);
    assertThat(second.outboxSequence()).isEqualTo("2");
    assertThat(fixture.producer().readCurrent(TENANT_ID).tenantAuthorityGeneration()).isEqualTo(3L);
    assertThat(countEvents(fixture, STREAM_KEY)).isEqualTo(2L);
  }

  @Test
  void historicalReadbackReturnsRequestedEventAndCurrentSourceFromOneTenantFence() {
    Fixture fixture = newFixture();
    seed(fixture);
    TenantGenerationAuthorityEvent first =
        fixture.producer().advance(TENANT_ID, UUID.randomUUID(), 1L, 1L);
    TenantGenerationAuthorityEvent second =
        fixture.producer().advance(TENANT_ID, UUID.randomUUID(), 2L, 2L);

    AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback readback =
        fixture.producer().readCommittedEvent(TENANT_ID, 1L);

    assertSameEvent(first, readback.requestedEvent());
    assertThat(readback.currentSnapshot().tenantAuthorityGeneration()).isEqualTo(3L);
    assertThat(readback.currentSnapshot().sourceVersion()).isEqualTo(3L);
    assertThat(readback.currentSnapshot().outboxSequence()).isEqualTo(2L);
    assertThat(readback.currentSnapshot().latestEvent()).isPresent();
    assertSameEvent(second, readback.currentSnapshot().latestEvent().orElseThrow());
  }

  @Test
  void concurrentHistoricalReadWaitsForTenantFenceAndReturnsConsistentCurrentEvidence()
      throws Exception {
    Fixture fixture = newFixture();
    seed(fixture);
    TenantGenerationAuthorityEvent first =
        fixture.producer().advance(TENANT_ID, UUID.randomUUID(), 1L, 1L);
    UUID secondRequest = UUID.randomUUID();
    CountDownLatch sourceLocked = new CountDownLatch(1);
    CountDownLatch releaseAdvance = new CountDownLatch(1);
    CountDownLatch readStarted = new CountDownLatch(1);
    AtomicInteger holderBackendPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> advance =
          executor.submit(
              () ->
                  transaction(
                      fixture.transaction(),
                      () -> {
                        holderBackendPid.set(
                            Objects.requireNonNull(
                                fixture
                                    .transactionDsl()
                                    .resultQuery("SELECT pg_backend_pid()")
                                    .fetchOne(0, Integer.class),
                                "PostgreSQL did not return the tenant row-lock holder backend PID"));
                        ScopeState current =
                            fixture.generations().read(AuthorityScope.tenant(TENANT_ID));
                        sourceLocked.countDown();
                        await(releaseAdvance);
                        ScopeState advanced = fixture.generations().advance(current, null);
                        appendSeedEvent(
                            fixture,
                            secondRequest,
                            advanced.generation(),
                            advanced.sourceVersion());
                        return null;
                      }));
      assertThat(sourceLocked.await(20, TimeUnit.SECONDS)).isTrue();
      Future<AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback> read =
          executor.submit(
              () -> {
                readStarted.countDown();
                return fixture.producer().readCommittedEvent(TENANT_ID, 1L);
              });
      assertThat(readStarted.await(20, TimeUnit.SECONDS)).isTrue();
      assertThat(
              awaitTenantAuthorityRowBlock(
                  fixture.setupDsl(), holderBackendPid.get(), Duration.ofSeconds(10)))
          .as("the historical reader is blocked by the exact tenant source-row transaction")
          .isTrue();

      releaseAdvance.countDown();
      advance.get(45, TimeUnit.SECONDS);
      AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback readback =
          read.get(45, TimeUnit.SECONDS);
      assertSameEvent(first, readback.requestedEvent());
      assertThat(readback.currentSnapshot().tenantAuthorityGeneration()).isEqualTo(3L);
      assertThat(readback.currentSnapshot().sourceVersion()).isEqualTo(3L);
      assertThat(readback.currentSnapshot().outboxSequence()).isEqualTo(2L);
    } finally {
      releaseAdvance.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentExactRequestSerializesAndCommitsOneTenantEvent() throws Exception {
    Fixture fixture = newFixture();
    seed(fixture);
    UUID requestId = UUID.randomUUID();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<TenantGenerationAuthorityEvent> first =
          executor.submit(() -> concurrentAdvance(fixture, requestId, ready, start));
      Future<TenantGenerationAuthorityEvent> second =
          executor.submit(() -> concurrentAdvance(fixture, requestId, ready, start));
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertSameEvent(first.get(45, TimeUnit.SECONDS), second.get(45, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    assertThat(readTenantState(fixture).generation()).isEqualTo(2L);
    assertThat(readTenantState(fixture).sourceVersion()).isEqualTo(2L);
    assertThat(countEvents(fixture, STREAM_KEY)).isEqualTo(1L);
    assertThat(countTenantStreams(fixture)).isEqualTo(1L);
  }

  @Test
  void unknownTenantStaleBindingAndChangedRetryFailWithoutEnrollmentOrMutation() {
    Fixture fixture = newFixture();
    seed(fixture);
    StoredState baseline = snapshot(fixture);
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(() -> fixture.producer().readCurrent(UNKNOWN_TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("generation is missing");
    assertThatThrownBy(() -> fixture.producer().advance(UNKNOWN_TENANT_ID, requestId, 1L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("generation is missing");
    assertThatThrownBy(() -> fixture.producer().advance(TENANT_ID, requestId, 2L, 2L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("compare-and-advance is stale");
    fixture.producer().advance(TENANT_ID, requestId, 1L, 1L);
    StoredState committed = snapshot(fixture);
    assertThatThrownBy(() -> fixture.producer().advance(TENANT_ID, requestId, 2L, 2L))
        .isInstanceOf(AccountAuthorityOutboxRepository.IdempotencyConflictException.class);

    assertThat(countTenantStreams(fixture)).isEqualTo(1L);
    assertThat(countEvents(fixture, "account:auth-authority:v1:tenant/" + UNKNOWN_TENANT_ID))
        .isZero();
    assertThat(snapshot(fixture)).isEqualTo(committed);
    assertThat(committed).isNotEqualTo(baseline);
  }

  @Test
  void sequenceZeroRequiresOriginalBaselineAndPositiveHistoryRequiresBothCountersToProgress() {
    Fixture pristine = newFixture();
    seed(pristine);
    AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot zero =
        pristine.producer().readCurrent(TENANT_ID);
    assertThat(zero.tenantAuthorityGeneration()).isEqualTo(1L);
    assertThat(zero.sourceVersion()).isEqualTo(1L);
    assertThat(zero.outboxSequence()).isZero();

    Fixture advancedWithoutEvents = newFixture();
    seed(advancedWithoutEvents);
    transaction(
        advancedWithoutEvents.transaction(),
        () -> {
          ScopeState current =
              advancedWithoutEvents.generations().read(AuthorityScope.tenant(TENANT_ID));
          advancedWithoutEvents.generations().advance(current, null);
          return null;
        });
    assertThatThrownBy(() -> advancedWithoutEvents.producer().readCurrent(TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("original positive 1/1");

    Fixture contradictory = newFixture();
    seed(contradictory);
    transaction(
        contradictory.transaction(),
        () -> {
          appendSeedEvent(contradictory, UUID.randomUUID(), 1L, 1L);
          return null;
        });
    assertThatThrownBy(() -> contradictory.producer().readCurrent(TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("advanced together");
  }

  @Test
  void persistedNumericTenantScopeEvidenceIsRejectedWithoutChangingSourceState() {
    Fixture fixture = newFixture();
    seed(fixture);
    UUID requestId = UUID.randomUUID();
    String requestText = requestId.toString();
    String eventId = EVENT_ID_PREFIX + requestText;
    String digest = "sha256:" + "0".repeat(64);
    String payload =
        "{\"schemaVersion\":\""
            + TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION
            + "\",\"eventType\":\""
            + TenantGenerationAuthorityEventV1Codec.EVENT_TYPE
            + "\",\"eventId\":\""
            + eventId
            + "\",\"requestId\":\""
            + requestText
            + "\",\"tenantId\":\""
            + TENANT_ID
            + "\",\"sourceScope\":\"tenant/731\",\"outboxStreamKey\":"
            + "\"account:auth-authority:v1:tenant/731\",\"outboxSequence\":\"1\","
            + "\"tenantAuthorityGeneration\":\"2\",\"sourceVersion\":\"2\","
            + "\"eventDigest\":\""
            + digest
            + "\"}";
    transaction(
        fixture.transaction(),
        () -> {
          ScopeState current = fixture.generations().read(AuthorityScope.tenant(TENANT_ID));
          fixture.generations().advance(current, null);
          fixture
              .outbox()
              .append(
                  STREAM_KEY,
                  requestText,
                  eventId,
                  digest,
                  payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          return null;
        });
    StoredState committed = snapshot(fixture);

    assertThatThrownBy(() -> fixture.producer().readCurrent(TENANT_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("event.sourceScope must equal tenant/" + TENANT_ID);
    assertThat(snapshot(fixture)).isEqualTo(committed);
  }

  @Test
  void historicalReadRejectsMissingRetainedPayloadAndCurrentReadRejectsChangedDigestEvidence() {
    Fixture missing = newFixture();
    seed(missing);
    missing.producer().advance(TENANT_ID, UUID.randomUUID(), 1L, 1L);
    missing.producer().advance(TENANT_ID, UUID.randomUUID(), 2L, 2L);
    AccountAuthorityOutboxRepository hideHistoricalEvent =
        new AccountAuthorityOutboxRepository(missing.transactionDsl()) {
          @Override
          public Optional<Event> findEvent(String streamKey, long outboxSequence) {
            if (STREAM_KEY.equals(streamKey) && outboxSequence == 1L) {
              return Optional.empty();
            }
            return super.findEvent(streamKey, outboxSequence);
          }
        };
    StoredState beforeMissingRead = snapshot(missing);

    assertThatThrownBy(
            () -> missing.producer(hideHistoricalEvent).readCommittedEvent(TENANT_ID, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("retained tenant event is missing");
    assertThat(snapshot(missing)).isEqualTo(beforeMissingRead);

    Fixture missingLatest = newFixture();
    seed(missingLatest);
    missingLatest.producer().advance(TENANT_ID, UUID.randomUUID(), 1L, 1L);
    AccountAuthorityOutboxRepository hideLatestEvent =
        new AccountAuthorityOutboxRepository(missingLatest.transactionDsl()) {
          @Override
          public Optional<Event> findEvent(String streamKey, long outboxSequence) {
            if (STREAM_KEY.equals(streamKey) && outboxSequence == 1L) {
              return Optional.empty();
            }
            return super.findEvent(streamKey, outboxSequence);
          }
        };
    StoredState beforeMissingLatestRead = snapshot(missingLatest);

    assertThatThrownBy(() -> missingLatest.producer(hideLatestEvent).readCurrent(TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("checkpoint has no matching event evidence");
    assertThat(snapshot(missingLatest)).isEqualTo(beforeMissingLatestRead);

    Fixture changedPayload = newFixture();
    seed(changedPayload);
    changedPayload.producer().advance(TENANT_ID, UUID.randomUUID(), 1L, 1L);
    AccountAuthorityOutboxRepository malformedEventReadback =
        new AccountAuthorityOutboxRepository(changedPayload.transactionDsl()) {
          @Override
          public Optional<Event> findEvent(String streamKey, long outboxSequence) {
            Optional<Event> stored = super.findEvent(streamKey, outboxSequence);
            if (STREAM_KEY.equals(streamKey) && outboxSequence == 1L && stored.isPresent()) {
              Event event = stored.orElseThrow();
              return Optional.of(
                  new Event(
                      event.outboxStreamKey(),
                      event.requestId(),
                      event.outboxSequence(),
                      event.eventId(),
                      event.eventDigest(),
                      "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            return stored;
          }
        };
    StoredState beforeMalformedRead = snapshot(changedPayload);

    assertThatThrownBy(() -> changedPayload.producer(malformedEventReadback).readCurrent(TENANT_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("event JSON is malformed");
    assertThat(snapshot(changedPayload)).isEqualTo(beforeMalformedRead);

    Fixture changedScope = newFixture();
    seed(changedScope);
    UUID requestId = UUID.randomUUID();
    changedScope.producer().advance(TENANT_ID, requestId, 1L, 1L);
    TenantGenerationAuthorityEvent otherScopeEvent =
        TenantGenerationAuthorityEventV1Codec.seal(
            Map.of(
                "schemaVersion",
                TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                "eventType",
                TenantGenerationAuthorityEventV1Codec.EVENT_TYPE,
                "eventId",
                EVENT_ID_PREFIX + requestId,
                "requestId",
                requestId.toString(),
                "tenantId",
                UNKNOWN_TENANT_ID.toString(),
                "sourceScope",
                "tenant/" + UNKNOWN_TENANT_ID,
                "outboxStreamKey",
                "account:auth-authority:v1:tenant/" + UNKNOWN_TENANT_ID,
                "outboxSequence",
                "1",
                "tenantAuthorityGeneration",
                "2",
                "sourceVersion",
                "2"));
    AccountAuthorityOutboxRepository otherScopeReadback =
        new AccountAuthorityOutboxRepository(changedScope.transactionDsl()) {
          @Override
          public Optional<Event> findEvent(String streamKey, long outboxSequence) {
            Optional<Event> stored = super.findEvent(streamKey, outboxSequence);
            if (STREAM_KEY.equals(streamKey) && outboxSequence == 1L && stored.isPresent()) {
              Event event = stored.orElseThrow();
              return Optional.of(
                  new Event(
                      event.outboxStreamKey(),
                      event.requestId(),
                      event.outboxSequence(),
                      event.eventId(),
                      otherScopeEvent.eventDigest(),
                      otherScopeEvent.canonicalJsonUtf8()));
            }
            return stored;
          }
        };
    StoredState beforeChangedScopeRead = snapshot(changedScope);

    assertThatThrownBy(() -> changedScope.producer(otherScopeReadback).readCurrent(TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stored event payload or database columns contradict");
    assertThat(snapshot(changedScope)).isEqualTo(beforeChangedScopeRead);

    Fixture changedCounters = newFixture();
    seed(changedCounters);
    changedCounters.producer().advance(TENANT_ID, UUID.randomUUID(), 1L, 1L);
    TenantGenerationAuthorityEvent actualLatest =
        changedCounters.producer().advance(TENANT_ID, UUID.randomUUID(), 2L, 2L);
    TenantGenerationAuthorityEvent staleCountersEvent =
        TenantGenerationAuthorityEventV1Codec.seal(
            Map.of(
                "schemaVersion",
                TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                "eventType",
                TenantGenerationAuthorityEventV1Codec.EVENT_TYPE,
                "eventId",
                actualLatest.eventId(),
                "requestId",
                actualLatest.requestId(),
                "tenantId",
                TENANT_ID.toString(),
                "sourceScope",
                SOURCE_SCOPE,
                "outboxStreamKey",
                STREAM_KEY,
                "outboxSequence",
                actualLatest.outboxSequence(),
                "tenantAuthorityGeneration",
                "2",
                "sourceVersion",
                "2"));
    AccountAuthorityOutboxRepository staleCounterReadback =
        new AccountAuthorityOutboxRepository(changedCounters.transactionDsl()) {
          @Override
          public Optional<Event> findEvent(String streamKey, long outboxSequence) {
            Optional<Event> stored = super.findEvent(streamKey, outboxSequence);
            if (STREAM_KEY.equals(streamKey) && outboxSequence == 2L && stored.isPresent()) {
              Event event = stored.orElseThrow();
              return Optional.of(
                  new Event(
                      event.outboxStreamKey(),
                      event.requestId(),
                      event.outboxSequence(),
                      event.eventId(),
                      staleCountersEvent.eventDigest(),
                      staleCountersEvent.canonicalJsonUtf8()));
            }
            return stored;
          }
        };
    StoredState beforeChangedCounterRead = snapshot(changedCounters);

    assertThatThrownBy(() -> changedCounters.producer(staleCounterReadback).readCurrent(TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("counters do not match their uninterrupted outbox sequence");
    assertThat(snapshot(changedCounters)).isEqualTo(beforeChangedCounterRead);
  }

  @Test
  void appendFailureRollsBackTenantSourceAndOutboxTogether() {
    Fixture fixture = newFixture();
    seed(fixture);
    ScopeState accountBefore = readAccountState(fixture);
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "ADD CONSTRAINT reject_tenant_source_event "
                + "CHECK (event_id NOT LIKE 'account-tenant-generation-event-v1:%')");

    assertThatThrownBy(() -> fixture.producer().advance(TENANT_ID, UUID.randomUUID(), 1L, 1L))
        .isInstanceOf(RuntimeException.class);

    assertTenantCountersAndOutbox(fixture, 1L, 1L, 0L, 0L);
    assertThat(readAccountState(fixture)).isEqualTo(accountBefore);
  }

  @Test
  void transactionLocalReadbackFailureRollsBackTenantAdvance() {
    Fixture fixture = newFixture();
    seed(fixture);
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
    AccountTenantAuthorityEventProducer producer = fixture.producer(hideSecondRequestRead);

    assertThatThrownBy(() -> producer.advance(TENANT_ID, UUID.randomUUID(), 1L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("request event readback is missing");

    assertTenantCountersAndOutbox(fixture, 1L, 1L, 0L, 0L);
  }

  @Test
  void unavailablePostCommitReadLeavesDurableEventAndExactRetryRecoversIt() {
    Fixture fixture = newFixture();
    seed(fixture);
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
    AccountTenantAuthorityEventProducer firstAttempt = fixture.producer(hidePostCommitRead);

    assertThatThrownBy(() -> firstAttempt.advance(TENANT_ID, requestId, 1L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Post-commit tenant authority request event is missing");

    assertTenantCountersAndOutbox(fixture, 2L, 2L, 1L, 1L);
    TenantGenerationAuthorityEvent recovered =
        fixture.producer().advance(TENANT_ID, requestId, 1L, 1L);
    assertThat(recovered.eventId()).isEqualTo(EVENT_ID_PREFIX + requestId);
    assertThat(recovered.outboxSequence()).isEqualTo("1");
    assertTenantCountersAndOutbox(fixture, 2L, 2L, 1L, 1L);
  }

  private TenantGenerationAuthorityEvent concurrentAdvance(
      Fixture fixture, UUID requestId, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    return fixture.producer().advance(TENANT_ID, requestId, 1L, 1L);
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
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Record account =
        setupDsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash) "
                + "VALUES (?, ?, ?) RETURNING account_uuid",
            "tenant-proof-" + suffix,
            "tenant-proof-" + suffix + "@example.test",
            "integration-proof-hash");
    if (account == null || account.get("account_uuid", UUID.class) == null) {
      throw new IllegalStateException("Account authority-fence fixture UUID was not persisted");
    }
    UUID accountUuid = account.get("account_uuid", UUID.class);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(transactionDsl);
    FreshTenantIdentityAssociationRepository associations =
        new FreshTenantIdentityAssociationRepository(transactionDsl, TEST_NAMESPACE);
    return new Fixture(
        setupDsl,
        transactionDsl,
        transactionManager,
        transaction,
        generations,
        outbox,
        associations,
        accountUuid);
  }

  private void seed(Fixture fixture) {
    FreshTenantCreationEvidence association =
        tenantEvidence(
            UUID.randomUUID(),
            UUID.randomUUID(),
            TENANT_ID,
            731L,
            "tn-" + UUID.randomUUID().toString().replace("-", ""));
    transaction(
        fixture.transaction(),
        () -> {
          fixture.associations().importVerified(association);
          fixture.generations().initializeTenantIfAbsent(TENANT_ID);
          fixture.generations().initialize(AuthorityScope.account(fixture.accountUuid()));
          return null;
        });
  }

  private FreshTenantCreationEvidence tenantEvidence(
      UUID requestId,
      UUID operationId,
      UUID tenantId,
      long sourceGameRowId,
      String sourceGameTenantKey) {
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            REQUEST_DIGEST,
            tenantId,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        REQUEST_DIGEST,
        tenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private void appendSeedEvent(
      Fixture fixture, UUID requestId, long generation, long sourceVersion) {
    String requestText = requestId.toString();
    String eventId = EVENT_ID_PREFIX + requestText;
    fixture
        .outbox()
        .append(
            STREAM_KEY,
            requestText,
            sequence -> {
              TenantGenerationAuthorityEvent event =
                  TenantGenerationAuthorityEventV1Codec.seal(
                      Map.of(
                          "schemaVersion",
                          TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                          "eventType",
                          TenantGenerationAuthorityEventV1Codec.EVENT_TYPE,
                          "eventId",
                          eventId,
                          "requestId",
                          requestText,
                          "tenantId",
                          TENANT_ID.toString(),
                          "sourceScope",
                          SOURCE_SCOPE,
                          "outboxStreamKey",
                          STREAM_KEY,
                          "outboxSequence",
                          Long.toString(sequence),
                          "tenantAuthorityGeneration",
                          Long.toString(generation),
                          "sourceVersion",
                          Long.toString(sourceVersion)));
              return new AccountAuthorityOutboxRepository.EventEvidence(
                  event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
            });
  }

  private void assertTenantCountersAndOutbox(
      Fixture fixture,
      long generation,
      long sourceVersion,
      long expectedEventCount,
      long expectedStreamCount) {
    ScopeState tenant = readTenantState(fixture);
    assertThat(tenant.generation()).isEqualTo(generation);
    assertThat(tenant.sourceVersion()).isEqualTo(sourceVersion);
    assertThat(countEvents(fixture, STREAM_KEY)).isEqualTo(expectedEventCount);
    assertThat(countTenantStreams(fixture)).isEqualTo(expectedStreamCount);
  }

  private ScopeState readTenantState(Fixture fixture) {
    return transaction(
        fixture.transaction(), () -> fixture.generations().read(AuthorityScope.tenant(TENANT_ID)));
  }

  private ScopeState readAccountState(Fixture fixture) {
    return transaction(
        fixture.transaction(),
        () -> fixture.generations().read(AuthorityScope.account(fixture.accountUuid())));
  }

  private StoredState snapshot(Fixture fixture) {
    ScopeState tenant = readTenantState(fixture);
    ScopeState account = readAccountState(fixture);
    return new StoredState(
        tenant.generation(),
        tenant.sourceVersion(),
        countEvents(fixture, STREAM_KEY),
        countTenantStreams(fixture),
        account);
  }

  private long countEvents(Fixture fixture, String streamKey) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class),
        "Tenant event count readback is missing");
  }

  private long countTenantStreams(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_streams "
                    + "WHERE outbox_stream_key LIKE 'account:auth-authority:v1:tenant/%'")
            .fetchOne(0, Long.class),
        "Tenant stream count readback is missing");
  }

  private boolean awaitTenantAuthorityRowBlock(
      DSLContext observerDsl, int holderBackendPid, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Boolean blocked =
          observerDsl
              .resultQuery(
                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity waiting "
                      + "WHERE waiting.wait_event_type = 'Lock' "
                      + "AND waiting.query ILIKE '%account_authority_generations%' "
                      + "AND waiting.query ILIKE '%tenant_uuid IS NOT DISTINCT FROM%' "
                      + "AND ? = ANY(pg_blocking_pids(waiting.pid)))",
                  holderBackendPid)
              .fetchOne(0, Boolean.class);
      if (Boolean.TRUE.equals(blocked)) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private void assertSameEvent(
      TenantGenerationAuthorityEvent expected, TenantGenerationAuthorityEvent actual) {
    assertThat(actual.schemaVersion()).isEqualTo(expected.schemaVersion());
    assertThat(actual.eventType()).isEqualTo(expected.eventType());
    assertThat(actual.eventId()).isEqualTo(expected.eventId());
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.tenantId()).isEqualTo(expected.tenantId());
    assertThat(actual.sourceScope()).isEqualTo(expected.sourceScope());
    assertThat(actual.outboxStreamKey()).isEqualTo(expected.outboxStreamKey());
    assertThat(actual.outboxSequence()).isEqualTo(expected.outboxSequence());
    assertThat(actual.tenantAuthorityGeneration()).isEqualTo(expected.tenantAuthorityGeneration());
    assertThat(actual.sourceVersion()).isEqualTo(expected.sourceVersion());
    assertThat(actual.eventDigest()).isEqualTo(expected.eventDigest());
    assertThat(actual.canonicalJson()).isEqualTo(expected.canonicalJson());
    assertThat(actual.canonicalJsonUtf8()).containsExactly(expected.canonicalJsonUtf8());
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent tenant authority barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Concurrent tenant authority proof was interrupted", interrupted);
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
      AccountAuthorityOutboxRepository outbox,
      FreshTenantIdentityAssociationRepository associations,
      UUID accountUuid) {
    private AccountTenantAuthorityEventProducer producer() {
      return producer(outbox);
    }

    private AccountTenantAuthorityEventProducer producer(
        AccountAuthorityOutboxRepository override) {
      return new AccountTenantAuthorityEventProducer(
          generations, override, transactionDsl, transactionManager);
    }
  }

  private record StoredState(
      long generation,
      long sourceVersion,
      long eventCount,
      long streamCount,
      ScopeState accountAuthority) {}
}
