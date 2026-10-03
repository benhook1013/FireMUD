package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeIdentity;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeOperation.Lifecycle;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeRepository;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginResponseEnvelope;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountBareLoginExchangeRepositoryIntegrationTest {
  private static final String SCHEMA = "account_bare_login_exchange_proof";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void durablePendingClaimRecoversExactOperationAfterLostAcknowledgementWithoutEnvelope() {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(0);

    // The first response is intentionally discarded to model a committed claim with a lost ack.
    inTransaction(context.transaction(), () -> context.repository().claim(identity, requestDigest));
    UUID originalOperationId =
        context
            .dsl()
            .resultQuery(
                "SELECT operation_id FROM account_bare_login_exchange_operations "
                    + "WHERE source_connect_operation_id = ?",
                identity.sourceConnectOperationId())
            .fetchOne(0, UUID.class);
    assertThat(originalOperationId).isNotNull();

    var recovered =
        inTransaction(
            context.transaction(), () -> context.repository().claim(identity, requestDigest));
    var persisted =
        inTransaction(
            context.transaction(),
            () -> context.repository().find(identity, requestDigest).orElseThrow());
    AccountEnvelopeBinding binding = binding(originalOperationId, identity, requestDigest, 3);

    assertThat(recovered.disposition())
        .isEqualTo(AccountBareLoginExchangeRepository.ClaimDisposition.REPLAYED);
    assertThat(recovered.operation().operationId()).isEqualTo(originalOperationId);
    assertThat(recovered.operation().lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThat(persisted.operationId()).isEqualTo(originalOperationId);
    assertThat(persisted.lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThat(persisted.requestDigest()).containsExactly(requestDigest);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readResponseEnvelope(identity, requestDigest, binding)))
        .isEmpty();
    Long envelopeCount =
        context
            .dsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_bare_login_response_envelopes "
                    + "WHERE operation_id = ?",
                originalOperationId)
            .fetchOne(0, Long.class);
    assertThat(envelopeCount).isEqualTo(0L);

    var evidenceReadback =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .recordPendingEvidence(
                        recovered,
                        requestDigest,
                        "delegation-jti-recovered",
                        digest(80),
                        binding.contextEvidenceDigest(),
                        binding.authorityTupleDigest(),
                        binding.issuanceFenceDigest(),
                        binding.postconditionDigest()));
    assertThat(evidenceReadback.lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThat(evidenceReadback.operationId()).isEqualTo(originalOperationId);
    assertThat(evidenceReadback.requestDigest()).containsExactly(requestDigest);

    AccountEnvelopeBinding changedBinding =
        binding(originalOperationId, identity, requestDigest, 4);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .recordPendingEvidence(
                                recovered,
                                requestDigest,
                                "delegation-jti-changed",
                                digest(81),
                                changedBinding.contextEvidenceDigest(),
                                changedBinding.authorityTupleDigest(),
                                changedBinding.issuanceFenceDigest(),
                                changedBinding.postconditionDigest())))
        .isInstanceOf(AccountBareLoginExchangeRepository.EvidenceMismatchException.class);

    var exactPendingRetry =
        inTransaction(
            context.transaction(), () -> context.repository().claim(identity, requestDigest));
    assertThat(exactPendingRetry.disposition())
        .isEqualTo(AccountBareLoginExchangeRepository.ClaimDisposition.REPLAYED);
    assertThat(exactPendingRetry.operation().operationId()).isEqualTo(originalOperationId);
    assertThat(exactPendingRetry.operation().lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readResponseEnvelope(identity, requestDigest, binding)))
        .isEmpty();

    AccountBareLoginResponseEnvelope stored =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .completeWithEnvelope(
                        exactPendingRetry,
                        requestDigest,
                        "delegation-jti-recovered",
                        digest(80),
                        binding,
                        encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE)));
    assertThat(stored.operationId()).isEqualTo(originalOperationId);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().find(identity, requestDigest).orElseThrow().lifecycle()))
        .isEqualTo(Lifecycle.COMMITTED);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readResponseEnvelope(identity, requestDigest, binding)))
        .contains(stored);
  }

  @Test
  void changedDigestRequestOrSourceCannotReplaceDurablePendingClaim() {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(4);
    var original =
        inTransaction(
            context.transaction(), () -> context.repository().claim(identity, requestDigest));

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> context.repository().claim(identity, digest(5))))
        .isInstanceOf(AccountBareLoginExchangeRepository.IdempotencyConflictException.class);

    AccountBareLoginExchangeIdentity changedRequest =
        new AccountBareLoginExchangeIdentity(
            identity.sourceConnectOperationId(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeId(),
            identity.requestId() + "-changed");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().claim(changedRequest, requestDigest)))
        .isInstanceOf(AccountBareLoginExchangeRepository.IdentityConflictException.class);

    AccountBareLoginExchangeIdentity changedSourceBinding =
        new AccountBareLoginExchangeIdentity(
            identity.sourceConnectOperationId(),
            identity.accountId() + 1,
            identity.tenantId(),
            identity.connectScopeId(),
            identity.requestId());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().claim(changedSourceBinding, requestDigest)))
        .isInstanceOf(AccountBareLoginExchangeRepository.IdentityConflictException.class);

    AccountBareLoginExchangeIdentity changedSourceOperation =
        new AccountBareLoginExchangeIdentity(
            UUID.randomUUID(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeId(),
            identity.requestId());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().claim(changedSourceOperation, requestDigest)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source connect operation is missing");

    var unchanged =
        inTransaction(
            context.transaction(),
            () -> context.repository().find(identity, requestDigest).orElseThrow());
    assertThat(unchanged.operationId()).isEqualTo(original.operation().operationId());
    assertThat(unchanged.lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThat(unchanged.requestDigest()).containsExactly(requestDigest);
    assertThat(unchanged.tokenIdentity()).isNull();
    Long envelopeCount =
        context
            .dsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_bare_login_response_envelopes "
                    + "WHERE operation_id = ?",
                unchanged.operationId())
            .fetchOne(0, Long.class);
    assertThat(envelopeCount).isEqualTo(0L);
  }

  @Test
  void rollingBackPendingClaimLeavesNoPartialExchange() {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(6);

    AccountBareLoginExchangeRepository.ClaimResult attempted =
        context
            .transaction()
            .execute(
                status -> {
                  var claim = context.repository().claim(identity, requestDigest);
                  status.setRollbackOnly();
                  return claim;
                });

    assertThat(attempted).isNotNull();
    assertThat(attempted.disposition())
        .isEqualTo(AccountBareLoginExchangeRepository.ClaimDisposition.CLAIMED);
    assertThat(
            inTransaction(
                context.transaction(), () -> context.repository().find(identity, requestDigest)))
        .isEmpty();
    Long operationCount =
        context
            .dsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_bare_login_exchange_operations "
                    + "WHERE source_connect_operation_id = ?",
                identity.sourceConnectOperationId())
            .fetchOne(0, Long.class);
    assertThat(operationCount).isEqualTo(0L);
  }

  @Test
  void sourceMustBeCommittedAndFirstWriterOwnsTheExchangeIdentity() {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] digest = digest(1);

    ExchangeResult firstResult =
        inTransaction(
            context.transaction(),
            () -> {
              var claimed = context.repository().claim(identity, digest);
              AccountEnvelopeBinding binding =
                  binding(claimed.operation().operationId(), identity, digest, 2);
              context
                  .repository()
                  .recordPendingEvidence(
                      claimed,
                      digest,
                      "delegation-jti-source",
                      digest(80),
                      binding.contextEvidenceDigest(),
                      binding.authorityTupleDigest(),
                      binding.issuanceFenceDigest(),
                      binding.postconditionDigest());
              AccountBareLoginResponseEnvelope stored =
                  context
                      .repository()
                      .completeWithEnvelope(
                          claimed,
                          digest,
                          "delegation-jti-source",
                          digest(80),
                          binding,
                          encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE));
              return new ExchangeResult(claimed, binding, stored);
            });
    var claimed = firstResult.claim();
    var exactRetry =
        inTransaction(context.transaction(), () -> context.repository().claim(identity, digest));

    assertThat(claimed.disposition())
        .isEqualTo(AccountBareLoginExchangeRepository.ClaimDisposition.CLAIMED);
    assertThat(exactRetry.disposition())
        .isEqualTo(AccountBareLoginExchangeRepository.ClaimDisposition.REPLAYED);
    assertThat(exactRetry.operation().operationId()).isEqualTo(claimed.operation().operationId());
    assertThat(exactRetry.operation().lifecycle()).isEqualTo(Lifecycle.COMMITTED);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .recordPendingEvidence(
                                exactRetry,
                                digest,
                                "delegation-jti-source",
                                digest(80),
                                firstResult.binding().contextEvidenceDigest(),
                                firstResult.binding().authorityTupleDigest(),
                                firstResult.binding().issuanceFenceDigest(),
                                firstResult.binding().postconditionDigest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact pending bare LOGIN exchange claim");
    assertThat(
            inTransaction(
                context.transaction(),
                () ->
                    context
                        .repository()
                        .readResponseEnvelope(identity, digest, firstResult.binding())))
        .contains(firstResult.envelope());

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> context.repository().claim(identity, digest(2))))
        .isInstanceOf(AccountBareLoginExchangeRepository.IdempotencyConflictException.class);

    AccountBareLoginExchangeIdentity competingIdentity =
        new AccountBareLoginExchangeIdentity(
            identity.sourceConnectOperationId(),
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeId(),
            "different-exchange-request");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().claim(competingIdentity, digest)))
        .isInstanceOf(AccountBareLoginExchangeRepository.IdentityConflictException.class);

    Source pendingSource = pendingSource(context, identity.accountId(), identity.connectScopeId());
    AccountBareLoginExchangeIdentity pendingIdentity = pendingSource.exchangeIdentity();
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().claim(pendingIdentity, digest)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not committed");
  }

  @Test
  void concurrentExactPendingClaimsConvergeOnOneDurableOperation() throws Exception {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(7);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<AccountBareLoginExchangeRepository.ClaimResult> claimExactPending =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Pending exchange claim race did not start together");
            }
            return inTransaction(
                context.transaction(), () -> context.repository().claim(identity, requestDigest));
          };
      Future<AccountBareLoginExchangeRepository.ClaimResult> first =
          executor.submit(claimExactPending);
      Future<AccountBareLoginExchangeRepository.ClaimResult> second =
          executor.submit(claimExactPending);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results)
          .extracting(AccountBareLoginExchangeRepository.ClaimResult::disposition)
          .containsExactlyInAnyOrder(
              AccountBareLoginExchangeRepository.ClaimDisposition.CLAIMED,
              AccountBareLoginExchangeRepository.ClaimDisposition.REPLAYED);
      UUID operationId = results.get(0).operation().operationId();
      assertThat(results.get(1).operation().operationId()).isEqualTo(operationId);
      assertThat(results)
          .extracting(result -> result.operation().lifecycle())
          .containsOnly(Lifecycle.PENDING);

      var persisted =
          inTransaction(
              context.transaction(),
              () -> context.repository().find(identity, requestDigest).orElseThrow());
      assertThat(persisted.operationId()).isEqualTo(operationId);
      assertThat(persisted.lifecycle()).isEqualTo(Lifecycle.PENDING);
      assertThat(persisted.requestDigest()).containsExactly(requestDigest);
      Long envelopeCount =
          context
              .dsl()
              .resultQuery(
                  "SELECT COUNT(*) FROM account_bare_login_response_envelopes "
                      + "WHERE operation_id = ?",
                  operationId)
              .fetchOne(0, Long.class);
      assertThat(envelopeCount).isEqualTo(0L);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentChangedPendingEvidenceWritersKeepOneFirstWrite() throws Exception {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(8);
    var originalClaim =
        inTransaction(
            context.transaction(), () -> context.repository().claim(identity, requestDigest));
    var exactRetry =
        inTransaction(
            context.transaction(), () -> context.repository().claim(identity, requestDigest));
    AccountEnvelopeBinding firstBinding =
        binding(originalClaim.operation().operationId(), identity, requestDigest, 9);
    AccountEnvelopeBinding secondBinding =
        binding(originalClaim.operation().operationId(), identity, requestDigest, 19);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> firstWriter =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Pending evidence race did not start together");
            }
            try {
              inTransaction(
                  context.transaction(),
                  () ->
                      context
                          .repository()
                          .recordPendingEvidence(
                              originalClaim,
                              requestDigest,
                              "delegation-jti-concurrent-first",
                              digest(90),
                              firstBinding.contextEvidenceDigest(),
                              firstBinding.authorityTupleDigest(),
                              firstBinding.issuanceFenceDigest(),
                              firstBinding.postconditionDigest()));
              return true;
            } catch (AccountBareLoginExchangeRepository.EvidenceMismatchException exception) {
              return false;
            }
          };
      Callable<Boolean> secondWriter =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Pending evidence race did not start together");
            }
            try {
              inTransaction(
                  context.transaction(),
                  () ->
                      context
                          .repository()
                          .recordPendingEvidence(
                              exactRetry,
                              requestDigest,
                              "delegation-jti-concurrent-second",
                              digest(91),
                              secondBinding.contextEvidenceDigest(),
                              secondBinding.authorityTupleDigest(),
                              secondBinding.issuanceFenceDigest(),
                              secondBinding.postconditionDigest()));
              return true;
            } catch (AccountBareLoginExchangeRepository.EvidenceMismatchException exception) {
              return false;
            }
          };
      Future<Boolean> first = executor.submit(firstWriter);
      Future<Boolean> second = executor.submit(secondWriter);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      var persisted =
          inTransaction(
              context.transaction(),
              () -> context.repository().find(identity, requestDigest).orElseThrow());
      assertThat(persisted.operationId()).isEqualTo(originalClaim.operation().operationId());
      assertThat(persisted.lifecycle()).isEqualTo(Lifecycle.PENDING);
      AccountEnvelopeBinding winningBinding;
      if ("delegation-jti-concurrent-first".equals(persisted.tokenIdentity())) {
        assertThat(persisted.tokenHash()).containsExactly(digest(90));
        winningBinding = firstBinding;
      } else {
        assertThat(persisted.tokenIdentity()).isEqualTo("delegation-jti-concurrent-second");
        assertThat(persisted.tokenHash()).containsExactly(digest(91));
        winningBinding = secondBinding;
      }
      assertThat(persisted.contextEvidenceDigest())
          .containsExactly(winningBinding.contextEvidenceDigest());
      assertThat(persisted.authorityTupleDigest())
          .containsExactly(winningBinding.authorityTupleDigest());
      assertThat(persisted.issuanceFenceDigest())
          .containsExactly(winningBinding.issuanceFenceDigest());
      assertThat(persisted.postconditionDigest())
          .containsExactly(winningBinding.postconditionDigest());
      Long envelopeCount =
          context
              .dsl()
              .resultQuery(
                  "SELECT COUNT(*) FROM account_bare_login_response_envelopes "
                      + "WHERE operation_id = ?",
                  persisted.operationId())
              .fetchOne(0, Long.class);
      assertThat(envelopeCount).isEqualTo(0L);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentExactExchangeClaimsHaveOneTerminalFirstWriter() throws Exception {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(70);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<AccountBareLoginExchangeRepository.ClaimResult> exchange =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Exchange claim race did not start together");
            }
            return inTransaction(
                context.transaction(),
                () -> {
                  var claim = context.repository().claim(identity, requestDigest);
                  if (claim.disposition()
                      == AccountBareLoginExchangeRepository.ClaimDisposition.CLAIMED) {
                    AccountEnvelopeBinding binding =
                        binding(claim.operation().operationId(), identity, requestDigest, 71);
                    context
                        .repository()
                        .recordPendingEvidence(
                            claim,
                            requestDigest,
                            "delegation-jti-race",
                            digest(80),
                            binding.contextEvidenceDigest(),
                            binding.authorityTupleDigest(),
                            binding.issuanceFenceDigest(),
                            binding.postconditionDigest());
                    context
                        .repository()
                        .completeWithEnvelope(
                            claim,
                            requestDigest,
                            "delegation-jti-race",
                            digest(80),
                            binding,
                            encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE));
                  }
                  return claim;
                });
          };
      Future<AccountBareLoginExchangeRepository.ClaimResult> first = executor.submit(exchange);
      Future<AccountBareLoginExchangeRepository.ClaimResult> second = executor.submit(exchange);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results)
          .extracting(AccountBareLoginExchangeRepository.ClaimResult::disposition)
          .containsExactlyInAnyOrder(
              AccountBareLoginExchangeRepository.ClaimDisposition.CLAIMED,
              AccountBareLoginExchangeRepository.ClaimDisposition.REPLAYED);
      assertThat(results.get(0).operation().operationId())
          .isEqualTo(results.get(1).operation().operationId());
      assertThat(
              inTransaction(
                      context.transaction(),
                      () -> context.repository().find(identity, requestDigest).orElseThrow())
                  .lifecycle())
          .isEqualTo(Lifecycle.COMMITTED);
      AccountEnvelopeBinding binding =
          binding(results.get(0).operation().operationId(), identity, requestDigest, 71);
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
  void lostExchangeResponseRecoversExactEncryptedResultFromDurableReadback(
      @TempDir Path temporaryDirectory) throws Exception {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(72);
    byte[] originalResult = "original-private-delegation-result".getBytes(StandardCharsets.UTF_8);
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
                  binding(claim.operation().operationId(), identity, requestDigest, 73);
              context
                  .repository()
                  .recordPendingEvidence(
                      claim,
                      requestDigest,
                      "delegation-jti-recovery",
                      digest(80),
                      issuedBinding.contextEvidenceDigest(),
                      issuedBinding.authorityTupleDigest(),
                      issuedBinding.issuanceFenceDigest(),
                      issuedBinding.postconditionDigest());
              context
                  .repository()
                  .completeWithEnvelope(
                      claim,
                      requestDigest,
                      "delegation-jti-recovery",
                      digest(80),
                      issuedBinding,
                      writer.encrypt(
                          AccountEnvelopePurpose.BARE_LOGIN_RESPONSE,
                          issuedBinding,
                          originalResult));
              return issuedBinding;
            });

    var retry =
        inTransaction(
            context.transaction(), () -> context.repository().claim(identity, requestDigest));
    assertThat(retry.disposition())
        .isEqualTo(AccountBareLoginExchangeRepository.ClaimDisposition.REPLAYED);
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
                stored.envelope(), AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, stored.binding()))
        .containsExactly(originalResult);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> context.repository().find(identity, digest(74))))
        .isInstanceOf(AccountBareLoginExchangeRepository.IdempotencyConflictException.class);
  }

  @Test
  void pendingEvidenceThenTerminalSuccessReadBacksExactPurposeBoundEnvelope() {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(10);
    ExchangeResult result =
        inTransaction(
            context.transaction(),
            () -> {
              var claim = context.repository().claim(identity, requestDigest);
              AccountEnvelopeBinding binding =
                  binding(claim.operation().operationId(), identity, requestDigest, 21);
              var pending =
                  context
                      .repository()
                      .recordPendingEvidence(
                          claim,
                          requestDigest,
                          "delegation-jti-1",
                          digest(80),
                          binding.contextEvidenceDigest(),
                          binding.authorityTupleDigest(),
                          binding.issuanceFenceDigest(),
                          binding.postconditionDigest());
              assertThat(pending.lifecycle()).isEqualTo(Lifecycle.PENDING);
              assertThat(pending.tokenIdentity()).isEqualTo("delegation-jti-1");
              AccountBareLoginResponseEnvelope stored =
                  context
                      .repository()
                      .completeWithEnvelope(
                          claim,
                          requestDigest,
                          "delegation-jti-1",
                          digest(80),
                          binding,
                          encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE));
              return new ExchangeResult(claim, binding, stored);
            });
    var claim = result.claim();
    AccountEnvelopeBinding binding = result.binding();
    var stored = result.envelope();
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readResponseEnvelope(identity, requestDigest, binding)))
        .contains(stored);
    var exactReadback =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .readResponseEnvelope(identity, requestDigest, binding)
                    .orElseThrow());

    assertThat(stored.operationId()).isEqualTo(claim.operation().operationId());
    assertThat(exactReadback).isEqualTo(stored);
    assertThat(exactReadback.binding()).isEqualTo(binding);
    assertThat(exactReadback.envelope()).isEqualTo(stored.envelope());
    var exactRetry =
        inTransaction(
            context.transaction(), () -> context.repository().claim(identity, requestDigest));
    assertThat(exactRetry.disposition())
        .isEqualTo(AccountBareLoginExchangeRepository.ClaimDisposition.REPLAYED);
    assertThat(exactRetry.operation().operationId()).isEqualTo(claim.operation().operationId());
    var committed =
        inTransaction(
            context.transaction(),
            () -> context.repository().find(identity, requestDigest).orElseThrow());
    assertThat(committed.lifecycle()).isEqualTo(Lifecycle.COMMITTED);
    assertThat(committed.outcomeCode()).isEqualTo("SUCCESS");
    assertThat(committed.tokenHash()).containsExactly(digest(80));
    assertThat(committed.contextEvidenceDigest()).containsExactly(binding.contextEvidenceDigest());
    assertThat(committed.toString())
        .isEqualTo(
            "AccountBareLoginExchangeOperation{operationId="
                + committed.operationId()
                + ", sourceConnectOperationId="
                + committed.sourceConnectOperationId()
                + ", accountId="
                + committed.accountId()
                + ", tenantId="
                + committed.tenantId()
                + ", requestId='"
                + committed.requestId()
                + "', lifecycle=COMMITTED, secretEvidence=<redacted>}");
    assertThat(committed.toString()).doesNotContain("delegation-jti-1");

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> context.repository().find(identity, digest(11))))
        .isInstanceOf(AccountBareLoginExchangeRepository.IdempotencyConflictException.class);
  }

  @Test
  void terminalSuccessWithoutItsExactBoundEnvelopeCannotCommit() {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(12);
    var claim =
        inTransaction(
            context.transaction(), () -> context.repository().claim(identity, requestDigest));
    AccountEnvelopeBinding binding =
        binding(claim.operation().operationId(), identity, requestDigest, 22);
    inTransaction(
        context.transaction(),
        () ->
            context
                .repository()
                .recordPendingEvidence(
                    claim,
                    requestDigest,
                    "delegation-jti-no-envelope",
                    digest(82),
                    binding.contextEvidenceDigest(),
                    binding.authorityTupleDigest(),
                    binding.issuanceFenceDigest(),
                    binding.postconditionDigest()));

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> {
                      int updated =
                          context
                              .dsl()
                              .execute(
                                  "UPDATE account_bare_login_exchange_operations "
                                      + "SET status = 'COMMITTED', outcome_code = 'SUCCESS' "
                                      + "WHERE operation_id = ?",
                                  claim.operation().operationId());
                      assertThat(updated).isEqualTo(1);
                      return null;
                    }))
        .isInstanceOf(TransactionSystemException.class)
        .satisfies(
            exception -> {
              Throwable rootCause = ((TransactionSystemException) exception).getRootCause();
              assertThat(rootCause).isNotNull();
              assertThat(rootCause.getMessage())
                  .contains("Terminal bare LOGIN exchange requires its exact response envelope");
            });

    var unchanged =
        inTransaction(
            context.transaction(),
            () -> context.repository().find(identity, requestDigest).orElseThrow());
    assertThat(unchanged.lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThat(unchanged.outcomeCode()).isNull();
    assertThat(unchanged.requestDigest()).containsExactly(requestDigest);
    assertThat(unchanged.contextEvidenceDigest()).containsExactly(binding.contextEvidenceDigest());
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readResponseEnvelope(identity, requestDigest, binding)))
        .isEmpty();
    Long envelopeCount =
        context
            .dsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_bare_login_response_envelopes "
                    + "WHERE operation_id = ?",
                claim.operation().operationId())
            .fetchOne(0, Long.class);
    assertThat(envelopeCount).isEqualTo(0L);
  }

  @Test
  void wrongIdentityBindingOrPurposeCannotTerminalizeThePendingExchange() {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(20);
    ExchangeResult result =
        inTransaction(
            context.transaction(),
            () -> {
              var claim = context.repository().claim(identity, requestDigest);
              AccountEnvelopeBinding binding =
                  binding(claim.operation().operationId(), identity, requestDigest, 31);
              assertThatThrownBy(
                      () ->
                          context
                              .repository()
                              .completeWithEnvelope(
                                  claim,
                                  requestDigest,
                                  "delegation-jti-2",
                                  digest(81),
                                  binding(UUID.randomUUID(), identity, requestDigest, 31),
                                  encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE)))
                  .isInstanceOf(AccountBareLoginExchangeRepository.EvidenceMismatchException.class);
              assertThatThrownBy(
                      () ->
                          context
                              .repository()
                              .completeWithEnvelope(
                                  claim,
                                  requestDigest,
                                  "delegation-jti-2",
                                  digest(81),
                                  binding,
                                  encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE)))
                  .isInstanceOf(AccountBareLoginExchangeRepository.EvidenceMismatchException.class);
              AccountBareLoginResponseEnvelope stored =
                  context
                      .repository()
                      .completeWithEnvelope(
                          claim,
                          requestDigest,
                          "delegation-jti-2",
                          digest(81),
                          binding,
                          encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE));
              return new ExchangeResult(claim, binding, stored);
            });
    assertThat(result.envelope().operationId()).isEqualTo(result.claim().operation().operationId());
    assertThat(
            inTransaction(
                context.transaction(),
                () ->
                    context
                        .repository()
                        .readResponseEnvelope(identity, requestDigest, result.binding())))
        .contains(result.envelope());
  }

  @Test
  void conflictingPendingEvidenceCannotReplaceWriteOnceTerminalEvidence() {
    TestContext context = newTestContext();
    Source source = committedSource(context);
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = digest(30);
    ExchangeResult result =
        inTransaction(
            context.transaction(),
            () -> {
              var claim = context.repository().claim(identity, requestDigest);
              AccountEnvelopeBinding originalBinding =
                  binding(claim.operation().operationId(), identity, requestDigest, 41);
              var pending =
                  context
                      .repository()
                      .recordPendingEvidence(
                          claim,
                          requestDigest,
                          "delegation-jti-original",
                          digest(90),
                          originalBinding.contextEvidenceDigest(),
                          originalBinding.authorityTupleDigest(),
                          originalBinding.issuanceFenceDigest(),
                          originalBinding.postconditionDigest());
              assertThat(pending.lifecycle()).isEqualTo(Lifecycle.PENDING);
              assertThat(pending.tokenIdentity()).isEqualTo("delegation-jti-original");

              AccountEnvelopeBinding conflictingBinding =
                  binding(claim.operation().operationId(), identity, requestDigest, 42);
              assertThatThrownBy(
                      () ->
                          context
                              .repository()
                              .recordPendingEvidence(
                                  claim,
                                  requestDigest,
                                  "delegation-jti-conflict",
                                  digest(91),
                                  conflictingBinding.contextEvidenceDigest(),
                                  conflictingBinding.authorityTupleDigest(),
                                  conflictingBinding.issuanceFenceDigest(),
                                  conflictingBinding.postconditionDigest()))
                  .isInstanceOf(AccountBareLoginExchangeRepository.EvidenceMismatchException.class);
              assertThatThrownBy(
                      () ->
                          context
                              .repository()
                              .completeWithEnvelope(
                                  claim,
                                  requestDigest,
                                  "delegation-jti-conflict",
                                  digest(91),
                                  conflictingBinding,
                                  encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE)))
                  .isInstanceOf(AccountBareLoginExchangeRepository.EvidenceMismatchException.class);
              AccountBareLoginResponseEnvelope stored =
                  context
                      .repository()
                      .completeWithEnvelope(
                          claim,
                          requestDigest,
                          "delegation-jti-original",
                          digest(90),
                          originalBinding,
                          encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE));
              return new ExchangeResult(claim, originalBinding, stored);
            });
    assertThat(result.envelope().operationId()).isEqualTo(result.claim().operation().operationId());
    assertThat(
            inTransaction(
                context.transaction(),
                () ->
                    context
                        .repository()
                        .readResponseEnvelope(identity, requestDigest, result.binding())))
        .contains(result.envelope());
  }

  @Test
  void directSqlExchangeInsertRequiresCommittedMatchingSourceIdentity() {
    TestContext context = newTestContext();
    long pendingAccountId = insertAccount(context.dsl());
    Source pendingSource =
        pendingSource(context, pendingAccountId, "pending-source-" + UUID.randomUUID());
    AccountBareLoginExchangeIdentity pendingIdentity = pendingSource.exchangeIdentity();

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        insertBareOperationDirect(
                            context, UUID.randomUUID(), pendingIdentity, digest(70))))
        .isInstanceOf(DataAccessException.class);

    Source committedSource = committedSource(context);
    AccountBareLoginExchangeIdentity committedIdentity = committedSource.exchangeIdentity();
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        insertBareOperationDirect(
                            context,
                            UUID.randomUUID(),
                            committedIdentity.sourceConnectOperationId(),
                            committedIdentity.accountId(),
                            UUID.randomUUID(),
                            committedIdentity.connectScopeId(),
                            "wrong-tenant-" + UUID.randomUUID(),
                            digest(71))))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        insertBareOperationDirect(
                            context,
                            UUID.randomUUID(),
                            committedIdentity.sourceConnectOperationId(),
                            committedIdentity.accountId(),
                            committedIdentity.tenantId(),
                            committedIdentity.connectScopeId() + "-wrong",
                            "wrong-scope-" + UUID.randomUUID(),
                            digest(72))))
        .isInstanceOf(DataAccessException.class);

    long otherAccountId = insertAccount(context.dsl());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        insertBareOperationDirect(
                            context,
                            UUID.randomUUID(),
                            committedIdentity.sourceConnectOperationId(),
                            otherAccountId,
                            committedIdentity.tenantId(),
                            committedIdentity.connectScopeId(),
                            "wrong-account-" + UUID.randomUUID(),
                            digest(73))))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void directSqlCannotDeletePendingOrTerminalExchangeRows() {
    TestContext context = newTestContext();
    Source pendingSource = committedSource(context);
    AccountBareLoginExchangeIdentity pendingIdentity = pendingSource.exchangeIdentity();
    byte[] pendingDigest = digest(74);
    UUID pendingOperationId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        deletePendingOperationDirect(
                            context, pendingOperationId, pendingIdentity, pendingDigest)))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().find(pendingIdentity, pendingDigest)))
        .isEmpty();

    Source terminalSource = committedSource(context);
    AccountBareLoginExchangeIdentity terminalIdentity = terminalSource.exchangeIdentity();
    byte[] terminalDigest = digest(75);
    ExchangeResult terminalResult =
        inTransaction(
            context.transaction(),
            () -> {
              var terminalClaim = context.repository().claim(terminalIdentity, terminalDigest);
              AccountEnvelopeBinding terminalBinding =
                  binding(
                      terminalClaim.operation().operationId(),
                      terminalIdentity,
                      terminalDigest,
                      76);
              context
                  .repository()
                  .recordPendingEvidence(
                      terminalClaim,
                      terminalDigest,
                      "delegation-jti-delete-guard",
                      digest(92),
                      terminalBinding.contextEvidenceDigest(),
                      terminalBinding.authorityTupleDigest(),
                      terminalBinding.issuanceFenceDigest(),
                      terminalBinding.postconditionDigest());
              AccountBareLoginResponseEnvelope stored =
                  context
                      .repository()
                      .completeWithEnvelope(
                          terminalClaim,
                          terminalDigest,
                          "delegation-jti-delete-guard",
                          digest(92),
                          terminalBinding,
                          encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE));
              return new ExchangeResult(terminalClaim, terminalBinding, stored);
            });
    AccountBareLoginResponseEnvelope stored = terminalResult.envelope();

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .dsl()
                            .execute(
                                "DELETE FROM account_bare_login_response_envelopes "
                                    + "WHERE operation_id = ?",
                                stored.operationId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .dsl()
                            .execute(
                                "DELETE FROM account_bare_login_exchange_operations "
                                    + "WHERE operation_id = ?",
                                stored.operationId())))
        .isInstanceOf(DataAccessException.class);
    var terminalReadback =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .readResponseEnvelope(
                        terminalIdentity, terminalDigest, terminalResult.binding()));
    assertThat(terminalReadback).contains(stored);
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
        new AccountBareLoginExchangeRepository(dsl),
        new AccountConnectTokenIssuanceRepository(dsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private Source committedSource(TestContext context) {
    long accountId = insertAccount(context.dsl());
    return createSource(context, accountId, "connect-scope-" + UUID.randomUUID(), true);
  }

  private Source pendingSource(TestContext context, long accountId, String connectScopeId) {
    return createSource(context, accountId, connectScopeId, false);
  }

  private Source createSource(
      TestContext context, long accountId, String connectScopeId, boolean commitSource) {
    AccountConnectTokenIssuanceIdentity sourceIdentity =
        new AccountConnectTokenIssuanceIdentity(
            accountId, UUID.randomUUID(), connectScopeId, "connect-request-" + UUID.randomUUID());
    byte[] sourceDigest = digest(commitSource ? 60 : 61);
    var sourceClaim =
        inTransaction(
            context.transaction(),
            () -> context.sourceRepository().claim(sourceIdentity, sourceDigest));
    if (commitSource) {
      AccountEnvelopeBinding sourceBinding =
          new AccountEnvelopeBinding(
              AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
              sourceClaim.operation().operationId().toString(),
              sourceIdentity.requestId(),
              Long.toString(sourceIdentity.accountId()),
              sourceIdentity.tenantId().toString(),
              sourceIdentity.connectScopeId(),
              null,
              sourceDigest,
              digest(62),
              digest(63),
              digest(64),
              digest(65));
      inTransaction(
          context.transaction(),
          () ->
              context
                  .sourceRepository()
                  .completeWithEnvelope(
                      sourceClaim,
                      sourceDigest,
                      net.firedevops.firemud.accountservice.repository
                          .AccountConnectTokenIssuanceOperation.Lifecycle.COMMITTED,
                      "SUCCESS",
                      "connect-jti-" + UUID.randomUUID(),
                      digest(66),
                      sourceBinding,
                      encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE)));
    }
    return new Source(
        sourceClaim.operation().operationId(),
        new AccountBareLoginExchangeIdentity(
            sourceClaim.operation().operationId(),
            sourceClaim.operation().accountId(),
            sourceClaim.operation().tenantId(),
            connectScopeId,
            "exchange-request-" + UUID.randomUUID()));
  }

  private long insertAccount(DSLContext dsl) {
    String unique = UUID.randomUUID().toString().replace("-", "");
    Long accountId =
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                "bare_login_" + unique.substring(0, 12),
                unique + "@example.test",
                "test-hash")
            .fetchOne(0, Long.class);
    if (accountId == null) {
      throw new IllegalStateException("Bare LOGIN repository test account was not inserted");
    }
    return accountId;
  }

  private int insertBareOperationDirect(
      TestContext context,
      UUID operationId,
      AccountBareLoginExchangeIdentity identity,
      byte[] requestDigest) {
    return insertBareOperationDirect(
        context,
        operationId,
        identity.sourceConnectOperationId(),
        identity.accountId(),
        identity.tenantId(),
        identity.connectScopeId(),
        identity.requestId(),
        requestDigest);
  }

  private int insertBareOperationDirect(
      TestContext context,
      UUID operationId,
      UUID sourceOperationId,
      long accountId,
      UUID tenantId,
      String connectScopeId,
      String requestId,
      byte[] requestDigest) {
    return context
        .dsl()
        .execute(
            "INSERT INTO account_bare_login_exchange_operations "
                + "(operation_id, source_connect_operation_id, account_id, tenant_id, "
                + "connect_scope_hash, request_id, request_digest_version, request_digest, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, 'PENDING')",
            operationId,
            sourceOperationId,
            accountId,
            tenantId,
            AccountJoinDigest.tokenHash(connectScopeId),
            requestId,
            requestDigest);
  }

  private int deletePendingOperationDirect(
      TestContext context,
      UUID operationId,
      AccountBareLoginExchangeIdentity identity,
      byte[] requestDigest) {
    insertBareOperationDirect(context, operationId, identity, requestDigest);
    return context
        .dsl()
        .execute(
            "DELETE FROM account_bare_login_exchange_operations WHERE operation_id = ?",
            operationId);
  }

  private AccountEnvelopeBinding binding(
      UUID operationId, AccountBareLoginExchangeIdentity identity, byte[] requestDigest, int seed) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
        operationId.toString(),
        identity.requestId(),
        Long.toString(identity.accountId()),
        identity.tenantId().toString(),
        identity.connectScopeId(),
        identity.sourceConnectOperationId().toString(),
        requestDigest,
        digest(seed),
        digest(seed + 1),
        digest(seed + 2),
        digest(seed + 3));
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

  private record ExchangeResult(
      AccountBareLoginExchangeRepository.ClaimResult claim,
      AccountEnvelopeBinding binding,
      AccountBareLoginResponseEnvelope envelope) {}

  private record Source(UUID sourceOperationId, AccountBareLoginExchangeIdentity exchangeIdentity) {
    private Source {
      if (!sourceOperationId.equals(exchangeIdentity.sourceConnectOperationId())) {
        throw new IllegalArgumentException("Source operation identity must be bound exactly");
      }
    }
  }

  private record TestContext(
      DSLContext dsl,
      AccountBareLoginExchangeRepository repository,
      AccountConnectTokenIssuanceRepository sourceRepository,
      TransactionTemplate transaction) {}
}
