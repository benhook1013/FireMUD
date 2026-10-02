package net.firedevops.firemud.accountservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository.LogoutAllReceipt;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec.AccountLogoutAllAuthorityEvent;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired source-only Account logout-all mutation and immutable result readback.
 *
 * <p>This primitive does not authenticate a caller. Its caller must perform the initial exact token
 * registry and Account evidence-bundle authorization required by ADR 0036 before invoking a new
 * operation. It creates no authorization context and is not exposed through a REST, gRPC, or
 * controller path. Exact committed retries return only the durable lifecycle result.
 */
public final class AccountLogoutAllAuthorityEventProducer {
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String EVENT_ID_PREFIX = "account-logout-all-event-v1:";
  private static final int DIGEST_VERSION = 1;
  private static final String OPERATION_KIND = "LOGOUT_ALL";
  private static final HexFormat HEX = HexFormat.of();

  private final AccountRepository accountRepository;
  private final AccountAuthorityGenerationRepository generationRepository;
  private final AccountAuthorityOutboxRepository outboxRepository;
  private final AccountLogoutAllOperationRepository operationRepository;
  private final AccountAuthoritySourceEventReadback sourceReadback;
  private final TransactionTemplate ownerTransaction;

  public AccountLogoutAllAuthorityEventProducer(
      AccountRepository accountRepository,
      AccountAuthorityGenerationRepository generationRepository,
      AccountAuthorityOutboxRepository outboxRepository,
      AccountLogoutAllOperationRepository operationRepository,
      AccountAuthoritySourceEventReadback sourceReadback,
      DSLContext dsl,
      PlatformTransactionManager transactionManager) {
    this.accountRepository =
        Objects.requireNonNull(accountRepository, "Account repository is required");
    this.generationRepository =
        Objects.requireNonNull(generationRepository, "authority-generation repository is required");
    this.outboxRepository =
        Objects.requireNonNull(outboxRepository, "authority outbox repository is required");
    this.operationRepository =
        Objects.requireNonNull(operationRepository, "logout-all operation repository is required");
    this.sourceReadback =
        Objects.requireNonNull(sourceReadback, "Account source-event readback is required");
    Objects.requireNonNull(dsl, "transaction-aware DSLContext is required");
    Objects.requireNonNull(transactionManager, "Account transaction manager is required");

    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Commits one account-wide cutoff or recovers that request's exact original lifecycle result.
   *
   * <p>The request digest and presented token hash are opaque lowercase SHA-256 hex bindings; this
   * method never derives either from a raw credential or treats them as proof of authorization.
   * The supplied Account association and scope state are compared against locked persisted state.
   */
  public LogoutAllResult commit(
      UUID requestId,
      int requestDigestVersion,
      String requestDigest,
      String presentedTokenHash,
      Account verifiedAccountAssociation,
      ScopeState expectedAccountState) {
    validateRequest(requestId, requestDigestVersion, requestDigest, presentedTokenHash);
    validateExpectedInputs(verifiedAccountAssociation, expectedAccountState);
    requireNoAmbientTransaction();

    LogoutAllReceipt transactionResult =
      ownerTransaction.execute(
          status ->
              commitInOwnerTransaction(
                    requestId,
                    requestDigestVersion,
                    requestDigest,
                    presentedTokenHash,
                    verifiedAccountAssociation,
                    expectedAccountState));
    if (transactionResult == null) {
      throw new IllegalStateException("Logout-all source transaction returned no receipt");
    }

    LogoutAllReceipt committedResult =
        readCommittedOperation(
            requestId,
            requestDigestVersion,
            requestDigest,
            presentedTokenHash,
            verifiedAccountAssociation);
    if (!transactionResult.equals(committedResult)) {
      throw new IllegalStateException(
          "Post-commit logout-all receipt differs from the source transaction result");
    }
    return LogoutAllResult.valueOf(committedResult.lifecycleResult());
  }

  private LogoutAllReceipt commitInOwnerTransaction(
      UUID requestId,
      int requestDigestVersion,
      String requestDigest,
      String presentedTokenHash,
      Account requestedAccount,
      ScopeState expectedAccountState) {
    Account account = lockVerifiedAccount(requestedAccount);
    String requestText = requestId.toString();

    // The immutable request and token bindings take precedence over current server-derived counters.
    Optional<LogoutAllReceipt> priorRequest = operationRepository.findByRequestId(requestId);
    Optional<LogoutAllReceipt> priorToken =
        operationRepository.findByPresentedTokenHash(presentedTokenHash);
    if (priorRequest.isPresent()) {
      LogoutAllReceipt receipt = priorRequest.orElseThrow();
      requireExactRetryBinding(
          receipt, requestId, requestDigestVersion, requestDigest, presentedTokenHash, account);
      if (priorToken.isEmpty() || !receipt.equals(priorToken.orElseThrow())) {
        throw new IllegalStateException("Logout-all token receipt index readback is inconsistent");
      }
      return recoverReceipt(account, receipt);
    }
    if (priorToken.isPresent()) {
      throw new OperationConflictException(
          "Presented token identity is already bound to another logout-all request");
    }

    if (requestDigestVersion != DIGEST_VERSION) {
      throw new IllegalArgumentException("Logout-all request digest version must be 1");
    }
    ScopeState current =
        generationRepository.read(AuthorityScope.account(account.getAccountUuid()));
    if (!current.equals(expectedAccountState)) {
      throw new IllegalStateException("Logout-all Account source compare-and-advance is stale");
    }
    AccountAuthoritySourceEventReadback.LatestSourceSnapshot source =
        sourceReadback.requireCurrentLatest(account, current);
    long nextGeneration = incrementExact(current.generation(), "Account authority generation");
    long nextSourceVersion = incrementExact(current.sourceVersion(), "Account source version");
    long nextIssuanceFence =
        incrementExact(current.issuanceFence().value(), "Account issuance fence");
    incrementExact(current.issuanceFence().sourceVersion(), "issuance-fence source version");
    long nextSequence = incrementExact(source.outboxSequence(), "Account source outbox sequence");
    String streamKey = streamKey(account.getAccountUuid());
    String eventId = EVENT_ID_PREFIX + requestText;

    ScopeState advanced = generationRepository.advance(current, current.issuanceFence());
    requireAdvancedState(
        current,
        advanced,
        nextGeneration,
        nextSourceVersion,
        nextIssuanceFence);

    Event appended =
        outboxRepository.append(
            streamKey,
            requestText,
            sequence ->
                eventEvidence(
                    account.getAccountUuid(),
                    requestId,
                    eventId,
                    sequence,
                    nextGeneration,
                    nextSourceVersion));
    if (appended.outboxSequence() != nextSequence) {
      throw new IllegalStateException("Logout-all source outbox sequence did not advance once");
    }
    AccountLogoutAllAuthorityEvent event =
        requireEvent(
            appended,
            account.getAccountUuid(),
            requestId,
            eventId,
            nextGeneration,
            nextSourceVersion);
    Checkpoint checkpoint =
        outboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(() -> new IllegalStateException("Logout-all checkpoint is missing"));
    requireCheckpointMatches(checkpoint, appended);

    LogoutAllReceipt receipt =
        LogoutAllReceipt.committed(
            requestId,
            account.getId(),
            account.getAccountUuid(),
            requestDigest,
            presentedTokenHash,
            streamKey,
            appended.outboxSequence(),
            event.eventId(),
            event.eventDigest(),
            advanced,
            advanced.issuanceFence());
    operationRepository.insert(receipt);

    Event requestReadback =
        outboxRepository
            .findEvent(streamKey, requestText)
            .orElseThrow(() -> new IllegalStateException("Logout-all request event readback is missing"));
    Event sequenceReadback =
        outboxRepository
            .findEvent(streamKey, nextSequence)
            .orElseThrow(
                () -> new IllegalStateException("Logout-all sequence event readback is missing"));
    if (!appended.equals(requestReadback) || !appended.equals(sequenceReadback)) {
      throw new IllegalStateException("Logout-all immutable event readback differs from append");
    }
    ScopeState sourceReadbackState =
        generationRepository.read(AuthorityScope.account(account.getAccountUuid()));
    if (!advanced.equals(sourceReadbackState)) {
      throw new IllegalStateException("Logout-all source state readback differs from its commit");
    }
    AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest =
        sourceReadback.requireCurrentLatest(account, sourceReadbackState);
    if (latest.outboxSequence() != nextSequence
        || latest.latestEvent().isEmpty()
        || !appended.equals(latest.latestEvent().orElseThrow())) {
      throw new IllegalStateException("Logout-all current source checkpoint readback differs");
    }
    requireReceiptReadback(
        receipt,
        requestId,
        requestDigestVersion,
        requestDigest,
        presentedTokenHash,
        account);
    return receipt;
  }

  private LogoutAllReceipt recoverReceipt(Account account, LogoutAllReceipt receipt) {
    ScopeState current = generationRepository.read(AuthorityScope.account(account.getAccountUuid()));
    sourceReadback.requireCurrentLatest(account, current);
    Event event =
        outboxRepository
            .findEvent(receipt.outboxStreamKey(), receipt.outboxSequence())
            .orElseThrow(() -> new IllegalStateException("Logout-all historical event is missing"));
    sourceReadback.requireLogoutAllReceiptRetained(account, receipt, event, current);
    LogoutAllReceipt readback =
        operationRepository
            .findByRequestId(receipt.requestId())
            .orElseThrow(() -> new IllegalStateException("Logout-all retry receipt is missing"));
    if (!receipt.equals(readback)) {
      throw new IllegalStateException("Logout-all retry receipt changed during readback");
    }
    Optional<LogoutAllReceipt> tokenReadback =
        operationRepository.findByPresentedTokenHash(receipt.presentedTokenHash());
    if (tokenReadback.isEmpty() || !receipt.equals(tokenReadback.orElseThrow())) {
      throw new IllegalStateException("Logout-all retry token receipt changed during readback");
    }
    return readback;
  }

  private LogoutAllReceipt readCommittedOperation(
      UUID requestId,
      int requestDigestVersion,
      String requestDigest,
      String presentedTokenHash,
      Account requestedAccount) {
    requireNoAmbientTransaction();
    LogoutAllReceipt receipt =
        ownerTransaction.execute(
            status -> {
              Account account = lockVerifiedAccount(requestedAccount);
              LogoutAllReceipt committed =
                  operationRepository
                      .findByRequestId(requestId)
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Post-commit logout-all request receipt is missing"));
              requireExactRetryBinding(
                  committed,
                  requestId,
                  requestDigestVersion,
                  requestDigest,
                  presentedTokenHash,
                  account);
              return recoverReceipt(account, committed);
            });
    if (receipt == null) {
      throw new IllegalStateException("Post-commit logout-all receipt readback returned no result");
    }
    return receipt;
  }

  private LogoutAllReceipt requireReceiptReadback(
      LogoutAllReceipt expected,
      UUID requestId,
      int requestDigestVersion,
      String requestDigest,
      String presentedTokenHash,
      Account account) {
    LogoutAllReceipt byRequest =
        operationRepository
            .findByRequestId(requestId)
            .orElseThrow(() -> new IllegalStateException("Logout-all receipt readback is missing"));
    Optional<LogoutAllReceipt> byToken =
        operationRepository.findByPresentedTokenHash(presentedTokenHash);
    requireExactRetryBinding(
        byRequest, requestId, requestDigestVersion, requestDigest, presentedTokenHash, account);
    if (!expected.equals(byRequest) || byToken.isEmpty() || !expected.equals(byToken.orElseThrow())) {
      throw new IllegalStateException("Logout-all receipt readback differs from its insert");
    }
    return byRequest;
  }

  private void requireExactRetryBinding(
      LogoutAllReceipt receipt,
      UUID requestId,
      int requestDigestVersion,
      String requestDigest,
      String presentedTokenHash,
      Account account) {
    if (!receipt.requestId().equals(requestId)
        || !OPERATION_KIND.equals(receipt.operationKind())
        || receipt.requestDigestVersion() != requestDigestVersion
        || !constantTimeTextEquals(receipt.requestDigest(), requestDigest)
        || !constantTimeTextEquals(receipt.presentedTokenHash(), presentedTokenHash)
        || receipt.accountId() != account.getId()
        || !receipt.accountUuid().equals(account.getAccountUuid())) {
      throw new OperationConflictException(
          "Logout-all request, digest, operation, token, or Account binding conflicts with its receipt");
    }
  }

  private Account lockVerifiedAccount(Account requestedAccount) {
    Account locked =
        accountRepository
            .findByIdForUpdate(requestedAccount.getId())
            .orElseThrow(() -> new IllegalStateException("Verified Account association is missing"));
    if (!Objects.equals(requestedAccount.getId(), locked.getId())
        || !Objects.equals(requestedAccount.getAccountUuid(), locked.getAccountUuid())
        || !Objects.equals(
            requestedAccount.getAccountUuidSourceNumericId(), locked.getAccountUuidSourceNumericId())
        || requestedAccount.getAccountUuidProvenance() != locked.getAccountUuidProvenance()
        || !isVerifiedProvenance(locked.getAccountUuidProvenance())) {
      throw new IllegalStateException("Verified Account association changed before logout-all");
    }
    // The source readback enforces that this is an exact persisted UUID association.
    if (locked.getId() == null
        || locked.getId() <= 0L
        || locked.getAccountUuid() == null
        || new UUID(0L, 0L).equals(locked.getAccountUuid())
        || !locked.getId().equals(locked.getAccountUuidSourceNumericId())) {
      throw new IllegalStateException("Verified persisted Account association is invalid");
    }
    return locked;
  }

  private EventEvidence eventEvidence(
      UUID accountUuid,
      UUID requestId,
      String eventId,
      long sequence,
      long generation,
      long sourceVersion) {
    String streamKey = streamKey(accountUuid);
    AccountLogoutAllAuthorityEvent event =
        AccountLogoutAllAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", eventId),
                Map.entry("requestId", requestId.toString()),
                Map.entry("accountId", accountUuid.toString()),
                Map.entry("sourceScope", "account/" + accountUuid),
                Map.entry("outboxStreamKey", streamKey),
                Map.entry("outboxSequence", Long.toString(sequence)),
                Map.entry("accountAuthorityGeneration", Long.toString(generation)),
                Map.entry("sourceVersion", Long.toString(sourceVersion)),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", Long.toString(generation),
                        "outboxStreamKey", streamKey,
                        "outboxSequence", Long.toString(sequence)))));
    return new EventEvidence(event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
  }

  private AccountLogoutAllAuthorityEvent requireEvent(
      Event event,
      UUID accountUuid,
      UUID requestId,
      String eventId,
      long generation,
      long sourceVersion) {
    AccountLogoutAllAuthorityEvent verified =
        AccountLogoutAllAuthorityEventV1Codec.verify(
            new String(event.payload(), StandardCharsets.UTF_8));
    if (!MessageDigest.isEqual(event.payload(), verified.canonicalJsonUtf8())
        || !streamKey(accountUuid).equals(event.outboxStreamKey())
        || !requestId.toString().equals(event.requestId())
        || !eventId.equals(event.eventId())
        || !event.eventDigest().equals(verified.eventDigest())
        || !accountUuid.toString().equals(verified.accountId())
        || !Long.toString(event.outboxSequence()).equals(verified.outboxSequence())
        || !Long.toString(generation).equals(verified.accountAuthorityGeneration())
        || !Long.toString(sourceVersion).equals(verified.sourceVersion())) {
      throw new IllegalStateException("Logout-all source event differs from its committed state");
    }
    return verified;
  }

  private void requireAdvancedState(
      ScopeState previous,
      ScopeState advanced,
      long expectedGeneration,
      long expectedSourceVersion,
      long expectedFence) {
    if (advanced == null
        || !previous.scope().equals(advanced.scope())
        || advanced.generation() != expectedGeneration
        || advanced.sourceVersion() != expectedSourceVersion
        || advanced.issuanceFence() == null
        || !previous.issuanceFence().accountId().equals(advanced.issuanceFence().accountId())
        || advanced.issuanceFence().value() != expectedFence
        || advanced.issuanceFence().sourceVersion()
            != incrementExact(previous.issuanceFence().sourceVersion(), "issuance-fence source version")) {
      throw new IllegalStateException("Logout-all Account source and issuance fence did not advance once");
    }
  }

  private void requireCheckpointMatches(Checkpoint checkpoint, Event event) {
    if (!checkpoint.outboxStreamKey().equals(event.outboxStreamKey())
        || checkpoint.outboxSequence() != event.outboxSequence()
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException("Logout-all outbox checkpoint differs from its event");
    }
  }

  private void validateRequest(
      UUID requestId, int requestDigestVersion, String requestDigest, String presentedTokenHash) {
    if (requestId == null || new UUID(0L, 0L).equals(requestId)) {
      throw new IllegalArgumentException("A canonical non-nil logout-all request UUID is required");
    }
    if (requestDigestVersion <= 0) {
      throw new IllegalArgumentException("Logout-all request digest version must be positive");
    }
    decodeDigest(requestDigest, "request digest");
    decodeDigest(presentedTokenHash, "presented token hash");
  }

  private void validateExpectedInputs(Account account, ScopeState expectedState) {
    if (account == null
        || account.getId() == null
        || account.getId() <= 0L
        || account.getAccountUuid() == null
        || new UUID(0L, 0L).equals(account.getAccountUuid())
        || !isVerifiedProvenance(account.getAccountUuidProvenance())
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())) {
      throw new IllegalArgumentException("Verified persisted Account association is required");
    }
    if (expectedState == null
        || !AuthorityScope.account(account.getAccountUuid()).equals(expectedState.scope())
        || expectedState.generation() <= 0L
        || expectedState.sourceVersion() <= 0L
        || expectedState.issuanceFence() == null
        || !account.getAccountUuid().equals(expectedState.issuanceFence().accountId())
        || expectedState.issuanceFence().value() <= 0L
        || expectedState.issuanceFence().sourceVersion() <= 0L) {
      throw new IllegalArgumentException("Expected Account authority scope and fence are required");
    }
  }

  private boolean isVerifiedProvenance(AccountIdentityProvenance provenance) {
    return provenance == AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
        || provenance == AccountIdentityProvenance.ACCOUNT_V29_MIGRATION
        || provenance == AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT;
  }

  private void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Logout-all source operations must own their Account transaction without an ambient transaction");
    }
  }

  private String streamKey(UUID accountUuid) {
    return STREAM_PREFIX + accountUuid;
  }

  private static String decodeDigest(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Logout-all " + field + " must be lowercase SHA-256 hex");
    }
    HEX.parseHex(value);
    return value;
  }

  private boolean constantTimeTextEquals(String left, String right) {
    return left != null
        && right != null
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private long incrementExact(long value, String field) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Logout-all " + field + " is exhausted", overflow);
    }
  }

  /** Caller bindings changed for an existing logout-all request or token identity. */
  public static final class OperationConflictException extends IllegalStateException {
    public OperationConflictException(String message) {
      super(message);
    }
  }

  /** The non-authorizing lifecycle outcome returned by both initial commit and exact retry. */
  public enum LogoutAllResult {
    LOGOUT_ALL_COMMITTED
  }
}
