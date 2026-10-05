package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
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
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository.LogoutAllReceipt;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer.LogoutAllResult;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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
class AccountLogoutAllAuthorityProducerPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "logout_src";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String EVENT_ID_PREFIX = "account-logout-all-event-v1:";
  private static final long MAX_COUNTER = Long.MAX_VALUE;
  private static final String TOKEN_PROFILE = "control-ui";
  private static final UUID UNKNOWN_ACCOUNT_UUID =
      UUID.fromString("90000000-0000-4000-8000-000000000001");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void commitsAccountGenerationFenceClosedEventCheckpointAndImmutableReceiptTogether() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);

    LogoutAllResult result =
        commit(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());

    ScopeState current = authority(fixture, seed);
    LogoutAllReceipt receipt = logoutReceipt(fixture, requestId);
    var event =
        transaction(
            fixture.transaction(),
            () -> fixture.outbox().findEvent(streamKey(seed.accountUuid()), 1L).orElseThrow());
    var checkpoint =
        transaction(
            fixture.transaction(),
            () -> fixture.outbox().readCheckpoint(streamKey(seed.accountUuid())).orElseThrow());
    var decoded =
        AccountLogoutAllAuthorityEventV1Codec.verify(
            new String(event.payload(), StandardCharsets.UTF_8));

    assertThat(result).isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);
    assertThat(current.generation()).isEqualTo(2L);
    assertThat(current.sourceVersion()).isEqualTo(2L);
    assertThat(current.issuanceFence().value()).isEqualTo(2L);
    assertThat(current.issuanceFence().sourceVersion()).isEqualTo(2L);
    assertThat(receipt.accountId()).isEqualTo(seed.accountId());
    assertThat(receipt.accountUuid()).isEqualTo(seed.accountUuid());
    assertThat(receipt.requestId()).isEqualTo(requestId);
    assertThat(receipt.requestDigestVersion()).isEqualTo(1);
    assertThat(receipt.requestDigest()).isEqualTo(requestDigest);
    assertThat(receipt.presentedTokenHash()).isEqualTo(tokenHash);
    assertThat(receipt.tokenProfile()).isEqualTo(TOKEN_PROFILE);
    assertThat(receipt.operationKind()).isEqualTo("ACCOUNT_LOGOUT_ALL");
    assertThat(receipt.lifecycleResult()).isEqualTo("LOGOUT_ALL_COMMITTED");
    assertThat(receipt.accountAuthorityGeneration()).isEqualTo(2L);
    assertThat(receipt.accountSourceVersion()).isEqualTo(2L);
    assertThat(receipt.issuanceFence()).isEqualTo(2L);
    assertThat(receipt.issuanceFenceSourceVersion()).isEqualTo(2L);
    assertThat(receipt.outboxSequence()).isEqualTo(1L);
    assertThat(receipt.eventId()).isEqualTo(EVENT_ID_PREFIX + requestId);
    assertThat(checkpoint.outboxSequence()).isEqualTo(receipt.outboxSequence());
    assertThat(checkpoint.sourceEventId()).isEqualTo(receipt.eventId());
    assertThat(checkpoint.sourceEventDigest()).isEqualTo(receipt.eventDigest());
    assertThat(decoded.requestId()).isEqualTo(requestId.toString());
    assertThat(decoded.accountId()).isEqualTo(seed.accountUuid().toString());
    assertThat(decoded.accountAuthorityGeneration()).isEqualTo("2");
    assertThat(decoded.sourceVersion()).isEqualTo("2");
    assertThat(new String(event.payload(), StandardCharsets.UTF_8)).doesNotContain(tokenHash);
    assertThat(count(fixture, "account_logout_all_operation_receipts")).isEqualTo(1L);
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(1L);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_logout_all_operation_receipts "
                            + "SET request_digest = request_digest WHERE request_id = ?",
                        requestId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_logout_all_operation_receipts WHERE request_id = ?",
                        requestId))
        .isInstanceOf(DataAccessException.class);
    assertThat(logoutReceipt(fixture, requestId)).isEqualTo(receipt);
  }

  @Test
  void exactRetryRecoversOriginalResultAfterLaterSourceAdvanceWithoutRewritingCounters() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID firstRequest = UUID.randomUUID();
    String firstTokenHash = digest("presented-token:" + firstRequest);
    String firstDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, firstTokenHash);
    LogoutAllResult firstResult =
        commit(fixture, seed, firstRequest, firstDigest, firstTokenHash, seed.initialState());
    LogoutAllReceipt firstReceipt = logoutReceipt(fixture, firstRequest);

    UUID laterRequest = UUID.randomUUID();
    String laterTokenHash = digest("presented-token:" + laterRequest);
    commit(
        fixture,
        seed,
        laterRequest,
        logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, laterTokenHash),
        laterTokenHash,
        authority(fixture, seed));
    StoredState afterLaterAdvance = snapshot(fixture, seed);

    LogoutAllResult retry =
        commit(fixture, seed, firstRequest, firstDigest, firstTokenHash, seed.initialState());

    assertThat(retry).isEqualTo(firstResult);
    assertThat(logoutReceipt(fixture, firstRequest)).isEqualTo(firstReceipt);
    assertThat(snapshot(fixture, seed)).isEqualTo(afterLaterAdvance);
  }

  @Test
  void changedRequestBindingsConflictWithoutMutation() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    commit(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
    StoredState committed = snapshot(fixture, seed);
    LogoutAllReceipt committedReceipt = logoutReceipt(fixture, requestId);
    var committedEvent =
        transaction(
            fixture.transaction(),
            () -> fixture.outbox().findEvent(streamKey(seed.accountUuid()), 1L).orElseThrow());

    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    requestId,
                    digest("changed-request:" + requestId),
                    tokenHash,
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                producer(fixture)
                    .commit(
                        requestId,
                        2,
                        requestDigest,
                        TOKEN_PROFILE,
                        tokenHash,
                        account(fixture, seed),
                        seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    requestId,
                    requestDigest,
                    digest("changed-token:" + requestId),
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    requestId,
                    logoutAllDigest(seed.accountUuid(), "player-bootstrap", tokenHash),
                    "player-bootstrap",
                    tokenHash,
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    UUID.randomUUID(),
                    digest("changed-request-id"),
                    tokenHash,
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    UUID unboundRequestId = UUID.randomUUID();
    String unboundTokenHash = digest("unbound-token:" + unboundRequestId);
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    unboundRequestId,
                    digest("unbound-request-digest"),
                    unboundTokenHash,
                    authority(fixture, seed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical caller bindings");

    assertThat(snapshot(fixture, seed)).isEqualTo(committed);
    assertThat(logoutReceipt(fixture, requestId)).isEqualTo(committedReceipt);
    assertThat(
            transaction(
                fixture.transaction(),
                () -> fixture.outbox().findEvent(streamKey(seed.accountUuid()), 1L).orElseThrow()))
        .isEqualTo(committedEvent);
  }

  @Test
  void concurrentExactRequestCommitsOnlyOneAuthorityAdvanceAndEvent() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  concurrentCommit(
                      fixture, seed, requestId, requestDigest, tokenHash, ready, start));
      var second =
          executor.submit(
              () ->
                  concurrentCommit(
                      fixture, seed, requestId, requestDigest, tokenHash, ready, start));
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get(45, TimeUnit.SECONDS)).isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);
      assertThat(second.get(45, TimeUnit.SECONDS)).isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    ScopeState current = authority(fixture, seed);
    assertThat(current.generation()).isEqualTo(2L);
    assertThat(current.sourceVersion()).isEqualTo(2L);
    assertThat(current.issuanceFence().value()).isEqualTo(2L);
    assertThat(count(fixture, "account_logout_all_operation_receipts")).isEqualTo(1L);
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(1L);
  }

  @Test
  void eventInsertFailureRollsBackGenerationFenceCheckpointAndReceipt() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "ADD CONSTRAINT reject_logout_all_event "
                + "CHECK (event_id NOT LIKE 'account-logout-all-event-v1:%')");

    UUID rollbackRequest = UUID.randomUUID();
    String rollbackTokenHash = digest("rollback-token");
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    rollbackRequest,
                    logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, rollbackTokenHash),
                    rollbackTokenHash,
                    seed.initialState()))
        .isInstanceOf(RuntimeException.class);

    assertThat(snapshot(fixture, seed))
        .isEqualTo(new StoredState(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 0L, 0L, 0L, 0L));
    assertThat(count(fixture, "account_authority_outbox_streams")).isZero();
  }

  @Test
  void sourceCounterOverflowFailsBeforeAnyCutoffMutation() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture, MAX_COUNTER);
    seedLatestLogoutReceiptAtMaximumCounter(fixture, seed);
    ScopeState maximum = authority(fixture, seed);
    StoredState before = snapshot(fixture, seed);

    UUID overflowRequest = UUID.randomUUID();
    String overflowTokenHash = digest("overflow-token");
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    overflowRequest,
                    logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, overflowTokenHash),
                    overflowTokenHash,
                    maximum))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exhausted");

    assertThat(snapshot(fixture, seed)).isEqualTo(before);
  }

  @Test
  void passwordResetThenLogoutAllPreservesBothReceiptsAndResetRetry() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    reset(fixture, seed, "reset-before-logout");
    PasswordResetReceipt resetReceipt = passwordResetReceipt(fixture, seed);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);

    commit(
        fixture,
        seed,
        requestId,
        logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash),
        tokenHash,
        authority(fixture, seed));
    LogoutAllReceipt logoutReceipt = logoutReceipt(fixture, requestId);

    reset(fixture, seed, "reset-before-logout");

    assertThat(passwordResetReceipt(fixture, seed)).isEqualTo(resetReceipt);
    assertThat(logoutReceipt(fixture, requestId)).isEqualTo(logoutReceipt);
    assertThat(authority(fixture, seed).generation()).isEqualTo(3L);
    assertThat(authority(fixture, seed).sourceVersion()).isEqualTo(3L);
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(2L);
  }

  @Test
  void logoutAllThenPasswordResetValidatesMixedLatestSourceAndPreservesLogoutReceipt() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);

    commit(
        fixture,
        seed,
        requestId,
        logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash),
        tokenHash,
        seed.initialState());
    LogoutAllReceipt logoutReceipt = logoutReceipt(fixture, requestId);
    reset(fixture, seed, "reset-after-logout");

    assertThat(logoutReceipt(fixture, requestId)).isEqualTo(logoutReceipt);
    assertThat(passwordResetReceipt(fixture, seed).outboxSequence()).isEqualTo(2L);
    assertThat(authority(fixture, seed).generation()).isEqualTo(3L);
    assertThat(authority(fixture, seed).sourceVersion()).isEqualTo(3L);
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(2L);
  }

  @Test
  void currentSourceReadbackProvesPristineAccountAndRejectsUnknownAccountWithoutMutation() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    StoredState before = snapshot(fixture, seed);

    AccountAuthoritySourceReader.AccountSourceSnapshot current =
        sourceReader(fixture).readCurrent(seed.accountUuid());

    assertThat(current.accountId()).isEqualTo(seed.accountUuid());
    assertThat(current.sourceState().scope()).isEqualTo(AuthorityScope.account(seed.accountUuid()));
    assertThat(current.sourceState().generation()).isEqualTo(1L);
    assertThat(current.sourceState().sourceVersion()).isEqualTo(1L);
    assertThat(current.sourceState().issuanceFence().value()).isEqualTo(1L);
    assertThat(current.sourceState().issuanceFence().sourceVersion()).isEqualTo(1L);
    assertThat(current.outboxStreamKey()).isEqualTo(streamKey(seed.accountUuid()));
    assertThat(current.outboxSequence()).isZero();
    assertThat(current.latestEvent()).isEmpty();

    assertThatThrownBy(() -> sourceReader(fixture).readCurrent(UNKNOWN_ACCOUNT_UUID))
        .isInstanceOf(IllegalStateException.class);

    assertThat(snapshot(fixture, seed)).isEqualTo(before);
    assertThat(count(fixture, "account_authority_outbox_streams")).isZero();
  }

  @Test
  void
      historicalReadbackPreservesMixedEventsWhileReturningCurrentSourceAndResetRetryStaysSuperseded() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    String firstPassword = "historical-password-one";
    reset(fixture, seed, firstPassword);
    PasswordResetReceipt firstResetReceipt = passwordResetReceipt(fixture, seed);
    AccountAuthorityOutboxRepository.Event firstResetEvent = event(fixture, seed, 1L);

    UUID logoutRequestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + logoutRequestId);
    commit(
        fixture,
        seed,
        logoutRequestId,
        logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash),
        tokenHash,
        authority(fixture, seed));
    LogoutAllReceipt logoutReceipt = logoutReceipt(fixture, logoutRequestId);
    AccountAuthorityOutboxRepository.Event logoutEvent = event(fixture, seed, 2L);

    String laterResetToken = addPasswordResetToken(fixture, seed);
    String laterPassword = "historical-password-two";
    reset(fixture, laterResetToken, laterPassword);
    PasswordResetReceipt laterResetReceipt = passwordResetReceipt(fixture, laterResetToken);
    AccountAuthorityOutboxRepository.Event laterResetEvent = event(fixture, seed, 3L);

    AccountAuthoritySourceReader reader = sourceReader(fixture);
    AccountAuthoritySourceReader.AccountSourceSnapshot current =
        reader.readCurrent(seed.accountUuid());
    AccountAuthoritySourceReader.AccountSourceEventReadback historicalReset =
        reader.readCommittedEvent(seed.accountUuid(), 1L);
    AccountAuthoritySourceReader.AccountSourceEventReadback historicalLogout =
        reader.readCommittedEvent(seed.accountUuid(), 2L);

    assertThat(current.sourceState().generation()).isEqualTo(4L);
    assertThat(current.sourceState().sourceVersion()).isEqualTo(4L);
    assertThat(current.sourceState().issuanceFence().value()).isEqualTo(4L);
    assertThat(current.outboxSequence()).isEqualTo(3L);
    assertThat(current.latestEvent()).isPresent();
    assertSameEvent(laterResetEvent, current.latestEvent().orElseThrow());
    assertThat(historicalReset.currentSnapshot().sourceState()).isEqualTo(current.sourceState());
    assertThat(historicalReset.currentSnapshot().outboxStreamKey())
        .isEqualTo(current.outboxStreamKey());
    assertThat(historicalReset.currentSnapshot().outboxSequence()).isEqualTo(3L);
    assertSameEvent(laterResetEvent, historicalReset.currentSnapshot().latestEvent().orElseThrow());
    assertSameEvent(firstResetEvent, historicalReset.requestedEvent());
    assertThat(historicalLogout.currentSnapshot().sourceState()).isEqualTo(current.sourceState());
    assertThat(historicalLogout.currentSnapshot().outboxSequence()).isEqualTo(3L);
    assertSameEvent(
        laterResetEvent, historicalLogout.currentSnapshot().latestEvent().orElseThrow());
    assertSameEvent(logoutEvent, historicalLogout.requestedEvent());
    assertThat(passwordResetReceipt(fixture, seed)).isEqualTo(firstResetReceipt);
    assertThat(passwordResetReceipt(fixture, laterResetToken)).isEqualTo(laterResetReceipt);
    assertThat(logoutReceipt(fixture, logoutRequestId)).isEqualTo(logoutReceipt);
    assertThat(new String(firstResetEvent.payload(), StandardCharsets.UTF_8))
        .doesNotContain(seed.rawToken(), firstPassword, laterPassword);
    assertThat(new String(logoutEvent.payload(), StandardCharsets.UTF_8))
        .doesNotContain(seed.rawToken(), firstPassword, laterPassword, tokenHash);
    assertThat(new String(laterResetEvent.payload(), StandardCharsets.UTF_8))
        .doesNotContain(laterResetToken, firstPassword, laterPassword);
    for (Object evidence : List.of(current, historicalReset, historicalLogout)) {
      assertThat(evidence.toString())
          .doesNotContain(
              seed.rawToken(), laterResetToken, firstPassword, laterPassword, tokenHash);
    }

    StoredState beforeSupersededRetry = snapshot(fixture, seed);
    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), firstPassword))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("superseded");
    assertThat(snapshot(fixture, seed)).isEqualTo(beforeSupersededRetry);
    assertThat(passwordResetReceipt(fixture, seed)).isEqualTo(firstResetReceipt);
    assertThat(passwordResetReceipt(fixture, laterResetToken)).isEqualTo(laterResetReceipt);
    assertThat(logoutReceipt(fixture, logoutRequestId)).isEqualTo(logoutReceipt);
    assertSameEvent(firstResetEvent, event(fixture, seed, 1L));
    assertSameEvent(logoutEvent, event(fixture, seed, 2L));
    assertSameEvent(laterResetEvent, event(fixture, seed, 3L));
  }

  @Test
  void invalidHistoricalSelectorsFailWithoutChangingAnyAccountSourceEvidence() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    reset(fixture, seed, "selector-test-password");
    UUID logoutRequestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + logoutRequestId);
    commit(
        fixture,
        seed,
        logoutRequestId,
        logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash),
        tokenHash,
        authority(fixture, seed));

    StoredState before = snapshot(fixture, seed);
    Account accountBefore = account(fixture, seed);
    PasswordResetReceipt resetReceipt = passwordResetReceipt(fixture, seed);
    LogoutAllReceipt logoutReceipt = logoutReceipt(fixture, logoutRequestId);
    AccountAuthorityOutboxRepository.Event firstEvent = event(fixture, seed, 1L);
    AccountAuthorityOutboxRepository.Event secondEvent = event(fixture, seed, 2L);
    AccountAuthoritySourceReader reader = sourceReader(fixture);

    assertThatThrownBy(() -> reader.readCommittedEvent(seed.accountUuid(), 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(snapshot(fixture, seed)).isEqualTo(before);
    assertAccountCredentialUnchanged(fixture, seed, accountBefore);
    assertThat(passwordResetReceipt(fixture, seed)).isEqualTo(resetReceipt);
    assertThat(logoutReceipt(fixture, logoutRequestId)).isEqualTo(logoutReceipt);
    assertSameEvent(firstEvent, event(fixture, seed, 1L));
    assertSameEvent(secondEvent, event(fixture, seed, 2L));
    assertThatThrownBy(() -> reader.readCommittedEvent(seed.accountUuid(), -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(snapshot(fixture, seed)).isEqualTo(before);
    assertAccountCredentialUnchanged(fixture, seed, accountBefore);
    assertThat(passwordResetReceipt(fixture, seed)).isEqualTo(resetReceipt);
    assertThat(logoutReceipt(fixture, logoutRequestId)).isEqualTo(logoutReceipt);
    assertSameEvent(firstEvent, event(fixture, seed, 1L));
    assertSameEvent(secondEvent, event(fixture, seed, 2L));
    assertThatThrownBy(() -> reader.readCommittedEvent(seed.accountUuid(), 3L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ahead of its current checkpoint");
    assertThat(snapshot(fixture, seed)).isEqualTo(before);
    assertThat(passwordResetReceipt(fixture, seed)).isEqualTo(resetReceipt);
    assertThat(logoutReceipt(fixture, logoutRequestId)).isEqualTo(logoutReceipt);

    AccountAuthorityOutboxRepository missingHistoricalEvent =
        new AccountAuthorityOutboxRepository(fixture.transactionDsl()) {
          @Override
          public Optional<AccountAuthorityOutboxRepository.Event> findEvent(
              String requestedStreamKey, long outboxSequence) {
            if (streamKey(seed.accountUuid()).equals(requestedStreamKey) && outboxSequence == 1L) {
              return Optional.empty();
            }
            return super.findEvent(requestedStreamKey, outboxSequence);
          }
        };
    assertThatThrownBy(
            () ->
                sourceReader(fixture, fixture.accounts(), missingHistoricalEvent)
                    .readCommittedEvent(seed.accountUuid(), 1L))
        .isInstanceOf(IllegalStateException.class);
    assertThat(snapshot(fixture, seed)).isEqualTo(before);
    assertThat(passwordResetReceipt(fixture, seed)).isEqualTo(resetReceipt);
    assertThat(logoutReceipt(fixture, logoutRequestId)).isEqualTo(logoutReceipt);
    assertSameEvent(firstEvent, event(fixture, seed, 1L));
    assertSameEvent(secondEvent, event(fixture, seed, 2L));

    Seed otherAccount = seedAccount(fixture);
    reset(fixture, otherAccount, "other-account-source-password");
    AccountAuthorityOutboxRepository.Event foreignEvent = event(fixture, seed, 1L);
    AccountAuthorityOutboxRepository.Event otherAccountEvent = event(fixture, otherAccount, 1L);
    ScopeState otherAccountBefore = authority(fixture, otherAccount);
    Account otherAccountRowBefore = account(fixture, otherAccount);
    PasswordResetReceipt otherAccountReceipt = passwordResetReceipt(fixture, otherAccount);
    StoredState beforeCrossAccountRead = snapshot(fixture, seed);
    AtomicInteger otherAccountEventReads = new AtomicInteger();
    AccountAuthorityOutboxRepository crossAccountReadback =
        new AccountAuthorityOutboxRepository(fixture.transactionDsl()) {
          @Override
          public Optional<AccountAuthorityOutboxRepository.Event> findEvent(
              String requestedStreamKey, long outboxSequence) {
            if (streamKey(otherAccount.accountUuid()).equals(requestedStreamKey)
                && outboxSequence == 1L) {
              if (otherAccountEventReads.incrementAndGet() > 1) {
                return Optional.of(foreignEvent);
              }
            }
            return super.findEvent(requestedStreamKey, outboxSequence);
          }
        };
    assertThatThrownBy(
            () ->
                sourceReader(fixture, fixture.accounts(), crossAccountReadback)
                    .readCommittedEvent(otherAccount.accountUuid(), 1L))
        .isInstanceOf(IllegalStateException.class);
    assertThat(snapshot(fixture, seed)).isEqualTo(beforeCrossAccountRead);
    assertThat(authority(fixture, otherAccount)).isEqualTo(otherAccountBefore);
    assertAccountCredentialUnchanged(fixture, otherAccount, otherAccountRowBefore);
    assertThat(passwordResetReceipt(fixture, seed)).isEqualTo(resetReceipt);
    assertThat(logoutReceipt(fixture, logoutRequestId)).isEqualTo(logoutReceipt);
    assertThat(passwordResetReceipt(fixture, otherAccount)).isEqualTo(otherAccountReceipt);
    assertSameEvent(firstEvent, event(fixture, seed, 1L));
    assertSameEvent(secondEvent, event(fixture, seed, 2L));
    assertSameEvent(otherAccountEvent, event(fixture, otherAccount, 1L));
  }

  @Test
  void historicalReaderHoldsAccountFenceThroughSelectedAndCurrentSourceReadback() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    reset(fixture, seed, "fenced-source-password");
    AccountAuthorityOutboxRepository.Event firstEvent = event(fixture, seed, 1L);
    ScopeState beforeAdvance = authority(fixture, seed);
    Account verifiedAccount = account(fixture, seed);

    CountDownLatch readerLockedAccount = new CountDownLatch(1);
    CountDownLatch releaseReader = new CountDownLatch(1);
    CountDownLatch writerReachedAccountLock = new CountDownLatch(1);
    AtomicInteger readerBackendPid = new AtomicInteger();
    AtomicInteger writerBackendPid = new AtomicInteger();
    AccountRepository readerAccounts =
        new AccountRepository(fixture.transactionDsl()) {
          @Override
          public Optional<Account> findByIdForUpdate(Long id) {
            Optional<Account> locked = super.findByIdForUpdate(id);
            readerBackendPid.set(currentBackendPid(fixture.transactionDsl()));
            readerLockedAccount.countDown();
            await(releaseReader);
            return locked;
          }
        };
    AccountRepository writerAccounts =
        new AccountRepository(fixture.transactionDsl()) {
          @Override
          public Optional<Account> findByIdForUpdate(Long id) {
            writerBackendPid.set(currentBackendPid(fixture.transactionDsl()));
            writerReachedAccountLock.countDown();
            return super.findByIdForUpdate(id);
          }
        };
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var read =
          executor.submit(
              () ->
                  sourceReader(fixture, readerAccounts, fixture.outbox())
                      .readCommittedEvent(seed.accountUuid(), 1L));
      assertThat(readerLockedAccount.await(20, TimeUnit.SECONDS)).isTrue();
      var write =
          executor.submit(
              () ->
                  producer(fixture, writerAccounts)
                      .commit(
                          UUID.randomUUID(),
                          1,
                          logoutAllDigest(
                              seed.accountUuid(), TOKEN_PROFILE, digest("fenced-writer-token")),
                          TOKEN_PROFILE,
                          digest("fenced-writer-token"),
                          verifiedAccount,
                          beforeAdvance));
      assertThat(writerReachedAccountLock.await(20, TimeUnit.SECONDS)).isTrue();
      assertThat(
              awaitAccountRowBlock(
                  fixture.setupDsl(),
                  readerBackendPid.get(),
                  writerBackendPid.get(),
                  Duration.ofSeconds(10)))
          .as("the source mutation waits on the reader's locked Account row")
          .isTrue();

      releaseReader.countDown();
      AccountAuthoritySourceReader.AccountSourceEventReadback readback =
          read.get(45, TimeUnit.SECONDS);
      assertThat(write.get(45, TimeUnit.SECONDS)).isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);

      assertSameEvent(firstEvent, readback.requestedEvent());
      assertThat(readback.currentSnapshot().sourceState()).isEqualTo(beforeAdvance);
      assertThat(readback.currentSnapshot().outboxSequence()).isEqualTo(1L);
      assertSameEvent(firstEvent, readback.currentSnapshot().latestEvent().orElseThrow());
      ScopeState afterAdvance = authority(fixture, seed);
      assertThat(afterAdvance.generation()).isEqualTo(3L);
      assertThat(afterAdvance.sourceVersion()).isEqualTo(3L);
      assertThat(afterAdvance.issuanceFence().value()).isEqualTo(3L);
      assertThat(event(fixture, seed, 2L).outboxSequence()).isEqualTo(2L);
    } finally {
      releaseReader.countDown();
      executor.shutdownNow();
    }
  }

  private LogoutAllResult concurrentCommit(
      Fixture fixture,
      Seed seed,
      UUID requestId,
      String requestDigest,
      String tokenHash,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    await(start);
    return commit(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
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
    AccountRepository accounts = new AccountRepository(transactionDsl);
    AccountAuthorityGenerationRepository authority =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(transactionDsl);
    AccountPasswordResetOperationRepository passwordResetOperations =
        new AccountPasswordResetOperationRepository(transactionDsl);
    AccountLogoutAllOperationRepository logoutOperations =
        new AccountLogoutAllOperationRepository(transactionDsl);
    PasswordResetTokenRepository tokens = new PasswordResetTokenRepository(transactionDsl);
    return new Fixture(
        setupDsl,
        transactionDsl,
        transactionManager,
        transaction,
        accounts,
        authority,
        outbox,
        passwordResetOperations,
        logoutOperations,
        tokens);
  }

  private AccountAuthoritySourceReader sourceReader(Fixture fixture) {
    return sourceReader(fixture, fixture.accounts(), fixture.outbox());
  }

  private AccountAuthoritySourceReader sourceReader(
      Fixture fixture, AccountRepository accounts, AccountAuthorityOutboxRepository outbox) {
    return new AccountAuthoritySourceReader(
        accounts,
        fixture.authority(),
        outbox,
        sourceReadback(fixture, outbox),
        fixture.transactionManager());
  }

  private Seed seedAccount(Fixture fixture) {
    return seedAccount(fixture, 1L);
  }

  private Seed seedAccount(Fixture fixture, long initialCounter) {
    return transaction(
        fixture.transaction(),
        () -> {
          Account account = new Account();
          String unique = UUID.randomUUID().toString();
          account.setUsername("logout-all-" + unique);
          account.setEmail("logout-all-" + unique + "@example.test");
          account.setPasswordHash("initial-verifier");
          Account saved = fixture.accounts().save(account);
          if (initialCounter == 1L) {
            fixture.authority().initialize(AuthorityScope.account(saved.getAccountUuid()));
          } else {
            // Seed a synthetic retained maximum by INSERT, not an illegal monotonic jump.
            // All migration constraints and update triggers stay enabled for the whole test.
            fixture
                .transactionDsl()
                .execute(
                    "INSERT INTO account_authority_generations "
                        + "(scope_kind, account_uuid, generation, source_version) "
                        + "VALUES ('ACCOUNT', ?, ?, ?)",
                    saved.getAccountUuid(),
                    initialCounter,
                    initialCounter);
            fixture
                .transactionDsl()
                .execute(
                    "INSERT INTO account_authority_issuance_fences "
                        + "(account_uuid, issuance_fence, source_version) VALUES (?, ?, ?)",
                    saved.getAccountUuid(),
                    initialCounter,
                    initialCounter);
          }
          PasswordResetToken token = new PasswordResetToken();
          String rawToken = "reset-token-" + unique;
          LocalDateTime deadline = LocalDateTime.now().plusHours(2);
          token.setAccount(saved);
          token.setToken(rawToken);
          token.setExpiresAt(deadline);
          fixture.tokens().save(token);
          return new Seed(
              saved.getId(),
              saved.getAccountUuid(),
              rawToken,
              fixture.authority().read(AuthorityScope.account(saved.getAccountUuid())));
        });
  }

  private LogoutAllResult commit(
      Fixture fixture,
      Seed seed,
      UUID requestId,
      String requestDigest,
      String tokenHash,
      ScopeState expected) {
    return commit(fixture, seed, requestId, requestDigest, TOKEN_PROFILE, tokenHash, expected);
  }

  private LogoutAllResult commit(
      Fixture fixture,
      Seed seed,
      UUID requestId,
      String requestDigest,
      String tokenProfile,
      String tokenHash,
      ScopeState expected) {
    return producer(fixture)
        .commit(
            requestId, 1, requestDigest, tokenProfile, tokenHash, account(fixture, seed), expected);
  }

  private AccountLogoutAllAuthorityEventProducer producer(Fixture fixture) {
    return producer(fixture, fixture.accounts());
  }

  private AccountLogoutAllAuthorityEventProducer producer(
      Fixture fixture, AccountRepository accounts) {
    return new AccountLogoutAllAuthorityEventProducer(
        accounts,
        fixture.authority(),
        fixture.outbox(),
        fixture.logoutOperations(),
        sourceReadback(fixture, fixture.outbox()),
        fixture.transactionDsl(),
        fixture.transactionManager());
  }

  private AccountAuthoritySourceEventReadback sourceReadback(
      Fixture fixture, AccountAuthorityOutboxRepository outbox) {
    return new AccountAuthoritySourceEventReadback(
        outbox, fixture.passwordResetOperations(), fixture.logoutOperations());
  }

  private void reset(Fixture fixture, Seed seed, String password) {
    reset(fixture, seed.rawToken(), password);
  }

  private void reset(Fixture fixture, String rawToken, String password) {
    AccountServiceImpl service =
        new AccountServiceImpl(
            fixture.accounts(),
            fixture.authority(),
            fixture.outbox(),
            fixture.passwordResetOperations(),
            fixture.logoutOperations(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            fixture.tokens(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            fixture.transactionManager());
    transaction(
        fixture.transaction(),
        () -> {
          service.completePasswordReset(new CompletePasswordResetRequest(rawToken, password));
          return null;
        });
  }

  private String addPasswordResetToken(Fixture fixture, Seed seed) {
    return transaction(
        fixture.transaction(),
        () -> {
          PasswordResetToken token = new PasswordResetToken();
          String rawToken = "reset-token-" + UUID.randomUUID();
          token.setAccount(fixture.accounts().findById(seed.accountId()).orElseThrow());
          token.setToken(rawToken);
          token.setExpiresAt(LocalDateTime.now().plusHours(2));
          fixture.tokens().save(token);
          return rawToken;
        });
  }

  private void seedLatestLogoutReceiptAtMaximumCounter(Fixture fixture, Seed seed) {
    UUID priorRequestId = UUID.randomUUID();
    String tokenHash = digest("prior-max-token");
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    String streamKey = streamKey(seed.accountUuid());
    String eventId = EVENT_ID_PREFIX + priorRequestId;
    transaction(
        fixture.transaction(),
        () -> {
          var event =
              AccountLogoutAllAuthorityEventV1Codec.seal(
                  Map.ofEntries(
                      Map.entry(
                          "schemaVersion", AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION),
                      Map.entry("eventType", AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE),
                      Map.entry("eventId", eventId),
                      Map.entry("requestId", priorRequestId.toString()),
                      Map.entry("accountId", seed.accountUuid().toString()),
                      Map.entry("sourceScope", "account/" + seed.accountUuid()),
                      Map.entry("outboxStreamKey", streamKey),
                      Map.entry("outboxSequence", "1"),
                      Map.entry("accountAuthorityGeneration", Long.toString(MAX_COUNTER)),
                      Map.entry("sourceVersion", Long.toString(MAX_COUNTER)),
                      Map.entry(
                          "accountSecurityCutoff",
                          Map.of(
                              "accountAuthorityGeneration",
                              Long.toString(MAX_COUNTER),
                              "outboxStreamKey",
                              streamKey,
                              "outboxSequence",
                              "1"))));
          var appended =
              fixture
                  .outbox()
                  .append(
                      streamKey,
                      priorRequestId.toString(),
                      event.eventId(),
                      event.eventDigest(),
                      event.canonicalJsonUtf8());
          ScopeState maximum = fixture.authority().read(AuthorityScope.account(seed.accountUuid()));
          fixture
              .logoutOperations()
              .insert(
                  LogoutAllReceipt.committed(
                      priorRequestId,
                      seed.accountId(),
                      seed.accountUuid(),
                      requestDigest,
                      tokenHash,
                      TOKEN_PROFILE,
                      streamKey,
                      appended.outboxSequence(),
                      eventId,
                      event.eventDigest(),
                      maximum,
                      maximum.issuanceFence()));
          return null;
        });
  }

  private Account account(Fixture fixture, Seed seed) {
    return transaction(
        fixture.transaction(), () -> fixture.accounts().findById(seed.accountId()).orElseThrow());
  }

  private ScopeState authority(Fixture fixture, Seed seed) {
    return transaction(
        fixture.transaction(),
        () -> fixture.authority().read(AuthorityScope.account(seed.accountUuid())));
  }

  private LogoutAllReceipt logoutReceipt(Fixture fixture, UUID requestId) {
    return transaction(
        fixture.transaction(),
        () -> fixture.logoutOperations().findByRequestId(requestId).orElseThrow());
  }

  private PasswordResetReceipt passwordResetReceipt(Fixture fixture, Seed seed) {
    return passwordResetReceipt(fixture, seed.rawToken());
  }

  private PasswordResetReceipt passwordResetReceipt(Fixture fixture, String rawToken) {
    return transaction(
        fixture.transaction(),
        () -> fixture.passwordResetOperations().findByTokenHash(digest(rawToken)).orElseThrow());
  }

  private AccountAuthorityOutboxRepository.Event event(
      Fixture fixture, Seed seed, long outboxSequence) {
    return transaction(
        fixture.transaction(),
        () ->
            fixture
                .outbox()
                .findEvent(streamKey(seed.accountUuid()), outboxSequence)
                .orElseThrow());
  }

  private StoredState snapshot(Fixture fixture, Seed seed) {
    ScopeState state = authority(fixture, seed);
    return new StoredState(
        state.generation(),
        state.sourceVersion(),
        state.issuanceFence().value(),
        state.issuanceFence().sourceVersion(),
        count(fixture, "accounts"),
        count(fixture, "account_authority_generations"),
        count(fixture, "account_authority_issuance_fences"),
        count(fixture, "password_reset_token"),
        count(fixture, "account_logout_all_operation_receipts"),
        count(fixture, "account_password_reset_operation_receipts"),
        count(fixture, "account_authority_outbox_events"),
        count(fixture, "account_authority_outbox_streams"));
  }

  private long count(Fixture fixture, String table) {
    return Objects.requireNonNull(
        fixture.setupDsl().resultQuery("SELECT COUNT(*) FROM " + table).fetchOne(0, Long.class),
        "Logout-all source row count is missing");
  }

  private String streamKey(UUID accountUuid) {
    return STREAM_PREFIX + accountUuid;
  }

  private String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String logoutAllDigest(UUID accountUuid, String tokenProfile, String tokenHash) {
    return AccountLogoutRequestDigest.accountLogoutAll(accountUuid, tokenProfile, tokenHash);
  }

  private void assertSameEvent(
      AccountAuthorityOutboxRepository.Event expected,
      AccountAuthorityOutboxRepository.Event actual) {
    assertThat(actual.outboxStreamKey()).isEqualTo(expected.outboxStreamKey());
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.outboxSequence()).isEqualTo(expected.outboxSequence());
    assertThat(actual.eventId()).isEqualTo(expected.eventId());
    assertThat(actual.eventDigest()).isEqualTo(expected.eventDigest());
    assertThat(actual.payload()).containsExactly(expected.payload());
  }

  private void assertAccountCredentialUnchanged(Fixture fixture, Seed seed, Account before) {
    Account after = account(fixture, seed);
    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getAccountUuid()).isEqualTo(before.getAccountUuid());
    assertThat(after.getPasswordHash()).isEqualTo(before.getPasswordHash());
  }

  private int currentBackendPid(DSLContext dsl) {
    return Objects.requireNonNull(
        dsl.resultQuery("SELECT pg_backend_pid()").fetchOne(0, Integer.class),
        "PostgreSQL did not return the Account row-lock holder backend PID");
  }

  private boolean awaitAccountRowBlock(
      DSLContext observerDsl, int readerBackendPid, int writerBackendPid, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Boolean blocked =
          observerDsl
              .resultQuery(
                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity waiting "
                      + "WHERE waiting.pid = ? AND waiting.wait_event_type = 'Lock' "
                      + "AND ? = ANY(pg_blocking_pids(waiting.pid)))",
                  writerBackendPid,
                  readerBackendPid)
              .fetchOne(0, Boolean.class);
      if (Boolean.TRUE.equals(blocked)) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent logout-all barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent logout-all proof was interrupted", interrupted);
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
      AccountRepository accounts,
      AccountAuthorityGenerationRepository authority,
      AccountAuthorityOutboxRepository outbox,
      AccountPasswordResetOperationRepository passwordResetOperations,
      AccountLogoutAllOperationRepository logoutOperations,
      PasswordResetTokenRepository tokens) {}

  private record Seed(long accountId, UUID accountUuid, String rawToken, ScopeState initialState) {}

  private record StoredState(
      long generation,
      long sourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      long accountRowCount,
      long generationRowCount,
      long issuanceFenceRowCount,
      long passwordResetTokenCount,
      long logoutReceiptCount,
      long passwordResetReceiptCount,
      long eventCount,
      long streamCount) {}
}
