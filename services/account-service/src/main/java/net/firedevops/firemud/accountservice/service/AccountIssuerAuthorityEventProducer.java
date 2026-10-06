package net.firedevops.firemud.accountservice.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired owner-local issuer-generation source mutation and readback.
 *
 * <p>This class is deliberately not a Spring component. Its caller must explicitly supply the
 * configured issuer and dependencies; it does not add an endpoint, activate delivery, rotate a
 * signing key, or establish authenticated consumer evidence. Each public operation owns a short
 * writable READ_COMMITTED transaction and performs no network calls.
 */
public final class AccountIssuerAuthorityEventProducer {
  private static final String AUTHORITY_STREAM_PREFIX = "account:auth-authority:v1:";
  private static final int MAX_ISSUER_ID_LENGTH = 512;
  private static final String EVENT_ID_PREFIX = "account-issuer-authority-event-v1:";

  private final String exactIssuerId;
  private final AuthorityScope issuerScope;
  private final String sourceScope;
  private final String outboxStreamKey;
  private final AccountAuthorityGenerationRepository generationRepository;
  private final AccountAuthorityOutboxRepository outboxRepository;
  private final DSLContext dsl;
  private final TransactionTemplate ownerTransaction;
  private final IssuerTenantDraftSourceChangeRepository draftSourceChanges;

  public AccountIssuerAuthorityEventProducer(
      String exactIssuerId,
      AccountAuthorityGenerationRepository generationRepository,
      AccountAuthorityOutboxRepository outboxRepository,
      DSLContext dsl,
      PlatformTransactionManager transactionManager) {
    if (exactIssuerId == null
        || exactIssuerId.isBlank()
        || exactIssuerId.length() > MAX_ISSUER_ID_LENGTH) {
      throw new IllegalArgumentException("Exact configured Account issuer ID is required");
    }
    this.exactIssuerId = exactIssuerId;
    this.issuerScope = AuthorityScope.issuer(exactIssuerId);
    this.sourceScope = "issuer/" + exactIssuerId;
    this.outboxStreamKey = AUTHORITY_STREAM_PREFIX + sourceScope;
    this.generationRepository =
        Objects.requireNonNull(generationRepository, "authority-generation repository is required");
    this.outboxRepository =
        Objects.requireNonNull(outboxRepository, "authority outbox repository is required");
    this.dsl = Objects.requireNonNull(dsl, "transaction-aware DSLContext is required");
    Objects.requireNonNull(transactionManager, "Account transaction manager is required");
    this.draftSourceChanges = new IssuerTenantDraftSourceChangeRepository(dsl);

    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Advances the exact configured issuer source or recovers its original immutable event.
   *
   * <p>The request UUID is the stable operation identity within this exact issuer. The transaction
   * first locks the persisted issuer row, then resolves the immutable request event before
   * considering the caller's expected source state. A second, genuinely post-commit locked read
   * must prove the same event before success is returned. If that read is unavailable, an exact
   * retry recovers it without applying another advance.
   */
  public IssuerGenerationAuthorityEvent advance(
      String requestedIssuerId,
      UUID requestId,
      long expectedIssuerAuthGeneration,
      long expectedSourceVersion) {
    requireExactIssuer(requestedIssuerId);
    requireRequestId(requestId);
    if (expectedIssuerAuthGeneration <= 0L || expectedSourceVersion <= 0L) {
      throw new IllegalArgumentException(
          "Expected issuer generation and source version must be positive");
    }
    requireNoAmbientTransaction();

    AdvanceResult transactionResult =
        ownerTransaction.execute(
            status ->
                advanceInOwnerTransaction(
                    requestId, expectedIssuerAuthGeneration, expectedSourceVersion));
    if (transactionResult == null) {
      throw new IllegalStateException("Issuer authority advance transaction returned no event");
    }
    if (transactionResult.pendingChange() != null) {
      ownerTransaction.executeWithoutResult(
          status ->
              draftSourceChanges.verifyWaiting(
                  SourceKind.ISSUER,
                  exactIssuerId,
                  requestId,
                  expectedIssuerAuthGeneration,
                  expectedSourceVersion,
                  transactionResult.pendingChange()));
      throw new IssuerTenantDraftSourceChangeRepository.PendingSourceChangeException(
          transactionResult.pendingChange().changeId());
    }

    IssuerGenerationAuthorityEvent committedResult =
        readCommittedOperation(requestId, expectedIssuerAuthGeneration, expectedSourceVersion);
    if (!sameEvent(transactionResult.event(), committedResult)) {
      throw new IllegalStateException(
          "Post-commit issuer event readback differs from its transaction result");
    }
    return committedResult;
  }

  /**
   * Reads current positive issuer source state and exact latest V33 checkpoint in one row-fenced
   * snapshot.
   */
  public IssuerAuthoritySnapshot readCurrent(String requestedIssuerId) {
    requireExactIssuer(requestedIssuerId);
    requireNoAmbientTransaction();
    IssuerAuthoritySnapshot snapshot =
        ownerTransaction.execute(status -> readCurrentInTransaction());
    if (snapshot == null) {
      throw new IllegalStateException("Issuer authority snapshot transaction returned no state");
    }
    return snapshot;
  }

  /**
   * Reads existing issuer state and its exact history in the caller's writable Account snapshot.
   *
   * <p>This owner-local composition boundary acquires the same issuer row fence as {@link
   * #readCurrent(String)}, but never creates a separate transaction or enrolls missing authority.
   * Callers must acquire this issuer fence before locking recipient Account source rows. The
   * returned source evidence is not recipient authorization or an issuance result.
   */
  public IssuerAuthoritySnapshot readCurrentInAccountSnapshot(String requestedIssuerId) {
    requireExactIssuer(requestedIssuerId);
    requireActiveOwnerTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Issuer snapshot access requires a writable Account transaction");
    }
    return readCurrentInTransaction();
  }

