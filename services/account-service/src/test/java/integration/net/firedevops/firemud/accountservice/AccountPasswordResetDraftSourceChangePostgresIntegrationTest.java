package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository.AbortReason;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository.Participation;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository.PendingResetIntent;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository.PendingResetSnapshot;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof for V61 storage and V57 source-ordering participation only.
 *
 * <p>Owner outcomes in these fixtures are synthetic rows supplied directly to the Account fence.
 * They model exact durable upstream readback shapes; they do not authenticate an owner producer or
 * prove the public password-reset flow, source recovery worker, or cleanup worker.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountPasswordResetDraftSourceChangePostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "pw_reset_draft_source";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String TARGET_VERIFIER =
      "$argon2id$v=19$m=65536,t=3,p=1$AAAAAAAAAAAAAAAAAAAAAA$"
          + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final byte[] TARGET_VERIFIER_BYTES =
      TARGET_VERIFIER.getBytes(StandardCharsets.UTF_8);
  private static final String ORIGINAL_VERIFIER = "unchanged-original-verifier";
  private static final HexFormat HEX = HexFormat.of();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir Path temporaryDirectory;

  @Test
  void liveWaitingRetainsExactEnvelopeAndSourceAcrossExactRetryWithoutAccountMutation()
      throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, Duration.ofHours(2));
    AccountState accountBefore = accountState(fixture, seed);

    PendingCase pending = beginPending(fixture, seed, FenceOrder.REVOKE);
    PendingResetSnapshot snapshot = durableSnapshot(fixture, seed, pending.intent());
    StoredState retained = storedState(fixture, seed, pending);
    AccountEncryptedEnvelope storedEnvelope =
        transaction(
            fixture.transaction(),
            () -> fixture.pendingResets().readPendingEnvelope(snapshot).orElseThrow());
    assertThat(storedEnvelope).isEqualTo(pending.originalEnvelope());
    byte[] recoveredVerifier =
        fixture.crypto().decryptPendingReset(storedEnvelope, pending.intent().envelopeBinding());
    try {
      assertThat(recoveredVerifier).containsExactly(TARGET_VERIFIER_BYTES);
    } finally {
      Arrays.fill(recoveredVerifier, (byte) 0);
    }

    assertThat(snapshot.status()).isEqualTo("WAITING");
    assertThat(snapshot.envelopePresent()).isTrue();
    assertThat(snapshot.intent()).isEqualTo(pending.intent());
    assertThat(
            transaction(
                fixture.transaction(),
                () -> fixture.fences().readSourceChange(pending.intent().sourceChange()).binding()))
        .containsExactly(pending.intent().sourceChange().canonicalBytes());
    assertThat(accountState(fixture, seed)).isEqualTo(accountBefore);

    Participation exactRetry =
        transaction(
            fixture.transaction(),
            () -> fixture.pendingResets().beginWaiting(pending.intent(), Optional.empty()));

    assertThat(exactRetry.snapshot().intent()).isEqualTo(snapshot.intent());
    assertThat(exactRetry.snapshot().status()).isEqualTo("WAITING");
    assertThat(exactRetry.snapshot().envelopePresent()).isTrue();
    assertThat(storedState(fixture, seed, pending)).isEqualTo(retained);
    assertThat(accountState(fixture, seed)).isEqualTo(accountBefore);
  }

  @Test
  void changedRequestAccountTokenBindingAndRemintAreRejectedWithoutMutation() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, Duration.ofHours(2));
    PendingCase pending = beginPending(fixture, seed, FenceOrder.REVOKE);
    StoredState retained = storedState(fixture, seed, pending);
    AccountState accountBefore = accountState(fixture, seed);

    assertThatThrownBy(
            () ->
                transaction(
                    fixture.transaction(),
                    () ->
                        fixture
                            .pendingResets()
                            .findForUpdate(
                                seed.accountId(), UUID.randomUUID(), pending.intent().tokenHash())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            transaction(
                fixture.transaction(),
                () ->
                    fixture
                        .pendingResets()
                        .findForUpdate(
                            seed.accountId(),
                            seed.accountUuid(),
                            tokenHash("different-reset-token"))
                        .isEmpty()))
        .isTrue();

    PendingResetIntent changedRequest =
        recapture(
            fixture,
            seed,
            pending.intent().tokenExpiresAt().plusMinutes(1),
            TARGET_VERIFIER_BYTES,
            UUID.randomUUID());
    assertRejectedBegin(fixture, changedRequest, Optional.empty());

    PendingResetIntent changedPassword =
        recapture(
            fixture,
            seed,
            pending.intent().tokenExpiresAt(),
            "$argon2id$v=19$m=65536,t=3,p=1$BBBBBBBBBBBBBBBBBBBBBB$"
                .concat("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                .getBytes(StandardCharsets.UTF_8),
            UUID.randomUUID());
    assertRejectedBegin(fixture, changedPassword, Optional.empty());

    PendingResetIntent changedSourceBinding =
        recapture(
            fixture,
            seed,
            pending.intent().tokenExpiresAt(),
            TARGET_VERIFIER_BYTES,
            UUID.randomUUID());
    assertRejectedBegin(fixture, changedSourceBinding, Optional.empty());
    assertRejectedBegin(fixture, pending.intent(), Optional.of(encrypt(fixture, pending.intent())));

    assertThat(storedState(fixture, seed, pending)).isEqualTo(retained);
    assertThat(accountState(fixture, seed)).isEqualTo(accountBefore);
  }

  @Test
  void deferredGuardRejectsLiveWaitingWithoutEnvelopeAtTransactionCommit() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, Duration.ofHours(2));
    PendingResetIntent intent =
        recapture(fixture, seed, seed.deadline(), TARGET_VERIFIER_BYTES, UUID.randomUUID());
    AccountState accountBefore = accountState(fixture, seed);

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status -> {
                          Participation settled =
                              fixture.pendingResets().beginWaiting(intent, Optional.empty());
                          assertThat(settled.settledAtClaim()).isTrue();
                          return null;
                        }))
        .hasStackTraceContaining(
            "WAITING password reset cannot mutate source or lose its live envelope");

    assertThat(accountState(fixture, seed)).isEqualTo(accountBefore);
    assertThat(
            rows(
                fixture.setupDsl(),
                "account_password_reset_draft_source_changes",
                "request_id = ?",
                intent.requestId()))
        .isEmpty();
    assertThat(
            rows(
                fixture.setupDsl(),
                "account_draft_authorization_source_changes",
                "change_id = ?",
                intent.sourceChange().changeId()))
        .isEmpty();
    assertThat(
            rows(
                fixture.setupDsl(),
                "account_password_reset_pending_envelopes",
                "request_id = ?",
                intent.requestId()))
        .isEmpty();
  }

  @Test
  void commitAndRevokeOrderingRequireTheirExactDefinitiveOwnerOutcomesBeforeSourceAbort()
      throws Exception {
    Fixture revokeFixture = newFixture();
    Seed revokeSeed = seedAccountAndToken(revokeFixture, Duration.ofHours(2));
    PendingCase revokePending = beginPending(revokeFixture, revokeSeed, FenceOrder.REVOKE);
    PendingResetSnapshot revokeSnapshot =
        durableSnapshot(revokeFixture, revokeSeed, revokePending.intent());

    assertThat(readOrdering(revokeFixture, revokePending.binding()))
        .isEqualTo(Ordering.REVOKE_ORDER);
    assertThat(abort(revokeFixture, revokeSnapshot, AbortReason.DEFINITIVE_ABORT)).isFalse();
    recordOwner(revokeFixture, revokePending.binding(), Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    assertThat(abort(revokeFixture, revokeSnapshot, AbortReason.DEFINITIVE_ABORT)).isFalse();
    recordOwner(
        revokeFixture, revokePending.binding(), Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThat(readSettlement(revokeFixture, revokePending.binding()))
        .isEqualTo(Settlement.FAILED_NONPUBLICATION);
    assertThat(abort(revokeFixture, revokeSnapshot, AbortReason.DEFINITIVE_ABORT)).isTrue();

    Fixture commitFixture = newFixture();
    Seed commitSeed = seedAccountAndToken(commitFixture, Duration.ofHours(2));
    PendingCase commitPending = beginPending(commitFixture, commitSeed, FenceOrder.COMMIT);
    PendingResetSnapshot commitSnapshot =
        durableSnapshot(commitFixture, commitSeed, commitPending.intent());

    assertThat(readOrdering(commitFixture, commitPending.binding()))
        .isEqualTo(Ordering.COMMIT_ORDER);
    recordOwner(commitFixture, commitPending.binding(), Owner.WORLD, Outcome.COMMITTED);
    assertThat(abort(commitFixture, commitSnapshot, AbortReason.DEFINITIVE_ABORT)).isFalse();
    recordOwner(
        commitFixture, commitPending.binding(), Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThat(readSettlement(commitFixture, commitPending.binding()))
        .isEqualTo(Settlement.PENDING);
    StoredState mixedEvidence = storedState(commitFixture, commitSeed, commitPending);
    assertThat(abort(commitFixture, commitSnapshot, AbortReason.DEFINITIVE_ABORT)).isFalse();
    assertThat(readSettlement(commitFixture, commitPending.binding()))
        .isEqualTo(Settlement.PENDING);
    assertThat(readSourceStatus(commitFixture, commitPending.intent().sourceChange()))
        .isEqualTo("WAITING");
    assertThat(durableSnapshot(commitFixture, commitSeed, commitPending.intent()).status())
        .isEqualTo("WAITING");
    assertThat(storedState(commitFixture, commitSeed, commitPending)).isEqualTo(mixedEvidence);
    assertThat(
            transaction(
                commitFixture.transaction(),
                () ->
                    commitFixture
                        .fences()
                        .readOwnerResult(commitPending.binding(), Owner.WORLD)
                        .orElseThrow()
                        .outcome()))
        .isEqualTo(Outcome.COMMITTED);
    assertThat(
            transaction(
                commitFixture.transaction(),
                () ->
                    commitFixture
                        .fences()
                        .readOwnerResult(commitPending.binding(), Owner.GAME_DESIGN)
                        .orElseThrow()
                        .outcome()))
        .isEqualTo(Outcome.DEFINITIVELY_ABORTED);
  }

  @Test
  void expiryErasesSecretButDoesNotSettleOrReleaseUnresolvedSourceParticipation() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, Duration.ofSeconds(4));
    PendingCase pending = beginPending(fixture, seed, FenceOrder.REVOKE);
    PendingResetSnapshot original = durableSnapshot(fixture, seed, pending.intent());

    awaitExpiry(pending.intent().tokenExpiresAt());
    assertThat(
            transaction(
                fixture.transaction(),
                () ->
                    fixture
                        .pendingResets()
                        .eraseEnvelopeAfterExpiry(original, LocalDateTime.now())))
        .isTrue();

    PendingResetSnapshot erased = durableSnapshot(fixture, seed, pending.intent());
    assertThat(erased.status()).isEqualTo("WAITING");
    assertThat(erased.envelopePresent()).isFalse();
    assertThat(readSourceStatus(fixture, pending.intent().sourceChange())).isEqualTo("WAITING");
    assertThat(readOrdering(fixture, pending.binding())).isEqualTo(Ordering.REVOKE_ORDER);
    assertThat(abort(fixture, erased, AbortReason.EXPIRED)).isFalse();
    assertThat(readSourceStatus(fixture, pending.intent().sourceChange())).isEqualTo("WAITING");

    recordOwner(fixture, pending.binding(), Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    recordOwner(fixture, pending.binding(), Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThat(abort(fixture, erased, AbortReason.EXPIRED)).isTrue();
  }

  @Test
  void lateRollbackRestoresBothPendingResetJournalAndV57SourceFence() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, Duration.ofHours(2));
    PendingCase pending = beginPending(fixture, seed, FenceOrder.REVOKE);
    PendingResetSnapshot waiting = durableSnapshot(fixture, seed, pending.intent());
    recordOwner(fixture, pending.binding(), Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    recordOwner(fixture, pending.binding(), Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    StoredState beforeAbort = storedState(fixture, seed, pending);

    assertThatThrownBy(
            () ->
                transaction(
                    fixture.transaction(),
                    () -> {
                      assertThat(
                              fixture
                                  .pendingResets()
                                  .abort(
                                      waiting, AbortReason.DEFINITIVE_ABORT, LocalDateTime.now()))
                          .isTrue();
                      throw new DeliberateRollback();
                    }))
        .isInstanceOf(DeliberateRollback.class);

    assertThat(storedState(fixture, seed, pending)).isEqualTo(beforeAbort);
    assertThat(durableSnapshot(fixture, seed, pending.intent()).status()).isEqualTo("WAITING");
  }

  @Test
  void sourceAbortedIsImmutableNoMutationTerminalDistinctFromSourceCommit() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, Duration.ofHours(2));
    AccountState accountBefore = accountState(fixture, seed);
    PendingCase pending = beginPending(fixture, seed, FenceOrder.REVOKE);
    PendingResetSnapshot waiting = durableSnapshot(fixture, seed, pending.intent());
    recordOwner(fixture, pending.binding(), Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    recordOwner(fixture, pending.binding(), Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);

    assertThat(abort(fixture, waiting, AbortReason.DEFINITIVE_ABORT)).isTrue();

    PendingResetSnapshot aborted = durableSnapshot(fixture, seed, pending.intent());
    StoredState terminal = storedState(fixture, seed, pending);
    assertThat(aborted.status()).isEqualTo("SOURCE_ABORTED");
    assertThat(aborted.abortReason()).isEqualTo(AbortReason.DEFINITIVE_ABORT);
    assertThat(aborted.committedAt()).isNull();
    assertThat(aborted.eventId()).isNull();
    assertThat(aborted.eventPayload()).isNull();
    assertThat(aborted.envelopePresent()).isFalse();
    assertThat(readSourceStatus(fixture, pending.intent().sourceChange()))
        .isEqualTo("SOURCE_ABORTED");
    assertThat(readSourceAbortReason(fixture, pending.intent().sourceChange()))
        .isEqualTo(DraftAuthorizationFenceRepository.SourceChangeAbortReason.DEFINITIVE_ABORT);
    assertThat(accountState(fixture, seed)).isEqualTo(accountBefore);
    assertThat(countReceipts(fixture, seed.accountId())).isZero();
    assertThat(countEvents(fixture, STREAM_PREFIX + seed.accountUuid())).isZero();
    assertThat(
            transaction(
                fixture.transaction(),
                () ->
                    fixture
                        .pendingResets()
                        .abort(aborted, AbortReason.DEFINITIVE_ABORT, LocalDateTime.now())))
        .isTrue();

    assertThatThrownBy(
            () ->
                transaction(
                    fixture.transaction(),
                    () -> {
                      fixture.fences().markSourceCommitted(pending.intent().sourceChange());
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                transaction(
                    fixture.transaction(),
                    () ->
                        fixture
                            .pendingResets()
                            .abort(aborted, AbortReason.EXPIRED, LocalDateTime.now())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_password_reset_draft_source_changes"
                            + " SET abort_reason = 'EXPIRED' WHERE request_id = ?",
                        pending.intent().requestId()))
        .isInstanceOf(DataAccessException.class);

    assertThat(storedState(fixture, seed, pending)).isEqualTo(terminal);
  }

  private Fixture newFixture() throws Exception {
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
    AccountPasswordResetOperationRepository operations =
        new AccountPasswordResetOperationRepository(transactionDsl);
    AccountLogoutAllOperationRepository logoutAll =
        new AccountLogoutAllOperationRepository(transactionDsl);
    PasswordResetTokenRepository tokens = new PasswordResetTokenRepository(transactionDsl);
    DraftAuthorizationFenceRepository fences =
        new DraftAuthorizationFenceRepository(transactionDsl);
    AccountPasswordResetDraftSourceChangeRepository pendingResets =
        new AccountPasswordResetDraftSourceChangeRepository(transactionDsl);
    AccountAuthoritySourceEventReadback sourceReadback =
        new AccountAuthoritySourceEventReadback(
            outbox,
            operations,
            logoutAll,
            new net.firedevops.firemud.accountservice.repository
                .AccountSecurityStateOperationRepository(transactionDsl));
    Path manifest = temporaryDirectory.resolve(UUID.randomUUID() + ".manifest.v1");
    writeKeyRing(manifest);
    return new Fixture(
        setupDsl,
        transaction,
        accounts,
        authority,
        outbox,
        operations,
        logoutAll,
        tokens,
        fences,
        pendingResets,
        sourceReadback,
        new AccountEnvelopeCrypto(manifest));
  }

  private Seed seedAccountAndToken(Fixture fixture, Duration lifetime) {
    return transaction(
        fixture.transaction(),
        () -> {
          Account account = new Account();
          String unique = UUID.randomUUID().toString();
          account.setUsername("pending-reset-" + unique);
          account.setEmail("pending-reset-" + unique + "@example.test");
          account.setPasswordHash(ORIGINAL_VERIFIER);
          Account saved = fixture.accounts().save(account);
          fixture.authority().initialize(AuthorityScope.account(saved.getAccountUuid()));

          String rawToken = "pending-reset-token-" + unique;
          PasswordResetToken token = new PasswordResetToken();
          token.setAccount(saved);
          token.setToken(rawToken);
          token.setExpiresAt(LocalDateTime.now().plus(lifetime));
          fixture.tokens().save(token);
          LocalDateTime storedDeadline =
              fixture.tokens().findByToken(rawToken).orElseThrow().getExpiresAt();
          return new Seed(
              saved.getId(), saved.getAccountUuid(), rawToken, storedDeadline, ORIGINAL_VERIFIER);
        });
  }

  private PendingCase beginPending(Fixture fixture, Seed seed, FenceOrder order) {
    PendingResetIntent intent =
        recapture(fixture, seed, seed.deadline(), TARGET_VERIFIER_BYTES, UUID.randomUUID());
    DraftAuthorizationFenceBinding binding =
        order == FenceOrder.NONE ? null : draftBinding(seed.accountUuid(), intent.sourceChange());
    if (binding != null) {
      transaction(
          fixture.transaction(),
          () -> {
            fixture.fences().reserve(binding);
            if (order == FenceOrder.COMMIT) {
              fixture.fences().claimCommitOrder(binding);
            }
            return null;
          });
    }
    AccountEncryptedEnvelope envelope = encrypt(fixture, intent);
    Participation participation =
        transaction(
            fixture.transaction(),
            () -> fixture.pendingResets().beginWaiting(intent, Optional.of(envelope)));
    if (!participation.snapshot().status().equals("WAITING")) {
      throw new IllegalStateException("Pending reset fixture did not persist WAITING");
    }
    return new PendingCase(intent, binding, envelope);
  }

  private PendingResetIntent recapture(
      Fixture fixture,
      Seed seed,
      LocalDateTime deadline,
      byte[] verifierBytes,
      UUID sourceChangeId) {
    return transaction(
        fixture.transaction(),
        () -> {
          Account account = fixture.accounts().findById(seed.accountId()).orElseThrow();
          ScopeState authority =
              fixture.authority().read(AuthorityScope.account(seed.accountUuid()));
          AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest =
              fixture.sourceReadback().requireCurrentLatest(account, authority);
          byte[] verifierDigest = sha256(verifierBytes);
          String tokenHash = tokenHash(seed.rawToken());
          return fixture
              .pendingResets()
              .captureIntent(
                  account,
                  tokenHash,
                  deadline,
                  requestDigest(seed.accountUuid(), tokenHash, deadline, verifierDigest),
                  verifierDigest,
                  authority,
                  latest,
                  sourceChangeId);
        });
  }

  private DraftAuthorizationFenceBinding draftBinding(UUID accountUuid, SourceChange sourceChange) {
    DraftCommitBinding complete =
        DraftCommitBinding.create(
            new TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "tenant-key",
                2,
                "tenant-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "opaque/base:pending-reset-proof",
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "source-ordering-fixture")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "9007199254740999")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        accountUuid,
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "2",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        sourceChange.sources());
  }

  private AccountEncryptedEnvelope encrypt(Fixture fixture, PendingResetIntent intent) {
    return fixture.crypto().encryptPendingReset(intent.envelopeBinding(), TARGET_VERIFIER_BYTES);
  }

  private void assertRejectedBegin(
      Fixture fixture,
      PendingResetIntent changedIntent,
      Optional<AccountEncryptedEnvelope> remint) {
    assertThatThrownBy(
            () ->
                transaction(
                    fixture.transaction(),
                    () -> fixture.pendingResets().beginWaiting(changedIntent, remint)))
        .isInstanceOf(RuntimeException.class);
  }

  private PendingResetSnapshot durableSnapshot(
      Fixture fixture, Seed seed, PendingResetIntent intent) {
    return transaction(
        fixture.transaction(),
        () ->
            fixture
                .pendingResets()
                .findForUpdate(seed.accountId(), seed.accountUuid(), intent.tokenHash())
                .orElseThrow());
  }

  private AccountState accountState(Fixture fixture, Seed seed) {
    return transaction(
        fixture.transaction(),
        () -> {
          Account account = fixture.accounts().findById(seed.accountId()).orElseThrow();
          ScopeState authority =
              fixture.authority().read(AuthorityScope.account(seed.accountUuid()));
          return new AccountState(
              account.getPasswordHash(),
              authority.generation(),
              authority.sourceVersion(),
              authority.issuanceFence().value(),
              authority.issuanceFence().sourceVersion(),
              rows(fixture.setupDsl(), "password_reset_token", "account_id = ?", seed.accountId()),
              rows(
                  fixture.setupDsl(),
                  "account_password_reset_operation_receipts",
                  "account_id = ?",
                  seed.accountId()),
              rows(
                  fixture.setupDsl(),
                  "account_authority_outbox_events",
                  "outbox_stream_key = ?",
                  STREAM_PREFIX + seed.accountUuid()),
              rows(
                  fixture.setupDsl(),
                  "account_authority_outbox_streams",
                  "outbox_stream_key = ?",
                  STREAM_PREFIX + seed.accountUuid()),
              countReceipts(fixture, seed.accountId()),
              countEvents(fixture, STREAM_PREFIX + seed.accountUuid()));
        });
  }

  private StoredState storedState(Fixture fixture, Seed seed, PendingCase pending) {
    DraftAuthorizationFenceBinding binding = pending.binding();
    SourceChange sourceChange = pending.intent().sourceChange();
    String requestId = pending.intent().requestId();
    return new StoredState(
        accountState(fixture, seed),
        rows(
            fixture.setupDsl(),
            "account_password_reset_draft_source_changes",
            "request_id = ?",
            requestId),
        rows(
            fixture.setupDsl(),
            "account_password_reset_pending_envelopes",
            "request_id = ?",
            requestId),
        rows(
            fixture.setupDsl(),
            "account_draft_authorization_source_changes",
            "change_id = ?",
            sourceChange.changeId()),
        rows(
            fixture.setupDsl(),
            "account_draft_authorization_changed_scopes",
            "change_id = ?",
            sourceChange.changeId()),
        binding == null
            ? List.of()
            : rows(
                fixture.setupDsl(),
                "account_draft_authorization_fences",
                "operation_id = ?",
                binding.operationId()),
        binding == null
            ? List.of()
            : rows(
                fixture.setupDsl(),
                "account_draft_authorization_sources",
                "operation_id = ?",
                binding.operationId()),
        binding == null
            ? List.of()
            : rows(
                fixture.setupDsl(),
                "account_draft_authorization_owner_readbacks",
                "operation_id = ?",
                binding.operationId()));
  }

  private List<String> rows(DSLContext dsl, String table, String predicate, Object... arguments) {
    return dsl
        .fetch("SELECT * FROM " + table + " WHERE " + predicate + " ORDER BY 1", arguments)
        .stream()
        .map(
            row -> {
              List<String> values = new ArrayList<>();
              for (Field<?> field : row.fields()) {
                Object value = row.get(field);
                String rendered =
                    value instanceof byte[] bytes
                        ? HEX.formatHex(bytes)
                        : value == null ? "<null>" : value.toString();
                values.add(field.getName() + "=" + rendered);
              }
              return String.join("|", values);
            })
        .toList();
  }

  private long countReceipts(Fixture fixture, long accountId) {
    return countRows(
        fixture.setupDsl(),
        "account_password_reset_operation_receipts",
        "account_id = ?",
        accountId);
  }

  private long countEvents(Fixture fixture, String streamKey) {
    return countRows(
        fixture.setupDsl(), "account_authority_outbox_events", "outbox_stream_key = ?", streamKey);
  }

  private long countRows(DSLContext dsl, String table, String predicate, Object... arguments) {
    var result = dsl.fetchOne("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, arguments);
    if (result == null) {
      throw new AssertionError("Count query returned no row for " + table);
    }
    return result.get(0, Long.class);
  }

  private Ordering readOrdering(Fixture fixture, DraftAuthorizationFenceBinding binding) {
    return transaction(fixture.transaction(), () -> fixture.fences().read(binding).ordering());
  }

  private Settlement readSettlement(Fixture fixture, DraftAuthorizationFenceBinding binding) {
    return transaction(fixture.transaction(), () -> fixture.fences().readSettlement(binding));
  }

  private String readSourceStatus(Fixture fixture, SourceChange change) {
    return transaction(
        fixture.transaction(), () -> fixture.fences().readSourceChange(change).status());
  }

  private DraftAuthorizationFenceRepository.SourceChangeAbortReason readSourceAbortReason(
      Fixture fixture, SourceChange change) {
    return transaction(
        fixture.transaction(), () -> fixture.fences().readSourceChange(change).abortReason());
  }

  private boolean abort(Fixture fixture, PendingResetSnapshot snapshot, AbortReason reason) {
    return transaction(
        fixture.transaction(),
        () -> fixture.pendingResets().abort(snapshot, reason, LocalDateTime.now()));
  }

  private void recordOwner(
      Fixture fixture, DraftAuthorizationFenceBinding binding, Owner owner, Outcome outcome) {
    OwnerReadback readback =
        new OwnerReadback(
            owner,
            outcome,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            binding.canonicalBytes(),
            ("synthetic durable fixture result for " + owner + " " + outcome)
                .getBytes(StandardCharsets.UTF_8));
    transaction(
        fixture.transaction(),
        () -> {
          fixture.fences().recordOwnerReadback(binding, readback);
          return null;
        });
  }

  private void awaitExpiry(LocalDateTime deadline) throws InterruptedException {
    while (!LocalDateTime.now().isAfter(deadline.plusNanos(250_000_000))) {
      Thread.sleep(50);
    }
  }

  private void writeKeyRing(Path manifest) throws Exception {
    byte[] connectKey = key(1);
    byte[] loginKey = key(2);
    byte[] resetKey = key(3);
    String contents =
        "version=1\n"
            + "activeKeyId=k1\n"
            + "key:k1:bare-login="
            + Base64.getUrlEncoder().withoutPadding().encodeToString(loginKey)
            + "\nkey:k1:connect-token="
            + Base64.getUrlEncoder().withoutPadding().encodeToString(connectKey)
            + "\nkey:k1:pending-reset="
            + Base64.getUrlEncoder().withoutPadding().encodeToString(resetKey)
            + "\n";
    Files.writeString(manifest, contents, StandardCharsets.US_ASCII);
    Arrays.fill(connectKey, (byte) 0);
    Arrays.fill(loginKey, (byte) 0);
    Arrays.fill(resetKey, (byte) 0);
  }

  private static byte[] key(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  private String tokenHash(String rawToken) {
    return HEX.formatHex(sha256(rawToken.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] requestDigest(
      UUID accountUuid, String tokenHash, LocalDateTime deadline, byte[] verifierDigest) {
    return sha256(
        framed(
            List.of(
                "account-password-reset-request/v1",
                "PASSWORD_RESET",
                accountUuid.toString(),
                tokenHash,
                deadline.toString(),
                HEX.formatHex(verifierDigest))));
  }

  private static byte[] framed(List<String> fields) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (String field : fields) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      output.writeBytes(bytes);
    }
    return output.toByteArray();
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private <T> T transaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private enum FenceOrder {
    NONE,
    REVOKE,
    COMMIT
  }

  private record Fixture(
      DSLContext setupDsl,
      TransactionTemplate transaction,
      AccountRepository accounts,
      AccountAuthorityGenerationRepository authority,
      AccountAuthorityOutboxRepository outbox,
      AccountPasswordResetOperationRepository operations,
      AccountLogoutAllOperationRepository logoutAll,
      PasswordResetTokenRepository tokens,
      DraftAuthorizationFenceRepository fences,
      AccountPasswordResetDraftSourceChangeRepository pendingResets,
      AccountAuthoritySourceEventReadback sourceReadback,
      AccountEnvelopeCrypto crypto) {}

  private record Seed(
      long accountId,
      UUID accountUuid,
      String rawToken,
      LocalDateTime deadline,
      String originalVerifier) {}

  private record PendingCase(
      PendingResetIntent intent,
      DraftAuthorizationFenceBinding binding,
      AccountEncryptedEnvelope originalEnvelope) {}

  private record AccountState(
      String passwordHash,
      long generation,
      long sourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      List<String> resetTokens,
      List<String> receipts,
      List<String> events,
      List<String> checkpoints,
      long receiptCount,
      long eventCount) {}

  private record StoredState(
      AccountState account,
      List<String> passwordResetJournal,
      List<String> pendingEnvelope,
      List<String> sourceChange,
      List<String> changedScope,
      List<String> fence,
      List<String> source,
      List<String> ownerReadbacks) {}

  private static final class DeliberateRollback extends RuntimeException {}
}
