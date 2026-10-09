package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable exact Account password-reset intent linked to the V76 Draft source-writer fence. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The transaction-aware DSLContext is an internal persistence collaborator.")
public final class AccountPasswordResetDraftSourceChangeRepository {
  private static final String TABLE = "account_password_reset_draft_source_changes";
  private static final String REQUEST_SCHEMA = "account-password-reset-draft-source-request/v1";
  private static final String MUTATION_SCHEMA = "account-password-reset-draft-source-mutation/v1";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String REQUEST_ID_PREFIX = "account-password-reset-request-v1:";
  private static final String EVENT_ID_PREFIX = "account-password-reset-event-v1:";
  private static final HexFormat HEX = HexFormat.of();

  private final DSLContext dsl;
  private final DraftAuthorizationFenceRepository fences;

  public AccountPasswordResetDraftSourceChangeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    this.fences = new DraftAuthorizationFenceRepository(dsl);
  }

  /** Locks and verifies the original immutable request before a retry derives new source state. */
  public Optional<Long> findAccountIdByTokenHash(String tokenHash) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT account_id FROM " + TABLE + " WHERE token_hash = ?", decodeHex(tokenHash));
    return Optional.ofNullable(row == null ? null : row.get("account_id", Long.class));
  }

  public Optional<Intent> findByTokenHash(String tokenHash) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + TABLE + " WHERE token_hash = ? FOR UPDATE", decodeHex(tokenHash));
    return Optional.ofNullable(row == null ? null : requireIntent(row));
  }

  /**
   * Persists the exact original capture before returning a waiting result. An existing intent is
   * always replayed from its stored source change; a retry never replaces the original capture.
   */
  public Intent participate(
      Account account,
      long tokenId,
      String tokenHash,
      LocalDateTime tokenExpiresAt,
      String requestDigest,
      String passwordVerifier,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest) {
    requireTransaction();
    requireAccount(account);
    requireDigest(tokenHash, "token hash");
    requireDigest(requestDigest, "request digest");
    if (tokenId <= 0L
        || tokenExpiresAt == null
        || passwordVerifier == null
        || passwordVerifier.isBlank()
        || passwordVerifier.length() > 255) {
      throw new IllegalArgumentException("Exact password-reset request evidence is required");
    }

    String verifierDigest = sha256Hex(passwordVerifier);
    byte[] request =
        requestBytes(account, tokenId, tokenHash, tokenExpiresAt, requestDigest, verifierDigest);
    Record prior = findRow(tokenHash);
    if (prior != null) {
      Intent intent = requireIntent(prior);
      requireSameRequest(intent, request, account);
      if (!"WAITING".equals(intent.status())) {
        throw new IllegalStateException("Terminal password-reset source intent has no receipt");
      }
      fences.requestSourceChange(intent.sourceChange());
      return requireIntent(findRow(tokenHash));
    }

    SourceEvidence source = capture(account, current, latest);
    String requestId = REQUEST_ID_PREFIX + tokenHash;
    String eventId = EVENT_ID_PREFIX + tokenHash;
    long nextGeneration = Math.addExact(current.generation(), 1L);
    long nextSourceVersion = Math.addExact(current.sourceVersion(), 1L);
    long nextFence = Math.addExact(current.issuanceFence().value(), 1L);
    long nextFenceSourceVersion = Math.addExact(current.issuanceFence().sourceVersion(), 1L);
    long nextSequence = Math.addExact(latest.outboxSequence(), 1L);
    byte[] mutation =
        mutationBytes(
            request,
            current.generation(),
            current.sourceVersion(),
            current.issuanceFence().value(),
            current.issuanceFence().sourceVersion(),
            latest.outboxSequence(),
            source.canonicalBytes(),
            nextGeneration,
            nextSourceVersion,
            nextFence,
            nextFenceSourceVersion,
            nextSequence,
            requestId,
            eventId);
    SourceChange change = new SourceChange(UUID.randomUUID(), List.of(source), mutation);
    fences.requestSourceChange(change);

    dsl.execute(
        "INSERT INTO "
            + TABLE
            + " (token_hash, account_id, account_uuid, account_uuid_provenance, "
            + "account_uuid_source_numeric_id, token_id, request_id, event_id, "
            + "request_digest_version, request_digest, password_verifier, password_verifier_digest, "
            + "token_expires_at, token_expires_epoch_nanos, expected_generation, expected_source_version, "
            + "expected_issuance_fence, expected_issuance_fence_source_version, checkpoint_stream, "
            + "checkpoint_sequence, capture_evidence, request_payload, source_evidence, source_change_id, "
            + "source_change_request, source_change_binding, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING')",
        decodeHex(tokenHash),
        account.getId(),
        account.getAccountUuid(),
        account.getAccountUuidProvenance().name(),
        account.getAccountUuidSourceNumericId(),
        tokenId,
        requestId,
        eventId,
        decodeHex(requestDigest),
        passwordVerifier,
        decodeHex(verifierDigest),
        tokenExpiresAt,
        epochNanos(tokenExpiresAt),
        current.generation(),
        current.sourceVersion(),
        current.issuanceFence().value(),
        current.issuanceFence().sourceVersion(),
        source.checkpointStream(),
        latest.outboxSequence(),
        captureEvidence(account, current, latest),
        request,
        source.canonicalBytes(),
        change.changeId(),
        mutation,
        change.canonicalBytes());

    Intent stored = requireIntent(findRow(tokenHash));
    if (!Arrays.equals(stored.requestPayload(), request)
        || !Arrays.equals(stored.sourceChange().canonicalBytes(), change.canonicalBytes())) {
      throw new IllegalStateException("Password-reset Draft intent readback differs from capture");
    }
    return stored;
  }

  /** Currentness check is separate from immutable-intent recovery and grants no mutation right. */
  public boolean matchesCapture(
      Intent intent,
      Account account,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest) {
    requireTransaction();
    if (intent.accountId() != account.getId()
        || !intent.accountUuid().equals(account.getAccountUuid())
        || current.generation() != intent.expectedGeneration()
        || current.sourceVersion() != intent.expectedSourceVersion()
        || current.issuanceFence().value() != intent.expectedIssuanceFence()
        || current.issuanceFence().sourceVersion() != intent.expectedIssuanceFenceSourceVersion()
        || latest.outboxSequence() != intent.checkpointSequence()) {
      return false;
    }
    byte[] currentCapture = captureEvidence(account, current, latest);
    return Arrays.equals(currentCapture, intent.captureEvidence());
  }

  public boolean sourceMutationPermitted(Intent intent) {
    requireTransaction();
    return fences.sourceMutationPermitted(intent.sourceChange());
  }

  /** V76 transition and immutable operation receipt/event are committed in the same transaction. */
  public void markSourceCommitted(Intent intent, Event event) {
    requireTransaction();
    fences.markSourceCommitted(intent.sourceChange());
    int updated =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'SOURCE_COMMITTED', event_stream = ?, event_sequence = ?, "
                + "event_id = ?, event_digest = ?, event_payload = ? "
                + "WHERE token_hash = ? AND source_change_id = ? AND status = 'WAITING'",
            event.outboxStreamKey(),
            event.outboxSequence(),
            event.eventId(),
            event.eventDigest(),
            event.payload(),
            decodeHex(intent.tokenHash()),
            intent.sourceChangeId());
    if (updated != 1) {
      throw new IllegalStateException(
          "Password-reset Draft source intent did not commit exactly once");
    }
    Intent readback = requireIntent(findRow(intent.tokenHash()));
    if (!"SOURCE_COMMITTED".equals(readback.status())
        || !"SOURCE_COMMITTED".equals(fences.readSourceChange(readback.sourceChange()).status())
        || !event.equals(readback.event())) {
      throw new IllegalStateException("Password-reset Draft source commit readback differs");
    }
  }

  /** A definitive no-mutation result closes both durable records only after V76 permits it. */
  public void markSourceAborted(Intent intent, SourceChangeAbortReason reason) {
    requireTransaction();
    fences.markSourceAborted(intent.sourceChange(), reason);
    int updated =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'SOURCE_ABORTED', abort_reason = ? "
                + "WHERE token_hash = ? AND source_change_id = ? AND status = 'WAITING'",
            reason.name(),
            decodeHex(intent.tokenHash()),
            intent.sourceChangeId());
    if (updated != 1) {
      throw new IllegalStateException(
          "Password-reset Draft source intent did not abort exactly once");
    }
    Intent readback = requireIntent(findRow(intent.tokenHash()));
    if (!"SOURCE_ABORTED".equals(readback.status())
        || !"SOURCE_ABORTED".equals(fences.readSourceChange(readback.sourceChange()).status())) {
      throw new IllegalStateException("Password-reset Draft source abort readback differs");
    }
  }

  public void verifyWaiting(Intent intent) {
    requireTransaction();
    Intent readback = requireIntent(findRow(intent.tokenHash()));
    if (!"WAITING".equals(readback.status())
        || !"WAITING".equals(fences.readSourceChange(readback.sourceChange()).status())
        || !Arrays.equals(readback.requestPayload(), intent.requestPayload())) {
      throw new IllegalStateException("Password-reset waiting source intent readback differs");
    }
  }

  private Record findRow(String tokenHash) {
    return dsl.fetchOne(
        "SELECT * FROM " + TABLE + " WHERE token_hash = ? FOR UPDATE", decodeHex(tokenHash));
  }

  private Intent requireIntent(Record row) {
    if (row == null) {
      throw new IllegalStateException("Password-reset Draft source intent is missing");
    }
    Intent intent = toIntent(row);
    byte[] expectedRequest =
        requestBytes(
            intent.account(),
            intent.tokenId(),
            intent.tokenHash(),
            intent.tokenExpiresAt(),
            intent.requestDigest(),
            intent.passwordVerifierDigest());
    byte[] expectedMutation =
        mutationBytes(
            expectedRequest,
            intent.expectedGeneration(),
            intent.expectedSourceVersion(),
            intent.expectedIssuanceFence(),
            intent.expectedIssuanceFenceSourceVersion(),
            intent.checkpointSequence(),
            intent.sourceEvidence(),
            Math.addExact(intent.expectedGeneration(), 1L),
            Math.addExact(intent.expectedSourceVersion(), 1L),
            Math.addExact(intent.expectedIssuanceFence(), 1L),
            Math.addExact(intent.expectedIssuanceFenceSourceVersion(), 1L),
            Math.addExact(intent.checkpointSequence(), 1L),
            intent.requestId(),
            intent.eventId());
    if (intent.requestDigestVersion() != 1
        || !MessageDigest.isEqual(expectedRequest, intent.requestPayload())
        || !MessageDigest.isEqual(expectedMutation, intent.sourceChange().mutation())
        || !Arrays.equals(intent.sourceChange().canonicalBytes(), intent.sourceChangeBinding())
        || !Arrays.equals(
            intent.sourceChange().sources().getFirst().canonicalBytes(), intent.sourceEvidence())
        || !intent
            .sourceChange()
            .sources()
            .getFirst()
            .key()
            .equals("ACCOUNT:" + intent.accountUuid())
        || !sha256Hex(intent.passwordVerifier()).equals(intent.passwordVerifierDigest())
        || !intent.requestId().equals(REQUEST_ID_PREFIX + intent.tokenHash())
        || !intent.eventId().equals(EVENT_ID_PREFIX + intent.tokenHash())) {
      throw new IllegalStateException("Stored password-reset Draft intent is inconsistent");
    }
    fences.readSourceChange(intent.sourceChange());
    return intent;
  }

  private Intent toIntent(Record row) {
    UUID accountUuid = Objects.requireNonNull(row.get("account_uuid", UUID.class));
    Account account = new Account();
    account.setId(row.get("account_id", Long.class));
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(
        net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance.fromStorageValue(
            row.get("account_uuid_provenance", String.class)));
    account.setAccountUuidSourceNumericId(row.get("account_uuid_source_numeric_id", Long.class));
    String tokenHash = encodeHex(row.get("token_hash", byte[].class));
    var event =
        row.get("event_sequence", Long.class) == null
            ? null
            : new Event(
                row.get("event_stream", String.class),
                row.get("request_id", String.class),
                row.get("event_sequence", Long.class),
                row.get("event_id", String.class),
                row.get("event_digest", String.class),
                row.get("event_payload", byte[].class));
    return new Intent(
        account,
        row.get("token_id", Long.class),
        tokenHash,
        row.get("request_id", String.class),
        row.get("event_id", String.class),
        row.get("request_digest_version", Integer.class),
        encodeHex(row.get("request_digest", byte[].class)),
        row.get("password_verifier", String.class),
        encodeHex(row.get("password_verifier_digest", byte[].class)),
        row.get("token_expires_at", LocalDateTime.class),
        row.get("expected_generation", Long.class),
        row.get("expected_source_version", Long.class),
        row.get("expected_issuance_fence", Long.class),
        row.get("expected_issuance_fence_source_version", Long.class),
        row.get("checkpoint_stream", String.class),
        row.get("checkpoint_sequence", Long.class),
        row.get("capture_evidence", byte[].class),
        row.get("request_payload", byte[].class),
        row.get("source_evidence", byte[].class),
        row.get("source_change_id", UUID.class),
        row.get("source_change_request", byte[].class),
        row.get("source_change_binding", byte[].class),
        row.get("status", String.class),
        row.get("abort_reason", String.class),
        event,
        SourceChange.fromStored(row.get("source_change_binding", byte[].class)));
  }

  private static SourceEvidence capture(
      Account account,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest) {
    String stream = STREAM_PREFIX + account.getAccountUuid();
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        account.getAccountUuid().toString(),
        Long.toString(current.generation()),
        Long.toString(current.sourceVersion()),
        stream,
        Long.toString(latest.outboxSequence()),
        captureEvidence(account, current, latest));
  }

  private static byte[] captureEvidence(
      Account account,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest) {
    String stream = STREAM_PREFIX + account.getAccountUuid();
    if (latest.outboxSequence() == 0L) {
      if (latest.latestEvent().isPresent()
          || current.generation() != 1L
          || current.sourceVersion() != 1L) {
        throw new IllegalStateException("Password-reset source sequence zero is not pristine");
      }
      return framed(
          List.of(
              "account-draft-source-baseline/v1",
              account.getAccountUuid().toString(),
              "1",
              "1",
              stream,
              "0"));
    }
    Event latestEvent = latest.latestEvent().orElseThrow();
    if (latestEvent.outboxSequence() != latest.outboxSequence()
        || !stream.equals(latestEvent.outboxStreamKey())) {
      throw new IllegalStateException("Password-reset source checkpoint readback differs");
    }
    return latestEvent.payload();
  }

  private static byte[] requestBytes(
      Account account,
      long tokenId,
      String tokenHash,
      LocalDateTime tokenExpiresAt,
      String requestDigest,
      String verifierDigest) {
    return framed(
        List.of(
            REQUEST_SCHEMA,
            REQUEST_ID_PREFIX + tokenHash,
            Long.toString(account.getId()),
            account.getAccountUuid().toString(),
            Long.toString(tokenId),
            tokenHashBytes(tokenHash),
            Long.toString(epochNanos(tokenExpiresAt)),
            "1",
            decodeHex(requestDigest),
            decodeHex(verifierDigest),
            account.getAccountUuidProvenance().name(),
            Long.toString(account.getAccountUuidSourceNumericId())));
  }

  private static byte[] mutationBytes(
      byte[] request,
      long generation,
      long sourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      long checkpointSequence,
      byte[] sourceEvidence,
      long nextGeneration,
      long nextSourceVersion,
      long nextFence,
      long nextFenceSourceVersion,
      long nextSequence,
      String requestId,
      String eventId) {
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
            Long.toString(nextGeneration),
            Long.toString(nextSourceVersion),
            Long.toString(nextFence),
            Long.toString(nextFenceSourceVersion),
            Long.toString(nextSequence),
            requestId,
            eventId));
  }

  private static byte[] framed(List<?> fields) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (Object field : fields) {
      byte[] bytes =
          field instanceof byte[] binary
              ? binary
              : field.toString().getBytes(StandardCharsets.UTF_8);
      output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      output.writeBytes(bytes);
    }
    return output.toByteArray();
  }

  private static void requireSameRequest(Intent intent, byte[] request, Account account) {
    if (intent.accountId() != account.getId()
        || !intent.accountUuid().equals(account.getAccountUuid())
        || !Arrays.equals(intent.requestPayload(), request)) {
      throw new IllegalStateException(
          "Password-reset token is already bound to a different Account or reset request");
    }
  }

  private static void requireAccount(Account account) {
    if (account == null
        || account.getId() == null
        || account.getId() <= 0L
        || account.getAccountUuid() == null
        || account.getAccountUuidProvenance() == null
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())) {
      throw new IllegalArgumentException("Persisted Account identity is required");
    }
  }

  private static void requireDigest(String digest, String field) {
    if (digest == null || !digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Password-reset " + field + " must be lowercase SHA-256");
    }
  }

  private static byte[] tokenHashBytes(String value) {
    requireDigest(value, "token hash");
    return decodeHex(value);
  }

  private static byte[] decodeHex(String value) {
    requireDigest(value, "digest");
    return HEX.parseHex(value);
  }

  private static String encodeHex(byte[] value) {
    if (value == null || value.length != 32) {
      throw new IllegalStateException("Password-reset source digest is missing or malformed");
    }
    return HEX.formatHex(value);
  }

  private static String sha256Hex(String value) {
    try {
      return HEX.formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static long epochNanos(LocalDateTime value) {
    Objects.requireNonNull(value, "token deadline");
    return Math.addExact(
        Math.multiplyExact(value.toEpochSecond(ZoneOffset.UTC), 1_000_000_000L), value.getNano());
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Writable Account transaction required for password-reset source fencing");
    }
  }

  /** In-memory recovery value; its password verifier is deliberately redacted from diagnostics. */
  public static final class Intent {
    private final Account account;
    private final long tokenId;
    private final String tokenHash;
    private final String requestId;
    private final String eventId;
    private final int requestDigestVersion;
    private final String requestDigest;
    private final String passwordVerifier;
    private final String passwordVerifierDigest;
    private final LocalDateTime tokenExpiresAt;
    private final long expectedGeneration;
    private final long expectedSourceVersion;
    private final long expectedIssuanceFence;
    private final long expectedIssuanceFenceSourceVersion;
    private final String checkpointStream;
    private final long checkpointSequence;
    private final byte[] captureEvidence;
    private final byte[] requestPayload;
    private final byte[] sourceEvidence;
    private final UUID sourceChangeId;
    private final byte[] sourceChangeRequest;
    private final byte[] sourceChangeBinding;
    private final String status;
    private final String abortReason;
    private final Event event;
    private final SourceChange sourceChange;

    private Intent(
        Account account,
        long tokenId,
        String tokenHash,
        String requestId,
        String eventId,
        int requestDigestVersion,
        String requestDigest,
        String passwordVerifier,
        String passwordVerifierDigest,
        LocalDateTime tokenExpiresAt,
        long expectedGeneration,
        long expectedSourceVersion,
        long expectedIssuanceFence,
        long expectedIssuanceFenceSourceVersion,
        String checkpointStream,
        long checkpointSequence,
        byte[] captureEvidence,
        byte[] requestPayload,
        byte[] sourceEvidence,
        UUID sourceChangeId,
        byte[] sourceChangeRequest,
        byte[] sourceChangeBinding,
        String status,
        String abortReason,
        Event event,
        SourceChange sourceChange) {
      this.account = account;
      this.tokenId = tokenId;
      this.tokenHash = tokenHash;
      this.requestId = requestId;
      this.eventId = eventId;
      this.requestDigestVersion = requestDigestVersion;
      this.requestDigest = requestDigest;
      this.passwordVerifier = passwordVerifier;
      this.passwordVerifierDigest = passwordVerifierDigest;
      this.tokenExpiresAt = tokenExpiresAt;
      this.expectedGeneration = expectedGeneration;
      this.expectedSourceVersion = expectedSourceVersion;
      this.expectedIssuanceFence = expectedIssuanceFence;
      this.expectedIssuanceFenceSourceVersion = expectedIssuanceFenceSourceVersion;
      this.checkpointStream = checkpointStream;
      this.checkpointSequence = checkpointSequence;
      this.captureEvidence = captureEvidence.clone();
      this.requestPayload = requestPayload.clone();
      this.sourceEvidence = sourceEvidence.clone();
      this.sourceChangeId = sourceChangeId;
      this.sourceChangeRequest = sourceChangeRequest.clone();
      this.sourceChangeBinding = sourceChangeBinding.clone();
      this.status = status;
      this.abortReason = abortReason;
      this.event = event;
      this.sourceChange = sourceChange;
    }

    public long accountId() {
      return account.getId();
    }

    public UUID accountUuid() {
      return account.getAccountUuid();
    }

    Account account() {
      return account;
    }

    public long tokenId() {
      return tokenId;
    }

    public String tokenHash() {
      return tokenHash;
    }

    public String requestId() {
      return requestId;
    }

    public String eventId() {
      return eventId;
    }

    int requestDigestVersion() {
      return requestDigestVersion;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public String passwordVerifier() {
      return passwordVerifier;
    }

    public String passwordVerifierDigest() {
      return passwordVerifierDigest;
    }

    public LocalDateTime tokenExpiresAt() {
      return tokenExpiresAt;
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

    public String checkpointStream() {
      return checkpointStream;
    }

    public long checkpointSequence() {
      return checkpointSequence;
    }

    byte[] captureEvidence() {
      return captureEvidence.clone();
    }

    byte[] requestPayload() {
      return requestPayload.clone();
    }

    byte[] sourceEvidence() {
      return sourceEvidence.clone();
    }

    public UUID sourceChangeId() {
      return sourceChangeId;
    }

    byte[] sourceChangeRequest() {
      return sourceChangeRequest.clone();
    }

    byte[] sourceChangeBinding() {
      return sourceChangeBinding.clone();
    }

    public String status() {
      return status;
    }

    String abortReason() {
      return abortReason;
    }

    public Event event() {
      return event;
    }

    SourceChange sourceChange() {
      return sourceChange;
    }

    @Override
    public String toString() {
      return "PasswordResetDraftIntent[accountId="
          + accountId()
          + ", tokenHash=<redacted>, "
          + "passwordVerifier=<redacted>, status="
          + status
          + "]";
    }
  }
}