  /**
   * Reads one exact retained immutable event together with the current source and checkpoint
   * evidence that bounds it, all while holding the issuer source row lock.
   *
   * <p>The historical event is returned only as historical evidence. Callers must use {@link
   * IssuerAuthorityEventReadback#currentSnapshot()} for current source decisions.
   */
  public IssuerAuthorityEventReadback readCommittedEvent(
      String requestedIssuerId, long outboxSequence) {
    requireExactIssuer(requestedIssuerId);
    if (outboxSequence <= 0L) {
      throw new IllegalArgumentException("Issuer outbox sequence must be positive");
    }
    requireNoAmbientTransaction();

    IssuerAuthorityEventReadback readback =
        ownerTransaction.execute(status -> readCommittedEventInTransaction(outboxSequence));
    if (readback == null) {
      throw new IllegalStateException(
          "Issuer authority event readback transaction returned no state");
    }
    return readback;
  }

  /**
   * Reads the current issuer source inside the reconciliation service's already-owned transaction.
   *
   * <p>This package-private bridge preserves the same issuer-row fence while a reconciliation
   * receipt is selected or inserted. It does not expose another public read contract.
   */
  IssuerAuthoritySnapshot readCurrentForProjectionReconciliation(String requestedIssuerId) {
    requireExactIssuer(requestedIssuerId);
    requireActiveOwnerTransaction();
    return readCurrentInTransaction();
  }

  /**
   * Reads one retained positive source event under the reconciliation transaction's issuer fence.
   */
  IssuerAuthorityEventReadback readCommittedEventForProjectionReconciliation(
      String requestedIssuerId, long outboxSequence) {
    requireExactIssuer(requestedIssuerId);
    if (outboxSequence <= 0L) {
      throw new IllegalArgumentException("Issuer outbox sequence must be positive");
    }
    requireActiveOwnerTransaction();
    return readCommittedEventInTransaction(outboxSequence);
  }

