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
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
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
import net.firedevops.firemud.accountservice.service.AccountLogoutAllDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllDraftSourceChangeRepository.PendingIntentSnapshot;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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
    AccountAuthorityOutboxRepository.Event firstEvent = event(fixture, seed, 1L);
    PendingIntentSnapshot firstSourceIntent = pendingIntent(fixture, firstRequest).orElseThrow();
    var firstFenceChange = sourceChange(firstSourceIntent);
    var firstFenceChangeSnapshot =
        transaction(
            fixture.transaction(), () -> draftFences(fixture).readSourceChange(firstFenceChange));

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
    assertSameEvent(firstEvent, event(fixture, seed, 1L));
    assertThat(pendingIntent(fixture, firstRequest).orElseThrow())
        .satisfies(
            replay -> {
              assertThat(replay.requestPayload())
                  .containsExactly(firstSourceIntent.requestPayload());
              assertThat(replay.captureEvidence())
                  .containsExactly(firstSourceIntent.captureEvidence());
              assertThat(replay.sourceEvidence())
                  .containsExactly(firstSourceIntent.sourceEvidence());
              assertThat(replay.sourceChangeBinding())
                  .containsExactly(firstSourceIntent.sourceChangeBinding());
              assertThat(replay.status()).isEqualTo("SOURCE_COMMITTED");
            });
    var replayedFenceChange =
        transaction(
            fixture.transaction(), () -> draftFences(fixture).readSourceChange(firstFenceChange));
    assertThat(replayedFenceChange.changeId()).isEqualTo(firstFenceChangeSnapshot.changeId());
    assertThat(replayedFenceChange.binding()).containsExactly(firstFenceChangeSnapshot.binding());
    assertThat(replayedFenceChange.status()).isEqualTo(firstFenceChangeSnapshot.status());
    assertThat(replayedFenceChange.requestedAt()).isEqualTo(firstFenceChangeSnapshot.requestedAt());
    assertThat(replayedFenceChange.committedAt()).isEqualTo(firstFenceChangeSnapshot.committedAt());
  }

  /**
   * These cases connect real PostgreSQL Account source writes to the V57 fence tables. The Draft
   * bindings and owner readbacks below are synthetic test evidence; they do not authenticate a real
   * Draft operation or prove the absent Game Design/World owner readback producers.
   */
  @Test
  void revokeOrderWaitsForBothExactAbortsBeforeOneLogoutAdvanceAndNeverAdmitsDelayedCommit() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    DraftAuthorizationFenceBinding binding =
        syntheticDraftBinding(seed, seed.initialState(), 0L, null);
    transaction(
        fixture.transaction(),
        () -> {
          draftFences(fixture).reserve(binding);
          return null;
        });
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("revocation-wins-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    StoredState before = snapshot(fixture, seed);

    assertPending(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
    PendingIntentSnapshot waiting = pendingIntent(fixture, requestId).orElseThrow();
    assertThat(waiting.status()).isEqualTo("WAITING");
    assertThat(waiting.expectedGeneration()).isEqualTo(1L);
    assertThat(waiting.expectedSourceVersion()).isEqualTo(1L);
    assertThat(waiting.expectedIssuanceFence()).isEqualTo(1L);
    assertThat(waiting.expectedIssuanceFenceSourceVersion()).isEqualTo(1L);
    assertThat(waiting.checkpointSequence()).isZero();
    SourceChange sourceChange = sourceChange(waiting);
    assertThat(sourceChange.sources()).hasSize(1);
    assertThat(sourceChange.sources().getFirst().kind()).isEqualTo(SourceKind.ACCOUNT);
    assertThat(sourceChange.sources().getFirst().scopeId())
        .isEqualTo(seed.accountUuid().toString());
    assertThat(
            transaction(fixture.transaction(), () -> draftFences(fixture).read(binding).ordering()))
        .isEqualTo(Ordering.REVOKE_ORDER);
    assertThatThrownBy(
            () ->
                transaction(
                    fixture.transaction(), () -> draftFences(fixture).claimCommitOrder(binding)))
        .isInstanceOf(IllegalStateException.class);

    syntheticOwnerReadback(
        fixture, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {31});
    assertPending(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
    assertThat(snapshot(fixture, seed)).isEqualTo(before);
    assertThat(pendingIntent(fixture, requestId).orElseThrow().sourceChangeBinding())
        .containsExactly(waiting.sourceChangeBinding());
    assertThat(
            transaction(fixture.transaction(), () -> draftFences(fixture).readSettlement(binding)))
        .isEqualTo(Settlement.PENDING);

    syntheticOwnerReadback(
        fixture, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {32});
    assertThat(
            transaction(fixture.transaction(), () -> draftFences(fixture).readSettlement(binding)))
        .isEqualTo(Settlement.FAILED_NONPUBLICATION);
    assertThat(
            transaction(
                fixture.transaction(),
                () -> draftFences(fixture).sourceMutationPermitted(sourceChange)))
        .isTrue();

    assertThat(commit(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState()))
        .isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);
    assertThat(authority(fixture, seed).generation()).isEqualTo(2L);
    assertThat(authority(fixture, seed).sourceVersion()).isEqualTo(2L);
    assertThat(authority(fixture, seed).issuanceFence().value()).isEqualTo(2L);
    assertThat(authority(fixture, seed).issuanceFence().sourceVersion()).isEqualTo(2L);
    assertThat(logoutReceipt(fixture, requestId).outboxSequence()).isEqualTo(1L);
    assertThat(event(fixture, seed, 1L).requestId()).isEqualTo(requestId.toString());
    assertThat(pendingIntent(fixture, requestId).orElseThrow().status())
        .isEqualTo("SOURCE_COMMITTED");
    assertThat(
            transaction(
                fixture.transaction(),
                () -> draftFences(fixture).readSourceChange(sourceChange).status()))
        .isEqualTo("SOURCE_COMMITTED");
    assertThatThrownBy(
            () ->
                transaction(
                    fixture.transaction(), () -> draftFences(fixture).claimCommitOrder(binding)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(snapshot(fixture, seed).generation()).isEqualTo(2L);
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(1L);
    assertThat(count(fixture, "account_logout_all_operation_receipts")).isEqualTo(1L);
  }

  @Test
  void commitOrderWaitsForBothExactOutcomesIncludingMixedFailureBeforeLogoutAdvance() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    DraftAuthorizationFenceBinding binding =
        syntheticDraftBinding(seed, seed.initialState(), 0L, null);
    transaction(
        fixture.transaction(),
        () -> {
          draftFences(fixture).reserve(binding);
          draftFences(fixture).claimCommitOrder(binding);
          return null;
        });
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("commit-order-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    StoredState before = snapshot(fixture, seed);

    assertPending(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
    PendingIntentSnapshot waiting = pendingIntent(fixture, requestId).orElseThrow();
    SourceChange sourceChange = sourceChange(waiting);
    assertThat(
            transaction(fixture.transaction(), () -> draftFences(fixture).read(binding).ordering()))
        .isEqualTo(Ordering.COMMIT_ORDER);

    OwnerReadback expectedWorldReadback =
        syntheticOwnerReadback(fixture, binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {41});
    byte[] expectedWorldReadbackBytes = expectedWorldReadback.canonicalBytes();
    assertPending(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
    assertThat(snapshot(fixture, seed)).isEqualTo(before);
    assertThat(
            transaction(fixture.transaction(), () -> draftFences(fixture).readSettlement(binding)))
        .isEqualTo(Settlement.PENDING);

    syntheticOwnerReadback(
        fixture, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {42});
    assertThat(
            transaction(fixture.transaction(), () -> draftFences(fixture).readSettlement(binding)))
        .isEqualTo(Settlement.FAILED_NONPUBLICATION);
    var originalWorldResult =
        transaction(
                fixture.transaction(),
                () -> draftFences(fixture).readOwnerResult(binding, Owner.WORLD))
            .orElseThrow();
    assertThat(originalWorldResult.outcome()).isEqualTo(Outcome.COMMITTED);
    assertThat(originalWorldResult.readback()).containsExactly(expectedWorldReadbackBytes);

    assertThat(commit(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState()))
        .isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);
    assertThat(authority(fixture, seed).generation()).isEqualTo(2L);
    assertThat(logoutReceipt(fixture, requestId).outboxSequence()).isEqualTo(1L);
    assertThat(event(fixture, seed, 1L).requestId()).isEqualTo(requestId.toString());
    assertThat(pendingIntent(fixture, requestId).orElseThrow().status())
        .isEqualTo("SOURCE_COMMITTED");
    assertThat(
            transaction(
                fixture.transaction(),
                () -> draftFences(fixture).readSourceChange(sourceChange).status()))
        .isEqualTo("SOURCE_COMMITTED");
    var replayedWorldResult =
        transaction(
                fixture.transaction(),
                () -> draftFences(fixture).readOwnerResult(binding, Owner.WORLD))
            .orElseThrow();
    assertThat(replayedWorldResult.outcome()).isEqualTo(originalWorldResult.outcome());
    assertThat(replayedWorldResult.readback()).containsExactly(expectedWorldReadbackBytes);
    assertThat(replayedWorldResult.recordedAt()).isEqualTo(originalWorldResult.recordedAt());
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(1L);
    assertThat(count(fixture, "account_logout_all_operation_receipts")).isEqualTo(1L);
  }

  @Test
  void pendingRetryKeepsOriginalCaptureAndConflictsOnlyOnChangedCallerBindings() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    Seed otherAccount = seedAccount(fixture);
    DraftAuthorizationFenceBinding binding =
        syntheticDraftBinding(seed, seed.initialState(), 0L, null);
    transaction(
        fixture.transaction(),
        () -> {
          draftFences(fixture).reserve(binding);
          return null;
        });
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("original-pending-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    assertPending(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
    PendingIntentSnapshot original = pendingIntent(fixture, requestId).orElseThrow();
    StoredState before = snapshot(fixture, seed);
    ScopeState changedServerCounters =
        new ScopeState(
            seed.initialState().scope(),
            81L,
            82L,
            new AccountAuthorityGenerationRepository.IssuanceFence(seed.accountUuid(), 83L, 84L));

    assertPending(fixture, seed, requestId, requestDigest, tokenHash, changedServerCounters);
    assertPendingIntentEquals(pendingIntent(fixture, requestId).orElseThrow(), original);
    assertThat(snapshot(fixture, seed)).isEqualTo(before);

    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    requestId,
                    digest("changed-pending-caller-digest:" + requestId),
                    tokenHash,
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
                    requestId,
                    requestDigest,
                    digest("changed-pending-token:" + requestId),
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                producer(fixture)
                    .commit(
                        requestId,
                        1,
                        requestDigest,
                        TOKEN_PROFILE,
                        tokenHash,
                        account(fixture, otherAccount),
                        authority(fixture, otherAccount)))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    UUID.randomUUID(),
                    logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash),
                    tokenHash,
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);

    assertPendingIntentEquals(pendingIntent(fixture, requestId).orElseThrow(), original);
    assertThat(snapshot(fixture, seed)).isEqualTo(before);
    assertThat(count(fixture, "account_logout_all_operation_receipts")).isZero();
    assertThat(count(fixture, "account_authority_outbox_events")).isZero();
  }

  @Test
  void lateDatabaseFailureAfterReceiptEventAndBothJournalTransitionsRollsBackLogoutOnly() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    DraftAuthorizationFenceBinding binding =
        syntheticDraftBinding(seed, seed.initialState(), 0L, null);
    transaction(
        fixture.transaction(),
        () -> {
          draftFences(fixture).reserve(binding);
          draftFences(fixture).claimCommitOrder(binding);
          return null;
        });
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("late-failure-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    assertPending(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
    PendingIntentSnapshot waiting = pendingIntent(fixture, requestId).orElseThrow();
    SourceChange sourceChange = sourceChange(waiting);
    syntheticOwnerReadback(fixture, binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {51});
    syntheticOwnerReadback(fixture, binding, Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {52});
    StoredState beforeRetry = snapshot(fixture, seed);

    installLateLogoutFailureTrigger(fixture, requestId);
    assertThatThrownBy(
            () -> commit(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState()))
        .satisfies(
            failure -> {
              PSQLException postgresFailure = rootPostgresCause(failure);
              assertThat(postgresFailure.getSQLState()).isEqualTo("P0001");
              assertThat((Throwable) postgresFailure)
                  .hasMessageContaining(
                      "injected late failure after logout receipt, event, V57 and V60 transitions");
            });

    assertThat(snapshot(fixture, seed)).isEqualTo(beforeRetry);
    assertThat(count(fixture, "account_authority_outbox_events")).isZero();
    assertThat(count(fixture, "account_authority_outbox_streams")).isZero();
    assertThat(count(fixture, "account_logout_all_operation_receipts")).isZero();
    PendingIntentSnapshot retained = pendingIntent(fixture, requestId).orElseThrow();
    assertPendingIntentEquals(retained, waiting);
    assertThat(retained.status()).isEqualTo("WAITING");
    assertThat(
            transaction(
                fixture.transaction(),
                () -> draftFences(fixture).readSourceChange(sourceChange).status()))
        .isEqualTo("WAITING");
    assertThat(
            transaction(fixture.transaction(), () -> draftFences(fixture).readSettlement(binding)))
        .isEqualTo(Settlement.COMMITTED);
  }

  @Test
  void v59ToV60PreservesImmutableLogoutResetSourceOutboxAndFenceHistoryWithoutBackfill() {
    Fixture fixture = newFixtureAtVersion("59");
    Seed seed = seedAccount(fixture);
    reset(fixture, seed, "migration-preservation-password");
    seedLegacyLogoutReceipt(fixture, seed);
    seedSyntheticDraftHistory(fixture, seed);

    List<String> preservedTables =
        List.of(
            "account_password_reset_operation_receipts",
            "account_logout_all_operation_receipts",
            "account_authority_generations",
            "account_authority_issuance_fences",
            "account_authority_outbox_streams",
            "account_authority_outbox_events",
            "account_draft_authorization_source_locks",
            "account_draft_authorization_fences",
            "account_draft_authorization_sources",
            "account_draft_authorization_owner_readbacks",
            "account_draft_authorization_source_changes",
            "account_draft_authorization_changed_scopes",
            "account_issuer_tenant_draft_source_changes");
    List<List<String>> before = preservedTables.stream().map(t -> rowImages(fixture, t)).toList();

    migrateFixtureToV60(fixture);

    for (int index = 0; index < preservedTables.size(); index++) {
      assertThat(rowImages(fixture, preservedTables.get(index)))
          .as("V60 preserves every existing row in %s byte-for-byte", preservedTables.get(index))
          .isEqualTo(before.get(index));
    }
    assertThat(count(fixture, "account_logout_all_draft_source_changes")).isZero();
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

  private DraftAuthorizationFenceRepository draftFences(Fixture fixture) {
    return new DraftAuthorizationFenceRepository(fixture.transactionDsl());
  }

  private Optional<PendingIntentSnapshot> pendingIntent(Fixture fixture, UUID requestId) {
    return transaction(
        fixture.transaction(),
        () ->
            new AccountLogoutAllDraftSourceChangeRepository(fixture.transactionDsl())
                .readPendingIntent(requestId));
  }

  private SourceChange sourceChange(PendingIntentSnapshot intent) {
    return SourceChange.fromStored(intent.sourceChangeBinding());
  }

  private void assertPending(
      Fixture fixture,
      Seed seed,
      UUID requestId,
      String requestDigest,
      String tokenHash,
      ScopeState expectedState) {
    assertThatThrownBy(
            () -> commit(fixture, seed, requestId, requestDigest, tokenHash, expectedState))
        .isInstanceOf(
            AccountLogoutAllDraftSourceChangeRepository.PendingSourceChangeException.class);
  }

  private void assertPendingIntentEquals(
      PendingIntentSnapshot actual, PendingIntentSnapshot expected) {
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.sourceChangeId()).isEqualTo(expected.sourceChangeId());
    assertThat(actual.requestPayload()).containsExactly(expected.requestPayload());
    assertThat(actual.captureEvidence()).containsExactly(expected.captureEvidence());
    assertThat(actual.sourceEvidence()).containsExactly(expected.sourceEvidence());
    assertThat(actual.sourceChangeRequest()).containsExactly(expected.sourceChangeRequest());
    assertThat(actual.sourceChangeBinding()).containsExactly(expected.sourceChangeBinding());
    assertThat(actual.status()).isEqualTo(expected.status());
    assertThat(actual.expectedGeneration()).isEqualTo(expected.expectedGeneration());
    assertThat(actual.expectedSourceVersion()).isEqualTo(expected.expectedSourceVersion());
    assertThat(actual.expectedIssuanceFence()).isEqualTo(expected.expectedIssuanceFence());
    assertThat(actual.expectedIssuanceFenceSourceVersion())
        .isEqualTo(expected.expectedIssuanceFenceSourceVersion());
    assertThat(actual.checkpointSequence()).isEqualTo(expected.checkpointSequence());
  }

  private DraftAuthorizationFenceBinding syntheticDraftBinding(
      Seed seed, ScopeState sourceState, long checkpointSequence, byte[] sourceEvidence) {
    byte[] evidence =
        sourceEvidence == null
            ? "synthetic-authenticated-owner-source-readback".getBytes(StandardCharsets.UTF_8)
            : sourceEvidence.clone();
    SourceEvidence accountSource =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            seed.accountUuid().toString(),
            Long.toString(sourceState.generation()),
            Long.toString(sourceState.sourceVersion()),
            streamKey(seed.accountUuid()),
            Long.toString(checkpointSequence),
            evidence);
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID commitId = UUID.randomUUID();
    DraftCommitBinding complete =
        DraftCommitBinding.create(
            new TargetProof(tenantId, versionId, 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW"),
            requestId,
            commitId,
            "logout-all-draft-base:" + UUID.randomUUID(),
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "synthetic complete Draft owner payload")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "9007199254740999")));
    byte[] completeBytes = complete.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        seed.accountUuid(),
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "1",
        completeBytes,
        completeBytes,
        complete.digest(),
        List.of(accountSource));
  }

  private OwnerReadback syntheticOwnerReadback(
      Fixture fixture,
      DraftAuthorizationFenceBinding binding,
      Owner owner,
      Outcome outcome,
      byte[] result) {
    OwnerReadback readback =
        new OwnerReadback(
            owner,
            outcome,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            binding.canonicalBytes(),
            result);
    transaction(
        fixture.transaction(),
        () -> {
          draftFences(fixture).recordOwnerReadback(binding, readback);
          return null;
        });
    return readback;
  }

  private void installLateLogoutFailureTrigger(Fixture fixture, UUID requestId) {
    fixture
        .setupDsl()
        .execute(
            "CREATE FUNCTION account_logout_all_test_late_failure() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NOT EXISTS (SELECT 1 FROM account_logout_all_operation_receipts receipt "
                + "JOIN account_authority_outbox_events event ON "
                + "event.outbox_stream_key = receipt.outbox_stream_key "
                + "AND event.outbox_sequence = receipt.outbox_sequence "
                + "JOIN account_logout_all_draft_source_changes journal ON "
                + "journal.request_id = receipt.request_id "
                + "JOIN account_draft_authorization_source_changes source_change ON "
                + "source_change.change_id = journal.source_change_id "
                + "WHERE receipt.request_id = NEW.request_id "
                + "AND journal.status = 'SOURCE_COMMITTED' "
                + "AND source_change.status = 'SOURCE_COMMITTED' "
                + "AND event.event_id = receipt.event_id AND event.event_digest = receipt.event_digest) "
                + "THEN RAISE EXCEPTION 'late failure ran before receipt, event, and both journal transitions'; "
                + "END IF; "
                + "RAISE EXCEPTION 'injected late failure after logout receipt, event, V57 and V60 transitions'; "
                + "END; $$");
    fixture
        .setupDsl()
        .execute(
            "CREATE CONSTRAINT TRIGGER account_logout_all_test_late_failure "
                + "AFTER INSERT ON account_logout_all_operation_receipts "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
                + "EXECUTE FUNCTION account_logout_all_test_late_failure()");
  }

  private LogoutAllReceipt seedLegacyLogoutReceipt(Fixture fixture, Seed seed) {
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("pre-v60-logout-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    return transaction(
        fixture.transaction(),
        () -> {
          ScopeState current = fixture.authority().read(AuthorityScope.account(seed.accountUuid()));
          ScopeState advanced = fixture.authority().advance(current, current.issuanceFence());
          String stream = streamKey(seed.accountUuid());
          String eventId = EVENT_ID_PREFIX + requestId;
          long sequence =
              fixture
                  .outbox()
                  .readCheckpoint(stream)
                  .map(checkpoint -> checkpoint.outboxSequence() + 1L)
                  .orElse(1L);
          var eventEvidence =
              AccountLogoutAllAuthorityEventV1Codec.seal(
                  Map.ofEntries(
                      Map.entry(
                          "schemaVersion", AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION),
                      Map.entry("eventType", AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE),
                      Map.entry("eventId", eventId),
                      Map.entry("requestId", requestId.toString()),
                      Map.entry("accountId", seed.accountUuid().toString()),
                      Map.entry("sourceScope", "account/" + seed.accountUuid()),
                      Map.entry("outboxStreamKey", stream),
                      Map.entry("outboxSequence", Long.toString(sequence)),
                      Map.entry("accountAuthorityGeneration", Long.toString(advanced.generation())),
                      Map.entry("sourceVersion", Long.toString(advanced.sourceVersion())),
                      Map.entry(
                          "accountSecurityCutoff",
                          Map.of(
                              "accountAuthorityGeneration", Long.toString(advanced.generation()),
                              "outboxStreamKey", stream,
                              "outboxSequence", Long.toString(sequence)))));
          var appended =
              fixture
                  .outbox()
                  .append(
                      stream,
                      requestId.toString(),
                      eventEvidence.eventId(),
                      eventEvidence.eventDigest(),
                      eventEvidence.canonicalJsonUtf8());
          LogoutAllReceipt receipt =
              LogoutAllReceipt.committed(
                  requestId,
                  seed.accountId(),
                  seed.accountUuid(),
                  requestDigest,
                  tokenHash,
                  TOKEN_PROFILE,
                  stream,
                  appended.outboxSequence(),
                  eventEvidence.eventId(),
                  eventEvidence.eventDigest(),
                  advanced,
                  advanced.issuanceFence());
          fixture.logoutOperations().insert(receipt);
          return receipt;
        });
  }

  private void seedSyntheticDraftHistory(Fixture fixture, Seed seed) {
    ScopeState state = authority(fixture, seed);
    AccountAuthorityOutboxRepository.Event latest = event(fixture, seed, 2L);
    DraftAuthorizationFenceBinding binding =
        syntheticDraftBinding(seed, state, latest.outboxSequence(), latest.payload());
    DraftAuthorizationFenceRepository fences = draftFences(fixture);
    SourceChange change =
        new SourceChange(
            UUID.randomUUID(),
            binding.sources(),
            "synthetic prior source change".getBytes(StandardCharsets.UTF_8));
    transaction(
        fixture.transaction(),
        () -> {
          fences.reserve(binding);
          fences.claimCommitOrder(binding);
          // Canonical synthetic V59 fixture SQL preserves the declared historical boundary;
          // the current source-change engine requires V67 and must not run on this old schema.
          fixture
              .transactionDsl()
              .execute(
                  "INSERT INTO account_draft_authorization_source_changes (change_id, binding, status)"
                      + " VALUES (?, ?, 'WAITING')",
                  change.changeId(),
                  change.canonicalBytes());
          for (SourceEvidence source : change.sources()) {
            fixture
                .transactionDsl()
                .execute(
                    "INSERT INTO account_draft_authorization_changed_scopes (change_id, source_key) VALUES (?, ?)",
                    change.changeId(),
                    source.key());
          }
          return null;
        });
    syntheticOwnerReadback(fixture, binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {61});
    syntheticOwnerReadback(fixture, binding, Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {62});
    transaction(
        fixture.transaction(),
        () -> {
          fixture
              .transactionDsl()
              .execute(
                  "UPDATE account_draft_authorization_source_changes SET status = 'SOURCE_COMMITTED',"
                      + " committed_at = CURRENT_TIMESTAMP WHERE change_id = ?",
                  change.changeId());
          return null;
        });
  }

  private List<String> rowImages(Fixture fixture, String table) {
    return fixture
        .setupDsl()
        .fetch(
            "SELECT row_to_json(row_data)::text AS row_image FROM "
                + table
                + " row_data ORDER BY row_image")
        .getValues("row_image", String.class);
  }

  private void migrateFixtureToV60(Fixture fixture) {
    Flyway.configure()
        .dataSource(fixture.dataSource())
        .schemas(fixture.schema())
        .defaultSchema(fixture.schema())
        .placeholders(Map.of("serviceSchema", fixture.schema()))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("60"))
        .load()
        .migrate();
  }

  private static PSQLException rootPostgresCause(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(PSQLException.class);
    return (PSQLException) cause;
  }

  private Fixture newFixture() {
    return newFixtureAtVersion(null);
  }

  private Fixture newFixtureAtVersion(String targetVersion) {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    var flywayConfiguration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (targetVersion != null) {
      flywayConfiguration.target(MigrationVersion.fromVersion(targetVersion));
    }
    flywayConfiguration.load().migrate();

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
        schema,
        dataSource,
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
        outbox,
        fixture.passwordResetOperations(),
        fixture.logoutOperations(),
        new net.firedevops.firemud.accountservice.repository
            .AccountSecurityStateOperationRepository(fixture.transactionDsl()));
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
            new net.firedevops.firemud.accountservice.repository
                .AccountSecurityStateOperationRepository(fixture.transactionDsl()),
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
      String schema,
      DriverManagerDataSource dataSource,
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
