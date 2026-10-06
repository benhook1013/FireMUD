package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChangeAbortReason;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import net.firedevops.firemud.accountservice.security.AccountPendingResetEnvelopeBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Exact Account password-reset owner journal linked to the V57 Draft authorization fence. Methods
 * require the caller's writable Account transaction and lock Account before journal and envelope
 * rows; V57 source participation then acquires its canonical sorted source locks. No source writer,
 * authentication producer, cleanup worker, or Spring component is registered here.
 */
public final class AccountPasswordResetDraftSourceChangeRepository {
  private static final String JOURNAL = "account_password_reset_draft_source_changes";
  private static final String ENVELOPES = "account_password_reset_pending_envelopes";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String REQUEST_PREFIX = "account-password-reset-request-v1:";
  private static final String EVENT_PREFIX = "account-password-reset-event-v1:";
  private static final String REQUEST_DIGEST_SCHEMA = "account-password-reset-request/v1";
  private static final String REQUEST_SCHEMA = "account-password-reset-draft-source-request/v1";
  private static final String MUTATION_SCHEMA = "account-password-reset-draft-source-mutation/v1";
  private static final HexFormat HEX = HexFormat.of();

  private final DSLContext dsl;
  private final DraftAuthorizationFenceRepository fences;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The transaction-aware DSLContext is an internal persistence collaborator.")
  public AccountPasswordResetDraftSourceChangeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    this.fences = new DraftAuthorizationFenceRepository(dsl);
  }

  /**
   * Captures the original reset identity and complete current Account event/baseline. The caller
   * must already have authenticated and revalidated the source; this helper does neither.
   */
  public PendingResetIntent captureIntent(
      Account account,
      String tokenHash,
      LocalDateTime tokenExpiresAt,
      byte[] requestDigest,
      byte[] targetVerifierDigest,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest,
      UUID sourceChangeId) {
    requireAccount(account);
    Objects.requireNonNull(current, "Account authority state is required");
    Objects.requireNonNull(latest, "Account source checkpoint is required");
    Objects.requireNonNull(sourceChangeId, "source-change identity is required");
    byte[] tokenHashBytes = decodeDigest(tokenHash, "token hash");
    byte[] checkedRequestDigest = checkedDigest(requestDigest, "request digest");
    byte[] checkedTargetDigest = checkedDigest(targetVerifierDigest, "target verifier digest");
    if (tokenExpiresAt == null
        || !current
            .scope()
            .equals(
                net.firedevops.firemud.accountservice.repository
                    .AccountAuthorityGenerationRepository.AuthorityScope.account(
                    account.getAccountUuid()))
        || current.issuanceFence() == null
        || !account.getAccountUuid().equals(current.issuanceFence().accountId())) {
      throw new IllegalArgumentException("Original Account source binding is incomplete");
    }
    String requestId = REQUEST_PREFIX + tokenHash;
    byte[] expectedDigest =
        requestDigest(account.getAccountUuid(), tokenHash, tokenExpiresAt, checkedTargetDigest);
    if (!MessageDigest.isEqual(expectedDigest, checkedRequestDigest)) {
      throw new IllegalArgumentException("Password-reset request digest is inconsistent");
    }
    byte[] capture = captureEvidence(account, current, latest);
    SourceEvidence evidence =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            account.getAccountUuid().toString(),
            Long.toString(current.generation()),
            Long.toString(current.sourceVersion()),
            STREAM_PREFIX + account.getAccountUuid(),
            Long.toString(latest.outboxSequence()),
            capture);
    byte[] request =
        requestPayload(
            requestId,
            account.getId(),
            account.getAccountUuid(),
            checkedRequestDigest,
            tokenHashBytes,
            tokenExpiresAt,
            checkedTargetDigest);
    byte[] captureDigest = sha256(capture);
    byte[] mutation =
        mutationPayload(
            request,
            current.generation(),
            current.sourceVersion(),
            current.issuanceFence().value(),
            current.issuanceFence().sourceVersion(),
            latest.outboxSequence(),
            evidence.canonicalBytes(),
            captureDigest);
    SourceChange change = new SourceChange(sourceChangeId, List.of(evidence), mutation);
    return new PendingResetIntent(
        account.getId(),
        account.getAccountUuid(),
        tokenHash,
        tokenExpiresAt,
        checkedRequestDigest,
        checkedTargetDigest,
        current.generation(),
        current.sourceVersion(),
        current.issuanceFence().value(),
        current.issuanceFence().sourceVersion(),
        latest.outboxSequence(),
        capture,
        evidence.canonicalBytes(),
        request,
        change);
  }

  /**
   * Locks Account first, then returns exact durable intent and envelope-presence evidence. An
   * absent journal is not authority to recreate an envelope or recover a verifier from a backup.
   */
  public Optional<PendingResetSnapshot> findForUpdate(
      long accountId, UUID accountUuid, String tokenHash) {
    requireTransaction();
    requireIdentity(accountId, accountUuid);
    byte[] tokenHashBytes = decodeDigest(tokenHash, "token hash");
    lockAccount(accountId, accountUuid);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + JOURNAL + " WHERE token_hash = ? FOR UPDATE", tokenHashBytes);
    if (row == null) {
      return Optional.empty();
    }
    boolean hasEnvelope = envelopeExists(row.get("request_id", String.class));
    PendingResetIntent intent = requireStoredIntent(row);
    if (intent.accountId() != accountId || !intent.accountUuid().equals(accountUuid)) {
      throw new IllegalStateException("Pending reset belongs to a different Account");
    }
    return Optional.of(snapshot(row, intent, hasEnvelope));
  }

  /**
   * Begins the exact V57 source participation. A fresh settled operation may omit ciphertext only
   * because its caller must commit the matching reset in this same transaction. An unresolved
   * WAITING operation always needs its separate pending-reset envelope or the transaction fails.
   */
  public Participation beginWaiting(
      PendingResetIntent intent, Optional<AccountEncryptedEnvelope> pendingEnvelope) {
    requireTransaction();
    Objects.requireNonNull(intent, "pending reset intent");
    Objects.requireNonNull(pendingEnvelope, "pending envelope optional");
    pendingEnvelope.ifPresent(
        envelope -> {
          if (envelope.purpose() != AccountEnvelopePurpose.PENDING_PASSWORD_RESET) {
            throw new IllegalArgumentException(
                "Pending reset requires its distinct envelope purpose");
          }
        });
    lockAccount(intent.accountId(), intent.accountUuid());
    Record prior = findByRequestId(intent.requestId());
    if (prior != null) {
      PendingResetIntent stored = requireStoredIntent(prior);
      requireSameIntent(intent, stored);
      if (!"WAITING".equals(prior.get("status", String.class))) {
        throw new IllegalStateException("Terminal password-reset intent cannot be restarted");
      }
      if (!stored.tokenExpiresAt().isAfter(LocalDateTime.now())) {
        throw new IllegalArgumentException("Pending reset has expired");
      }
      if (pendingEnvelope.isPresent()) {
        throw new IllegalStateException("An existing reset intent cannot remint its envelope");
      }
      boolean hasEnvelope = envelopeExists(stored.requestId());
      if (!hasEnvelope && stored.tokenExpiresAt().isAfter(LocalDateTime.now())) {
        throw new IllegalStateException("Live pending reset has no original encrypted verifier");
      }
      boolean settled = fences.requestSourceChange(stored.sourceChange());
      return new Participation(snapshot(prior, stored, hasEnvelope), settled, false);
    }
    Record tokenCollision = findByTokenHash(decodeDigest(intent.tokenHash(), "token hash"));
    if (tokenCollision != null) {
      throw new IllegalStateException("Reset token identity is already bound to another intent");
    }
    if (dsl.fetchOne(
            "SELECT token_hash FROM account_password_reset_operation_receipts WHERE token_hash = ?",
            decodeDigest(intent.tokenHash(), "token hash"))
        != null) {
      throw new IllegalStateException("Completed reset receipt cannot be restarted");
    }
    if (!intent.tokenExpiresAt().isAfter(LocalDateTime.now())) {
      throw new IllegalArgumentException("Password-reset token is expired");
    }
    boolean settled = fences.requestSourceChange(intent.sourceChange());
    Optional<AccountEncryptedEnvelope> persistEnvelope =
        settled ? Optional.empty() : pendingEnvelope;
    if (persistEnvelope.isEmpty() && !settled) {
      throw new IllegalStateException(
          "Unsettled reset intent requires its separate encrypted verifier");
    }
    persistJournal(intent);
    persistEnvelope.ifPresent(envelope -> insertEnvelope(intent.requestId(), envelope));
    Record storedRow = findByRequestId(intent.requestId());
    PendingResetIntent readback = requireStoredIntent(storedRow);
    requireSameIntent(intent, readback);
    boolean envelopeStored = envelopeExists(intent.requestId());
    DraftAuthorizationFenceRepository.SourceChangeSnapshot sourceReadback =
        fences.readSourceChange(intent.sourceChange());
    if (!"WAITING".equals(sourceReadback.status())) {
      throw new IllegalStateException("New reset intent did not retain its exact V57 WAITING row");
    }
    if (envelopeStored != persistEnvelope.isPresent()) {
      throw new IllegalStateException("Pending reset envelope insertion readback differs");
    }
    return new Participation(snapshot(storedRow, readback, envelopeStored), settled, true);
  }

  /**
   * Claims existing exact WAITING intent after locking Account/journal; never creates ciphertext.
   */
  public Participation claim(PendingResetSnapshot snapshot) {
    requireTransaction();
    Objects.requireNonNull(snapshot, "pending reset snapshot");
    PendingResetIntent intent = snapshot.intent();
    lockAccount(intent.accountId(), intent.accountUuid());
    Record row = findByRequestId(intent.requestId());
    Record envelopeRow =
        dsl.fetchOne(
            "SELECT * FROM " + ENVELOPES + " WHERE request_id = ? FOR UPDATE", intent.requestId());
    PendingResetIntent stored = requireStoredIntent(row);
    requireSameIntent(intent, stored);
    if (!"WAITING".equals(row.get("status", String.class))) {
      throw new IllegalStateException("Only a pending reset can be claimed");
    }
    if (!stored.tokenExpiresAt().isAfter(LocalDateTime.now())) {
      throw new IllegalArgumentException("Pending reset has expired");
    }
    if (envelopeRow == null) {
      throw new IllegalStateException("Original pending reset envelope is missing");
    }
    boolean settled = fences.requestSourceChange(stored.sourceChange());
    return new Participation(snapshot(row, stored, true), settled, false);
  }

  /** Read-only exact envelope lookup. Absence never means re-encryption is permitted. */
  public Optional<AccountEncryptedEnvelope> readPendingEnvelope(PendingResetSnapshot snapshot) {
    requireTransaction();
    Objects.requireNonNull(snapshot, "pending reset snapshot");
    PendingResetIntent intent = snapshot.intent();
    lockAccount(intent.accountId(), intent.accountUuid());
    Record journal = findByRequestId(intent.requestId());
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + ENVELOPES + " WHERE request_id = ? FOR UPDATE", intent.requestId());
    requireSameIntent(intent, requireStoredIntent(journal));
    return Optional.ofNullable(row == null ? null : envelopeFromRow(row));
  }

  /** Permission is only the exact ordering-aware V57 predicate; no account state is advanced. */
  public boolean sourceMutationPermitted(Participation participation) {
    requireTransaction();
    PendingResetIntent intent = requireParticipation(participation).intent();
    lockAccount(intent.accountId(), intent.accountUuid());
    Record row = findByRequestId(intent.requestId());
    boolean hasEnvelope = envelopeExists(intent.requestId());
    requireSameIntent(intent, requireStoredIntent(row));
    if (!hasEnvelope
        && !(participation.createdInThisTransaction() && participation.settledAtClaim())) {
      return false;
    }
    return "WAITING".equals(row.get("status", String.class))
        && intent.tokenExpiresAt().isAfter(LocalDateTime.now())
        && fences.sourceMutationPermitted(intent.sourceChange());
  }

  /**
   * Links caller-produced account/token/source/event/receipt readbacks, transitions both journals,
   * and erases ciphertext in the same transaction. The no-envelope path is available only to the
   * fresh settled participation returned by beginWaiting; callers may not commit it as WAITING.
   */
  public void complete(
      Participation participation,
      PasswordResetReceipt receipt,
      Event event,
      LocalDateTime capturedNow) {
    requireTransaction();
    Objects.requireNonNull(receipt, "password-reset receipt");
    Objects.requireNonNull(event, "password-reset event");
    Objects.requireNonNull(capturedNow, "completion time");
    Participation selected = requireParticipation(participation);
    PendingResetIntent intent = selected.intent();
    lockAccount(intent.accountId(), intent.accountUuid());
    Record row = findByRequestId(intent.requestId());
    boolean hasEnvelope = envelopeExists(intent.requestId());
    PendingResetIntent stored = requireStoredIntent(row);
    requireSameIntent(intent, stored);
    String status = row.get("status", String.class);
    if ("SOURCE_ABORTED".equals(status)) {
      throw new IllegalStateException("Aborted password-reset intent cannot be completed");
    }
    requireReceiptAndEvent(intent, receipt, event);
    if ("SOURCE_COMMITTED".equals(status)) {
      requireCommittedReadback(intent, receipt, event);
      if (!"SOURCE_COMMITTED".equals(fences.readSourceChange(intent.sourceChange()).status())) {
        throw new IllegalStateException("Committed reset and V57 source outcome differ");
      }
      deleteEnvelopeIfPresent(intent.requestId());
      requireJournalCommitted(row, event);
      return;
    }
    if (!"WAITING".equals(status)
        || !intent.tokenExpiresAt().isAfter(capturedNow)
        || !fences.sourceMutationPermitted(intent.sourceChange())) {
      throw new IllegalStateException("Password-reset source is expired, unresolved, or terminal");
    }
    if (!hasEnvelope && !(selected.createdInThisTransaction() && selected.settledAtClaim())) {
      throw new IllegalStateException(
          "Pending reset cannot complete without its original envelope");
    }
    requireCommittedReadback(intent, receipt, event);
    fences.markSourceCommitted(intent.sourceChange());
    int updated =
        dsl.execute(
            "UPDATE "
                + JOURNAL
                + " SET status = 'SOURCE_COMMITTED', committed_at = CURRENT_TIMESTAMP,"
                + " event_stream = ?, event_sequence = ?, event_id = ?, event_digest = ?,"
                + " event_payload = ? WHERE request_id = ? AND status = 'WAITING'",
            event.outboxStreamKey(),
            event.outboxSequence(),
            event.eventId(),
            event.eventDigest(),
            event.payload(),
            intent.requestId());
    if (updated != 1) {
      throw new IllegalStateException("Password-reset journal did not commit exactly once");
    }
    deleteEnvelopeIfPresent(intent.requestId());
    Record readback = findByRequestId(intent.requestId());
    if (!"SOURCE_COMMITTED".equals(readback.get("status", String.class))
        || !"SOURCE_COMMITTED".equals(fences.readSourceChange(intent.sourceChange()).status())) {
      throw new IllegalStateException("Password-reset journal outcome readback differs");
    }
    requireJournalCommitted(readback, event);
    requireCommittedReadback(intent, receipt, event);
  }

  /**
   * Applies a no-mutation terminal result only after the exact V57 settlement predicate. Expiry
   * first erases ciphertext even if owners remain unresolved; in that case the journal/fence stay
   * WAITING and the method returns false.
   */
  public boolean abort(
      PendingResetSnapshot snapshot, AbortReason reason, LocalDateTime capturedNow) {
    requireTransaction();
    Objects.requireNonNull(snapshot, "pending reset snapshot");
    Objects.requireNonNull(reason, "abort reason");
    Objects.requireNonNull(capturedNow, "abort time");
    PendingResetIntent intent = snapshot.intent();
    lockAccount(intent.accountId(), intent.accountUuid());
    Record row = findByRequestId(intent.requestId());
    boolean hasEnvelope = envelopeExists(intent.requestId());
    PendingResetIntent stored = requireStoredIntent(row);
    requireSameIntent(intent, stored);
    String status = row.get("status", String.class);
    if ("SOURCE_ABORTED".equals(status)) {
      requireAbortReason(row, reason);
      requireUnchangedSource(intent);
      if (hasEnvelope) {
        throw new IllegalStateException("Aborted password-reset envelope was not erased");
      }
      return true;
    }
    if ("SOURCE_COMMITTED".equals(status)) {
      throw new IllegalStateException("Committed password-reset intent cannot be aborted");
    }
    if (!"WAITING".equals(status)) {
      throw new IllegalStateException("Password-reset intent is not waiting");
    }
    if (reason == AbortReason.EXPIRED) {
      if (intent.tokenExpiresAt().isAfter(capturedNow)) {
        throw new IllegalArgumentException("Original password-reset deadline has not expired");
      }
      deleteEnvelopeIfPresent(intent.requestId());
    }
    requireUnchangedSource(intent);
    if (!fences.sourceAbortPermitted(intent.sourceChange())) {
      return false;
    }
    SourceChangeAbortReason fenceReason =
        reason == AbortReason.EXPIRED
            ? SourceChangeAbortReason.EXPIRED
            : SourceChangeAbortReason.DEFINITIVE_ABORT;
    fences.markSourceAborted(intent.sourceChange(), fenceReason);
    int updated =
        dsl.execute(
            "UPDATE "
                + JOURNAL
                + " SET status = 'SOURCE_ABORTED', aborted_at = CURRENT_TIMESTAMP, abort_reason = ?"
                + " WHERE request_id = ? AND status = 'WAITING'",
            reason.name(),
            intent.requestId());
    if (updated != 1) {
      throw new IllegalStateException("Password-reset no-mutation outcome was not recorded once");
    }
    deleteEnvelopeIfPresent(intent.requestId());
    Record readback = findByRequestId(intent.requestId());
    if (!"SOURCE_ABORTED".equals(readback.get("status", String.class))
        || !reason.name().equals(readback.get("abort_reason", String.class))) {
      throw new IllegalStateException("Password-reset abort readback differs");
    }
    DraftAuthorizationFenceRepository.SourceChangeSnapshot fenceReadback =
        fences.readSourceChange(intent.sourceChange());
    if (!"SOURCE_ABORTED".equals(fenceReadback.status())
        || fenceReadback.abortReason() != fenceReason
        || envelopeExists(intent.requestId())) {
      throw new IllegalStateException("Password-reset abort and V57 fence readback differ");
    }
    return true;
  }

  /** Expiry-only idempotent ciphertext erasure; does not settle, cancel, or release V57 fences. */
  public boolean eraseEnvelopeAfterExpiry(
      PendingResetSnapshot snapshot, LocalDateTime capturedNow) {
    requireTransaction();
    Objects.requireNonNull(snapshot, "pending reset snapshot");
    Objects.requireNonNull(capturedNow, "expiry time");
    PendingResetIntent intent = snapshot.intent();
    lockAccount(intent.accountId(), intent.accountUuid());
    Record row = findByRequestId(intent.requestId());
    envelopeExists(intent.requestId());
    requireSameIntent(intent, requireStoredIntent(row));
    if (intent.tokenExpiresAt().isAfter(capturedNow)) {
      throw new IllegalArgumentException("Original password-reset deadline has not expired");
    }
    return deleteEnvelopeIfPresent(intent.requestId());
  }

  public enum AbortReason {
    EXPIRED,
    DEFINITIVE_ABORT
  }

  /** Immutable original reset request and complete captured Account source evidence. */
  public static final class PendingResetIntent {
    private final long accountId;
    private final UUID accountUuid;
    private final String tokenHash;
    private final LocalDateTime tokenExpiresAt;
    private final byte[] requestDigest;
    private final byte[] targetVerifierDigest;
    private final long expectedGeneration;
    private final long expectedSourceVersion;
    private final long expectedIssuanceFence;
    private final long expectedIssuanceFenceSourceVersion;
    private final long checkpointSequence;
    private final byte[] captureEvidence;
    private final byte[] sourceEvidence;
    private final byte[] requestPayload;
    private final SourceChange sourceChange;

    private PendingResetIntent(
        long accountId,
        UUID accountUuid,
        String tokenHash,
        LocalDateTime tokenExpiresAt,
        byte[] requestDigest,
        byte[] targetVerifierDigest,
        long expectedGeneration,
        long expectedSourceVersion,
        long expectedIssuanceFence,
        long expectedIssuanceFenceSourceVersion,
        long checkpointSequence,
        byte[] captureEvidence,
        byte[] sourceEvidence,
        byte[] requestPayload,
        SourceChange sourceChange) {
      requireIdentity(accountId, accountUuid);
      decodeDigest(tokenHash, "token hash");
      this.accountId = accountId;
      this.accountUuid = accountUuid;
      this.tokenHash = tokenHash;
      this.tokenExpiresAt = Objects.requireNonNull(tokenExpiresAt);
      this.requestDigest = checkedDigest(requestDigest, "request digest");
      this.targetVerifierDigest = checkedDigest(targetVerifierDigest, "target verifier digest");
      if (expectedGeneration <= 0L
          || expectedSourceVersion <= 0L
          || expectedIssuanceFence <= 0L
          || expectedIssuanceFenceSourceVersion <= 0L
          || checkpointSequence < 0L) {
        throw new IllegalArgumentException("Original Account source counters are invalid");
      }
      this.expectedGeneration = expectedGeneration;
      this.expectedSourceVersion = expectedSourceVersion;
      this.expectedIssuanceFence = expectedIssuanceFence;
      this.expectedIssuanceFenceSourceVersion = expectedIssuanceFenceSourceVersion;
      this.checkpointSequence = checkpointSequence;
      this.captureEvidence = nonEmptyBytes(captureEvidence, "Account capture evidence");
      this.sourceEvidence = nonEmptyBytes(sourceEvidence, "Account source evidence");
      this.requestPayload = nonEmptyBytes(requestPayload, "reset request payload");
      this.sourceChange = Objects.requireNonNull(sourceChange);
      if (sourceChange.sources().size() != 1
          || !sourceChange.sources().getFirst().key().equals("ACCOUNT:" + accountUuid)) {
        throw new IllegalArgumentException("Password-reset V57 source binding is malformed");
      }
    }

    public long accountId() {
      return accountId;
    }

    public UUID accountUuid() {
      return accountUuid;
    }

    public String tokenHash() {
      return tokenHash;
    }

    public String requestId() {
      return REQUEST_PREFIX + tokenHash;
    }

    public String eventId() {
      return EVENT_PREFIX + tokenHash;
    }

    public LocalDateTime tokenExpiresAt() {
      return tokenExpiresAt;
    }

    public byte[] requestDigest() {
      return requestDigest.clone();
    }

    public byte[] targetVerifierDigest() {
      return targetVerifierDigest.clone();
    }

    public long expectedGeneration() {
      return expectedGeneration;
    }

    public long expectedSourceVersion() {
      return expectedSourceVersion;
    }

    public long expectedIssuanceFence() {
      return expectedIssuanceFence;
    }

    public long expectedIssuanceFenceSourceVersion() {
      return expectedIssuanceFenceSourceVersion;
    }

    public long checkpointSequence() {
      return checkpointSequence;
    }

    public byte[] captureEvidence() {
      return captureEvidence.clone();
    }

    public byte[] sourceEvidence() {
      return sourceEvidence.clone();
    }

    public byte[] requestPayload() {
      return requestPayload.clone();
    }

    public SourceChange sourceChange() {
      return sourceChange;
    }

    public AccountPendingResetEnvelopeBinding envelopeBinding() {
      return new AccountPendingResetEnvelopeBinding(
          accountUuid,
          tokenHash,
          tokenExpiresAt,
          requestId(),
          requestDigest,
          sha256(captureEvidence),
          targetVerifierDigest);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof PendingResetIntent that)) return false;
      return accountId == that.accountId
          && expectedGeneration == that.expectedGeneration
          && expectedSourceVersion == that.expectedSourceVersion
          && expectedIssuanceFence == that.expectedIssuanceFence
          && expectedIssuanceFenceSourceVersion == that.expectedIssuanceFenceSourceVersion
          && checkpointSequence == that.checkpointSequence
          && accountUuid.equals(that.accountUuid)
          && tokenHash.equals(that.tokenHash)
          && tokenExpiresAt.equals(that.tokenExpiresAt)
          && Arrays.equals(requestDigest, that.requestDigest)
          && Arrays.equals(targetVerifierDigest, that.targetVerifierDigest)
          && Arrays.equals(captureEvidence, that.captureEvidence)
          && Arrays.equals(sourceEvidence, that.sourceEvidence)
          && Arrays.equals(requestPayload, that.requestPayload)
          && Arrays.equals(sourceChange.canonicalBytes(), that.sourceChange.canonicalBytes());
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(
              accountId,
              accountUuid,
              tokenHash,
              tokenExpiresAt,
              expectedGeneration,
              expectedSourceVersion,
              expectedIssuanceFence,
              expectedIssuanceFenceSourceVersion,
              checkpointSequence);
      result = 31 * result + Arrays.hashCode(requestDigest);
      result = 31 * result + Arrays.hashCode(targetVerifierDigest);
      result = 31 * result + Arrays.hashCode(captureEvidence);
      result = 31 * result + Arrays.hashCode(sourceEvidence);
      result = 31 * result + Arrays.hashCode(requestPayload);
      return 31 * result + Arrays.hashCode(sourceChange.canonicalBytes());
    }

    @Override
    public String toString() {
      return "PendingResetIntent{account=<redacted>, request=<redacted>, source=<redacted>}";
    }
  }

  /** Exact pending row readback, with immutable bytes and secret-free rendering. */
  public record PendingResetSnapshot(
      PendingResetIntent intent,
      String status,
      OffsetDateTime createdAt,
      OffsetDateTime committedAt,
      OffsetDateTime abortedAt,
      AbortReason abortReason,
      String eventStream,
      Long eventSequence,
      String eventId,
      String eventDigest,
      byte[] eventPayload,
      boolean envelopePresent) {
    public PendingResetSnapshot {
      Objects.requireNonNull(intent);
      Objects.requireNonNull(status);
      eventPayload = eventPayload == null ? null : eventPayload.clone();
    }

    @Override
    public byte[] eventPayload() {
      return eventPayload == null ? null : eventPayload.clone();
    }

    @Override
    public String toString() {
      return "PendingResetSnapshot{intent=<redacted>, status=" + status + ", outcome=<redacted>}";
    }
  }

  /** Returned participation is a caller-transaction capability, not a new source proof. */
  public static final class Participation {
    private final PendingResetSnapshot snapshot;
    private final boolean settledAtClaim;
    private final boolean createdInThisTransaction;

    private Participation(
        PendingResetSnapshot snapshot, boolean settledAtClaim, boolean createdInThisTransaction) {
      this.snapshot = Objects.requireNonNull(snapshot);
      this.settledAtClaim = settledAtClaim;
      this.createdInThisTransaction = createdInThisTransaction;
    }

    public PendingResetSnapshot snapshot() {
      return snapshot;
    }

    public boolean settledAtClaim() {
      return settledAtClaim;
    }

    public boolean createdInThisTransaction() {
      return createdInThisTransaction;
    }

    private PendingResetIntent intent() {
      return snapshot.intent();
    }
  }

  private Participation requireParticipation(Participation participation) {
    return Objects.requireNonNull(participation, "reset participation");
  }

  private void persistJournal(PendingResetIntent intent) {
    dsl.execute(
        "INSERT INTO "
            + JOURNAL
            + " (request_id, account_id, account_uuid, token_hash, request_digest_version,"
            + " request_digest, token_expires_at, token_expires_at_text, target_verifier_digest,"
            + " expected_generation, expected_source_version, expected_issuance_fence,"
            + " expected_issuance_fence_source_version, checkpoint_stream, checkpoint_sequence,"
            + " capture_evidence, capture_evidence_digest, request_payload, source_evidence,"
            + " source_change_id, source_change_request, source_change_binding, status)"
            + " VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING')",
        intent.requestId(),
        intent.accountId(),
        intent.accountUuid(),
        decodeDigest(intent.tokenHash(), "token hash"),
        intent.requestDigest(),
        intent.tokenExpiresAt(),
        intent.tokenExpiresAt().toString(),
        intent.targetVerifierDigest(),
        intent.expectedGeneration(),
        intent.expectedSourceVersion(),
        intent.expectedIssuanceFence(),
        intent.expectedIssuanceFenceSourceVersion(),
        STREAM_PREFIX + intent.accountUuid(),
        intent.checkpointSequence(),
        intent.captureEvidence(),
        sha256(intent.captureEvidence()),
        intent.requestPayload(),
        intent.sourceEvidence(),
        intent.sourceChange().changeId(),
        intent.sourceChange().mutation(),
        intent.sourceChange().canonicalBytes());
  }

  private void insertEnvelope(String requestId, AccountEncryptedEnvelope envelope) {
    if (envelope.purpose() != AccountEnvelopePurpose.PENDING_PASSWORD_RESET) {
      throw new IllegalArgumentException("Pending reset requires its distinct envelope purpose");
    }
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + ENVELOPES
                + " (request_id, format_version, key_id, purpose, nonce, ciphertext)"
                + " VALUES (?, ?, ?, 'pending-reset', ?, ?)",
            requestId,
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.nonce(),
            envelope.ciphertext());
    if (inserted != 1) {
      throw new IllegalStateException("Pending reset envelope was not inserted exactly once");
    }
  }

  private PendingResetIntent requireStoredIntent(Record row) {
    if (row == null) {
      throw new IllegalArgumentException("Pending reset intent is absent");
    }
    long accountId = requiredLong(row, "account_id");
    UUID accountUuid = Objects.requireNonNull(row.get("account_uuid", UUID.class));
    String tokenHash = HEX.formatHex(requiredBytes(row, "token_hash"));
    LocalDateTime expiresAt =
        Objects.requireNonNull(row.get("token_expires_at", LocalDateTime.class));
    String expiresText = Objects.requireNonNull(row.get("token_expires_at_text", String.class));
    if (!expiresAt.toString().equals(expiresText)) {
      throw new IllegalStateException("Stored password-reset deadline is not canonical");
    }
    byte[] requestDigest = requiredBytes(row, "request_digest");
    byte[] verifierDigest = requiredBytes(row, "target_verifier_digest");
    long generation = requiredLong(row, "expected_generation");
    long sourceVersion = requiredLong(row, "expected_source_version");
    long fence = requiredLong(row, "expected_issuance_fence");
    long fenceVersion = requiredLong(row, "expected_issuance_fence_source_version");
    long sequence = requiredLong(row, "checkpoint_sequence");
    byte[] capture = requiredBytes(row, "capture_evidence");
    byte[] sourceEvidence = requiredBytes(row, "source_evidence");
    byte[] request = requiredBytes(row, "request_payload");
    byte[] changeBinding = requiredBytes(row, "source_change_binding");
    SourceChange change = SourceChange.fromStored(changeBinding);
    PendingResetIntent intent =
        new PendingResetIntent(
            accountId,
            accountUuid,
            tokenHash,
            expiresAt,
            requestDigest,
            verifierDigest,
            generation,
            sourceVersion,
            fence,
            fenceVersion,
            sequence,
            capture,
            sourceEvidence,
            request,
            change);
    byte[] expectedRequest =
        requestPayload(
            intent.requestId(),
            accountId,
            accountUuid,
            requestDigest,
            decodeDigest(tokenHash, "token hash"),
            expiresAt,
            verifierDigest);
    SourceEvidence evidence =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            accountUuid.toString(),
            Long.toString(generation),
            Long.toString(sourceVersion),
            STREAM_PREFIX + accountUuid,
            Long.toString(sequence),
            capture);
    byte[] expectedMutation =
        mutationPayload(
            expectedRequest,
            generation,
            sourceVersion,
            fence,
            fenceVersion,
            sequence,
            evidence.canonicalBytes(),
            sha256(capture));
    byte[] expectedBinding =
        new SourceChange(change.changeId(), List.of(evidence), expectedMutation).canonicalBytes();
    byte[] expectedDigest = requestDigest(accountUuid, tokenHash, expiresAt, verifierDigest);
    if (!intent.requestId().equals(row.get("request_id", String.class))
        || row.get("request_digest_version", Integer.class) != 1
        || !Arrays.equals(expectedDigest, requestDigest)
        || !Arrays.equals(expectedRequest, request)
        || !Arrays.equals(evidence.canonicalBytes(), sourceEvidence)
        || !Arrays.equals(expectedMutation, change.mutation())
        || !Arrays.equals(expectedBinding, changeBinding)
        || !change.changeId().equals(row.get("source_change_id", UUID.class))
        || !Arrays.equals(change.mutation(), requiredBytes(row, "source_change_request"))
        || !Arrays.equals(sha256(capture), requiredBytes(row, "capture_evidence_digest"))) {
      throw new IllegalStateException("Pending reset row differs from its exact immutable binding");
    }
    DraftAuthorizationFenceRepository.SourceChangeSnapshot fenceReadback =
        fences.inspectSourceChange(change);
    if (!Objects.equals(row.get("status", String.class), fenceReadback.status())
        || !Arrays.equals(changeBinding, fenceReadback.binding())) {
      throw new IllegalStateException(
          "Pending reset journal differs from its exact V57 source row");
    }
    if ("SOURCE_ABORTED".equals(fenceReadback.status())
        && (!Objects.equals(
                row.get("abort_reason", String.class), fenceReadback.abortReason().name())
            || !Objects.equals(
                row.get("aborted_at", OffsetDateTime.class), fenceReadback.abortedAt()))) {
      throw new IllegalStateException("Pending reset and V57 abort readbacks differ");
    }
    return intent;
  }

  private PendingResetSnapshot snapshot(
      Record row, PendingResetIntent intent, boolean envelopePresent) {
    String abortReason = row.get("abort_reason", String.class);
    return new PendingResetSnapshot(
        intent,
        row.get("status", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("committed_at", OffsetDateTime.class),
        row.get("aborted_at", OffsetDateTime.class),
        abortReason == null ? null : AbortReason.valueOf(abortReason),
        row.get("event_stream", String.class),
        row.get("event_sequence", Long.class),
        row.get("event_id", String.class),
        row.get("event_digest", String.class),
        row.get("event_payload", byte[].class),
        envelopePresent);
  }

  private void requireSameIntent(PendingResetIntent expected, PendingResetIntent actual) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("Pending reset immutable request/source binding changed");
    }
  }

  private Record findByRequestId(String requestId) {
    return dsl.fetchOne("SELECT * FROM " + JOURNAL + " WHERE request_id = ? FOR UPDATE", requestId);
  }

  private Record findByTokenHash(byte[] tokenHash) {
    return dsl.fetchOne("SELECT * FROM " + JOURNAL + " WHERE token_hash = ? FOR UPDATE", tokenHash);
  }

  private boolean envelopeExists(String requestId) {
    return dsl.fetchOne(
            "SELECT request_id FROM " + ENVELOPES + " WHERE request_id = ? FOR UPDATE", requestId)
        != null;
  }

  private AccountEncryptedEnvelope envelopeFromRow(Record row) {
    if (!"pending-reset".equals(row.get("purpose", String.class))) {
      throw new IllegalStateException("Stored pending reset envelope has the wrong purpose");
    }
    return new AccountEncryptedEnvelope(
        row.get("format_version", Short.class).intValue(),
        row.get("key_id", String.class),
        AccountEnvelopePurpose.PENDING_PASSWORD_RESET,
        requiredBytes(row, "nonce"),
        requiredBytes(row, "ciphertext"));
  }

  private void requireReceiptAndEvent(
      PendingResetIntent intent, PasswordResetReceipt receipt, Event event) {
    if (receipt.accountId() != intent.accountId()
        || !receipt.accountUuid().equals(intent.accountUuid())
        || !receipt.tokenHash().equals(intent.tokenHash())
        || !receipt.requestId().equals(intent.requestId())
        || !receipt.requestDigest().equals(HEX.formatHex(intent.requestDigest()))
        || receipt.requestDigestVersion() != 1
        || !receipt.tokenExpiresAt().equals(intent.tokenExpiresAt())
        || !receipt.passwordVerifierDigest().equals(HEX.formatHex(intent.targetVerifierDigest()))
        || !receipt.operationKind().equals("PASSWORD_RESET")
        || !receipt.eventId().equals(intent.eventId())
        || !receipt.outboxStreamKey().equals(STREAM_PREFIX + intent.accountUuid())
        || receipt.outboxSequence() != intent.checkpointSequence() + 1
        || !event.outboxStreamKey().equals(receipt.outboxStreamKey())
        || !event.requestId().equals(intent.requestId())
        || event.outboxSequence() != receipt.outboxSequence()
        || !event.eventId().equals(receipt.eventId())
        || !event.eventDigest().equals(receipt.eventDigest())) {
      throw new IllegalArgumentException(
          "Reset receipt/event do not match the original pending intent");
    }
  }

  private void requireJournalCommitted(Record row, Event expectedEvent) {
    if (row == null
        || !"SOURCE_COMMITTED".equals(row.get("status", String.class))
        || row.get("committed_at", OffsetDateTime.class) == null
        || row.get("aborted_at", OffsetDateTime.class) != null
        || row.get("abort_reason", String.class) != null
        || !expectedEvent.outboxStreamKey().equals(row.get("event_stream", String.class))
        || !Long.valueOf(expectedEvent.outboxSequence())
            .equals(row.get("event_sequence", Long.class))
        || !expectedEvent.eventId().equals(row.get("event_id", String.class))
        || !expectedEvent.eventDigest().equals(row.get("event_digest", String.class))
        || !Arrays.equals(expectedEvent.payload(), row.get("event_payload", byte[].class))
        || envelopeExists(row.get("request_id", String.class))) {
      throw new IllegalStateException("Committed password-reset journal readback differs");
    }
  }

  private void requireAbortReason(Record row, AbortReason expected) {
    if (!expected.name().equals(row.get("abort_reason", String.class))
        || row.get("aborted_at", OffsetDateTime.class) == null
        || row.get("committed_at", OffsetDateTime.class) != null) {
      throw new IllegalArgumentException("Password-reset abort outcome differs from its readback");
    }
  }

  private void requireCommittedReadback(
      PendingResetIntent intent, PasswordResetReceipt expectedReceipt, Event expectedEvent) {
    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid, password_hash FROM accounts WHERE id = ? AND account_uuid = ? FOR UPDATE",
            intent.accountId(),
            intent.accountUuid());
    if (account == null
        || account.get("password_hash", String.class) == null
        || !Arrays.equals(
            sha256(account.get("password_hash", String.class).getBytes(StandardCharsets.UTF_8)),
            intent.targetVerifierDigest())) {
      throw new IllegalStateException("Committed reset verifier readback differs from its intent");
    }
    Record generation =
        dsl.fetchOne(
            "SELECT generation, source_version FROM account_authority_generations"
                + " WHERE scope_kind = 'ACCOUNT' AND account_uuid = ? FOR UPDATE",
            intent.accountUuid());
    Record issuance =
        dsl.fetchOne(
            "SELECT issuance_fence, source_version FROM account_authority_issuance_fences"
                + " WHERE account_uuid = ? FOR UPDATE",
            intent.accountUuid());
    if (generation == null
        || issuance == null
        || requiredLong(generation, "generation") != intent.expectedGeneration() + 1
        || requiredLong(generation, "source_version") != intent.expectedSourceVersion() + 1
        || requiredLong(issuance, "issuance_fence") != intent.expectedIssuanceFence() + 1
        || requiredLong(issuance, "source_version")
            != intent.expectedIssuanceFenceSourceVersion() + 1) {
      throw new IllegalStateException(
          "Committed reset authority counters differ from the original capture");
    }
    Record receipt =
        dsl.fetchOne(
            "SELECT * FROM account_password_reset_operation_receipts WHERE token_hash = ?",
            decodeDigest(intent.tokenHash(), "token hash"));
    if (receipt == null || !receiptMatches(receipt, expectedReceipt)) {
      throw new IllegalStateException("Committed reset receipt durable readback differs");
    }
    Record event =
        dsl.fetchOne(
            "SELECT * FROM account_authority_outbox_events"
                + " WHERE outbox_stream_key = ? AND outbox_sequence = ?",
            expectedEvent.outboxStreamKey(),
            expectedEvent.outboxSequence());
    if (event == null
        || !expectedEvent.requestId().equals(event.get("request_id", String.class))
        || !expectedEvent.eventId().equals(event.get("event_id", String.class))
        || !expectedEvent.eventDigest().equals(event.get("event_digest", String.class))
        || !Arrays.equals(expectedEvent.payload(), requiredBytes(event, "payload"))) {
      throw new IllegalStateException("Committed reset event durable readback differs");
    }
    Record checkpoint =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_authority_outbox_streams WHERE outbox_stream_key = ?",
            expectedEvent.outboxStreamKey());
    if (checkpoint == null
        || requiredLong(checkpoint, "last_sequence") != expectedEvent.outboxSequence()) {
      throw new IllegalStateException("Committed reset checkpoint durable readback differs");
    }
    if (dsl.fetchOne(
            "SELECT token_hash FROM password_reset_token WHERE account_id = ?"
                + " AND sha256(convert_to(token, 'UTF8')) = ?",
            intent.accountId(),
            decodeDigest(intent.tokenHash(), "token hash"))
        != null) {
      throw new IllegalStateException("Committed reset token was not consumed");
    }
  }

  private boolean receiptMatches(Record row, PasswordResetReceipt receipt) {
    return requiredLong(row, "account_id") == receipt.accountId()
        && receipt.accountUuid().equals(row.get("account_uuid", UUID.class))
        && Arrays.equals(
            requiredBytes(row, "token_hash"), decodeDigest(receipt.tokenHash(), "token hash"))
        && receipt.operationKind().equals(row.get("operation_kind", String.class))
        && receipt.requestId().equals(row.get("request_id", String.class))
        && row.get("request_digest_version", Integer.class) == receipt.requestDigestVersion()
        && Arrays.equals(
            requiredBytes(row, "request_digest"),
            decodeDigest(receipt.requestDigest(), "request digest"))
        && receipt.tokenExpiresAt().equals(row.get("token_expires_at", LocalDateTime.class))
        && Arrays.equals(
            requiredBytes(row, "password_verifier_digest"),
            decodeDigest(receipt.passwordVerifierDigest(), "verifier digest"))
        && receipt.outboxStreamKey().equals(row.get("outbox_stream_key", String.class))
        && requiredLong(row, "outbox_sequence") == receipt.outboxSequence()
        && receipt.eventId().equals(row.get("event_id", String.class))
        && receipt.eventDigest().equals(row.get("event_digest", String.class))
        && requiredLong(row, "account_authority_generation") == receipt.accountAuthorityGeneration()
        && requiredLong(row, "account_source_version") == receipt.accountSourceVersion()
        && requiredLong(row, "issuance_fence") == receipt.issuanceFence()
        && requiredLong(row, "issuance_fence_source_version")
            == receipt.issuanceFenceSourceVersion();
  }

  private void requireUnchangedSource(PendingResetIntent intent) {
    Record generation =
        dsl.fetchOne(
            "SELECT generation, source_version FROM account_authority_generations"
                + " WHERE scope_kind = 'ACCOUNT' AND account_uuid = ? FOR UPDATE",
            intent.accountUuid());
    Record issuance =
        dsl.fetchOne(
            "SELECT issuance_fence, source_version FROM account_authority_issuance_fences"
                + " WHERE account_uuid = ? FOR UPDATE",
            intent.accountUuid());
    Record checkpoint =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_authority_outbox_streams WHERE outbox_stream_key = ? FOR UPDATE",
            STREAM_PREFIX + intent.accountUuid());
    Long sequence = checkpoint == null ? null : requiredLong(checkpoint, "last_sequence");
    if (generation == null
        || issuance == null
        || requiredLong(generation, "generation") != intent.expectedGeneration()
        || requiredLong(generation, "source_version") != intent.expectedSourceVersion()
        || requiredLong(issuance, "issuance_fence") != intent.expectedIssuanceFence()
        || requiredLong(issuance, "source_version") != intent.expectedIssuanceFenceSourceVersion()
        || (intent.checkpointSequence() == 0
            ? (sequence != null && sequence != 0L)
            : !Long.valueOf(intent.checkpointSequence()).equals(sequence))) {
      throw new IllegalStateException(
          "Pending reset Account source changed before no-mutation outcome");
    }
    if (intent.checkpointSequence() > 0) {
      Record event =
          dsl.fetchOne(
              "SELECT payload FROM account_authority_outbox_events"
                  + " WHERE outbox_stream_key = ? AND outbox_sequence = ?",
              STREAM_PREFIX + intent.accountUuid(),
              intent.checkpointSequence());
      if (event == null
          || !Arrays.equals(requiredBytes(event, "payload"), intent.captureEvidence())) {
        throw new IllegalStateException("Pending reset lost its original source event capture");
      }
    }
    if (dsl.fetchOne(
                "SELECT token_hash FROM account_password_reset_operation_receipts WHERE token_hash = ?",
                decodeDigest(intent.tokenHash(), "token hash"))
            != null
        || dsl.fetchOne(
                "SELECT event_id FROM account_authority_outbox_events"
                    + " WHERE outbox_stream_key = ? AND request_id = ?",
                STREAM_PREFIX + intent.accountUuid(),
                intent.requestId())
            != null) {
      throw new IllegalStateException("Pending reset already has a source result");
    }
  }

  private boolean deleteEnvelopeIfPresent(String requestId) {
    return dsl.execute("DELETE FROM " + ENVELOPES + " WHERE request_id = ?", requestId) == 1;
  }

  private void lockAccount(long accountId, UUID accountUuid) {
    Record row =
        dsl.fetchOne(
            "SELECT id FROM accounts WHERE id = ? AND account_uuid = ? FOR UPDATE",
            accountId,
            accountUuid);
    if (row == null) {
      throw new IllegalStateException("Pending reset Account association is absent or changed");
    }
  }

  private static byte[] captureEvidence(
      Account account,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest) {
    String stream = STREAM_PREFIX + account.getAccountUuid();
    if (latest.outboxSequence() == 0L && latest.latestEvent().isPresent()) {
      throw new IllegalStateException("Account source baseline has contradictory event evidence");
    }
    if (latest.outboxSequence() > 0L && latest.latestEvent().isEmpty()) {
      throw new IllegalStateException("Account source checkpoint has no latest event");
    }
    return latest
        .latestEvent()
        .map(Event::payload)
        .orElseGet(
            () ->
                framed(
                    List.of(
                        "account-draft-source-baseline/v1",
                        account.getAccountUuid().toString(),
                        Long.toString(current.generation()),
                        Long.toString(current.sourceVersion()),
                        stream,
                        "0")));
  }

  private static byte[] requestPayload(
      String requestId,
      long accountId,
      UUID accountUuid,
      byte[] requestDigest,
      byte[] tokenHash,
      LocalDateTime expiresAt,
      byte[] verifierDigest) {
    return framed(
        List.of(
            REQUEST_SCHEMA,
            requestId,
            Long.toString(accountId),
            accountUuid.toString(),
            "1",
            requestDigest,
            tokenHash,
            expiresAt.toString(),
            verifierDigest));
  }

  private static byte[] mutationPayload(
      byte[] request,
      long generation,
      long sourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      long checkpointSequence,
      byte[] sourceEvidence,
      byte[] captureDigest) {
    return framed(
        List.of(
            MUTATION_SCHEMA,
            request,
            Long.toString(generation),
            Long.toString(sourceVersion),
            Long.toString(issuanceFence),
            Long.toString(issuanceFenceSourceVersion),
            Long.toString(checkpointSequence),
            sourceEvidence,
            captureDigest));
  }

  private static byte[] requestDigest(
      UUID accountUuid, String tokenHash, LocalDateTime expiresAt, byte[] verifierDigest) {
    return sha256(
        framed(
            List.of(
                REQUEST_DIGEST_SCHEMA,
                "PASSWORD_RESET",
                accountUuid.toString(),
                tokenHash,
                expiresAt.toString(),
                HEX.formatHex(verifierDigest))));
  }

  private static byte[] framed(List<?> fields) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (Object field : fields) {
      byte[] encoded =
          field instanceof byte[] binary
              ? binary
              : field.toString().getBytes(StandardCharsets.UTF_8);
      output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
      output.writeBytes(encoded);
    }
    return output.toByteArray();
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static byte[] checkedDigest(byte[] value, String name) {
    if (value == null || value.length != 32) {
      throw new IllegalArgumentException(name + " must be 32 bytes");
    }
    return value.clone();
  }

  private static byte[] nonEmptyBytes(byte[] value, String name) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.clone();
  }

  private static byte[] decodeDigest(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be lowercase SHA-256 hex");
    }
    return HEX.parseHex(value);
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null) {
      throw new IllegalStateException("Pending reset durable " + field + " is absent");
    }
    return value;
  }

  private static long requiredLong(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null) {
      throw new IllegalStateException("Pending reset durable " + field + " is absent");
    }
    return value;
  }

  private static void requireAccount(Account account) {
    if (account == null || account.getId() == null || account.getId() <= 0L) {
      throw new IllegalArgumentException("Persisted Account identity is required");
    }
    requireIdentity(account.getId(), account.getAccountUuid());
  }

  private static void requireIdentity(long accountId, UUID accountUuid) {
    if (accountId <= 0L || accountUuid == null || new UUID(0L, 0L).equals(accountUuid)) {
      throw new IllegalArgumentException("Canonical persisted Account identity is required");
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account owner transaction is required");
    }
  }
}