  private AdvanceResult advanceInOwnerTransaction(
      UUID requestId, long expectedGeneration, long expectedSourceVersion) {
    ScopeState current = readLockedIssuerState();

    // Resolve the immutable operation result first while the issuer row is held. This is the
    // recovery path after a committed operation whose response or post-commit read was lost.
    Optional<Event> prior = outboxRepository.findEvent(outboxStreamKey, requestId.toString());
    if (prior.isPresent()) {
      IssuerGenerationAuthorityEvent historical =
          verifyStoredEvent(prior.get(), requestId, expectedGeneration, expectedSourceVersion);
      LatestEvidence currentHistory = requireCurrentHistoryMatches(current);
      requireHistoricalEventIsRetained(prior.get(), current, currentHistory);
      draftSourceChanges.verifyCommittedIfPresent(
          SourceKind.ISSUER,
          exactIssuerId,
          requestId,
          expectedGeneration,
          expectedSourceVersion,
          prior.get());
      return new AdvanceResult(historical, null);
    }

    draftSourceChanges.verifyPendingRequest(
        SourceKind.ISSUER, exactIssuerId, requestId, expectedGeneration, expectedSourceVersion);

    if (current.generation() != expectedGeneration
        || current.sourceVersion() != expectedSourceVersion) {
      throw new IllegalStateException("Issuer authority source compare-and-advance is stale");
    }

    long nextGeneration = incrementExact(expectedGeneration, "issuer generation");
    long nextSourceVersion = incrementExact(expectedSourceVersion, "issuer source version");
    LatestEvidence priorHistory = requireCurrentHistoryMatches(current);
    long nextSequence = incrementExact(priorHistory.sequence(), "issuer outbox sequence");
    SourceChange sourceChange =
        draftSourceChanges.participate(
            SourceKind.ISSUER,
            exactIssuerId,
            requestId,
            expectedGeneration,
            expectedSourceVersion,
            IssuerTenantDraftSourceChangeRepository.capture(
                SourceKind.ISSUER,
                exactIssuerId,
                current.generation(),
                current.sourceVersion(),
                outboxStreamKey,
                priorHistory.sequence(),
                priorHistory.outboxEvent()));
    if (!draftSourceChanges.permitted(sourceChange)) {
      return new AdvanceResult(null, sourceChange);
    }
    String requestText = requestId.toString();
    String eventId = EVENT_ID_PREFIX + requestText;

    ScopeState advanced = generationRepository.advance(current, null);
    requireScopeState(advanced, nextGeneration, nextSourceVersion);

    Event appended =
        outboxRepository.append(
            outboxStreamKey,
            requestText,
            sequence ->
                eventEvidence(requestText, eventId, sequence, nextGeneration, nextSourceVersion));
    if (appended.outboxSequence() != nextSequence) {
      throw new IllegalStateException(
          "Issuer authority outbox sequence did not advance exactly once");
    }
    IssuerGenerationAuthorityEvent appendedEvent =
        verifyStoredEvent(appended, requestId, expectedGeneration, expectedSourceVersion);

    // Prove the committed transaction's exact event, source row and checkpoint before allowing
    // the transaction to commit. Any inconsistency escapes and rolls all three back together.
    Event requestReadback =
        outboxRepository
            .findEvent(outboxStreamKey, requestText)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Issuer authority request event readback is missing"));
    Event sequenceReadback =
        outboxRepository
            .findEvent(outboxStreamKey, nextSequence)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Issuer authority sequence event readback is missing"));
    if (!appended.equals(requestReadback) || !appended.equals(sequenceReadback)) {
      throw new IllegalStateException(
          "Issuer authority immutable event readback differs from append");
    }
    Checkpoint checkpoint =
        outboxRepository
            .readCheckpoint(outboxStreamKey)
            .orElseThrow(
                () -> new IllegalStateException("Issuer authority checkpoint readback is missing"));
    requireCheckpointMatches(checkpoint, appended);
    ScopeState sourceReadback = readLockedIssuerState();
    requireScopeState(sourceReadback, nextGeneration, nextSourceVersion);
    LatestEvidence currentHistory = requireCurrentHistoryMatches(sourceReadback);
    if (currentHistory.sequence() != nextSequence
        || currentHistory.event().isEmpty()
        || currentHistory.outboxEvent().isEmpty()
        || !appended.equals(currentHistory.outboxEvent().orElseThrow())) {
      throw new IllegalStateException(
          "Issuer authority source and outbox readback differ from the advance");
    }
    draftSourceChanges.complete(
        SourceKind.ISSUER, exactIssuerId, requestId, sourceChange, appended);
    draftSourceChanges.verifyCommittedIfPresent(
        SourceKind.ISSUER,
        exactIssuerId,
        requestId,
        expectedGeneration,
        expectedSourceVersion,
        appended);
    return new AdvanceResult(appendedEvent, null);
  }

