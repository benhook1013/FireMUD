package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact Account logout-all request journal linked to the V57 Draft authorization fence. */
public final class AccountLogoutAllDraftSourceChangeRepository {
  private static final String TABLE = "account_logout_all_draft_source_changes";
  private static final String REQUEST_SCHEMA = "account-logout-all-draft-source-request/v1";
  private static final String MUTATION_SCHEMA = "account-logout-all-draft-source-mutation/v1";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private final DSLContext dsl;
  private final DraftAuthorizationFenceRepository fences;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The transaction-aware DSLContext is an internal persistence collaborator.")
  public AccountLogoutAllDraftSourceChangeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    this.fences = new DraftAuthorizationFenceRepository(dsl);
  }

  /** Captures one exact logout request and its already verified current Account source. */
  Participation participate(
      UUID requestId,
      int digestVersion,
      String digest,
      String tokenProfile,
      String tokenHash,
      Account account,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest) {
    requireTransaction();
    byte[] request =
        requestBytes(requestId, digestVersion, digest, tokenProfile, tokenHash, account);
    SourceEvidence evidence = capture(account, current, latest);
    Record prior = findByRequestId(requestId);
    SourceChange change;
    if (prior != null) {
      change = requireRequest(prior, request, account);
      if (!"WAITING".equals(prior.get("status", String.class))) {
        throw new IllegalStateException("Completed logout-all source request has no receipt");
      }
      if (!Arrays.equals(change.sources().getFirst().canonicalBytes(), evidence.canonicalBytes())
          || !sameState(prior, current)) {
        throw new IllegalStateException(
            "Pending logout-all source request no longer matches its original capture");
      }
    } else {
      if (findByTokenHash(tokenHash) != null) {
        throw new IllegalStateException(
            "Presented token identity is already bound to another pending logout-all request");
      }
      byte[] mutation = mutationBytes(request, current, latest, evidence.canonicalBytes());
      change = new SourceChange(UUID.randomUUID(), List.of(evidence), mutation);
    }

    boolean settled = fences.requestSourceChange(change);
    if (prior == null) {
      dsl.execute(
          "INSERT INTO "
              + TABLE
              + " (request_id, account_id, account_uuid, request_digest_version, request_digest,"
              + " token_profile, presented_token_hash, expected_generation, expected_source_version,"
              + " expected_issuance_fence, expected_issuance_fence_source_version, checkpoint_stream,"
              + " checkpoint_sequence, capture_evidence, request_payload, source_evidence, source_change_id,"
              + " source_change_request, source_change_binding, status)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING')",
          requestId,
          account.getId(),
          account.getAccountUuid(),
          digestVersion,
          decodeHex(digest),
          tokenProfile,
          decodeHex(tokenHash),
          current.generation(),
          current.sourceVersion(),
          current.issuanceFence().value(),
          current.issuanceFence().sourceVersion(),
          evidence.checkpointStream(),
          latest.outboxSequence(),
          captureEvidence(account, current, latest),
          request,
          evidence.canonicalBytes(),
          change.changeId(),
          change.mutation(),
          change.canonicalBytes());
    }
    Record stored = findByRequestId(requestId);
    SourceChange readback = requireRequest(stored, request, account);
    if (!Arrays.equals(readback.canonicalBytes(), change.canonicalBytes())) {
      throw new IllegalStateException(
          "Logout-all source request readback differs from its capture");
    }
    return new Participation(change, settled);
  }

  /** Exact caller-binding lookup used before server-derived counters on retry. */
  Optional<SourceChange> findPending(
      UUID requestId,
      int digestVersion,
      String digest,
      String tokenProfile,
      String tokenHash,
      Account account) {
    requireTransaction();
    Record row = findByRequestId(requestId);
    if (row == null) {
      Record token = findByTokenHash(tokenHash);
      if (token != null) {
        requireRequest(
            token,
            requestBytes(requestId, digestVersion, digest, tokenProfile, tokenHash, account),
            account);
      }
      return Optional.empty();
    }
    SourceChange change =
        requireRequest(
            row,
            requestBytes(requestId, digestVersion, digest, tokenProfile, tokenHash, account),
            account);
    if (!"WAITING".equals(row.get("status", String.class))) {
      throw new IllegalStateException(
          "Committed logout-all source intent has no operation receipt");
    }
    return Optional.of(change);
  }

  byte[] requestBinding(
      UUID requestId,
      int digestVersion,
      String digest,
      String tokenProfile,
      String tokenHash,
      Account account) {
    return requestBytes(requestId, digestVersion, digest, tokenProfile, tokenHash, account);
  }

  /** Read-only exact journal evidence for owner-local recovery and PostgreSQL proof. */
  public Optional<PendingIntentSnapshot> readPendingIntent(UUID requestId) {
    requireTransaction();
    Record row = findByRequestId(requestId);
    if (row == null) {
      return Optional.empty();
    }
    requireRequest(row, row.get("request_payload", byte[].class), null);
    return Optional.of(snapshot(row));
  }

  boolean permitted(SourceChange change) {
    return fences.sourceMutationPermitted(change);
  }

  /** Source counters, receipt/outbox and both journal transitions share one owner transaction. */
  void complete(UUID requestId, byte[] request, SourceChange change, Event event) {
    requireTransaction();
    fences.markSourceCommitted(change);
    int updated =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'SOURCE_COMMITTED', committed_at = CURRENT_TIMESTAMP,"
                + " event_stream = ?, event_sequence = ?, event_id = ?, event_digest = ?, event_payload = ?"
                + " WHERE request_id = ? AND source_change_id = ? AND status = 'WAITING'",
            event.outboxStreamKey(),
            event.outboxSequence(),
            event.eventId(),
            event.eventDigest(),
            event.payload(),
            requestId,
            change.changeId());
    if (updated != 1) {
      throw new IllegalStateException("Logout-all source request did not complete exactly once");
    }
    verifyCommittedIfPresent(requestId, request, event);
  }

  void verifyCommittedIfPresent(UUID requestId, byte[] request, Event event) {
    requireTransaction();
    Record row = findByRequestId(requestId);
    if (row == null) return;
    SourceChange change = requireRequest(row, request, null);
    if (!"SOURCE_COMMITTED".equals(row.get("status", String.class))
        || !"SOURCE_COMMITTED".equals(fences.readSourceChange(change).status())
        || !event.outboxStreamKey().equals(row.get("event_stream", String.class))
        || !Long.valueOf(event.outboxSequence()).equals(row.get("event_sequence", Long.class))
        || !event.eventId().equals(row.get("event_id", String.class))
        || !event.eventDigest().equals(row.get("event_digest", String.class))
        || !Arrays.equals(event.payload(), row.get("event_payload", byte[].class))) {
      throw new IllegalStateException("Logout-all source request and retained event differ");
    }
  }

  void verifyWaiting(UUID requestId, byte[] request, SourceChange change) {
    requireTransaction();
    Record row = findByRequestId(requestId);
    SourceChange stored = requireRequest(row, request, null);
    if (!Arrays.equals(stored.canonicalBytes(), change.canonicalBytes())
        || !"WAITING".equals(row.get("status", String.class))
        || !"WAITING".equals(fences.readSourceChange(stored).status())) {
      throw new IllegalStateException(
          "Logout-all pending intent readback differs from its capture");
    }
  }

  private Record findByRequestId(UUID requestId) {
    return dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id = ? FOR UPDATE", requestId);
  }

  private Record findByTokenHash(String tokenHash) {
    return dsl.fetchOne(
        "SELECT * FROM " + TABLE + " WHERE presented_token_hash = ? FOR UPDATE",
        decodeHex(tokenHash));
  }

  private SourceChange requireRequest(Record row, byte[] request, Account account) {
    if (row == null || !Arrays.equals(request, row.get("request_payload", byte[].class))) {
      throw new AccountLogoutAllAuthorityEventProducer.OperationConflictException(
          "Logout-all request, token, or Account binding conflicts with its pending intent");
    }
    if (account != null
        && (!account.getId().equals(row.get("account_id", Long.class))
            || !account.getAccountUuid().equals(row.get("account_uuid", UUID.class)))) {
      throw new AccountLogoutAllAuthorityEventProducer.OperationConflictException(
          "Logout-all Account binding conflicts with its pending intent");
    }
    SourceChange change = SourceChange.fromStored(row.get("source_change_binding", byte[].class));
    byte[] expectedMutation =
        mutationBytes(
            row.get("request_payload", byte[].class),
            row.get("expected_generation", Long.class),
            row.get("expected_source_version", Long.class),
            row.get("expected_issuance_fence", Long.class),
            row.get("expected_issuance_fence_source_version", Long.class),
            row.get("checkpoint_sequence", Long.class),
            row.get("source_evidence", byte[].class));
    if (!change.changeId().equals(row.get("source_change_id", UUID.class))
        || !Arrays.equals(change.mutation(), row.get("source_change_request", byte[].class))
        || !Arrays.equals(expectedMutation, change.mutation())
        || !Arrays.equals(
            change.sources().getFirst().canonicalBytes(), row.get("source_evidence", byte[].class))
        || !change
            .sources()
            .getFirst()
            .key()
            .equals("ACCOUNT:" + row.get("account_uuid", UUID.class))) {
      throw new IllegalStateException("Logout-all journal differs from its immutable V57 capture");
    }
    fences.readSourceChange(change);
    return change;
  }

  private PendingIntentSnapshot snapshot(Record row) {
    return new PendingIntentSnapshot(
        row.get("request_id", UUID.class),
        row.get("source_change_id", UUID.class),
        row.get("request_payload", byte[].class),
        row.get("capture_evidence", byte[].class),
        row.get("source_evidence", byte[].class),
        row.get("source_change_request", byte[].class),
        row.get("source_change_binding", byte[].class),
        row.get("status", String.class),
        row.get("expected_generation", Long.class),
        row.get("expected_source_version", Long.class),
        row.get("expected_issuance_fence", Long.class),
        row.get("expected_issuance_fence_source_version", Long.class),
        row.get("checkpoint_sequence", Long.class));
  }

  private boolean sameState(Record row, ScopeState current) {
    return current.generation() == row.get("expected_generation", Long.class)
        && current.sourceVersion() == row.get("expected_source_version", Long.class)
        && current.issuanceFence().value() == row.get("expected_issuance_fence", Long.class)
        && current.issuanceFence().sourceVersion()
            == row.get("expected_issuance_fence_source_version", Long.class);
  }

  private static SourceEvidence capture(
      Account account,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest) {
    String stream = STREAM_PREFIX + account.getAccountUuid();
    if (latest.outboxSequence() == 0L && latest.latestEvent().isPresent()) {
      throw new IllegalStateException("Account source baseline has contradictory event evidence");
    }
    if (latest.outboxSequence() > 0L && latest.latestEvent().isEmpty()) {
      throw new IllegalStateException("Account source checkpoint has no exact latest event");
    }
    byte[] evidence = captureEvidence(account, current, latest);
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        account.getAccountUuid().toString(),
        Long.toString(current.generation()),
        Long.toString(current.sourceVersion()),
        stream,
        Long.toString(latest.outboxSequence()),
        evidence);
  }

  private static byte[] captureEvidence(
      Account account,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest) {
    String stream = STREAM_PREFIX + account.getAccountUuid();
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

  private static byte[] requestBytes(
      UUID requestId,
      int digestVersion,
      String digest,
      String tokenProfile,
      String tokenHash,
      Account account) {
    return framed(
        List.of(
            REQUEST_SCHEMA,
            requestId.toString(),
            Long.toString(account.getId()),
            account.getAccountUuid().toString(),
            Integer.toString(digestVersion),
            decodeHex(digest),
            tokenProfile,
            decodeHex(tokenHash)));
  }

  private static byte[] mutationBytes(
      byte[] request,
      ScopeState current,
      AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest,
      byte[] sourceEvidence) {
    return mutationBytes(
        request,
        current.generation(),
        current.sourceVersion(),
        current.issuanceFence().value(),
        current.issuanceFence().sourceVersion(),
        latest.outboxSequence(),
        sourceEvidence);
  }

  private static byte[] mutationBytes(
      byte[] request,
      long generation,
      long sourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      long checkpointSequence,
      byte[] sourceEvidence) {
    return framed(
        List.of(
            MUTATION_SCHEMA,
            request,
            Long.toString(generation),
            Long.toString(sourceVersion),
            Long.toString(issuanceFence),
            Long.toString(issuanceFenceSourceVersion),
            Long.toString(checkpointSequence),
            sourceEvidence));
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

  private static byte[] decodeHex(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Logout-all digest binding must be lowercase SHA-256 hex");
    }
    return java.util.HexFormat.of().parseHex(value);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Writable Account transaction required for logout-all source fence");
    }
  }

  record Participation(SourceChange change, boolean settled) {}

  public record PendingIntentSnapshot(
      UUID requestId,
      UUID sourceChangeId,
      byte[] requestPayload,
      byte[] captureEvidence,
      byte[] sourceEvidence,
      byte[] sourceChangeRequest,
      byte[] sourceChangeBinding,
      String status,
      long expectedGeneration,
      long expectedSourceVersion,
      long expectedIssuanceFence,
      long expectedIssuanceFenceSourceVersion,
      long checkpointSequence) {
    public PendingIntentSnapshot {
      requestPayload = requestPayload.clone();
      captureEvidence = captureEvidence.clone();
      sourceEvidence = sourceEvidence.clone();
      sourceChangeRequest = sourceChangeRequest.clone();
      sourceChangeBinding = sourceChangeBinding.clone();
    }

    @Override
    public byte[] requestPayload() {
      return requestPayload.clone();
    }

    @Override
    public byte[] captureEvidence() {
      return captureEvidence.clone();
    }

    @Override
    public byte[] sourceEvidence() {
      return sourceEvidence.clone();
    }

    @Override
    public byte[] sourceChangeRequest() {
      return sourceChangeRequest.clone();
    }

    @Override
    public byte[] sourceChangeBinding() {
      return sourceChangeBinding.clone();
    }
  }

  /** Pending source intent is durable and carries no permission to advance Account state. */
  public static final class PendingSourceChangeException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final UUID sourceChangeId;

    public PendingSourceChangeException(UUID sourceChangeId) {
      super("Draft owner outcomes remain pending for logout-all source change " + sourceChangeId);
      this.sourceChangeId = Objects.requireNonNull(sourceChangeId);
    }

    public UUID sourceChangeId() {
      return sourceChangeId;
    }
  }
}
