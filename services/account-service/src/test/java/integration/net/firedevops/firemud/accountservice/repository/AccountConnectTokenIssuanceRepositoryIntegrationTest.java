package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceOperation.Lifecycle;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountConnectTokenIssuanceRepositoryIntegrationTest {
  private static final String SCHEMA = "account_connect_token_issuance_proof";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void claimIsFirstWriterWinsAndChangedDigestDoesNotMutateTheOperation() {
    TestContext context = newTestContext();
    AccountConnectTokenIssuanceIdentity identity = newIdentity(insertAccount(context.dsl()));
    byte[] digest = digest(1);

    var claimed =
        inTransaction(context.transaction(), () -> context.repository().claim(identity, digest));
    var exactRetry =
        inTransaction(context.transaction(), () -> context.repository().claim(identity, digest));

    assertThat(claimed.disposition())
        .isEqualTo(AccountConnectTokenIssuanceRepository.ClaimDisposition.CLAIMED);
    assertThat(exactRetry.disposition())
        .isEqualTo(AccountConnectTokenIssuanceRepository.ClaimDisposition.REPLAYED);
    assertThat(exactRetry.operation().operationId()).isEqualTo(claimed.operation().operationId());
    assertThat(exactRetry.operation().lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .completeWithEnvelope(
                                exactRetry,
                                digest,
                                Lifecycle.FAILED,
                                "JOIN_REQUIRED",
                                null,
                                null,
                                binding(exactRetry.operation().operationId(), identity, digest),
                                encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Only the first");

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> context.repository().claim(identity, digest(2))))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.IdempotencyConflictException.class);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().find(identity, digest).orElseThrow()))
        .isEqualTo(claimed.operation());
  }

  @Test
  void concurrentExactIssuanceClaimsHaveOneTerminalFirstWriter() throws Exception {
    TestContext context = newTestContext();
    AccountConnectTokenIssuanceIdentity identity = newIdentity(insertAccount(context.dsl()));
    byte[] requestDigest = digest(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<AccountConnectTokenIssuanceRepository.ClaimResult> issue =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Issuance claim race did not start together");
            }
            return inTransaction(
                context.transaction(),
                () -> {
                  var claim = context.repository().claim(identity, requestDigest);
                  if (claim.disposition()
                      == AccountConnectTokenIssuanceRepository.ClaimDisposition.CLAIMED) {
                    AccountEnvelopeBinding binding =
                        binding(claim.operation().operationId(), identity, requestDigest);
                    context
                        .repository()
                        .completeWithEnvelope(
                            claim,
                            requestDigest,
                            Lifecycle.FAILED,
                            "JOIN_REQUIRED",
                            null,
                            null,
                            binding,
                            encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE));
                  }
                  return claim;
                });
          };
      Future<AccountConnectTokenIssuanceRepository.ClaimResult> first = executor.submit(issue);
      Future<AccountConnectTokenIssuanceRepository.ClaimResult> second = executor.submit(issue);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results)
          .extracting(AccountConnectTokenIssuanceRepository.ClaimResult::disposition)
          .containsExactlyInAnyOrder(
              AccountConnectTokenIssuanceRepository.ClaimDisposition.CLAIMED,
              AccountConnectTokenIssuanceRepository.ClaimDisposition.REPLAYED);
      assertThat(results.get(0).operation().operationId())
          .isEqualTo(results.get(1).operation().operationId());
      assertThat(
              inTransaction(
                      context.transaction(),
                      () -> context.repository().find(identity, requestDigest).orElseThrow())
                  .lifecycle())
          .isEqualTo(Lifecycle.FAILED);
      AccountEnvelopeBinding binding =
          binding(results.get(0).operation().operationId(), identity, requestDigest);
      assertThat(
              inTransaction(
                  context.transaction(),
                  () ->
                      context.repository().readResponseEnvelope(identity, requestDigest, binding)))
          .isPresent();
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void lostIssuanceResponseRecoversExactEncryptedResultFromDurableReadback(
      @TempDir Path temporaryDirectory) throws Exception {
    TestContext context = newTestContext();
    AccountConnectTokenIssuanceIdentity identity = newIdentity(insertAccount(context.dsl()));
    byte[] requestDigest = digest(3);
    byte[] originalResult = "original-connect-token-result".getBytes(StandardCharsets.UTF_8);
    byte[] connectKey = new byte[32];
    byte[] bareLoginKey = new byte[32];
    java.util.Arrays.fill(connectKey, (byte) 1);
    java.util.Arrays.fill(bareLoginKey, (byte) 2);
    Path manifest = temporaryDirectory.resolve("manifest.v1");
    Files.writeString(
        manifest,
        "version=1\nactiveKeyId=k1\n"
            + "key:k1:bare-login="
            + Base64.getUrlEncoder().withoutPadding().encodeToString(bareLoginKey)
            + "\nkey:k1:connect-token="
            + Base64.getUrlEncoder().withoutPadding().encodeToString(connectKey)
            + "\n",
        StandardCharsets.US_ASCII);
    AccountEnvelopeCrypto writer = new AccountEnvelopeCrypto(manifest);

    AccountEnvelopeBinding binding =
        inTransaction(
            context.transaction(),
            () -> {
              var claim = context.repository().claim(identity, requestDigest);
              AccountEnvelopeBinding issuedBinding =
                  binding(claim.operation().operationId(), identity, requestDigest);
              context
                  .repository()
                  .completeWithEnvelope(
                      claim,
                      requestDigest,
                      Lifecycle.COMMITTED,
                      "SUCCESS",
                      "connect-jti-recovery",
                      digest(84),
                      issuedBinding,
                      writer.encrypt(
                          AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
                          issuedBinding,
                          originalResult));
              return issuedBinding;
            });

    var retry =
        inTransaction(
            context.transaction(), () -> context.repository().claim(identity, requestDigest));
    assertThat(retry.disposition())
        .isEqualTo(AccountConnectTokenIssuanceRepository.ClaimDisposition.REPLAYED);
    assertThat(retry.operation().lifecycle()).isEqualTo(Lifecycle.COMMITTED);
    var stored =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .readResponseEnvelope(identity, requestDigest, binding)
                    .orElseThrow());
    AccountEnvelopeCrypto recoveryReader = new AccountEnvelopeCrypto(manifest);
    assertThat(
            recoveryReader.decrypt(
                stored.envelope(), AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, stored.binding()))
        .containsExactly(originalResult);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> context.repository().find(identity, digest(4))))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.IdempotencyConflictException.class);
  }

  @Test
  void pendingAmbiguousOperationRetainsWriteOnceEvidenceWithoutAnEnvelope() {
    TestContext context = newTestContext();
    AccountConnectTokenIssuanceIdentity identity = newIdentity(insertAccount(context.dsl()));
    byte[] digest = digest(6);
    var claim =
        inTransaction(context.transaction(), () -> context.repository().claim(identity, digest));
    Instant attemptedAt = Instant.parse("2026-09-27T00:00:00Z");
    Instant nextAttemptAt = Instant.parse("2026-09-27T00:00:30Z");
    var scheduled =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .recordReconciliationAttempt(
                        identity,
                        digest,
                        0,
                        2,
                        attemptedAt,
                        "authority read unavailable",
                        nextAttemptAt));
    assertThat(scheduled.reconciliationAttemptCount()).isEqualTo(1);
    assertThat(scheduled.lastReconciliationAttemptAt()).isEqualTo(attemptedAt);
    assertThat(scheduled.nextReconciliationAttemptAt()).isEqualTo(nextAttemptAt);

    var pending =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .recordPendingEvidence(
                        claim,
                        digest,
                        "gameplay-connect-jti-uncertain",
                        digest(82),
                        digest(35),
                        digest(36),
                        digest(37),
                        null));

    assertThat(pending.lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThat(pending.tokenIdentity()).isEqualTo("gameplay-connect-jti-uncertain");
    assertThat(pending.tokenHash()).containsExactly(digest(82));
    assertThat(pending.postconditionDigest()).isNull();
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .recordPendingEvidence(
                                claim,
                                digest,
                                "different-token-identity",
                                digest(82),
                                null,
                                null,
                                null,
                                null)))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.EvidenceMismatchException.class);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().find(identity, digest).orElseThrow()))
        .isEqualTo(pending);
    assertThat(
            inTransaction(
                context.transaction(),
                () ->
                    context
                        .repository()
                        .readResponseEnvelope(
                            identity,
                            digest,
                            binding(claim.operation().operationId(), identity, digest))))
        .isEmpty();
  }

  @Test
  void reconciledAbortedOperationResolvesOnceWithItsOriginalIdentityAndEvidence() {
    TestContext context = newTestContext();
    AccountConnectTokenIssuanceIdentity identity = newIdentity(insertAccount(context.dsl()));
    byte[] digest = digest(9);
    String tokenIdentity = "gameplay-connect-jti-reconciled";
    byte[] tokenHash = digest(83);
    var claim =
        inTransaction(context.transaction(), () -> context.repository().claim(identity, digest));
    AccountEnvelopeBinding binding = binding(claim.operation().operationId(), identity, digest);

    inTransaction(
        context.transaction(),
        () ->
            context
                .repository()
                .recordPendingEvidence(
                    claim,
                    digest,
                    tokenIdentity,
                    tokenHash,
                    binding.contextEvidenceDigest(),
                    binding.authorityTupleDigest(),
                    binding.issuanceFenceDigest(),
                    binding.postconditionDigest()));
    int aborted =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_connect_token_issuance_operations "
                            + "SET status = 'ABORTED' WHERE operation_id = ?",
                        claim.operation().operationId()));
    assertThat(aborted).isEqualTo(1);

    Instant attemptedAt = Instant.parse("2026-09-27T00:01:00Z");
    Instant nextAttemptAt = Instant.parse("2026-09-27T00:01:30Z");
    var reconciled =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .recordReconciliationAttempt(
                        identity,
                        digest,
                        0,
                        2,
                        attemptedAt,
                        "token registry result verified",
                        nextAttemptAt));
    assertThat(reconciled.lifecycle()).isEqualTo(Lifecycle.ABORTED);
    assertThat(reconciled.reconciliationAttemptCount()).isEqualTo(1);

    AccountEncryptedEnvelope envelope =
        encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .resolveAbortedWithEnvelope(
                                identity,
                                digest(10),
                                1,
                                Lifecycle.COMMITTED,
                                "SUCCESS",
                                tokenIdentity,
                                tokenHash,
                                binding,
                                envelope)))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .resolveAbortedWithEnvelope(
                                identity,
                                digest,
                                0,
                                Lifecycle.COMMITTED,
                                "SUCCESS",
                                tokenIdentity,
                                tokenHash,
                                binding,
                                envelope)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires a recorded reconciliation attempt");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .resolveAbortedWithEnvelope(
                                identity,
                                digest,
                                2,
                                Lifecycle.COMMITTED,
                                "SUCCESS",
                                tokenIdentity,
                                tokenHash,
                                binding,
                                envelope)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed before reconciliation resolution");

    var stored =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .resolveAbortedWithEnvelope(
                        identity,
                        digest,
                        1,
                        Lifecycle.COMMITTED,
                        "SUCCESS",
                        tokenIdentity,
                        tokenHash,
                        binding,
                        envelope));
    assertThat(stored.operationId()).isEqualTo(claim.operation().operationId());
    assertThat(stored.binding()).isEqualTo(binding);
    assertThat(stored.envelope()).isEqualTo(envelope);

    var resolved =
        inTransaction(
            context.transaction(), () -> context.repository().find(identity, digest).orElseThrow());
    assertThat(resolved.operationId()).isEqualTo(claim.operation().operationId());
    assertThat(resolved.requestDigest()).containsExactly(digest);
    assertThat(resolved.lifecycle()).isEqualTo(Lifecycle.COMMITTED);
    assertThat(resolved.tokenIdentity()).isEqualTo(tokenIdentity);
    assertThat(resolved.tokenHash()).containsExactly(tokenHash);
    assertThat(resolved.contextEvidenceDigest()).containsExactly(binding.contextEvidenceDigest());
    assertThat(resolved.authorityTupleDigest()).containsExactly(binding.authorityTupleDigest());
    assertThat(resolved.issuanceFenceDigest()).containsExactly(binding.issuanceFenceDigest());
    assertThat(resolved.postconditionDigest()).containsExactly(binding.postconditionDigest());
    assertThat(resolved.reconciliationAttemptCount()).isEqualTo(1);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readResponseEnvelope(identity, digest, binding)))
        .contains(stored);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .resolveAbortedWithEnvelope(
                                identity,
                                digest,
                                1,
                                Lifecycle.COMMITTED,
                                "SUCCESS",
                                tokenIdentity,
                                tokenHash,
                                binding,
                                encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed before reconciliation resolution");
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().find(identity, digest).orElseThrow()))
        .isEqualTo(resolved);
  }

  @Test
  void terminalResultAndEncryptedEnvelopeReadBackExactlyForTheBoundOperation() {
    TestContext context = newTestContext();
    AccountConnectTokenIssuanceIdentity identity = newIdentity(insertAccount(context.dsl()));
    byte[] digest = digest(11);
    var claim =
        inTransaction(context.transaction(), () -> context.repository().claim(identity, digest));
    AccountEnvelopeBinding binding = binding(claim.operation().operationId(), identity, digest);
    AccountEncryptedEnvelope envelope =
        encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE);

    var stored =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .completeWithEnvelope(
                        claim,
                        digest,
                        Lifecycle.COMMITTED,
                        "SUCCESS",
                        "gameplay-connect-jti-1",
                        digest(80),
                        binding,
                        envelope));
    var exactReadback =
        inTransaction(
            context.transaction(),
            () ->
                context.repository().readResponseEnvelope(identity, digest, binding).orElseThrow());

    assertThat(stored.operationId()).isEqualTo(claim.operation().operationId());
    assertThat(exactReadback.operationId()).isEqualTo(stored.operationId());
    assertThat(exactReadback.binding()).isEqualTo(binding);
    assertThat(exactReadback.envelope()).isEqualTo(envelope);
    var committed =
        inTransaction(
            context.transaction(), () -> context.repository().find(identity, digest).orElseThrow());
    assertThat(committed.lifecycle()).isEqualTo(Lifecycle.COMMITTED);
    assertThat(committed.tokenIdentity()).isEqualTo("gameplay-connect-jti-1");
    assertThat(committed.tokenHash()).containsExactly(digest(80));
    assertThat(committed.contextEvidenceDigest()).containsExactly(binding.contextEvidenceDigest());

    AccountEnvelopeBinding wrongOperationBinding = binding(UUID.randomUUID(), identity, digest);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .readResponseEnvelope(identity, digest, wrongOperationBinding)))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.EvidenceMismatchException.class);
  }

  @Test
  void directDeletionOfIssuanceOperationOrResponseEnvelopeIsRejected() {
    TestContext context = newTestContext();
    AccountConnectTokenIssuanceIdentity identity = newIdentity(insertAccount(context.dsl()));
    byte[] digest = digest(41);
    var claim =
        inTransaction(context.transaction(), () -> context.repository().claim(identity, digest));
    AccountEnvelopeBinding binding = binding(claim.operation().operationId(), identity, digest);
    inTransaction(
        context.transaction(),
        () ->
            context
                .repository()
                .completeWithEnvelope(
                    claim,
                    digest,
                    Lifecycle.COMMITTED,
                    "SUCCESS",
                    "gameplay-connect-jti-retained",
                    digest(84),
                    binding,
                    encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE)));

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .dsl()
                            .execute(
                                "DELETE FROM account_connect_token_issuance_operations "
                                    + "WHERE operation_id = ?",
                                claim.operation().operationId())))
        .hasMessageContaining("Connect-token issuance replay evidence cannot be deleted");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .dsl()
                            .execute(
                                "DELETE FROM account_connect_token_response_envelopes "
                                    + "WHERE operation_id = ?",
                                claim.operation().operationId())))
        .hasMessageContaining("Connect-token issuance replay evidence cannot be deleted");

    assertThat(
            context
                .dsl()
                .fetchCount(
                    DSL.table("account_connect_token_issuance_operations"),
                    DSL.field("operation_id", UUID.class).eq(claim.operation().operationId())))
        .isEqualTo(1);
    assertThat(
            context
                .dsl()
                .fetchCount(
                    DSL.table("account_connect_token_response_envelopes"),
                    DSL.field("operation_id", UUID.class).eq(claim.operation().operationId())))
        .isEqualTo(1);
  }

  @Test
  void deterministicFailureIsStoredOnlyAsItsBoundEncryptedEnvelope() {
    TestContext context = newTestContext();
    AccountConnectTokenIssuanceIdentity identity = newIdentity(insertAccount(context.dsl()));
    byte[] digest = digest(16);
    var claim =
        inTransaction(context.transaction(), () -> context.repository().claim(identity, digest));
    AccountEnvelopeBinding binding = binding(claim.operation().operationId(), identity, digest);
    AccountEncryptedEnvelope envelope =
        encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE);

    var stored =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .completeWithEnvelope(
                        claim,
                        digest,
                        Lifecycle.FAILED,
                        "JOIN_REQUIRED",
                        null,
                        null,
                        binding,
                        envelope));
    var operation =
        inTransaction(
            context.transaction(), () -> context.repository().find(identity, digest).orElseThrow());

    assertThat(operation.lifecycle()).isEqualTo(Lifecycle.FAILED);
    assertThat(operation.outcomeCode()).isEqualTo("JOIN_REQUIRED");
    assertThat(operation.tokenIdentity()).isNull();
    assertThat(operation.tokenHash()).isNull();
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readResponseEnvelope(identity, digest, binding)))
        .contains(stored);
  }

  @Test
  void rejectsWrongOperationBindingAndBareLoginPurposeBeforeStoringAnEnvelope() {
    TestContext context = newTestContext();
    AccountConnectTokenIssuanceIdentity identity = newIdentity(insertAccount(context.dsl()));
    byte[] digest = digest(21);
    var claim =
        inTransaction(context.transaction(), () -> context.repository().claim(identity, digest));
    AccountEnvelopeBinding binding = binding(claim.operation().operationId(), identity, digest);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .completeWithEnvelope(
                                claim,
                                digest,
                                Lifecycle.COMMITTED,
                                "SUCCESS",
                                "gameplay-connect-jti-2",
                                digest(81),
                                binding(UUID.randomUUID(), identity, digest),
                                encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE))))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.EvidenceMismatchException.class);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .completeWithEnvelope(
                                claim,
                                digest,
                                Lifecycle.COMMITTED,
                                "SUCCESS",
                                "gameplay-connect-jti-2",
                                digest(81),
                                binding,
                                encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE))))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.EvidenceMismatchException.class);

    Optional<net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceOperation>
        stillPending =
            inTransaction(context.transaction(), () -> context.repository().find(identity, digest));
    assertThat(stillPending).isPresent();
    assertThat(stillPending.orElseThrow().lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readResponseEnvelope(identity, digest, binding)))
        .isEmpty();
  }

  private TestContext newTestContext() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + SCHEMA);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .placeholders(Map.of("serviceSchema", SCHEMA))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        dsl,
        new AccountConnectTokenIssuanceRepository(dsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private long insertAccount(DSLContext dsl) {
    String unique = UUID.randomUUID().toString().replace("-", "");
    Long accountId =
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                "connect_" + unique.substring(0, 12),
                unique + "@example.test",
                "test-hash")
            .fetchOne(0, Long.class);
    if (accountId == null) {
      throw new IllegalStateException("Connect-token repository test account was not inserted");
    }
    return accountId;
  }

  private AccountConnectTokenIssuanceIdentity newIdentity(long accountId) {
    return new AccountConnectTokenIssuanceIdentity(
        accountId,
        UUID.randomUUID(),
        "opaque-connect-scope-" + UUID.randomUUID(),
        "request-" + UUID.randomUUID());
  }

  private AccountEnvelopeBinding binding(
      UUID operationId, AccountConnectTokenIssuanceIdentity identity, byte[] requestDigest) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
        operationId.toString(),
        identity.requestId(),
        Long.toString(identity.accountId()),
        identity.tenantId().toString(),
        identity.connectScopeId(),
        null,
        requestDigest,
        digest(31),
        digest(32),
        digest(33),
        digest(34));
  }

  private AccountEncryptedEnvelope encryptedEnvelope(AccountEnvelopePurpose purpose) {
    return new AccountEncryptedEnvelope(
        AccountEncryptedEnvelope.CURRENT_FORMAT_VERSION,
        "key_1",
        purpose,
        new byte[AccountEncryptedEnvelope.NONCE_LENGTH_BYTES],
        new byte[AccountEncryptedEnvelope.AUTHENTICATION_TAG_LENGTH_BYTES]);
  }

  private byte[] digest(int seed) {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (seed + index);
    }
    return value;
  }

  private <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record TestContext(
      DSLContext dsl,
      AccountConnectTokenIssuanceRepository repository,
      TransactionTemplate transaction) {}
}