  private IssuerGenerationAuthorityEvent readCommittedOperation(
      UUID requestId, long expectedGeneration, long expectedSourceVersion) {
    requireNoAmbientTransaction();
    IssuerGenerationAuthorityEvent event =
        ownerTransaction.execute(
            status -> {
              ScopeState current = readLockedIssuerState();
              Event committed =
                  outboxRepository
                      .findEvent(outboxStreamKey, requestId.toString())
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Post-commit issuer authority request event is missing"));
              IssuerGenerationAuthorityEvent verified =
                  verifyStoredEvent(
                      committed, requestId, expectedGeneration, expectedSourceVersion);
              LatestEvidence currentHistory = requireCurrentHistoryMatches(current);
              requireHistoricalEventIsRetained(committed, current, currentHistory);
              draftSourceChanges.verifyCommittedIfPresent(
                  SourceKind.ISSUER,
                  exactIssuerId,
                  requestId,
                  expectedGeneration,
                  expectedSourceVersion,
                  committed);
              if (committed.outboxSequence() > currentHistory.sequence()) {
                throw new IllegalStateException(
                    "Post-commit issuer event is ahead of its checkpoint");
              }
              return verified;
            });
    if (event == null) {
      throw new IllegalStateException("Post-commit issuer authority readback returned no event");
    }
    return event;
  }

  private IssuerAuthoritySnapshot readCurrentInTransaction() {
    ScopeState current = readLockedIssuerState();
    LatestEvidence history = requireCurrentHistoryMatches(current);
    return new IssuerAuthoritySnapshot(
        exactIssuerId,
        current.generation(),
        current.sourceVersion(),
        outboxStreamKey,
        history.sequence(),
        history.event());
  }

  private IssuerAuthorityEventReadback readCommittedEventInTransaction(long outboxSequence) {
    ScopeState current = readLockedIssuerState();
    LatestEvidence currentHistory = requireCurrentHistoryMatches(current);
    if (outboxSequence > currentHistory.sequence()) {
      throw new IllegalStateException("Requested issuer event is ahead of the current checkpoint");
    }
    Event requested =
        outboxRepository
            .findEvent(outboxStreamKey, outboxSequence)
            .orElseThrow(
                () -> new IllegalStateException("Requested retained issuer event is missing"));
    IssuerGenerationAuthorityEvent verified = verifyStoredEventWithoutRequest(requested);
    requireHistoricalEventIsRetained(requested, current, currentHistory);
    if (requested.outboxSequence() != outboxSequence) {
      throw new IllegalStateException(
          "Requested issuer event sequence differs from its lookup key");
    }

    IssuerAuthoritySnapshot currentSnapshot =
        new IssuerAuthoritySnapshot(
            exactIssuerId,
            current.generation(),
            current.sourceVersion(),
            outboxStreamKey,
            currentHistory.sequence(),
            currentHistory.event());
    if (outboxSequence == currentHistory.sequence()
        && (currentHistory.event().isEmpty()
            || !sameEvent(verified, currentHistory.event().orElseThrow()))) {
      throw new IllegalStateException(
          "Requested latest issuer event differs from current source checkpoint evidence");
    }
    return new IssuerAuthorityEventReadback(currentSnapshot, verified);
  }

