package net.firedevops.firemud.accountservice.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec.AccountLogoutAllAuthorityEvent;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec.PasswordResetAuthorityEvent;

/**
 * Narrow receipt validator for the three closed Account account-scope source-event schemas. It does
 * not authenticate a caller or turn source evidence into recipient authority.
 */
public final class AccountAuthoritySourceEventReadback {
  private static final ObjectMapper SNAPSHOT_JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
  private final AccountSecurityStateOperationRepository securityStateRepository;

  public AccountAuthoritySourceEventReadback(
      AccountAuthorityOutboxRepository outboxRepository,
      AccountPasswordResetOperationRepository passwordResetRepository,
      AccountLogoutAllOperationRepository logoutAllRepository,
      AccountSecurityStateOperationRepository securityStateRepository) {
    this.outboxRepository =
        Objects.requireNonNull(outboxRepository, "Account authority outbox repository is required");
    this.passwordResetRepository =
        Objects.requireNonNull(
            passwordResetRepository, "Password-reset operation repository is required");
    this.logoutAllRepository =
        Objects.requireNonNull(logoutAllRepository, "Logout-all operation repository is required");
    this.securityStateRepository =
        Objects.requireNonNull(
            securityStateRepository, "Security-state operation repository is required");
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
    // The latest closed receipt cannot make an earlier generic Account mutation authoritative.
    // Retain generic history for inspection elsewhere, but fail closed for currentness until every
    // event in this stream has the closed operation-specific receipt/readback contract.
    for (long sequence = 1L; sequence < latestCheckpoint.outboxSequence(); sequence++) {
      Event retainedEvent =
          outboxRepository
              .findEvent(streamKey, sequence)
              .orElseThrow(
                  () -> new IllegalStateException("Retained Account source event is missing"));
      requireRetainedEvent(account, retainedEvent, current);
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
    } else if (verified.securityState().isPresent()) {
      requireSecurityStateReceipt(account, latestEvent, verified, current, true);
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
  public RetainedEventEvidence requireRetainedEvent(
      Account account, Event event, ScopeState current) {
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
      return retainedEvidence(
          event, verified, receipt.issuanceFence(), receipt.issuanceFenceSourceVersion());
    }

    if (verified.securityState().isPresent()) {
      var operation =
          securityStateRepository
              .findByRequestId(UUID.fromString(verified.requestId()))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Selected Account security-state event has no immutable operation receipt"));
      var receipt =
          operation
              .receipt()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Selected Account security-state operation is not COMMITTED"));
      requireSecurityStateReceipt(account, event, verified, current, false);
      return retainedEvidence(
          event,
          verified,
          receipt.sourceState().issuanceFence().value(),
          receipt.sourceState().issuanceFence().sourceVersion());
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
    return retainedEvidence(
        event, verified, receipt.issuanceFence(), receipt.issuanceFenceSourceVersion());
  }

  private RetainedEventEvidence retainedEvidence(
      Event event, VerifiedSourceEvent verified, long issuanceFence, long fenceSourceVersion) {
    return new RetainedEventEvidence(
        event.outboxStreamKey(),
        event.outboxSequence(),
        Long.parseLong(verified.accountAuthorityGeneration()),
        Long.parseLong(verified.sourceVersion()),
        issuanceFence,
        fenceSourceVersion,
        verified.accountSecurityCutoff());
  }

  private long currentSourceSequence(Account account) {
    return outboxRepository
        .readCheckpoint(streamKey(account.getAccountUuid()))
        .map(Checkpoint::outboxSequence)
        .orElse(0L);
  }

  private void requireSecurityStateReceipt(
      Account account,
      Event event,
      VerifiedSourceEvent verified,
      ScopeState current,
      boolean latest) {
    var operation =
        securityStateRepository
            .findByRequestId(UUID.fromString(verified.requestId()))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Account security-state event has no immutable operation receipt"));
    var receipt =
        operation
            .receipt()
            .orElseThrow(
                () ->
                    new IllegalStateException("Account security-state operation is not COMMITTED"));
    if (!operation.request().accountUuid().equals(account.getAccountUuid())
        || operation.capture().accountId() != account.getId()
        || operation.capture().provenance() != account.getAccountUuidProvenance()
        || !receipt.event().equals(event)) {
      throw new IllegalStateException("Account security-state operation association/event differs");
    }
    requireReceiptNotAhead(
        receipt.sourceState().generation(),
        receipt.sourceState().sourceVersion(),
        receipt.sourceState().issuanceFence().value(),
        receipt.sourceState().issuanceFence().sourceVersion(),
        current,
        "Security-state");
    if (latest) securityStateRepository.requireCurrentPostState(operation, current);
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
    if (event == null) throw new IllegalStateException("Account source event is missing");
    String payload = new String(event.payload(), StandardCharsets.UTF_8);
    try {
      JsonNode tree = SNAPSHOT_JSON.readTree(payload);
      if (tree == null || !tree.isObject() || !tree.path("schemaVersion").isTextual()) {
        throw new IllegalStateException("Account source event schema is missing");
      }
      return switch (tree.path("schemaVersion").textValue()) {
        case PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION -> {
          var reset = PasswordResetAuthorityEventV1Codec.verify(payload);
          requireEventBinding(
              event,
              accountUuid,
              reset.accountId(),
              reset.requestId(),
              reset.eventId(),
              reset.eventDigest(),
              reset.outboxStreamKey(),
              reset.outboxSequence(),
              reset.canonicalJsonUtf8());
          yield VerifiedSourceEvent.passwordReset(reset);
        }
        case AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION -> {
          var logout = AccountLogoutAllAuthorityEventV1Codec.verify(payload);
          requireEventBinding(
              event,
              accountUuid,
              logout.accountId(),
              logout.requestId(),
              logout.eventId(),
              logout.eventDigest(),
              logout.outboxStreamKey(),
              logout.outboxSequence(),
              logout.canonicalJsonUtf8());
          yield VerifiedSourceEvent.logoutAll(logout);
        }
        case AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION -> {
          var security = AccountSecurityStateAuthorityEventV1Codec.verify(payload);
          requireEventBinding(
              event,
              accountUuid,
              security.accountId(),
              security.requestId(),
              security.eventId(),
              security.eventDigest(),
              security.outboxStreamKey(),
              security.outboxSequence(),
              security.canonicalJsonUtf8());
          yield VerifiedSourceEvent.securityState(security);
        }
        default -> throw new IllegalStateException("Account source event schema is unsupported");
      };
    } catch (IOException | IllegalArgumentException invalid) {
      throw new IllegalStateException("Account source event is invalid", invalid);
    }
  }

  private static void requireEventBinding(
      Event event,
      UUID accountUuid,
      String accountId,
      String requestId,
      String eventId,
      String digest,
      String stream,
      String sequence,
      byte[] canonical) {
    if (!MessageDigest.isEqual(event.payload(), canonical)
        || !accountUuid.toString().equals(accountId)
        || !event.requestId().equals(requestId)
        || !event.eventId().equals(eventId)
        || !event.eventDigest().equals(digest)
        || !event.outboxStreamKey().equals(stream)
        || !Long.toString(event.outboxSequence()).equals(sequence)) {
      throw new IllegalStateException("Account source event readback is inconsistent");
    }
  }

  /** Structural binding only; owner receipt proof is checked separately. */
  static void requireEventMatchesSnapshot(
      Event event, UUID accountUuid, ScopeState current, boolean mustBeCurrentLatest) {
    if (accountUuid == null
        || current == null
        || !AuthorityScope.account(accountUuid).equals(current.scope())
        || current.generation() <= 0L
        || current.sourceVersion() <= 0L) {
      throw new IllegalStateException("Account source snapshot scope or counters are invalid");
    }
    SnapshotCounters verified = verifySnapshotEvent(event, accountUuid);
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

  private static SnapshotCounters verifySnapshotEvent(Event event, UUID accountUuid) {
    VerifiedSourceEvent verified = verifyEvent(event, accountUuid);
    return new SnapshotCounters(verified.accountAuthorityGeneration(), verified.sourceVersion());
  }

  private record SnapshotCounters(String accountAuthorityGeneration, String sourceVersion) {}

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

  /** Exact closed-schema source counters and cutoff returned only after receipt validation. */
  public record RetainedEventEvidence(
      String outboxStreamKey,
      long outboxSequence,
      long accountAuthorityGeneration,
      long sourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      AccountSecurityCutoff accountSecurityCutoff) {
    public RetainedEventEvidence {
      if (outboxStreamKey == null
          || outboxSequence <= 0L
          || accountAuthorityGeneration <= 0L
          || sourceVersion <= 0L
          || issuanceFence != accountAuthorityGeneration
          || issuanceFenceSourceVersion != sourceVersion
          || accountSecurityCutoff == null
          || !outboxStreamKey.equals(accountSecurityCutoff.outboxStreamKey())
          || outboxSequence != Long.parseLong(accountSecurityCutoff.outboxSequence())
          || accountAuthorityGeneration
              != Long.parseLong(accountSecurityCutoff.accountAuthorityGeneration())) {
        throw new IllegalArgumentException("Retained Account source evidence is inconsistent");
      }
    }
  }

  private record VerifiedSourceEvent(
      String requestId,
      String accountAuthorityGeneration,
      String sourceVersion,
      AccountSecurityCutoff accountSecurityCutoff,
      Optional<PasswordResetAuthorityEvent> passwordReset,
      Optional<AccountSecurityStateAuthorityEventV1Codec.AccountSecurityStateAuthorityEvent>
          securityState) {
    private static VerifiedSourceEvent passwordReset(PasswordResetAuthorityEvent event) {
      return new VerifiedSourceEvent(
          event.requestId(),
          event.accountAuthorityGeneration(),
          event.sourceVersion(),
          new AccountSecurityCutoff(
              event.accountSecurityCutoff().accountAuthorityGeneration(),
              event.accountSecurityCutoff().outboxStreamKey(),
              event.accountSecurityCutoff().outboxSequence()),
          Optional.of(event),
          Optional.empty());
    }

    private static VerifiedSourceEvent logoutAll(AccountLogoutAllAuthorityEvent event) {
      return new VerifiedSourceEvent(
          event.requestId(),
          event.accountAuthorityGeneration(),
          event.sourceVersion(),
          new AccountSecurityCutoff(
              event.accountSecurityCutoff().accountAuthorityGeneration(),
              event.accountSecurityCutoff().outboxStreamKey(),
              event.accountSecurityCutoff().outboxSequence()),
          Optional.empty(),
          Optional.empty());
    }

    private static VerifiedSourceEvent securityState(
        AccountSecurityStateAuthorityEventV1Codec.AccountSecurityStateAuthorityEvent event) {
      return new VerifiedSourceEvent(
          event.requestId(),
          event.accountAuthorityGeneration(),
          event.sourceVersion(),
          new AccountSecurityCutoff(
              event.accountSecurityCutoff().accountAuthorityGeneration(),
              event.accountSecurityCutoff().outboxStreamKey(),
              event.accountSecurityCutoff().outboxSequence()),
          Optional.empty(),
          Optional.of(event));
    }
  }
}
