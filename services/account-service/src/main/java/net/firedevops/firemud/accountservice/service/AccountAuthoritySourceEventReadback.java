package net.firedevops.firemud.accountservice.service;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository.LogoutAllReceipt;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec.AccountLogoutAllAuthorityEvent;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec.PasswordResetAuthorityEvent;

/**
 * Narrow validator for the two closed Account account-scope source-event schemas currently emitted.
 * It does not authenticate a caller or turn source evidence into recipient authority.
 */
public final class AccountAuthoritySourceEventReadback {
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String PASSWORD_RESET_REQUEST_ID_PREFIX =
      "account-password-reset-request-v1:";
  private static final String PASSWORD_RESET_EVENT_ID_PREFIX = "account-password-reset-event-v1:";
  private static final String PASSWORD_RESET_REQUEST_DOMAIN = "account-password-reset-request/v1";
  private static final String LOGOUT_ALL_EVENT_ID_PREFIX = "account-logout-all-event-v1:";
  private static final String PASSWORD_RESET_OPERATION_KIND = "PASSWORD_RESET";
  private static final String LOGOUT_ALL_OPERATION_KIND = "ACCOUNT_LOGOUT_ALL";
  private static final String LOGOUT_ALL_RESULT = "LOGOUT_ALL_COMMITTED";

  private final AccountAuthorityOutboxRepository outboxRepository;
  private final AccountPasswordResetOperationRepository passwordResetRepository;
  private final AccountLogoutAllOperationRepository logoutAllRepository;

  public AccountAuthoritySourceEventReadback(
      AccountAuthorityOutboxRepository outboxRepository,
      AccountPasswordResetOperationRepository passwordResetRepository,
      AccountLogoutAllOperationRepository logoutAllRepository) {
    this.outboxRepository =
        Objects.requireNonNull(outboxRepository, "Account authority outbox repository is required");
    this.passwordResetRepository =
        Objects.requireNonNull(
            passwordResetRepository, "Password-reset operation repository is required");
    this.logoutAllRepository =
        Objects.requireNonNull(logoutAllRepository, "Logout-all operation repository is required");
  }