  private ScopeState readLockedIssuerState() {
    ScopeState state = generationRepository.read(issuerScope);
    if (state == null
        || !issuerScope.equals(state.scope())
        || state.issuanceFence() != null
        || state.generation() <= 0L
        || state.sourceVersion() <= 0L) {
      throw new IllegalStateException(
          "Persisted Account issuer authority state is absent or malformed");
    }
    return state;
  }

  private record AdvanceResult(IssuerGenerationAuthorityEvent event, SourceChange pendingChange) {}

  /**
   * Validates the exact V33 checkpoint and latest event while the source row is locked. Sequence
   * zero is returned only after counting the exact stream's events and proving the original source
   * baseline; a missing V33 stream row by itself is not absence proof.
   */
  private LatestEvidence requireCurrentHistoryMatches(ScopeState current) {
    Record stream =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_authority_outbox_streams "
                + "WHERE outbox_stream_key = ? FOR SHARE",
            outboxStreamKey);
    if (stream == null) {
      Record eventCountReadback =
          dsl.fetchOne(
              "SELECT COUNT(*) AS event_count FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
              outboxStreamKey);
      Long eventCount =
          eventCountReadback == null ? null : eventCountReadback.get("event_count", Long.class);
      if (eventCount == null || eventCount < 0L) {
        throw new IllegalStateException("Issuer authority retained event count is malformed");
      }
      if (eventCount != 0L) {
        throw new IllegalStateException(
            "Issuer authority event history has no matching stream head");
      }
      requireOriginalBaseline(current);
      return LatestEvidence.empty();
    }

    Long headValue = stream.get("last_sequence", Long.class);
    if (headValue == null || headValue < 0L) {
      throw new IllegalStateException("Issuer authority stream head is malformed");
    }
    if (headValue == 0L) {
      throw new IllegalStateException(
          "Committed zero-sequence issuer stream head contradicts the V33 checkpoint contract");
    }

