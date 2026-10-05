package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.IssuerTenantDraftSourceChangeRepository.PendingSourceChangeException;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
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
  void issuerCommitOrderWaitsForEveryOriginalOwnerOutcomeBeforeAtomicSourceCompletion() {
    for (List<Outcome> outcomes :
        List.of(
            List.of(Outcome.COMMITTED, Outcome.COMMITTED),
            List.of(Outcome.COMMITTED, Outcome.DEFINITIVELY_ABORTED),
            List.of(Outcome.DEFINITIVELY_ABORTED, Outcome.DEFINITIVELY_ABORTED))) {
      Fixture fixture = newFixture();
      seedIssuer(fixture);
      DraftAuthorizationFenceBinding binding =
          issuerBinding(ISSUER_ID, "1", "1", "0", new byte[] {1});
      DraftAuthorizationFenceRepository fences =
          new DraftAuthorizationFenceRepository(fixture.transactionDsl());
      transaction(
          fixture.transaction(),
          () -> {
            fences.reserve(binding);
            fences.claimCommitOrder(binding);
            return null;
          });
      UUID request = UUID.randomUUID();
      assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, request, 1, 1))
          .isInstanceOf(PendingSourceChangeException.class);
      SourceChange original = pendingIssuerChange(fixture, ISSUER_ID, request);
      assertThat(original.changeId()).isNotEqualTo(request);
      assertThat(original.sources().getFirst().kind()).isEqualTo(SourceKind.ISSUER);
      assertThat(original.sources().getFirst().scopeId()).isEqualTo(ISSUER_ID);
      recordOwner(fixture, binding, Owner.WORLD, outcomes.get(0));
      assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, request, 1, 1))
          .isInstanceOf(PendingSourceChangeException.class)
          .satisfies(
              error ->
                  assertThat(((PendingSourceChangeException) error).sourceChangeId())
                      .isEqualTo(original.changeId()));
      assertThat(readAuthority(fixture).generation()).isEqualTo(1);
      assertThat(countEvents(fixture, STREAM_KEY)).isZero();
      recordOwner(fixture, binding, Owner.GAME_DESIGN, outcomes.get(1));
      IssuerGenerationAuthorityEvent event = fixture.producer().advance(ISSUER_ID, request, 1, 1);
      assertThat(event.issuerAuthGeneration()).isEqualTo("2");
      assertThat(
              transaction(fixture.transaction(), () -> fences.readSourceChange(original).status()))
          .isEqualTo("SOURCE_COMMITTED");
      assertThat(
              Objects.requireNonNull(
                      fixture
                          .setupDsl()
                          .fetchOne(
                              "SELECT status FROM account_issuer_tenant_draft_source_changes WHERE request_id = ?",
                              request),
                      "Committed issuer source journal row is missing")
                  .get(0, String.class))
          .isEqualTo("SOURCE_COMMITTED");
      assertThat(
              transaction(
                  fixture.transaction(),
                  () -> fences.readOwnerResult(binding, Owner.WORLD).orElseThrow().outcome()))
          .isEqualTo(outcomes.get(0));
    }
  }

  @Test
  void issuerRevocationWaitsForBothExactAbortsAndPendingRequestAndCaptureAreImmutable() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    DraftAuthorizationFenceBinding binding =
        issuerBinding(ISSUER_ID, "1", "1", "0", new byte[] {1});
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(fixture.transactionDsl());
    transaction(fixture.transaction(), () -> fences.reserve(binding));
    UUID request = UUID.randomUUID();
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, request, 1, 1))
        .isInstanceOf(PendingSourceChangeException.class);
    SourceChange original = pendingIssuerChange(fixture, ISSUER_ID, request);
    byte[] stored = original.canonicalBytes();
    assertThat(transaction(fixture.transaction(), () -> fences.read(binding).ordering()))
        .isEqualTo(DraftAuthorizationFenceRepository.Ordering.REVOKE_ORDER);
    assertThatThrownBy(
            () -> transaction(fixture.transaction(), () -> fences.claimCommitOrder(binding)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, request, 2, 1))
        .isInstanceOf(AccountAuthorityOutboxRepository.IdempotencyConflictException.class);
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, request, 1, 2))
        .isInstanceOf(AccountAuthorityOutboxRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_issuer_tenant_draft_source_changes SET expected_generation = 2 WHERE request_id = ?",
                        request))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(pendingIssuerChange(fixture, ISSUER_ID, request).canonicalBytes())
        .containsExactly(stored);
    recordOwner(fixture, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, request, 1, 1))
        .isInstanceOf(PendingSourceChangeException.class);
    recordOwner(fixture, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    assertThatThrownBy(
            () ->
                transaction(
                    fixture.transaction(),
                    () -> {
                      fences.markSourceCommitted(original);
                      return null;
                    }))
        .satisfies(
            failure -> {
              PSQLException postgresFailure = rootPostgresCause(failure);
              assertThat(postgresFailure.getSQLState()).isEqualTo("23514");
              assertThat((Throwable) postgresFailure)
                  .hasMessageContaining(
                      "Issuer/tenant journal and V57 source transition must commit atomically");
            });
    assertThat(fixture.producer().advance(ISSUER_ID, request, 1, 1).issuerAuthGeneration())
        .isEqualTo("2");
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_issuer_tenant_draft_source_changes WHERE request_id = ?",
                        request))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  @Test
  void concurrentDistinctIssuerRequestsCannotLeaveCompetingPendingCaptures() throws Exception {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    DraftAuthorizationFenceBinding binding =
        issuerBinding(ISSUER_ID, "1", "1", "0", new byte[] {1});
    transaction(
        fixture.transaction(),
        () -> new DraftAuthorizationFenceRepository(fixture.transactionDsl()).reserve(binding));
    UUID original = UUID.randomUUID();
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, original, 1, 1))
        .isInstanceOf(PendingSourceChangeException.class);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> first =
          executor.submit(
              () -> {
                await(start);
                assertThatThrownBy(
                        () -> fixture.producer().advance(ISSUER_ID, UUID.randomUUID(), 1, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(PendingSourceChangeException.class);
              });
      Future<?> second =
          executor.submit(
              () -> {
                await(start);
                assertThatThrownBy(
                        () -> fixture.producer().advance(ISSUER_ID, UUID.randomUUID(), 1, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(PendingSourceChangeException.class);
              });
      start.countDown();
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    }
    assertThat(
            fixture.setupDsl().fetchCount(DSL.table("account_issuer_tenant_draft_source_changes")))
        .isEqualTo(1);
    assertThat(
            fixture.setupDsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isEqualTo(1);
    recordOwner(fixture, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    recordOwner(fixture, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    fixture.producer().advance(ISSUER_ID, original, 1, 1);
    assertThat(
            fixture.producer().advance(ISSUER_ID, UUID.randomUUID(), 2, 2).issuerAuthGeneration())
        .isEqualTo("3");
  }

  @Test
  void sameRequestUuidAcrossExactIssuersHasDistinctPersistedSourceChangeIdentities() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    String otherIssuer = "https://other.example.test/issuer";
    transaction(
        fixture.transaction(),
        () -> {
          fixture.generations().initializeIssuerIfAbsent(otherIssuer);
          return null;
        });
    UUID request = UUID.randomUUID();
    IssuerGenerationAuthorityEvent first = fixture.producer().advance(ISSUER_ID, request, 1, 1);
    AccountIssuerAuthorityEventProducer other =
        new AccountIssuerAuthorityEventProducer(
            otherIssuer,
            fixture.generations(),
            fixture.outbox(),
            fixture.transactionDsl(),
            fixture.transactionManager());
    IssuerGenerationAuthorityEvent second = other.advance(otherIssuer, request, 1, 1);
    assertThat(first.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(second.issuerId()).isEqualTo(otherIssuer);
    assertThat(
            fixture
                .setupDsl()
                .fetch(
                    "SELECT source_change_id FROM account_issuer_tenant_draft_source_changes WHERE request_id = ?",
                    request)
                .getValues(0, UUID.class))
        .hasSize(2)
        .doesNotHaveDuplicates()
        .doesNotContain(request);
  }

  @Test
  void failedIssuerAdvanceRetainsOriginalPendingCaptureForExactRetry() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    DraftAuthorizationFenceBinding binding =
        issuerBinding(ISSUER_ID, "1", "1", "0", new byte[] {1});
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(fixture.transactionDsl());
    transaction(fixture.transaction(), () -> fences.reserve(binding));
    UUID request = UUID.randomUUID();
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, request, 1, 1))
        .isInstanceOf(PendingSourceChangeException.class);
    SourceChange original = pendingIssuerChange(fixture, ISSUER_ID, request);
    recordOwner(fixture, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    recordOwner(fixture, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events ADD CONSTRAINT reject_pending_issuer_retry CHECK (event_id NOT LIKE 'account-issuer-authority-event-v1:%')");
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, request, 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(pendingIssuerChange(fixture, ISSUER_ID, request).canonicalBytes())
        .containsExactly(original.canonicalBytes());
    assertThat(transaction(fixture.transaction(), () -> fences.readSourceChange(original).status()))
        .isEqualTo("WAITING");
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .setupDsl()
                        .fetchOne(
                            "SELECT status FROM account_issuer_tenant_draft_source_changes WHERE request_id = ?",
                            request),
                    "Pending issuer source journal row is missing after rollback")
                .get(0, String.class))
        .isEqualTo("WAITING");
    assertThat(readAuthority(fixture).generation()).isEqualTo(1);
    assertThat(countEvents(fixture, STREAM_KEY)).isZero();
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events DROP CONSTRAINT reject_pending_issuer_retry");
    assertThat(fixture.producer().advance(ISSUER_ID, request, 1, 1).issuerAuthGeneration())
        .isEqualTo("2");
  }

  @Test
  void issuerPendingCaptureRetainsIndependentCountersCheckpointAndExactPositiveEventBytes() {
    Fixture fixture = newFixture();
    fixture
        .setupDsl()
        .execute(
            "INSERT INTO account_authority_generations (scope_kind, issuer_id, generation, source_version) VALUES ('ISSUER', ?, 3, 7)",
            ISSUER_ID);
    transaction(
        fixture.transaction(),
        () -> {
          fixture
              .generations()
              .advance(fixture.generations().read(AuthorityScope.issuer(ISSUER_ID)), null);
          appendSeedEvent(fixture, UUID.randomUUID(), 4, 8);
          return null;
        });
    Event latest =
        transaction(
            fixture.transaction(), () -> fixture.outbox().findEvent(STREAM_KEY, 1L).orElseThrow());
    DraftAuthorizationFenceBinding binding =
        issuerBinding(ISSUER_ID, "4", "8", "1", latest.payload());
    transaction(
        fixture.transaction(),
        () -> new DraftAuthorizationFenceRepository(fixture.transactionDsl()).reserve(binding));
    UUID request = UUID.randomUUID();
    assertThatThrownBy(() -> fixture.producer().advance(ISSUER_ID, request, 4, 8))
        .isInstanceOf(PendingSourceChangeException.class);
    SourceEvidence captured = pendingIssuerChange(fixture, ISSUER_ID, request).sources().getFirst();
    assertThat(captured.generation()).isEqualTo("4");
    assertThat(captured.sourceVersion()).isEqualTo("8");
    assertThat(captured.checkpointSequence()).isEqualTo("1");
    assertThat(captured.evidence()).containsExactly(latest.payload());
    recordOwner(fixture, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    recordOwner(fixture, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    IssuerGenerationAuthorityEvent advanced = fixture.producer().advance(ISSUER_ID, request, 4, 8);
    assertThat(advanced.issuerAuthGeneration()).isEqualTo("5");
    assertThat(advanced.sourceVersion()).isEqualTo("9");
    assertThat(advanced.outboxSequence()).isEqualTo("2");
  }

  @Test
  void v59PreservesV58SourceHistoryAndExactKeysAndSupportsFullMultibyteIssuerParticipation() {
    Fixture fixture = newFixture("58");
    seedIssuer(fixture);
    UUID oldRequest = UUID.randomUUID();
    transaction(
        fixture.transaction(),
        () -> {
          fixture
              .generations()
              .advance(fixture.generations().read(AuthorityScope.issuer(ISSUER_ID)), null);
          appendSeedEvent(fixture, oldRequest, 2, 2);
          return null;
        });
    DraftAuthorizationFenceBinding oldBinding =
        issuerBinding(ISSUER_ID, "2", "2", "1", new byte[] {7});
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(fixture.transactionDsl());
    transaction(fixture.transaction(), () -> fences.reserve(oldBinding));
    SourceChange oldChange =
        new SourceChange(UUID.randomUUID(), oldBinding.sources(), new byte[] {9});
    transaction(fixture.transaction(), () -> fences.requestSourceChange(oldChange));
    String oldEvents =
        fixture
            .setupDsl()
            .fetch(
                "SELECT * FROM account_authority_outbox_events ORDER BY outbox_stream_key, outbox_sequence")
            .formatJSON();
    String oldSources =
        fixture
            .setupDsl()
            .fetch(
                "SELECT * FROM account_draft_authorization_sources ORDER BY source_key, operation_id")
            .formatJSON();
    String oldLocks =
        fixture
            .setupDsl()
            .fetch("SELECT * FROM account_draft_authorization_source_locks ORDER BY source_key")
            .formatJSON();
    String oldChanges =
        fixture
            .setupDsl()
            .fetch("SELECT * FROM account_draft_authorization_source_changes ORDER BY change_id")
            .formatJSON();
    String oldChangedScopes =
        fixture
            .setupDsl()
            .fetch(
                "SELECT * FROM account_draft_authorization_changed_scopes ORDER BY source_key, change_id")
            .formatJSON();
    DriverManagerDataSource dataSource =
        (DriverManagerDataSource)
            ((DataSourceTransactionManager) fixture.transactionManager()).getDataSource();
    String schema =
        Objects.requireNonNull(
            Objects.requireNonNull(
                    fixture.setupDsl().fetchOne("SELECT current_schema()"),
                    "Issuer migration fixture schema row is missing")
                .get(0, String.class),
            "Issuer migration fixture schema is missing");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    assertThat(
            fixture
                .setupDsl()
                .fetch(
                    "SELECT * FROM account_authority_outbox_events ORDER BY outbox_stream_key, outbox_sequence")
                .formatJSON())
        .isEqualTo(oldEvents);
    assertThat(
            fixture
                .setupDsl()
                .fetch(
                    "SELECT * FROM account_draft_authorization_sources ORDER BY source_key, operation_id")
                .formatJSON())
        .isEqualTo(oldSources);
    assertThat(
            fixture
                .setupDsl()
                .fetch("SELECT * FROM account_draft_authorization_source_locks ORDER BY source_key")
                .formatJSON())
        .isEqualTo(oldLocks);
    assertThat(
            fixture
                .setupDsl()
                .fetch(
                    "SELECT * FROM account_draft_authorization_source_changes ORDER BY change_id")
                .formatJSON())
        .isEqualTo(oldChanges);
    assertThat(
            fixture
                .setupDsl()
                .fetch(
                    "SELECT * FROM account_draft_authorization_changed_scopes ORDER BY source_key, change_id")
                .formatJSON())
        .isEqualTo(oldChangedScopes);
    assertThat(
            transaction(fixture.transaction(), () -> fences.readSourceChange(oldChange).binding()))
        .containsExactly(oldChange.canonicalBytes());
    assertThat(fixture.producer().advance(ISSUER_ID, oldRequest, 1, 1).requestId())
        .isEqualTo(oldRequest.toString());
    assertThat(
            fixture.setupDsl().fetchCount(DSL.table("account_issuer_tenant_draft_source_changes")))
        .isZero();
    String longIssuer = "界".repeat(512);
    transaction(
        fixture.transaction(),
        () -> {
          fixture.generations().initializeIssuerIfAbsent(longIssuer);
          return null;
        });
    AccountIssuerAuthorityEventProducer producer =
        new AccountIssuerAuthorityEventProducer(
            longIssuer,
            fixture.generations(),
            fixture.outbox(),
            fixture.transactionDsl(),
            fixture.transactionManager());
    DraftAuthorizationFenceBinding longBinding =
        issuerBinding(longIssuer, "1", "1", "0", new byte[] {1});
    transaction(fixture.transaction(), () -> fences.reserve(longBinding));
    UUID request = UUID.randomUUID();
    assertThatThrownBy(() -> producer.advance(longIssuer, request, 1, 1))
        .isInstanceOf(PendingSourceChangeException.class);
    assertThat(pendingIssuerChange(fixture, longIssuer, request).sources().getFirst().scopeId())
        .isEqualTo(longIssuer);
    recordOwner(fixture, longBinding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    recordOwner(fixture, longBinding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThat(producer.advance(longIssuer, request, 1, 1).issuerId()).isEqualTo(longIssuer);
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .setupDsl()
                        .fetchOne(
                            "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ?",
                            "ISSUER:" + longIssuer),
                    "Exact multibyte issuer participation key row is missing")
                .get(0, String.class))
        .isEqualTo("ISSUER:" + longIssuer);
  }

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
  void exactHistoricalReadReturnsTheRequestedEventAndCurrentSourceCheckpoint() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    UUID firstRequest = UUID.randomUUID();
    IssuerGenerationAuthorityEvent first =
        fixture.producer().advance(ISSUER_ID, firstRequest, 1L, 1L);
    IssuerGenerationAuthorityEvent second =
        fixture.producer().advance(ISSUER_ID, UUID.randomUUID(), 2L, 2L);

    AccountIssuerAuthorityEventProducer.IssuerAuthorityEventReadback readback =
        fixture.producer().readCommittedEvent(ISSUER_ID, 1L);

    assertSameEvent(first, readback.requestedEvent());
    assertThat(readback.currentSnapshot().issuerAuthGeneration()).isEqualTo(3L);
    assertThat(readback.currentSnapshot().sourceVersion()).isEqualTo(3L);
    assertThat(readback.currentSnapshot().outboxSequence()).isEqualTo(2L);
    assertThat(readback.currentSnapshot().latestEvent()).isPresent();
    assertSameEvent(second, readback.currentSnapshot().latestEvent().orElseThrow());
  }

  @Test
  void historicalReadDenialsDoNotMutateAndMissingRetainedEventFailsClosed() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    fixture.producer().advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L);
    fixture.producer().advance(ISSUER_ID, UUID.randomUUID(), 2L, 2L);
    StoredState before = snapshot(fixture);

    assertThatThrownBy(
            () -> fixture.producer().readCommittedEvent("https://other.example.test/issuer", 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.producer().readCommittedEvent(ISSUER_ID, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.producer().readCommittedEvent(ISSUER_ID, 3L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ahead of the current checkpoint");
    assertThat(snapshot(fixture)).isEqualTo(before);

    Fixture missingHistorical = newFixture();
    seedIssuer(missingHistorical);
    missingHistorical.producer().advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L);
    missingHistorical.producer().advance(ISSUER_ID, UUID.randomUUID(), 2L, 2L);
    missingHistorical
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "DISABLE TRIGGER account_authority_outbox_event_update");
    missingHistorical
        .setupDsl()
        .execute(
            "DELETE FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
            STREAM_KEY);
    missingHistorical
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "ENABLE TRIGGER account_authority_outbox_event_update");
    StoredState afterFixtureCorruption = snapshot(missingHistorical);

    assertThatThrownBy(() -> missingHistorical.producer().readCommittedEvent(ISSUER_ID, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("retained issuer event is missing");
    assertThat(snapshot(missingHistorical)).isEqualTo(afterFixtureCorruption);

    Fixture malformedHistorical = newFixture();
    seedIssuer(malformedHistorical);
    malformedHistorical.producer().advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L);
    malformedHistorical.producer().advance(ISSUER_ID, UUID.randomUUID(), 2L, 2L);
    malformedHistorical
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "DISABLE TRIGGER account_authority_outbox_event_update");
    malformedHistorical
        .setupDsl()
        .execute(
            "UPDATE account_authority_outbox_events SET payload = ? "
                + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
            "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            STREAM_KEY);
    malformedHistorical
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "ENABLE TRIGGER account_authority_outbox_event_update");
    StoredState afterMalformedFixture = snapshot(malformedHistorical);

    assertThatThrownBy(() -> malformedHistorical.producer().readCommittedEvent(ISSUER_ID, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("event JSON is malformed");
    assertThat(snapshot(malformedHistorical)).isEqualTo(afterMalformedFixture);
  }

  @Test
  void concurrentHistoricalReadWaitsForIssuerRowFenceAndReturnsCommittedCurrentState()
      throws Exception {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    IssuerGenerationAuthorityEvent first =
        fixture.producer().advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L);
    UUID secondRequest = UUID.randomUUID();
    CountDownLatch sourceLocked = new CountDownLatch(1);
    CountDownLatch releaseAdvance = new CountDownLatch(1);
    CountDownLatch readStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> advance =
          executor.submit(
              () ->
                  transaction(
                      fixture.transaction(),
                      () -> {
                        ScopeState current =
                            fixture.generations().read(AuthorityScope.issuer(ISSUER_ID));
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
      Future<AccountIssuerAuthorityEventProducer.IssuerAuthorityEventReadback> read =
          executor.submit(
              () -> {
                readStarted.countDown();
                return fixture.producer().readCommittedEvent(ISSUER_ID, 1L);
              });
      assertThat(readStarted.await(20, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> read.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseAdvance.countDown();
      advance.get(45, TimeUnit.SECONDS);
      AccountIssuerAuthorityEventProducer.IssuerAuthorityEventReadback readback =
          read.get(45, TimeUnit.SECONDS);
      assertSameEvent(first, readback.requestedEvent());
      assertThat(readback.currentSnapshot().issuerAuthGeneration()).isEqualTo(3L);
      assertThat(readback.currentSnapshot().sourceVersion()).isEqualTo(3L);
      assertThat(readback.currentSnapshot().outboxSequence()).isEqualTo(2L);
    } finally {
      releaseAdvance.countDown();
      executor.shutdownNow();
    }
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
    assertThatThrownBy(() -> fixture.producer().readCommittedEvent(ISSUER_ID, 1L))
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
    assertThat(
            fixture.setupDsl().fetchCount(DSL.table("account_issuer_tenant_draft_source_changes")))
        .isZero();
    assertThat(
            fixture.setupDsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isZero();
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
    return newFixture("latest");
  }

  private Fixture newFixture(String target) {
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
        .target(target)
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

  private SourceChange pendingIssuerChange(Fixture fixture, String issuer, UUID request) {
    byte[] binding =
        Objects.requireNonNull(
            Objects.requireNonNull(
                    fixture
                        .setupDsl()
                        .fetchOne(
                            "SELECT source_change_binding FROM account_issuer_tenant_draft_source_changes WHERE source_kind = 'ISSUER' AND scope_id = ? AND request_id = ?",
                            issuer,
                            request),
                    "Pending issuer source journal row is missing")
                .get(0, byte[].class),
            "Pending issuer source capture is missing");
    return SourceChange.fromStored(binding);
  }

  private DraftAuthorizationFenceBinding issuerBinding(
      String issuer, String generation, String version, String sequence, byte[] evidence) {
    UUID tenant = UUID.randomUUID();
    DraftCommitBinding complete =
        DraftCommitBinding.create(
            new TargetProof(
                tenant, UUID.randomUUID(), 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base",
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "world-payload")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "1")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        tenant,
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "1",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.ISSUER,
                issuer,
                generation,
                version,
                "account:auth-authority:v1:issuer/" + issuer,
                sequence,
                evidence)));
  }

  private void recordOwner(
      Fixture fixture, DraftAuthorizationFenceBinding binding, Owner owner, Outcome outcome) {
    transaction(
        fixture.transaction(),
        () -> {
          new DraftAuthorizationFenceRepository(fixture.transactionDsl())
              .recordOwnerReadback(
                  binding,
                  new OwnerReadback(
                      owner,
                      outcome,
                      binding.operationId(),
                      binding.commitId(),
                      binding.fenceId(),
                      binding.inputDigest(),
                      binding.canonicalBytes(),
                      new byte[] {1}));
          return null;
        });
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

  private static PSQLException rootPostgresCause(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(PSQLException.class);
    return (PSQLException) cause;
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