  /**
   * Proves the exact current Account source generation, source version and shared outbox
   * checkpoint. Sequence zero is valid only for the original positive 1/1 source baseline and
   * absent history.
   */
  public LatestSourceSnapshot requireCurrentLatest(Account account, ScopeState current) {
    requireAccountAssociation(account);
    AuthorityScope expectedScope = AuthorityScope.account(account.getAccountUuid());
    if (current == null
        || !expectedScope.equals(current.scope())
        || current.generation() <= 0L
        || current.sourceVersion() <= 0L) {
      throw new IllegalStateException("Account source authority scope or counters are invalid");
    }
    requireCurrentFence(current.issuanceFence(), account.getAccountUuid());

    String streamKey = streamKey(account.getAccountUuid());
    Optional<Checkpoint> checkpoint = outboxRepository.readCheckpoint(streamKey);
    if (checkpoint.isEmpty()) {
      if (current.generation() != 1L || current.sourceVersion() != 1L) {
        throw new IllegalStateException(
            "Account source sequence zero is not the original positive 1/1 baseline");
      }
      return new LatestSourceSnapshot(0L, Optional.empty());
    }

    Checkpoint latestCheckpoint = checkpoint.orElseThrow();
    if (current.generation() == 1L && current.sourceVersion() == 1L) {
      throw new IllegalStateException("Pristine Account source has contradictory event history");
    }
    if (current.generation() <= 1L || current.sourceVersion() <= 1L) {
      throw new IllegalStateException("Account source history is not proven");
    }
    Event latestEvent =
        outboxRepository
            .findEvent(streamKey, latestCheckpoint.outboxSequence())
            .orElseThrow(() -> new IllegalStateException("Latest Account source event is missing"));
    requireCheckpointMatches(latestCheckpoint, latestEvent);
    VerifiedSourceEvent verified = verifyEvent(latestEvent, account.getAccountUuid());
    if (!Long.toString(current.generation()).equals(verified.accountAuthorityGeneration())
        || !Long.toString(current.sourceVersion()).equals(verified.sourceVersion())) {
      throw new IllegalStateException(
          "Latest Account source event does not match its current generation and source version");
    }

    if (verified.passwordReset().isPresent()) {
      PasswordResetReceipt receipt =
          passwordResetRepository
              .findByRequestId(verified.requestId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Latest Account password-reset event has no immutable operation receipt"));
      requirePasswordResetReceipt(account, receipt, latestEvent, verified, current);
    } else {
      UUID requestId = parseLogoutAllRequestId(verified.requestId());
      LogoutAllReceipt receipt =
          logoutAllRepository
              .findByRequestId(requestId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Latest Account logout-all event has no immutable operation receipt"));
      requireLogoutAllReceipt(account, receipt, latestEvent, verified, current);
    }
    return new LatestSourceSnapshot(latestEvent.outboxSequence(), Optional.of(latestEvent));
  }

  /** Validates one retained logout-all receipt after the account stream has advanced further. */
  public void requireLogoutAllReceiptRetained(
      Account account, LogoutAllReceipt receipt, Event event, ScopeState current) {
    requireAccountAssociation(account);
    if (current == null
        || !AuthorityScope.account(account.getAccountUuid()).equals(current.scope())) {
      throw new IllegalStateException("Logout-all current Account source scope is invalid");
    }
    requireCurrentFence(current.issuanceFence(), account.getAccountUuid());
    VerifiedSourceEvent verified = verifyEvent(event, account.getAccountUuid());
    requireLogoutAllReceipt(account, receipt, event, verified, current);
    if (event.outboxSequence() > currentSourceSequence(account)) {
      throw new IllegalStateException(
          "Logout-all operation event is ahead of its source checkpoint");
    }
  }

  /**
   * Validates one selected immutable Account event and its receipt against the current local
   * authority fence. Historical password-reset evidence deliberately does not prove that the old
   * password verifier remains current or make that reset operation recoverable.
   */
  public void requireRetainedEvent(Account account, Event event, ScopeState current) {
    requireAccountAssociation(account);
    if (current == null
        || !AuthorityScope.account(account.getAccountUuid()).equals(current.scope())
        || current.generation() <= 0L
        || current.sourceVersion() <= 0L) {
      throw new IllegalStateException("Retained Account source authority state is invalid");
    }
    requireCurrentFence(current.issuanceFence(), account.getAccountUuid());
    VerifiedSourceEvent verified = verifyEvent(event, account.getAccountUuid());
    if (event.outboxSequence() > currentSourceSequence(account)) {
      throw new IllegalStateException("Selected Account source event is ahead of its checkpoint");
    }

    if (verified.passwordReset().isPresent()) {
      PasswordResetReceipt receipt =
          passwordResetRepository
              .findByRequestId(verified.requestId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Selected Account password-reset event has no immutable operation receipt"));
      requirePasswordResetReceiptBinding(account, receipt, event, verified, current);
      return;
    }

    UUID requestId = parseLogoutAllRequestId(verified.requestId());
    LogoutAllReceipt receipt =
        logoutAllRepository
            .findByRequestId(requestId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Selected Account logout-all event has no immutable operation receipt"));
    requireLogoutAllReceipt(account, receipt, event, verified, current);
  }

  private long currentSourceSequence(Account account) {
    return outboxRepository
        .readCheckpoint(streamKey(account.getAccountUuid()))
        .map(Checkpoint::outboxSequence)
        .orElse(0L);
  }

  private void requirePasswordResetReceipt(
      Account account,
      PasswordResetReceipt receipt,
      Event event,
      VerifiedSourceEvent verified,
      ScopeState current) {
    requirePasswordResetReceiptBinding(account, receipt, event, verified, current);
    requireCurrentPasswordVerifier(account, receipt);
  }

  private void requirePasswordResetReceiptBinding(
      Account account,
      PasswordResetReceipt receipt,
      Event event,
      VerifiedSourceEvent verified,
      ScopeState current) {
    if (receipt.accountId() != account.getId()
        || !account.getAccountUuid().equals(receipt.accountUuid())
        || !PASSWORD_RESET_OPERATION_KIND.equals(receipt.operationKind())
        || receipt.requestDigestVersion() != 1
        || !streamKey(account.getAccountUuid()).equals(receipt.outboxStreamKey())
        || receipt.outboxSequence() != event.outboxSequence()
        || !receipt.requestId().equals(event.requestId())
        || !receipt.eventId().equals(event.eventId())
        || !receipt.eventDigest().equals(event.eventDigest())
        || !receipt.eventId().equals(PASSWORD_RESET_EVENT_ID_PREFIX + receipt.tokenHash())
        || !receipt.requestId().equals(PASSWORD_RESET_REQUEST_ID_PREFIX + receipt.tokenHash())
        || !Long.toString(receipt.accountAuthorityGeneration())
            .equals(verified.accountAuthorityGeneration())
        || !Long.toString(receipt.accountSourceVersion()).equals(verified.sourceVersion())) {
      throw new IllegalStateException(
          "Latest Account password-reset receipt does not match its source event");
    }
    String expectedDigest =
        passwordResetRequestDigest(
            receipt.accountUuid(),
            receipt.tokenHash(),
            receipt.tokenExpiresAt().toString(),
            receipt.passwordVerifierDigest());
    if (!constantTimeTextEquals(expectedDigest, receipt.requestDigest())) {
      throw new IllegalStateException(
          "Latest Account password-reset receipt request digest is inconsistent");
    }
    requireReceiptNotAhead(
        receipt.accountAuthorityGeneration(),
        receipt.accountSourceVersion(),
        receipt.issuanceFence(),
        receipt.issuanceFenceSourceVersion(),
        current,
        "Password-reset");
  }

  private void requireCurrentPasswordVerifier(Account account, PasswordResetReceipt receipt) {
    String currentVerifier = account.getPasswordHash();
    if (currentVerifier == null
        || currentVerifier.isBlank()
        || !constantTimeTextEquals(sha256Hex(currentVerifier), receipt.passwordVerifierDigest())) {
      throw new IllegalStateException(
          "Latest Account password-reset event does not match the current password verifier");
    }
  }

  private void requireLogoutAllReceipt(
      Account account,
      LogoutAllReceipt receipt,
      Event event,
      VerifiedSourceEvent verified,
      ScopeState current) {
    UUID requestId = parseLogoutAllRequestId(verified.requestId());
    if (!requestId.toString().equals(verified.requestId())
        || !requestId.equals(receipt.requestId())
        || receipt.accountId() != account.getId()
        || !account.getAccountUuid().equals(receipt.accountUuid())
        || !LOGOUT_ALL_OPERATION_KIND.equals(receipt.operationKind())
        || receipt.requestDigestVersion() != 1
        || !receipt.lifecycleResult().equals(LOGOUT_ALL_RESULT)
        || !streamKey(account.getAccountUuid()).equals(receipt.outboxStreamKey())
        || receipt.outboxSequence() != event.outboxSequence()
        || !receipt.requestId().toString().equals(event.requestId())
        || !receipt.eventId().equals(event.eventId())
        || !receipt.eventDigest().equals(event.eventDigest())
        || !receipt.eventId().equals(LOGOUT_ALL_EVENT_ID_PREFIX + receipt.requestId())
        || !Long.toString(receipt.accountAuthorityGeneration())
            .equals(verified.accountAuthorityGeneration())
        || !Long.toString(receipt.accountSourceVersion()).equals(verified.sourceVersion())) {
      throw new IllegalStateException("Latest Account logout-all receipt does not match its event");
    }
    String expectedRequestDigest =
        AccountLogoutRequestDigest.accountLogoutAll(
            receipt.accountUuid(), receipt.tokenProfile(), receipt.presentedTokenHash());
    if (!constantTimeTextEquals(expectedRequestDigest, receipt.requestDigest())) {
      throw new IllegalStateException(
          "Account logout-all receipt request digest does not match its immutable caller bindings");
    }
    requireReceiptNotAhead(
        receipt.accountAuthorityGeneration(),
        receipt.accountSourceVersion(),
        receipt.issuanceFence(),
        receipt.issuanceFenceSourceVersion(),
        current,
        "Logout-all");
  }

  private UUID parseLogoutAllRequestId(String requestIdText) {
    UUID requestId;
    try {
      requestId = UUID.fromString(requestIdText);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Logout-all source request ID is malformed", exception);
    }
    if (!requestId.toString().equals(requestIdText)) {
      throw new IllegalStateException("Logout-all source request ID is not canonical");
    }
    return requestId;
  }

  private static VerifiedSourceEvent verifyEvent(Event event, UUID accountUuid) {
    if (event == null) {
      throw new IllegalStateException("Account source event is missing");
    }
    String payload = new String(event.payload(), StandardCharsets.UTF_8);
    try {
      PasswordResetAuthorityEvent reset = PasswordResetAuthorityEventV1Codec.verify(payload);
      if (!MessageDigest.isEqual(event.payload(), reset.canonicalJsonUtf8())
          || !event.outboxStreamKey().equals(reset.outboxStreamKey())
          || !event.requestId().equals(reset.requestId())
          || !event.eventId().equals(reset.eventId())
          || !event.eventDigest().equals(reset.eventDigest())
          || !accountUuid.toString().equals(reset.accountId())
          || !Long.toString(event.outboxSequence()).equals(reset.outboxSequence())) {
        throw new IllegalStateException("Account password-reset event readback is inconsistent");
      }
      return VerifiedSourceEvent.passwordReset(reset);
    } catch (IllegalArgumentException resetFailure) {
      try {
        AccountLogoutAllAuthorityEvent logout =
            AccountLogoutAllAuthorityEventV1Codec.verify(payload);
        if (!MessageDigest.isEqual(event.payload(), logout.canonicalJsonUtf8())
            || !event.outboxStreamKey().equals(logout.outboxStreamKey())
            || !event.requestId().equals(logout.requestId())
            || !event.eventId().equals(logout.eventId())
            || !event.eventDigest().equals(logout.eventDigest())
            || !accountUuid.toString().equals(logout.accountId())
            || !Long.toString(event.outboxSequence()).equals(logout.outboxSequence())) {
          throw new IllegalStateException("Account logout-all event readback is inconsistent");
        }
        return VerifiedSourceEvent.logoutAll(logout);
      } catch (IllegalArgumentException logoutFailure) {
        logoutFailure.addSuppressed(resetFailure);
        throw new IllegalStateException(
            "Account source event is not a valid declared password-reset or logout-all event",
            logoutFailure);
      }
    }
  }

  /** Reuses the closed schema validator for immutable source-result constructor invariants. */
  static void requireEventMatchesSnapshot(
      Event event, UUID accountUuid, ScopeState current, boolean mustBeCurrentLatest) {
    if (accountUuid == null
        || current == null
        || !AuthorityScope.account(accountUuid).equals(current.scope())
        || current.generation() <= 0L
        || current.sourceVersion() <= 0L) {
      throw new IllegalStateException("Account source snapshot scope or counters are invalid");
    }
    VerifiedSourceEvent verified = verifyEvent(event, accountUuid);
    BigInteger eventGeneration = new BigInteger(verified.accountAuthorityGeneration());
    BigInteger eventSourceVersion = new BigInteger(verified.sourceVersion());
    BigInteger currentGeneration = BigInteger.valueOf(current.generation());
    BigInteger currentSourceVersion = BigInteger.valueOf(current.sourceVersion());
    boolean countersMatch =
        mustBeCurrentLatest
            ? eventGeneration.equals(currentGeneration)
                && eventSourceVersion.equals(currentSourceVersion)
            : eventGeneration.compareTo(currentGeneration) <= 0
                && eventSourceVersion.compareTo(currentSourceVersion) <= 0;
    if (!countersMatch) {
      throw new IllegalStateException(
          "Account source event counters contradict or lead the current snapshot");
    }
  }

  private void requireReceiptNotAhead(
      long generation,
      long sourceVersion,
      long fence,
      long fenceSourceVersion,
      ScopeState current,
      String operation) {
    IssuanceFence currentFence = current.issuanceFence();
    if (generation > current.generation()
        || sourceVersion > current.sourceVersion()
        || fence > currentFence.value()
        || fenceSourceVersion > currentFence.sourceVersion()) {
      throw new IllegalStateException(operation + " receipt is ahead of current Account authority");
    }
  }

  private void requireCurrentFence(IssuanceFence fence, UUID accountUuid) {
    if (fence == null
        || !accountUuid.equals(fence.accountId())
        || fence.value() <= 0L
        || fence.sourceVersion() <= 0L) {
      throw new IllegalStateException("Account source issuance fence is missing or invalid");
    }
  }

  private void requireAccountAssociation(Account account) {
    AccountIdentityProvenance provenance =
        account == null ? null : account.getAccountUuidProvenance();
    if (account == null
        || account.getId() == null
        || account.getId() <= 0L
        || account.getAccountUuid() == null
        || new UUID(0L, 0L).equals(account.getAccountUuid())
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())
        || (provenance != AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            && provenance != AccountIdentityProvenance.ACCOUNT_V29_MIGRATION
            && provenance != AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT)) {
      throw new IllegalStateException("Verified persisted Account association is required");
    }
  }

  private void requireCheckpointMatches(Checkpoint checkpoint, Event event) {
    if (!checkpoint.outboxStreamKey().equals(event.outboxStreamKey())
        || checkpoint.outboxSequence() != event.outboxSequence()
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException("Account source checkpoint differs from its latest event");
    }
  }

  private String streamKey(UUID accountUuid) {
    return STREAM_PREFIX + accountUuid;
  }

  private String passwordResetRequestDigest(
      UUID accountUuid, String tokenHash, String tokenExpiresAt, String verifierDigest) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      updateLengthPrefixed(digest, PASSWORD_RESET_REQUEST_DOMAIN);
      updateLengthPrefixed(digest, PASSWORD_RESET_OPERATION_KIND);
      updateLengthPrefixed(digest, accountUuid.toString());
      updateLengthPrefixed(digest, tokenHash);
      updateLengthPrefixed(digest, tokenExpiresAt);
      updateLengthPrefixed(digest, verifierDigest);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void updateLengthPrefixed(MessageDigest digest, String value) {
    if (value == null) {
      throw new IllegalArgumentException("Password-reset request field is required");
    }
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
    digest.update(encoded);
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

  private boolean constantTimeTextEquals(String left, String right) {
    return left != null
        && right != null
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  /** Current exact source checkpoint; empty event is legal only for the proven 1/1 baseline. */
  public record LatestSourceSnapshot(long outboxSequence, Optional<Event> latestEvent) {
    public LatestSourceSnapshot {
      latestEvent = Objects.requireNonNull(latestEvent, "latest source event optional is required");
      if (outboxSequence < 0L
          || (outboxSequence == 0L && latestEvent.isPresent())
          || (outboxSequence > 0L
              && (latestEvent.isEmpty()
                  || latestEvent.orElseThrow().outboxSequence() != outboxSequence))) {
        throw new IllegalArgumentException("Account latest source snapshot is inconsistent");
      }
    }
  }

  private record VerifiedSourceEvent(
      String requestId,
      String accountAuthorityGeneration,
      String sourceVersion,
      Optional<PasswordResetAuthorityEvent> passwordReset) {
    private static VerifiedSourceEvent passwordReset(PasswordResetAuthorityEvent event) {
      return new VerifiedSourceEvent(
          event.requestId(),
          event.accountAuthorityGeneration(),
          event.sourceVersion(),
          Optional.of(event));
    }

    private static VerifiedSourceEvent logoutAll(AccountLogoutAllAuthorityEvent event) {
      return new VerifiedSourceEvent(
          event.requestId(),
          event.accountAuthorityGeneration(),
          event.sourceVersion(),
          Optional.empty());
    }
  }
}