    long headSequence = headValue;
    Checkpoint checkpoint =
        outboxRepository
            .readCheckpoint(outboxStreamKey)
            .orElseThrow(
                () -> new IllegalStateException("Issuer authority positive checkpoint is missing"));
    Event latest =
        outboxRepository
            .findEvent(outboxStreamKey, headSequence)
            .orElseThrow(
                () -> new IllegalStateException("Issuer authority checkpoint event is missing"));
    requireCheckpointMatches(checkpoint, latest);
    IssuerGenerationAuthorityEvent verified = verifyStoredEventWithoutRequest(latest);
    if (!Long.toString(current.generation()).equals(verified.issuerAuthGeneration())
        || !Long.toString(current.sourceVersion()).equals(verified.sourceVersion())) {
      throw new IllegalStateException(
          "Latest issuer source event differs from current source counters");
    }
    return new LatestEvidence(headSequence, Optional.of(verified), Optional.of(latest));
  }

  private void requireOriginalBaseline(ScopeState current) {
    if (current.generation() != 1L || current.sourceVersion() != 1L) {
      throw new IllegalStateException(
          "Issuer sequence-zero readback requires the original positive 1/1 source baseline");
    }
  }

  private void requireHistoricalEventIsRetained(
      Event event, ScopeState current, LatestEvidence history) {
    if (history.sequence() < event.outboxSequence()) {
      throw new IllegalStateException("Historical issuer event is ahead of the current checkpoint");
    }
    IssuerGenerationAuthorityEvent verified = verifyStoredEventWithoutRequest(event);
    if (new java.math.BigInteger(verified.issuerAuthGeneration())
                .compareTo(java.math.BigInteger.valueOf(current.generation()))
            > 0
        || new java.math.BigInteger(verified.sourceVersion())
                .compareTo(java.math.BigInteger.valueOf(current.sourceVersion()))
            > 0) {
      throw new IllegalStateException("Historical issuer event is ahead of current source state");
    }
  }

  private EventEvidence eventEvidence(
      String requestId,
      String eventId,
      long sequence,
      long issuerAuthGeneration,
      long sourceVersion) {
    IssuerGenerationAuthorityEvent event =
        IssuerGenerationAuthorityEventV1Codec.seal(
            Map.of(
                "schemaVersion",
                IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                "eventType",
                IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
                "eventId",
                eventId,
                "requestId",
                requestId,
                "issuerId",
                exactIssuerId,
                "sourceScope",
                sourceScope,
                "outboxStreamKey",
                outboxStreamKey,
                "outboxSequence",
                Long.toString(sequence),
                "issuerAuthGeneration",
                Long.toString(issuerAuthGeneration),
                "sourceVersion",
                Long.toString(sourceVersion)));
    return new EventEvidence(event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
  }

  private IssuerGenerationAuthorityEvent verifyStoredEvent(
      Event stored, UUID requestId, long expectedGeneration, long expectedSourceVersion) {
    IssuerGenerationAuthorityEvent verified = verifyStoredEventWithoutRequest(stored);
    String canonicalRequestId = requestId.toString();
    long resultingGeneration;
    long resultingSourceVersion;
    try {
      resultingGeneration = Math.addExact(expectedGeneration, 1L);
      resultingSourceVersion = Math.addExact(expectedSourceVersion, 1L);
    } catch (ArithmeticException overflow) {
      throw new AccountAuthorityOutboxRepository.IdempotencyConflictException(
          "Issuer authority request identity was reused with different expected source state");
    }
    if (!canonicalRequestId.equals(stored.requestId())
        || !canonicalRequestId.equals(verified.requestId())
        || !(EVENT_ID_PREFIX + canonicalRequestId).equals(stored.eventId())
        || !Long.toString(resultingGeneration).equals(verified.issuerAuthGeneration())
        || !Long.toString(resultingSourceVersion).equals(verified.sourceVersion())) {
      throw new AccountAuthorityOutboxRepository.IdempotencyConflictException(
          "Issuer authority request identity was reused with different expected source state");
    }
    return verified;
  }

  private IssuerGenerationAuthorityEvent verifyStoredEventWithoutRequest(Event stored) {
    if (stored == null) {
      throw new IllegalStateException("Issuer authority stored event is missing");
    }
    IssuerGenerationAuthorityEvent verified =
        IssuerGenerationAuthorityEventV1Codec.verify(
            new String(stored.payload(), StandardCharsets.UTF_8));
    if (!MessageDigest.isEqual(stored.payload(), verified.canonicalJsonUtf8())
        || !outboxStreamKey.equals(stored.outboxStreamKey())
        || !sourceScope.equals(verified.sourceScope())
        || !outboxStreamKey.equals(verified.outboxStreamKey())
        || !exactIssuerId.equals(verified.issuerId())
        || !stored.requestId().equals(verified.requestId())
        || !stored.eventId().equals(verified.eventId())
        || !stored.eventDigest().equals(verified.eventDigest())
        || !Long.toString(stored.outboxSequence()).equals(verified.outboxSequence())
        || !(EVENT_ID_PREFIX + stored.requestId()).equals(stored.eventId())) {
      throw new IllegalStateException(
          "Issuer authority stored event payload or database columns contradict");
    }
    requireCanonicalStoredRequestId(stored.requestId());
    return verified;
  }

  private void requireCanonicalStoredRequestId(String requestId) {
    try {
      UUID parsed = UUID.fromString(requestId);
      if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(requestId)) {
        throw new IllegalStateException("Issuer authority stored request ID is not canonical");
      }
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Issuer authority stored request ID is not a canonical UUID", malformed);
    }
  }

  private void requireScopeState(ScopeState state, long generation, long sourceVersion) {
    if (state == null
        || !issuerScope.equals(state.scope())
        || state.issuanceFence() != null
        || state.generation() != generation
        || state.sourceVersion() != sourceVersion) {
      throw new IllegalStateException(
          "Persisted Account issuer source did not match its expected advance");
    }
  }

  private void requireCheckpointMatches(Checkpoint checkpoint, Event event) {
    if (checkpoint == null
        || event == null
        || !outboxStreamKey.equals(checkpoint.outboxStreamKey())
        || !checkpoint.outboxStreamKey().equals(event.outboxStreamKey())
        || checkpoint.outboxSequence() != event.outboxSequence()
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException(
          "Issuer authority checkpoint differs from its immutable event");
    }
  }

  private void requireExactIssuer(String requestedIssuerId) {
    if (!exactIssuerId.equals(requestedIssuerId)) {
      throw new IssuerMismatchException();
    }
  }

  private void requireRequestId(UUID requestId) {
    if (requestId == null || new UUID(0L, 0L).equals(requestId)) {
      throw new IllegalArgumentException(
          "Issuer authority request ID must be a canonical non-nil UUID");
    }
  }

  private void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Issuer authority producer must own its Account transaction without an ambient transaction");
    }
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Issuer reconciliation source access requires an active Account owner transaction");
    }
  }

  private static long incrementExact(long value, String field) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Account " + field + " is exhausted", overflow);
    }
  }

  private static boolean sameEvent(
      IssuerGenerationAuthorityEvent first, IssuerGenerationAuthorityEvent second) {
    return first != null
        && second != null
        && first.eventId().equals(second.eventId())
        && first.requestId().equals(second.requestId())
        && first.issuerId().equals(second.issuerId())
        && first.sourceScope().equals(second.sourceScope())
        && first.outboxStreamKey().equals(second.outboxStreamKey())
        && first.outboxSequence().equals(second.outboxSequence())
        && first.issuerAuthGeneration().equals(second.issuerAuthGeneration())
        && first.sourceVersion().equals(second.sourceVersion())
        && first.eventDigest().equals(second.eventDigest())
        && MessageDigest.isEqual(first.canonicalJsonUtf8(), second.canonicalJsonUtf8());
  }

  /** A caller selected an issuer other than this producer's configured authority. */
  public static final class IssuerMismatchException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public IssuerMismatchException() {
      super("Issuer authority request must name the exact configured issuer");
    }
  }

  /** Current exact source evidence; the event is omitted when the proven checkpoint is zero. */
  public record IssuerAuthoritySnapshot(
      String issuerId,
      long issuerAuthGeneration,
      long sourceVersion,
      String outboxStreamKey,
      long outboxSequence,
      Optional<IssuerGenerationAuthorityEvent> latestEvent) {
    public IssuerAuthoritySnapshot {
      if (issuerId == null
          || issuerId.isBlank()
          || issuerAuthGeneration <= 0L
          || sourceVersion <= 0L
          || outboxStreamKey == null
          || outboxSequence < 0L) {
        throw new IllegalArgumentException("Issuer authority snapshot fields are incomplete");
      }
      String expectedSourceScope = "issuer/" + issuerId;
      String expectedStreamKey = AUTHORITY_STREAM_PREFIX + expectedSourceScope;
      if (!expectedStreamKey.equals(outboxStreamKey)) {
        throw new IllegalArgumentException(
            "Issuer authority snapshot stream does not bind its issuer");
      }
      latestEvent = Objects.requireNonNull(latestEvent, "issuer latest event optional is required");
      if ((outboxSequence == 0L) != latestEvent.isEmpty()) {
        throw new IllegalArgumentException("Issuer sequence-zero snapshot must omit its event");
      }
      if (outboxSequence == 0L && (issuerAuthGeneration != 1L || sourceVersion != 1L)) {
        throw new IllegalArgumentException(
            "Issuer sequence-zero snapshot requires the positive 1/1 baseline");
      }
      latestEvent.ifPresent(
          event -> {
            if (!issuerId.equals(event.issuerId())
                || !expectedSourceScope.equals(event.sourceScope())
                || !outboxStreamKey.equals(event.outboxStreamKey())
                || !Long.toString(outboxSequence).equals(event.outboxSequence())
                || !Long.toString(issuerAuthGeneration).equals(event.issuerAuthGeneration())
                || !Long.toString(sourceVersion).equals(event.sourceVersion())) {
              throw new IllegalArgumentException(
                  "Issuer authority snapshot event differs from its checkpoint");
            }
          });
    }
  }

  /**
   * Exact retained event evidence tied to the current issuer source/checkpoint snapshot.
   *
   * <p>This shape proves only owner-local source evidence; it does not establish source
   * authentication, transport authenticity, consumer provenance, or issuer authorization.
   */
  public record IssuerAuthorityEventReadback(
      IssuerAuthoritySnapshot currentSnapshot, IssuerGenerationAuthorityEvent requestedEvent) {
    public IssuerAuthorityEventReadback {
      currentSnapshot =
          Objects.requireNonNull(currentSnapshot, "current issuer authority snapshot is required");
      requestedEvent =
          Objects.requireNonNull(requestedEvent, "requested issuer authority event is required");
      if (currentSnapshot.outboxSequence() <= 0L
          || !currentSnapshot.issuerId().equals(requestedEvent.issuerId())
          || !("issuer/" + currentSnapshot.issuerId()).equals(requestedEvent.sourceScope())
          || !currentSnapshot.outboxStreamKey().equals(requestedEvent.outboxStreamKey())) {
        throw new IllegalArgumentException(
            "Requested issuer event does not bind a positive current issuer snapshot");
      }

      BigInteger requestedSequence = parsePositiveCounter(requestedEvent.outboxSequence());
      BigInteger currentSequence = BigInteger.valueOf(currentSnapshot.outboxSequence());
      BigInteger requestedGeneration = parsePositiveCounter(requestedEvent.issuerAuthGeneration());
      BigInteger requestedSourceVersion = parsePositiveCounter(requestedEvent.sourceVersion());
      if (requestedSequence.compareTo(currentSequence) > 0
          || requestedGeneration.compareTo(
                  BigInteger.valueOf(currentSnapshot.issuerAuthGeneration()))
              > 0
          || requestedSourceVersion.compareTo(BigInteger.valueOf(currentSnapshot.sourceVersion()))
              > 0) {
        throw new IllegalArgumentException(
            "Requested historical issuer event is ahead of the current snapshot");
      }

      if (requestedSequence.equals(currentSequence)
          && (currentSnapshot.latestEvent().isEmpty()
              || !sameEvent(requestedEvent, currentSnapshot.latestEvent().orElseThrow()))) {
        throw new IllegalArgumentException(
            "Requested latest issuer event differs from the current snapshot checkpoint");
      }
    }

    private static BigInteger parsePositiveCounter(String value) {
      if (value == null || !value.matches("[1-9][0-9]*")) {
        throw new IllegalArgumentException(
            "Issuer event counters must be canonical positive integers");
      }
      return new BigInteger(value);
    }
  }

  private record LatestEvidence(
      long sequence, Optional<IssuerGenerationAuthorityEvent> event, Optional<Event> outboxEvent) {
    private LatestEvidence {
      if (sequence < 0L) {
        throw new IllegalArgumentException("Issuer authority history sequence cannot be negative");
      }
      event = Objects.requireNonNull(event, "issuer history event optional is required");
      outboxEvent = Objects.requireNonNull(outboxEvent, "issuer outbox event optional is required");
      if ((sequence == 0L) != event.isEmpty() || event.isEmpty() != outboxEvent.isEmpty()) {
        throw new IllegalArgumentException("Issuer authority history evidence is incomplete");
      }
    }

    private static LatestEvidence empty() {
      return new LatestEvidence(0L, Optional.empty(), Optional.empty());
    }
  }
}
