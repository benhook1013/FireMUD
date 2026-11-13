package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository.LogoutAllReceipt;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer.LogoutAllResult;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllDraftSourceChangeRepository.PendingIntentSnapshot;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountLogoutAllDraftSettlementPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "logout_src";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String EVENT_ID_PREFIX = "account-logout-all-event-v1:";
  private static final String TOKEN_PROFILE = "control-ui";

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
  void mixedCommitOrderHoldsLogoutAuthorityAndPreservesOriginalOwnerResult() {
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
        .isEqualTo(Settlement.PENDING);
    var originalWorldResult =
        transaction(
                fixture.transaction(),
                () -> draftFences(fixture).readOwnerResult(binding, Owner.WORLD))
            .orElseThrow();
    assertThat(originalWorldResult.outcome()).isEqualTo(Outcome.COMMITTED);
    assertThat(originalWorldResult.readback()).containsExactly(expectedWorldReadbackBytes);

    assertPending(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
    assertThat(snapshot(fixture, seed)).isEqualTo(before);
    assertThat(pendingIntent(fixture, requestId).orElseThrow().status()).isEqualTo("WAITING");
    assertThat(
            transaction(
                fixture.transaction(),
                () -> draftFences(fixture).readSourceChange(sourceChange).status()))
        .isEqualTo("WAITING");
    var replayedWorldResult =
        transaction(
                fixture.transaction(),
                () -> draftFences(fixture).readOwnerResult(binding, Owner.WORLD))
            .orElseThrow();
    assertThat(replayedWorldResult.outcome()).isEqualTo(originalWorldResult.outcome());
    assertThat(replayedWorldResult.readback()).containsExactly(expectedWorldReadbackBytes);
    assertThat(replayedWorldResult.recordedAt()).isEqualTo(originalWorldResult.recordedAt());
    assertThat(count(fixture, "account_authority_outbox_events")).isZero();
    assertThat(count(fixture, "account_logout_all_operation_receipts")).isZero();
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
        new net.firedevops.firemud.accountservice.repository
            .AccountAuthoritySourceEvidenceRepository(
            fixture.transactionDsl(), fixture.authority(), fixture.outbox()),
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
