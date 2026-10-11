package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.OperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback.LatestSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PasswordResetAuthorityProducerPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "password_reset_source_proof";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String REQUEST_PREFIX = "account-password-reset-request-v1:";
  private static final String EVENT_PREFIX = "account-password-reset-event-v1:";
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void commitsPasswordTokenAuthorityFenceClosedEventAndImmutableReceiptTogether() {
    Fixture fixture = newFixture();
    assertRealDraftOwnerFenceStorage(fixture);
    Seed seed = seedAccountAndToken(fixture, "initial-password");

    reset(fixture, seed.rawToken(), "new-password-one");

    Account account = account(fixture, seed.accountId());
    ScopeState authority = readAuthority(fixture, seed.accountUuid());
    String streamKey = streamKey(seed.accountUuid());
    PasswordResetReceipt receipt =
        transaction(
            fixture.transaction(),
            () -> fixture.operations().findByTokenHash(tokenHash(seed.rawToken())).orElseThrow());
    var checkpoint =
        transaction(
            fixture.transaction(), () -> fixture.outbox().readCheckpoint(streamKey).orElseThrow());
    var event =
        transaction(
            fixture.transaction(),
            () -> fixture.outbox().findEvent(streamKey, receipt.outboxSequence()).orElseThrow());
    var sourceHead =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT current_generation, current_source_version, current_issuance_fence, "
                        + "current_issuance_fence_source_version, last_outbox_sequence, "
                        + "last_event_id, last_event_digest, cutoff_generation, cutoff_stream_key, "
                        + "cutoff_sequence FROM account_authority_source_records "
                        + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    seed.accountUuid()),
            "The reset must advance the real Account source head");
    var decoded =
        PasswordResetAuthorityEventV1Codec.verify(
            new String(event.payload(), StandardCharsets.UTF_8));
    String payload = new String(event.payload(), StandardCharsets.UTF_8);

    assertThat(account.getPasswordHash()).isNotEqualTo(seed.originalVerifier());
    assertThat(account.getPasswordHash()).startsWith("$argon2");
    assertThat(authority.generation()).isEqualTo(2L);
    assertThat(authority.sourceVersion()).isEqualTo(2L);
    assertThat(authority.issuanceFence().value()).isEqualTo(2L);
    assertThat(authority.issuanceFence().sourceVersion()).isEqualTo(2L);
    assertThat(tokenExists(fixture, seed.rawToken())).isFalse();
    assertThat(receipt.accountId()).isEqualTo(seed.accountId());
    assertThat(receipt.accountUuid()).isEqualTo(seed.accountUuid());
    assertThat(receipt.tokenHash()).isEqualTo(tokenHash(seed.rawToken()));
    assertThat(receipt.tokenExpiresAt()).isEqualTo(seed.deadline());
    assertThat(receipt.passwordVerifierDigest()).isEqualTo(sha256Hex(account.getPasswordHash()));
    assertThat(receipt.eventId()).isEqualTo(EVENT_PREFIX + tokenHash(seed.rawToken()));
    assertThat(receipt.requestId()).isEqualTo(REQUEST_PREFIX + tokenHash(seed.rawToken()));
    assertThat(checkpoint.outboxSequence()).isEqualTo(receipt.outboxSequence());
    assertThat(checkpoint.sourceEventId()).isEqualTo(receipt.eventId());
    assertThat(checkpoint.sourceEventDigest()).isEqualTo(receipt.eventDigest());
    assertThat(sourceHead.get("current_generation", Long.class)).isEqualTo(authority.generation());
    assertThat(sourceHead.get("current_source_version", Long.class))
        .isEqualTo(authority.sourceVersion());
    assertThat(sourceHead.get("current_issuance_fence", Long.class))
        .isEqualTo(authority.issuanceFence().value());
    assertThat(sourceHead.get("current_issuance_fence_source_version", Long.class))
        .isEqualTo(authority.issuanceFence().sourceVersion());
    assertThat(sourceHead.get("last_outbox_sequence", Long.class))
        .isEqualTo(receipt.outboxSequence());
    assertThat(sourceHead.get("last_event_id", String.class)).isEqualTo(receipt.eventId());
    assertThat(sourceHead.get("last_event_digest", String.class)).isEqualTo(receipt.eventDigest());
    assertThat(sourceHead.get("cutoff_generation", Long.class)).isEqualTo(authority.generation());
    assertThat(sourceHead.get("cutoff_stream_key", String.class)).isEqualTo(streamKey);
    assertThat(sourceHead.get("cutoff_sequence", Long.class)).isEqualTo(receipt.outboxSequence());
    assertThat(decoded.accountId()).isEqualTo(seed.accountUuid().toString());
    assertThat(decoded.accountAuthorityGeneration()).isEqualTo("2");
    assertThat(decoded.sourceVersion()).isEqualTo("2");
    assertThat(payload)
        .doesNotContain(
            seed.rawToken(),
            "new-password-one",
            account.getPasswordHash(),
            seed.originalVerifier());
    assertThat(countReceipts(fixture, seed.accountId())).isEqualTo(1L);
    assertThat(countEvents(fixture, streamKey)).isEqualTo(1L);
    byte[] tokenHashBytes = HexFormat.of().parseHex(tokenHash(seed.rawToken()));
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_password_reset_operation_receipts "
                            + "SET request_digest = request_digest WHERE token_hash = ?",
                        tokenHashBytes))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_password_reset_operation_receipts WHERE token_hash = ?",
                        tokenHashBytes))
        .isInstanceOf(DataAccessException.class);
    assertThat(countReceipts(fixture, seed.accountId())).isEqualTo(1L);
  }

  @Test
  void exactRetryRecoversLostResponseWithoutAnySecondMutation() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");

    reset(fixture, seed.rawToken(), "recovered-password");
    StoredState committed = snapshot(fixture, seed);
    var journalBeforeRetry =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT password_verifier, password_verifier_digest, request_digest, "
                        + "request_payload, source_change_binding "
                        + "FROM account_password_reset_draft_source_changes WHERE token_hash = ?",
                    HexFormat.of().parseHex(tokenHash(seed.rawToken()))));
    assertThat(journalBeforeRetry.get("password_verifier", String.class)).isNull();
    reset(fixture, seed.rawToken(), "recovered-password");

    assertThat(snapshot(fixture, seed)).isEqualTo(committed);
    var journalAfterRetry =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT password_verifier, password_verifier_digest, request_digest, "
                        + "request_payload, source_change_binding "
                        + "FROM account_password_reset_draft_source_changes WHERE token_hash = ?",
                    HexFormat.of().parseHex(tokenHash(seed.rawToken()))));
    assertThat(journalAfterRetry.get("password_verifier", String.class)).isNull();
    assertThat(journalAfterRetry.get("password_verifier_digest", byte[].class))
        .isEqualTo(journalBeforeRetry.get("password_verifier_digest", byte[].class));
    assertThat(journalAfterRetry.get("request_digest", byte[].class))
        .isEqualTo(journalBeforeRetry.get("request_digest", byte[].class));
    assertThat(journalAfterRetry.get("request_payload", byte[].class))
        .isEqualTo(journalBeforeRetry.get("request_payload", byte[].class));
    assertThat(journalAfterRetry.get("source_change_binding", byte[].class))
        .isEqualTo(journalBeforeRetry.get("source_change_binding", byte[].class));
  }

  @Test
  void concurrentExactRetriesCommitOneSourceMutation() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> concurrentReset(fixture, seed, ready, start));
      var second = executor.submit(() -> concurrentReset(fixture, seed, ready, start));
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(45, TimeUnit.SECONDS);
      second.get(45, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    StoredState state = snapshot(fixture, seed);
    assertThat(state.generation()).isEqualTo(2L);
    assertThat(state.sourceVersion()).isEqualTo(2L);
    assertThat(state.issuanceFence()).isEqualTo(2L);
    assertThat(state.tokenCount()).isZero();
    assertThat(state.receiptCount()).isEqualTo(1L);
    assertThat(state.eventCount()).isEqualTo(1L);
  }

  @Test
  void waitingResetRetainsExactIntentAndSettlesOnlyAfterOriginalOwnersDeny() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");
    DraftAuthorizationFenceBinding binding = draftBinding(seed);
    transaction(
        fixture.transaction(),
        () -> {
          fixture.fences().reserve(binding);
          return null;
        });

    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), "waiting-password"))
        .isInstanceOf(
            net.firedevops.firemud.accountservice.repository
                .AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);

    assertThat(snapshot(fixture, seed))
        .isEqualTo(new StoredState(seed.originalVerifier(), 1L, 1L, 1L, 1L, 0L, 0L));
    var intentBeforeSettlement =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT status, request_payload, source_evidence, source_change_binding, "
                        + "password_verifier, password_verifier_digest "
                        + "FROM account_password_reset_draft_source_changes WHERE token_hash = ?",
                    HexFormat.of().parseHex(tokenHash(seed.rawToken()))),
            "Waiting password-reset source intent must be persisted");
    assertThat(intentBeforeSettlement.get("status", String.class)).isEqualTo("WAITING");
    byte[] requestBytes = intentBeforeSettlement.get("request_payload", byte[].class);
    byte[] capturedSource = intentBeforeSettlement.get("source_evidence", byte[].class);
    byte[] changeBinding = intentBeforeSettlement.get("source_change_binding", byte[].class);
    String waitingVerifier = intentBeforeSettlement.get("password_verifier", String.class);
    byte[] verifierDigest = intentBeforeSettlement.get("password_verifier_digest", byte[].class);
    assertThat(requestBytes).isNotEmpty();
    assertThat(capturedSource).isNotEmpty();
    assertThat(changeBinding).isNotEmpty();
    assertThat(waitingVerifier).isNotBlank();
    assertThat(verifierDigest).hasSize(32);
    assertThat(sha256Hex(waitingVerifier)).isEqualTo(HexFormat.of().formatHex(verifierDigest));
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_password_reset_draft_source_changes "
                            + "SET password_verifier = NULL WHERE token_hash = ?",
                        HexFormat.of().parseHex(tokenHash(seed.rawToken()))))
        .isInstanceOf(DataAccessException.class);
    var persistedTokenBinding =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT token_id, token_expires_at FROM "
                        + "account_password_reset_draft_source_changes WHERE token_hash = ?",
                    HexFormat.of().parseHex(tokenHash(seed.rawToken()))),
            "Waiting password-reset token binding must be persisted");
    assertThat(persistedTokenBinding.get("token_id", Long.class)).isPositive();
    assertThat(persistedTokenBinding.get("token_expires_at", LocalDateTime.class))
        .isEqualTo(seed.deadline());
    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), "different-waiting-password"))
        .isInstanceOf(OperationConflictException.class);
    assertThat(snapshot(fixture, seed))
        .isEqualTo(new StoredState(seed.originalVerifier(), 1L, 1L, 1L, 1L, 0L, 0L));
    var sameIntentAfterConflict =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT status, request_payload FROM "
                        + "account_password_reset_draft_source_changes WHERE token_hash = ?",
                    HexFormat.of().parseHex(tokenHash(seed.rawToken()))),
            "Original waiting reset intent must remain after a conflicting retry");
    assertThat(sameIntentAfterConflict.get("request_payload", byte[].class))
        .isEqualTo(requestBytes);

    transaction(
        fixture.transaction(),
        () -> {
          fixture.fences().recordOwnerReadback(binding, ownerDenial(binding, Owner.GAME_DESIGN));
          fixture.fences().recordOwnerReadback(binding, ownerDenial(binding, Owner.WORLD));
          return null;
        });
    reset(fixture, seed.rawToken(), "waiting-password");

    assertThat(snapshot(fixture, seed).generation()).isEqualTo(2L);
    assertThat(countEvents(fixture, streamKey(seed.accountUuid()))).isEqualTo(1L);
    var committedResetIntent =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT status, password_verifier, password_verifier_digest, request_digest, "
                        + "request_payload, source_change_binding "
                        + "FROM account_password_reset_draft_source_changes "
                        + "WHERE token_hash = ?",
                    HexFormat.of().parseHex(tokenHash(seed.rawToken()))),
            "Committed password-reset source intent must remain readable");
    assertThat(committedResetIntent.get("status", String.class)).isEqualTo("SOURCE_COMMITTED");
    assertThat(committedResetIntent.get("password_verifier", String.class)).isNull();
    assertThat(committedResetIntent.get("password_verifier_digest", byte[].class))
        .isEqualTo(verifierDigest);
    assertThat(committedResetIntent.get("request_digest", byte[].class)).hasSize(32);
    assertThat(committedResetIntent.get("request_payload", byte[].class)).isEqualTo(requestBytes);
    assertThat(committedResetIntent.get("source_change_binding", byte[].class))
        .isEqualTo(changeBinding);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_password_reset_draft_source_changes "
                            + "SET password_verifier_digest = password_verifier_digest "
                            + "WHERE token_hash = ?",
                        HexFormat.of().parseHex(tokenHash(seed.rawToken()))))
        .isInstanceOf(DataAccessException.class);
    var committedV76Change =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT status FROM account_draft_authorization_source_changes "
                        + "WHERE change_id = (SELECT source_change_id "
                        + "FROM account_password_reset_draft_source_changes WHERE token_hash = ?)",
                    HexFormat.of().parseHex(tokenHash(seed.rawToken()))),
            "Committed V76 source change must remain readable");
    assertThat(committedV76Change.get("status", String.class)).isEqualTo("SOURCE_COMMITTED");
  }

  @Test
  void overlappingWaitingSourceChangeMakesPasswordResetRetryableAndRollsBack() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");
    DraftAuthorizationFenceBinding committed = draftBinding(seed);
    DraftAuthorizationFenceBinding aborted = draftBinding(seed);
    transaction(
        fixture.transaction(),
        () -> {
          fixture.fences().reserve(committed);
          fixture.fences().reserve(aborted);
          fixture.fences().claimCommitOrder(committed);
          fixture.fences().claimCommitOrder(aborted);
          for (Owner owner : List.of(Owner.GAME_DESIGN, Owner.WORLD)) {
            fixture
                .fences()
                .recordOwnerReadback(committed, ownerOutcome(committed, owner, Outcome.COMMITTED));
            fixture.fences().recordOwnerReadback(aborted, ownerDenial(aborted, owner));
          }
          return null;
        });

    SourceChange overlappingChange =
        new SourceChange(
            UUID.randomUUID(),
            committed.sources(),
            "overlapping-source-change".getBytes(StandardCharsets.UTF_8));
    boolean settled =
        transaction(
            fixture.transaction(), () -> fixture.fences().requestSourceChange(overlappingChange));
    assertThat(settled).isTrue();
    assertThat(
            transaction(
                fixture.transaction(),
                () -> fixture.fences().sourceMutationPermitted(overlappingChange)))
        .isTrue();

    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), "overlapping-password"))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
    assertUnchangedAfterRollback(fixture, seed, 1L, 1L);
    assertThat(
            fixture
                .setupDsl()
                .fetchSingle(
                    "SELECT status FROM account_draft_authorization_source_changes "
                        + "WHERE change_id = ?",
                    overlappingChange.changeId())
                .get("status", String.class))
        .isEqualTo("WAITING");
    assertThat(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT token_hash FROM account_password_reset_draft_source_changes "
                        + "WHERE token_hash = ?",
                    HexFormat.of().parseHex(tokenHash(seed.rawToken()))))
        .isNull();
  }

  @Test
  void unrelatedSourcePersistenceFailurePropagatesAndRollsBackPasswordReset() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_draft_authorization_source_changes "
                + "ADD CONSTRAINT test_reject_waiting_source_change CHECK (status <> 'WAITING')");

    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), "unrelated-failure-password"))
        .isInstanceOf(DataAccessException.class);

    assertUnchangedAfterRollback(fixture, seed, 1L, 1L);
    assertThat(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT change_id FROM account_draft_authorization_source_changes "
                        + "WHERE status = 'WAITING'"))
        .isNull();
  }

  @Test
  void expiredWaitingResetAbortsAndDeletesOnlyItsExactOriginalToken() {
    Fixture fixture = newFixture();
    Seed seed =
        seedAccountAndToken(fixture, "initial-password", LocalDateTime.now().minusMinutes(1));
    DraftAuthorizationFenceBinding binding = draftBinding(seed);
    transaction(
        fixture.transaction(),
        () -> {
          fixture.fences().reserve(binding);
          return null;
        });

    String verifier = hashPasswordForTest("expired-password");
    String tokenHash = tokenHash(seed.rawToken());
    String requestDigest = passwordResetRequestDigest(seed, sha256Hex(verifier));
    transaction(
        fixture.transaction(),
        () -> {
          Account account = fixture.accounts().findById(seed.accountId()).orElseThrow();
          PasswordResetToken token = fixture.tokens().findByToken(seed.rawToken()).orElseThrow();
          ScopeState current = readAuthority(fixture, seed.accountUuid());
          fixture
              .sourceChanges()
              .participate(
                  account,
                  token.getId(),
                  tokenHash,
                  seed.deadline(),
                  requestDigest,
                  verifier,
                  current,
                  new LatestSourceSnapshot(0L, Optional.empty()));
          return null;
        });
    var waitingIntent =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT request_digest, password_verifier_digest, request_payload, "
                        + "source_change_binding FROM account_password_reset_draft_source_changes "
                        + "WHERE token_hash = ?",
                    HexFormat.of().parseHex(tokenHash)),
            "Expired waiting intent must persist its exact request");
    boolean wrongHashDeleted =
        transaction(
            fixture.transaction(),
            () -> {
              PasswordResetToken token =
                  fixture.tokens().findByToken(seed.rawToken()).orElseThrow();
              return fixture
                  .tokens()
                  .deleteExactAfterSourceAbort(
                      token.getId(), seed.accountId(), "0".repeat(64), seed.deadline());
            });
    assertThat(wrongHashDeleted).isFalse();
    assertThat(tokenExists(fixture, seed.rawToken())).isTrue();
    transaction(
        fixture.transaction(),
        () -> {
          fixture.fences().recordOwnerReadback(binding, ownerDenial(binding, Owner.GAME_DESIGN));
          fixture.fences().recordOwnerReadback(binding, ownerDenial(binding, Owner.WORLD));
          return null;
        });

    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), "expired-password"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Token expired");

    assertThat(tokenExists(fixture, seed.rawToken())).isFalse();
    assertThat(snapshot(fixture, seed))
        .isEqualTo(new StoredState(seed.originalVerifier(), 1L, 1L, 1L, 0L, 0L, 0L));
    var abortedIntent =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT status, abort_reason, password_verifier, password_verifier_digest, "
                        + "request_digest, request_payload, source_change_binding "
                        + "FROM account_password_reset_draft_source_changes WHERE token_hash = ?",
                    HexFormat.of().parseHex(tokenHash)),
            "Aborted source intent must remain readable after exact token deletion");
    assertThat(abortedIntent.get("status", String.class)).isEqualTo("SOURCE_ABORTED");
    assertThat(abortedIntent.get("abort_reason", String.class)).isEqualTo("EXPIRED");
    assertThat(abortedIntent.get("password_verifier", String.class)).isNull();
    assertThat(abortedIntent.get("password_verifier_digest", byte[].class))
        .isEqualTo(waitingIntent.get("password_verifier_digest", byte[].class));
    assertThat(abortedIntent.get("request_digest", byte[].class))
        .isEqualTo(waitingIntent.get("request_digest", byte[].class));
    assertThat(abortedIntent.get("request_payload", byte[].class))
        .isEqualTo(waitingIntent.get("request_payload", byte[].class));
    assertThat(abortedIntent.get("source_change_binding", byte[].class))
        .isEqualTo(waitingIntent.get("source_change_binding", byte[].class));
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_password_reset_draft_source_changes "
                            + "SET status = 'WAITING' WHERE token_hash = ?",
                        HexFormat.of().parseHex(tokenHash)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_password_reset_draft_source_changes "
                            + "WHERE token_hash = ?",
                        HexFormat.of().parseHex(tokenHash)))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), "expired-password"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid token");
    assertThat(tokenExists(fixture, seed.rawToken())).isFalse();
    assertThat(snapshot(fixture, seed))
        .isEqualTo(new StoredState(seed.originalVerifier(), 1L, 1L, 1L, 0L, 0L, 0L));
  }

  @Test
  void changedRetryConflictsAndConsecutiveResetsRequireExactPriorSourceProof() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");
    reset(fixture, seed.rawToken(), "first-password");
    StoredState committed = snapshot(fixture, seed);

    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), "different-password"))
        .isInstanceOf(OperationConflictException.class);
    assertThat(snapshot(fixture, seed)).isEqualTo(committed);

    String secondToken = "second-token-" + UUID.randomUUID();
    LocalDateTime secondDeadline = LocalDateTime.now().plusHours(2);
    transaction(
        fixture.transaction(),
        () -> {
          Account owner = fixture.accounts().findById(seed.accountId()).orElseThrow();
          PasswordResetToken token = new PasswordResetToken();
          token.setAccount(owner);
          token.setToken(secondToken);
          token.setExpiresAt(secondDeadline);
          fixture.tokens().save(token);
          return null;
        });
    reset(fixture, secondToken, "second-password");

    ScopeState authority = readAuthority(fixture, seed.accountUuid());
    assertThat(authority.generation()).isEqualTo(3L);
    assertThat(authority.sourceVersion()).isEqualTo(3L);
    assertThat(authority.issuanceFence().value()).isEqualTo(3L);
    assertThat(authority.issuanceFence().sourceVersion()).isEqualTo(3L);
    assertThat(countEvents(fixture, streamKey(seed.accountUuid()))).isEqualTo(2L);
    assertThat(countReceipts(fixture, seed.accountId())).isEqualTo(2L);
  }

  @Test
  void appendAndReceiptReadbackFailuresRollBackEveryEarlierMutation() {
    Fixture appendFailure = newFixture();
    Seed appendSeed = seedAccountAndToken(appendFailure, "initial-password");
    appendFailure
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "ADD CONSTRAINT reject_password_reset_producer_event "
                + "CHECK (event_id NOT LIKE 'account-password-reset-event-v1:%')");

    assertThatThrownBy(() -> reset(appendFailure, appendSeed.rawToken(), "append-failure-password"))
        .isInstanceOf(RuntimeException.class);
    assertUnchangedAfterRollback(appendFailure, appendSeed, 1L, 1L);

    Fixture readbackFailure = newFixture();
    Seed readbackSeed = seedAccountAndToken(readbackFailure, "initial-password");
    AccountPasswordResetOperationRepository hideInsertedReceipt =
        new AccountPasswordResetOperationRepository(readbackFailure.transactionDsl()) {
          private final AtomicInteger lookups = new AtomicInteger();

          @Override
          public Optional<PasswordResetReceipt> findByTokenHash(String tokenHash) {
            int lookup = lookups.incrementAndGet();
            if (lookup >= 3) {
              return Optional.empty();
            }
            return super.findByTokenHash(tokenHash);
          }
        };
    AccountServiceImpl service = newService(readbackFailure, hideInsertedReceipt);

    assertThatThrownBy(
            () ->
                transaction(
                    readbackFailure.transaction(),
                    () -> {
                      service.completePasswordReset(
                          new CompletePasswordResetRequest(
                              readbackSeed.rawToken(), "readback-failure-password"));
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt readback is missing");
    assertUnchangedAfterRollback(readbackFailure, readbackSeed, 1L, 1L);
  }

  @Test
  void issuanceFenceRejectsAnUnbackedOverflowAdvance() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_authority_issuance_fences "
                            + "SET issuance_fence = ?, source_version = ? WHERE account_uuid = ?",
                        Long.MAX_VALUE,
                        11L,
                        seed.accountUuid()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining(
            "Account authority issuance fence and source version must advance by one");
    assertUnchangedAfterRollback(fixture, seed, 1L, 1L);
  }

  private boolean concurrentReset(
      Fixture fixture, Seed seed, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    reset(fixture, seed.rawToken(), "concurrent-password");
    return true;
  }

  private Fixture newFixture() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
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
    AccountPasswordResetOperationRepository operations =
        new AccountPasswordResetOperationRepository(transactionDsl);
    AccountLogoutAllOperationRepository logoutAllOperations =
        new AccountLogoutAllOperationRepository(transactionDsl);
    AccountSecurityStateOperationRepository securityStateOperations =
        new AccountSecurityStateOperationRepository(transactionDsl);
    AccountPasswordResetDraftSourceChangeRepository sourceChanges =
        new AccountPasswordResetDraftSourceChangeRepository(transactionDsl);
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(transactionDsl);
    PasswordResetTokenRepository tokens = new PasswordResetTokenRepository(transactionDsl);
    return new Fixture(
        setupDsl,
        transactionDsl,
        transactionManager,
        transaction,
        accounts,
        authority,
        outbox,
        operations,
        logoutAllOperations,
        securityStateOperations,
        sourceChanges,
        fences,
        tokens);
  }

  private Seed seedAccountAndToken(Fixture fixture, String initialVerifier) {
    return seedAccountAndToken(fixture, initialVerifier, LocalDateTime.now().plusHours(2));
  }

  private Seed seedAccountAndToken(
      Fixture fixture, String initialVerifier, LocalDateTime deadline) {
    return transaction(
        fixture.transaction(),
        () -> {
          Account account = new Account();
          String unique = UUID.randomUUID().toString();
          account.setUsername("reset-" + unique);
          account.setEmail("reset-" + unique + "@example.test");
          account.setPasswordHash(initialVerifier);
          Account saved = fixture.accounts().save(account);

          String tokenValue = "reset-token-" + unique;
          PasswordResetToken token = new PasswordResetToken();
          token.setAccount(saved);
          token.setToken(tokenValue);
          token.setExpiresAt(deadline);
          fixture.tokens().save(token);
          LocalDateTime storedDeadline =
              fixture.tokens().findByToken(tokenValue).orElseThrow().getExpiresAt();
          return new Seed(
              saved.getId(), saved.getAccountUuid(), tokenValue, storedDeadline, initialVerifier);
        });
  }

  private AccountServiceImpl newService(
      Fixture fixture, AccountPasswordResetOperationRepository operations) {
    return new AccountServiceImpl(
        fixture.accounts(),
        fixture.authority(),
        new AccountAuthoritySourceEvidenceRepository(
            fixture.transactionDsl(), fixture.authority(), fixture.outbox()),
        fixture.outbox(),
        operations,
        fixture.logoutAllOperations(),
        fixture.securityStateOperations(),
        fixture.sourceChanges(),
        null, // audit outbox
        null, // connect scopes
        null, // join operations
        null, // email login challenges
        null, // realm grants
        null, // tenant memberships
        null, // account mapper is not on the password-reset path
        null, // profiles
        null, // profile mapper
        null, // payment transactions
        null, // subscriptions
        null, // external account identities
        fixture.tokens(),
        null, // email verification tokens
        null, // notifications
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        fixture.transactionManager());
  }

  private void reset(Fixture fixture, String rawToken, String password) {
    AccountServiceImpl service = newService(fixture, fixture.operations());
    transaction(
        fixture.transaction(),
        () -> {
          service.completePasswordReset(new CompletePasswordResetRequest(rawToken, password));
          return null;
        });
  }

  private DraftAuthorizationFenceBinding draftBinding(Seed seed) {
    var complete =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "test-only-tenant",
                2,
                "test-only-tenant",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-only/base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-only-world-payload")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "test-region",
                    "aggregate",
                    "test-region",
                    "0")));
    SourceEvidence accountSource =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            seed.accountUuid().toString(),
            "1",
            "1",
            streamKey(seed.accountUuid()),
            "0",
            "account-password-reset-source-proof".getBytes(StandardCharsets.UTF_8));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        seed.accountUuid(),
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "0",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(accountSource));
  }

  private OwnerReadback ownerDenial(DraftAuthorizationFenceBinding binding, Owner owner) {
    return ownerOutcome(binding, owner, Outcome.DEFINITIVELY_ABORTED);
  }

  private OwnerReadback ownerOutcome(
      DraftAuthorizationFenceBinding binding, Owner owner, Outcome outcome) {
    return new OwnerReadback(
        owner,
        outcome,
        binding.operationId(),
        binding.commitId(),
        binding.fenceId(),
        binding.inputDigest(),
        binding.canonicalBytes(),
        new byte[] {7});
  }

  private void assertUnchangedAfterRollback(
      Fixture fixture, Seed seed, long expectedFence, long expectedFenceSourceVersion) {
    Account account = account(fixture, seed.accountId());
    ScopeState authority = readAuthority(fixture, seed.accountUuid());
    assertThat(account.getPasswordHash()).isEqualTo(seed.originalVerifier());
    assertThat(tokenExists(fixture, seed.rawToken())).isTrue();
    assertThat(authority.generation()).isEqualTo(1L);
    assertThat(authority.sourceVersion()).isEqualTo(1L);
    assertThat(authority.issuanceFence().value()).isEqualTo(expectedFence);
    assertThat(authority.issuanceFence().sourceVersion()).isEqualTo(expectedFenceSourceVersion);
    assertThat(countReceipts(fixture, seed.accountId())).isZero();
    assertThat(countEvents(fixture, streamKey(seed.accountUuid()))).isZero();
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .setupDsl()
                        .fetchOne(
                            "SELECT last_sequence FROM account_authority_outbox_streams "
                                + "WHERE outbox_stream_key = ?",
                            streamKey(seed.accountUuid())),
                    "Fresh Account baseline checkpoint must remain after rollback")
                .get("last_sequence", Long.class))
        .isZero();
  }

  private StoredState snapshot(Fixture fixture, Seed seed) {
    Account account = account(fixture, seed.accountId());
    ScopeState authority = readAuthority(fixture, seed.accountUuid());
    return new StoredState(
        account.getPasswordHash(),
        authority.generation(),
        authority.sourceVersion(),
        authority.issuanceFence().value(),
        tokenExists(fixture, seed.rawToken()) ? 1L : 0L,
        countReceipts(fixture, seed.accountId()),
        countEvents(fixture, streamKey(seed.accountUuid())));
  }

  private Account account(Fixture fixture, long accountId) {
    return transaction(
        fixture.transaction(), () -> fixture.accounts().findById(accountId).orElseThrow());
  }

  private ScopeState readAuthority(Fixture fixture, UUID accountUuid) {
    return transaction(
        fixture.transaction(), () -> fixture.authority().read(AuthorityScope.account(accountUuid)));
  }

  private boolean tokenExists(Fixture fixture, String rawToken) {
    return transaction(
        fixture.transaction(), () -> fixture.tokens().findByToken(rawToken).isPresent());
  }

  private long countReceipts(Fixture fixture, long accountId) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_password_reset_operation_receipts WHERE account_id = ?",
                accountId)
            .fetchOne(0, Long.class),
        "Password-reset receipt count readback is missing");
  }

  private long countEvents(Fixture fixture, String streamKey) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class),
        "Password-reset event count readback is missing");
  }

  private void assertRealDraftOwnerFenceStorage(Fixture fixture) {
    var installed =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT to_regclass('account_control_ui_issuance_operations') IS NOT NULL "
                        + "AS issuance_operations, "
                        + "to_regclass('account_selected_publication_authorizations') IS NOT NULL "
                        + "AS publication_authorizations, "
                        + "to_regclass('account_selected_publication_sources') IS NOT NULL "
                        + "AS publication_sources, "
                        + "to_regclass('account_selected_publication_settlements') IS NOT NULL "
                        + "AS publication_settlements, "
                        + "to_regclass('account_game_logic_intake_authorizations') IS NOT NULL "
                        + "AS game_logic_authorizations, "
                        + "to_regclass('account_game_logic_intake_sources') IS NOT NULL "
                        + "AS game_logic_sources, "
                        + "to_regclass('account_game_logic_intake_settlements') IS NOT NULL "
                        + "AS game_logic_settlements, "
                        + "to_regclass('account_game_logic_intake_source_read_reservations') "
                        + "IS NOT NULL AS source_read_reservations, "
                        + "to_regclass('account_game_logic_intake_source_read_sources') "
                        + "IS NOT NULL AS source_read_sources, "
                        + "to_regclass('account_game_logic_intake_source_read_aborts') "
                        + "IS NOT NULL AS source_read_aborts, "
                        + "to_regprocedure('account_selected_publication_is_settled(uuid)') "
                        + "IS NOT NULL AS publication_settlement_function, "
                        + "to_regprocedure('account_game_logic_intake_is_settled(uuid)') "
                        + "IS NOT NULL AS game_logic_settlement_function, "
                        + "to_regprocedure('account_game_logic_intake_source_read_is_pending(uuid)') "
                        + "IS NOT NULL AS source_read_pending_function, "
                        + "to_regprocedure('require_selected_inventory_operation_v2(bytea)') "
                        + "IS NOT NULL AS selected_inventory_guard, "
                        + "NOT EXISTS (SELECT 1 FROM account_control_ui_issuance_operations) "
                        + "AND NOT EXISTS (SELECT 1 FROM account_selected_publication_authorizations) "
                        + "AND NOT EXISTS (SELECT 1 FROM account_game_logic_intake_authorizations) "
                        + "AND NOT EXISTS (SELECT 1 FROM account_game_logic_intake_source_read_reservations) "
                        + "AS owner_operations_absent"),
            "Real inactive owner-fence storage must be installed");
    for (String column :
        List.of(
            "issuance_operations",
            "publication_authorizations",
            "publication_sources",
            "publication_settlements",
            "game_logic_authorizations",
            "game_logic_sources",
            "game_logic_settlements",
            "source_read_reservations",
            "source_read_sources",
            "source_read_aborts",
            "publication_settlement_function",
            "game_logic_settlement_function",
            "source_read_pending_function",
            "selected_inventory_guard",
            "owner_operations_absent")) {
      assertThat(installed.get(column, Boolean.class)).as(column).isTrue();
    }

    UUID unknownOperation = UUID.randomUUID();
    var unknown =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT account_selected_publication_is_settled(?::uuid) "
                        + "AS publication_settled, "
                        + "account_game_logic_intake_is_settled(?::uuid) "
                        + "AS game_logic_settled, "
                        + "account_game_logic_intake_source_read_is_pending(?::uuid) "
                        + "AS source_read_pending",
                    unknownOperation,
                    unknownOperation,
                    unknownOperation),
            "Unknown external owner operation state must be readable");
    assertThat(unknown.get("publication_settled", Boolean.class)).isFalse();
    assertThat(unknown.get("game_logic_settled", Boolean.class)).isFalse();
    assertThat(unknown.get("source_read_pending", Boolean.class)).isFalse();
  }

  private String streamKey(UUID accountUuid) {
    return STREAM_PREFIX + accountUuid;
  }

  private String tokenHash(String rawToken) {
    return sha256Hex(rawToken);
  }

  private String passwordResetRequestDigest(Seed seed, String verifierDigest) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String field :
          List.of(
              "account-password-reset-request/v1",
              "PASSWORD_RESET",
              seed.accountUuid().toString(),
              tokenHash(seed.rawToken()),
              seed.deadline().toString(),
              verifierDigest)) {
        byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
        digest.update(encoded);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String hashPasswordForTest(String password) {
    de.mkammerer.argon2.Argon2 argon2 = de.mkammerer.argon2.Argon2Factory.create();
    char[] chars = password.toCharArray();
    try {
      return argon2.hash(1, 8192, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  private String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent password-reset barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Concurrent password-reset proof was interrupted", interrupted);
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
      AccountPasswordResetOperationRepository operations,
      AccountLogoutAllOperationRepository logoutAllOperations,
      AccountSecurityStateOperationRepository securityStateOperations,
      AccountPasswordResetDraftSourceChangeRepository sourceChanges,
      DraftAuthorizationFenceRepository fences,
      PasswordResetTokenRepository tokens) {}

  private record Seed(
      long accountId,
      UUID accountUuid,
      String rawToken,
      LocalDateTime deadline,
      String originalVerifier) {}

  private record StoredState(
      String passwordVerifier,
      long generation,
      long sourceVersion,
      long issuanceFence,
      long tokenCount,
      long receiptCount,
      long eventCount) {}
}
