package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceOperation.Lifecycle;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
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
        77L,
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
        Long.toString(identity.tenantId()),
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
