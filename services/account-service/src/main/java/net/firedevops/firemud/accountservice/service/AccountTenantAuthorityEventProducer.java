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
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired owner-local tenant-generation source mutation and readback.
 *
 * <p>This source-only primitive selects one canonical tenant UUID and requires its authority row to
 * have already been enrolled by the authoritative tenant-association path. A caller-supplied tenant
 * ID or authorization claim is not proof of enrollment or permission. This class does not
 * initialize tenant authority, mutate billing state, advance an Account issuance fence, fan out to
 * recipient Accounts, expose an endpoint, activate delivery, or establish authenticated consumer
 * evidence. Each public operation owns a short writable READ_COMMITTED transaction and performs no
 * network calls.
 */
public final class AccountTenantAuthorityEventProducer {
  private static final String AUTHORITY_STREAM_PREFIX = "account:auth-authority:v1:";
  private static final String EVENT_ID_PREFIX = "account-tenant-generation-event-v1:";

  private final AccountAuthorityGenerationRepository generationRepository;
  private final AccountAuthorityOutboxRepository outboxRepository;
  private final DSLContext dsl;
  private final TransactionTemplate ownerTransaction;
  private final IssuerTenantDraftSourceChangeRepository draftSourceChanges;

  public AccountTenantAuthorityEventProducer(
      AccountAuthorityGenerationRepository generationRepository,
      AccountAuthorityOutboxRepository outboxRepository,
      DSLContext dsl,
      PlatformTransactionManager transactionManager) {
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
   * Advances the exact already-enrolled tenant source or recovers its original immutable event.
   *
   * <p>The request UUID is the stable operation identity within this exact tenant. The transaction
   * locks the durable tenant source row before resolving request history. A separate post-commit
   * locked read must prove the same event; if that read is unavailable, an exact retry recovers it
   * without applying another advance.
   */
  public TenantGenerationAuthorityEvent advance(
      UUID tenantId, UUID requestId, long expectedGeneration, long expectedSourceVersion) {
    AuthorityScope scope = tenantScope(tenantId);
    requireRequestId(requestId);
    if (expectedGeneration <= 0L || expectedSourceVersion <= 0L) {
      throw new IllegalArgumentException(
          "Expected tenant generation and source version must be positive");
    }
    requireNoAmbientTransaction();

    AdvanceResult transactionResult =
        ownerTransaction.execute(
            status ->
                advanceInOwnerTransaction(
                    scope, requestId, expectedGeneration, expectedSourceVersion));
    if (transactionResult == null) {
      throw new IllegalStateException("Tenant authority advance transaction returned no event");
    }
    if (transactionResult.pendingChange() != null) {
      ownerTransaction.executeWithoutResult(
          status ->
              draftSourceChanges.verifyWaiting(
                  SourceKind.TENANT,
                  scope.tenantId().toString(),
                  requestId,
                  expectedGeneration,
                  expectedSourceVersion,
                  transactionResult.pendingChange()));
      throw new IssuerTenantDraftSourceChangeRepository.PendingSourceChangeException(
          transactionResult.pendingChange().changeId());
    }

    TenantGenerationAuthorityEvent committedResult =
        readCommittedOperation(scope, requestId, expectedGeneration, expectedSourceVersion);
    if (!sameEvent(transactionResult.event(), committedResult)) {
      throw new IllegalStateException(
          "Post-commit tenant event readback differs from its transaction result");
    }
    return committedResult;
  }

  /**
   * Reads current positive tenant source state and the exact latest checkpoint in one row-fenced
   * snapshot.
   */
  public TenantAuthoritySnapshot readCurrent(UUID tenantId) {
    AuthorityScope scope = tenantScope(tenantId);
    requireNoAmbientTransaction();
    TenantAuthoritySnapshot snapshot =
        ownerTransaction.execute(status -> readCurrentInTransaction(scope));
    if (snapshot == null) {
      throw new IllegalStateException("Tenant authority snapshot transaction returned no state");
    }
    return snapshot;
  }

  /**
   * Reads one exact retained immutable event together with current tenant source/checkpoint
   * evidence, all while holding the tenant source row lock.
   */
  public TenantAuthorityEventReadback readCommittedEvent(UUID tenantId, long outboxSequence) {
    AuthorityScope scope = tenantScope(tenantId);
    if (outboxSequence <= 0L) {
      throw new IllegalArgumentException("Tenant outbox sequence must be positive");
    }
    requireNoAmbientTransaction();

    TenantAuthorityEventReadback readback =
        ownerTransaction.execute(status -> readCommittedEventInTransaction(scope, outboxSequence));
    if (readback == null) {
      throw new IllegalStateException(
          "Tenant authority event readback transaction returned no state");
    }
    return readback;
  }

  private AdvanceResult advanceInOwnerTransaction(
      AuthorityScope scope, UUID requestId, long expectedGeneration, long expectedSourceVersion) {
    ScopeState current = readLockedTenantState(scope);
    String streamKey = streamKey(scope);

    // Resolve an immutable request first so lost acknowledgements and later source advances return
    // the original operation event rather than applying another increment.
    Optional<Event> prior = outboxRepository.findEvent(streamKey, requestId.toString());
    if (prior.isPresent()) {
      TenantGenerationAuthorityEvent historical =
          verifyStoredEvent(
              prior.get(), scope, requestId, expectedGeneration, expectedSourceVersion);
      LatestEvidence currentHistory = requireCurrentHistoryMatches(scope, current);
      requireHistoricalEventIsRetained(prior.get(), scope, current, currentHistory);
      draftSourceChanges.verifyCommittedIfPresent(
          SourceKind.TENANT,
          scope.tenantId().toString(),
          requestId,
          expectedGeneration,
          expectedSourceVersion,
          prior.get());
      return new AdvanceResult(historical, null);
    }

    draftSourceChanges.verifyPendingRequest(
        SourceKind.TENANT,
        scope.tenantId().toString(),
        requestId,
        expectedGeneration,
        expectedSourceVersion);

    if (current.generation() != expectedGeneration
        || current.sourceVersion() != expectedSourceVersion) {
      throw new IllegalStateException("Tenant authority source compare-and-advance is stale");
    }

    long nextGeneration = incrementExact(expectedGeneration, "tenant generation");
    long nextSourceVersion = incrementExact(expectedSourceVersion, "tenant source version");
    LatestEvidence priorHistory = requireCurrentHistoryMatches(scope, current);
    long nextSequence = incrementExact(priorHistory.sequence(), "tenant outbox sequence");
    SourceChange sourceChange =
        draftSourceChanges.participate(
            SourceKind.TENANT,
            scope.tenantId().toString(),
            requestId,
            expectedGeneration,
            expectedSourceVersion,
            IssuerTenantDraftSourceChangeRepository.capture(
                SourceKind.TENANT,
                scope.tenantId().toString(),
                current.generation(),
                current.sourceVersion(),
                streamKey,
                priorHistory.sequence(),
                priorHistory.outboxEvent()));
    if (!draftSourceChanges.permitted(sourceChange)) {
      return new AdvanceResult(null, sourceChange);
    }
    String requestText = requestId.toString();
    String eventId = EVENT_ID_PREFIX + requestText;

    ScopeState advanced = generationRepository.advance(current, null);
    requireScopeState(advanced, scope, nextGeneration, nextSourceVersion);

    Event appended =
        outboxRepository.append(
            streamKey,
            requestText,
            sequence ->
                eventEvidence(
                    scope, requestText, eventId, sequence, nextGeneration, nextSourceVersion));
    if (appended.outboxSequence() != nextSequence) {
      throw new IllegalStateException(
          "Tenant authority outbox sequence did not advance exactly once");
    }
    TenantGenerationAuthorityEvent appendedEvent =
        verifyStoredEvent(appended, scope, requestId, expectedGeneration, expectedSourceVersion);

    // Exact in-transaction event, checkpoint, and current-source readback make any discrepancy
    // abort the source advance and immutable outbox append together.
    Event requestReadback =
        outboxRepository
            .findEvent(streamKey, requestText)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Tenant authority request event readback is missing"));
    Event sequenceReadback =
        outboxRepository
            .findEvent(streamKey, nextSequence)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Tenant authority sequence event readback is missing"));
    if (!appended.equals(requestReadback) || !appended.equals(sequenceReadback)) {
      throw new IllegalStateException(
          "Tenant authority immutable event readback differs from append");
    }
    Checkpoint checkpoint =
        outboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () -> new IllegalStateException("Tenant authority checkpoint readback is missing"));
    requireCheckpointMatches(checkpoint, appended, streamKey);
    ScopeState sourceReadback = readLockedTenantState(scope);
    requireScopeState(sourceReadback, scope, nextGeneration, nextSourceVersion);
    LatestEvidence currentHistory = requireCurrentHistoryMatches(scope, sourceReadback);
    if (currentHistory.sequence() != nextSequence
        || currentHistory.event().isEmpty()
        || currentHistory.outboxEvent().isEmpty()
        || !appended.equals(currentHistory.outboxEvent().orElseThrow())) {
      throw new IllegalStateException(
          "Tenant authority source and outbox readback differ from the advance");
    }
    draftSourceChanges.complete(
        SourceKind.TENANT, scope.tenantId().toString(), requestId, sourceChange, appended);
    draftSourceChanges.verifyCommittedIfPresent(
        SourceKind.TENANT,
        scope.tenantId().toString(),
        requestId,
        expectedGeneration,
        expectedSourceVersion,
        appended);
    return new AdvanceResult(appendedEvent, null);
  }

  private TenantGenerationAuthorityEvent readCommittedOperation(
      AuthorityScope scope, UUID requestId, long expectedGeneration, long expectedSourceVersion) {
    requireNoAmbientTransaction();
    TenantGenerationAuthorityEvent event =
        ownerTransaction.execute(
            status -> {
              ScopeState current = readLockedTenantState(scope);
              String streamKey = streamKey(scope);
              Event committed =
                  outboxRepository
                      .findEvent(streamKey, requestId.toString())
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Post-commit tenant authority request event is missing"));
              TenantGenerationAuthorityEvent verified =
                  verifyStoredEvent(
                      committed, scope, requestId, expectedGeneration, expectedSourceVersion);
              LatestEvidence currentHistory = requireCurrentHistoryMatches(scope, current);
              requireHistoricalEventIsRetained(committed, scope, current, currentHistory);
              draftSourceChanges.verifyCommittedIfPresent(
                  SourceKind.TENANT,
                  scope.tenantId().toString(),
                  requestId,
                  expectedGeneration,
                  expectedSourceVersion,
                  committed);
              if (committed.outboxSequence() > currentHistory.sequence()) {
                throw new IllegalStateException(
                    "Post-commit tenant event is ahead of its checkpoint");
              }
              return verified;
            });
    if (event == null) {
      throw new IllegalStateException("Post-commit tenant authority readback returned no event");
    }
    return event;
  }

  private TenantAuthoritySnapshot readCurrentInTransaction(AuthorityScope scope) {
    ScopeState current = readLockedTenantState(scope);
    LatestEvidence history = requireCurrentHistoryMatches(scope, current);
    return new TenantAuthoritySnapshot(
        scope.tenantId(),
        current.generation(),
        current.sourceVersion(),
        streamKey(scope),
        history.sequence(),
        history.event());
  }

  private TenantAuthorityEventReadback readCommittedEventInTransaction(
      AuthorityScope scope, long outboxSequence) {
    ScopeState current = readLockedTenantState(scope);
    LatestEvidence currentHistory = requireCurrentHistoryMatches(scope, current);
    if (outboxSequence > currentHistory.sequence()) {
      throw new IllegalStateException("Requested tenant event is ahead of the current checkpoint");
    }
    String streamKey = streamKey(scope);
    Event requested =
        outboxRepository
            .findEvent(streamKey, outboxSequence)
            .orElseThrow(
                () -> new IllegalStateException("Requested retained tenant event is missing"));
    TenantGenerationAuthorityEvent verified = verifyStoredEventWithoutRequest(requested, scope);
    requireHistoricalEventIsRetained(requested, scope, current, currentHistory);
    if (requested.outboxSequence() != outboxSequence) {
      throw new IllegalStateException(
          "Requested tenant event sequence differs from its lookup key");
    }

    TenantAuthoritySnapshot currentSnapshot =
        new TenantAuthoritySnapshot(
            scope.tenantId(),
            current.generation(),
            current.sourceVersion(),
            streamKey,
            currentHistory.sequence(),
            currentHistory.event());
    if (outboxSequence == currentHistory.sequence()
        && (currentHistory.event().isEmpty()
            || !sameEvent(verified, currentHistory.event().orElseThrow()))) {
      throw new IllegalStateException(
          "Requested latest tenant event differs from current source checkpoint evidence");
    }
    return new TenantAuthorityEventReadback(currentSnapshot, verified);
  }

  private ScopeState readLockedTenantState(AuthorityScope scope) {
    ScopeState state = generationRepository.read(scope);
    if (state == null
        || !scope.equals(state.scope())
        || state.issuanceFence() != null
        || state.generation() <= 0L
        || state.sourceVersion() <= 0L) {
      throw new IllegalStateException(
          "Persisted Account tenant authority state is absent or malformed");
    }
    return state;
  }

  private record AdvanceResult(TenantGenerationAuthorityEvent event, SourceChange pendingChange) {}

  /**
   * Validates the exact stream checkpoint and latest event while the tenant source row is locked.
   * Sequence zero requires the original positive source baseline and proved absence of all event
   * history; a missing stream row alone is not evidence of that baseline.
   */
  private LatestEvidence requireCurrentHistoryMatches(AuthorityScope scope, ScopeState current) {
    String streamKey = streamKey(scope);
    Record stream =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_authority_outbox_streams "
                + "WHERE outbox_stream_key = ? FOR SHARE",
            streamKey);
    if (stream == null) {
      Record eventCountReadback =
          dsl.fetchOne(
              "SELECT COUNT(*) AS event_count FROM account_authority_outbox_events "
                  + "WHERE outbox_stream_key = ?",
              streamKey);
      Long eventCount =
          eventCountReadback == null ? null : eventCountReadback.get("event_count", Long.class);
      if (eventCount == null || eventCount < 0L) {
        throw new IllegalStateException("Tenant authority retained event count is malformed");
      }
      if (eventCount != 0L) {
        throw new IllegalStateException(
            "Tenant authority event history has no matching stream head");
      }
      requireOriginalBaseline(current);
      return LatestEvidence.empty();
    }

    Long headValue = stream.get("last_sequence", Long.class);
    if (headValue == null || headValue < 0L) {
      throw new IllegalStateException("Tenant authority stream head is malformed");
    }
    if (headValue == 0L) {
      throw new IllegalStateException(
          "Committed zero-sequence tenant stream head contradicts the authority checkpoint contract");
    }

    // Every canonical tenant source event advances generation and source version together from
    // the initialized 1/1 baseline. Reject a pristine or one-sided source before looking up any
    // positive event; a forged matching event cannot legitimize incomplete source progress.
    if (current.generation() == 1L
        || current.sourceVersion() == 1L
        || current.generation() != current.sourceVersion()) {
      throw new IllegalStateException(
          "Positive tenant checkpoint requires both tenant generation and source version to have "
              + "advanced together");
    }

    long headSequence = headValue;
    if (headSequence != current.generation() - 1L || headSequence != current.sourceVersion() - 1L) {
      throw new IllegalStateException(
          "Tenant authority checkpoint sequence does not match uninterrupted source counters");
    }
    Record eventRange =
        dsl.fetchOne(
            "SELECT COUNT(*) AS event_count, MIN(outbox_sequence) AS first_sequence, "
                + "MAX(outbox_sequence) AS last_sequence FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key = ?",
            streamKey);
    Long eventCount = eventRange == null ? null : eventRange.get("event_count", Long.class);
    Long firstSequence = eventRange == null ? null : eventRange.get("first_sequence", Long.class);
    Long lastSequence = eventRange == null ? null : eventRange.get("last_sequence", Long.class);
    if (eventCount == null
        || firstSequence == null
        || lastSequence == null
        || eventCount != headSequence
        || firstSequence != 1L
        || lastSequence != headSequence) {
      throw new IllegalStateException(
          "Tenant authority outbox event history contains a sequence gap or dangling head");
    }

    Checkpoint checkpoint =
        outboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () -> new IllegalStateException("Tenant authority positive checkpoint is missing"));
    Event latest =
        outboxRepository
            .findEvent(streamKey, headSequence)
            .orElseThrow(
                () -> new IllegalStateException("Tenant authority checkpoint event is missing"));
    requireCheckpointMatches(checkpoint, latest, streamKey);
    TenantGenerationAuthorityEvent verified = verifyStoredEventWithoutRequest(latest, scope);
    if (!Long.toString(current.generation()).equals(verified.tenantAuthorityGeneration())
        || !Long.toString(current.sourceVersion()).equals(verified.sourceVersion())) {
      throw new IllegalStateException(
          "Latest tenant source event differs from current source counters");
    }
    return new LatestEvidence(headSequence, Optional.of(verified), Optional.of(latest));
  }

  private void requireOriginalBaseline(ScopeState current) {
    if (current.generation() != 1L || current.sourceVersion() != 1L) {
      throw new IllegalStateException(
          "Tenant sequence-zero readback requires the original positive 1/1 source baseline");
    }
  }

  private void requireHistoricalEventIsRetained(
      Event event, AuthorityScope scope, ScopeState current, LatestEvidence history) {
    if (history.sequence() < event.outboxSequence()) {
      throw new IllegalStateException("Historical tenant event is ahead of the current checkpoint");
    }
    TenantGenerationAuthorityEvent verified = verifyStoredEventWithoutRequest(event, scope);
    if (new BigInteger(verified.tenantAuthorityGeneration())
                .compareTo(BigInteger.valueOf(current.generation()))
            > 0
        || new BigInteger(verified.sourceVersion())
                .compareTo(BigInteger.valueOf(current.sourceVersion()))
            > 0) {
      throw new IllegalStateException("Historical tenant event is ahead of current source state");
    }
  }

  private EventEvidence eventEvidence(
      AuthorityScope scope,
      String requestId,
      String eventId,
      long sequence,
      long tenantAuthorityGeneration,
      long sourceVersion) {
    String streamKey = streamKey(scope);
    String sourceScope = sourceScope(scope);
    TenantGenerationAuthorityEvent event =
        TenantGenerationAuthorityEventV1Codec.seal(
            Map.of(
                "schemaVersion",
                TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                "eventType",
                TenantGenerationAuthorityEventV1Codec.EVENT_TYPE,
                "eventId",
                eventId,
                "requestId",
                requestId,
                "tenantId",
                scope.tenantId().toString(),
                "sourceScope",
                sourceScope,
                "outboxStreamKey",
                streamKey,
                "outboxSequence",
                Long.toString(sequence),
                "tenantAuthorityGeneration",
                Long.toString(tenantAuthorityGeneration),
                "sourceVersion",
                Long.toString(sourceVersion)));
    return new EventEvidence(event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
  }

  private TenantGenerationAuthorityEvent verifyStoredEvent(
      Event stored,
      AuthorityScope scope,
      UUID requestId,
      long expectedGeneration,
      long expectedSourceVersion) {
    TenantGenerationAuthorityEvent verified = verifyStoredEventWithoutRequest(stored, scope);
    String canonicalRequestId = requestId.toString();
    long resultingGeneration;
    long resultingSourceVersion;
    try {
      resultingGeneration = Math.addExact(expectedGeneration, 1L);
      resultingSourceVersion = Math.addExact(expectedSourceVersion, 1L);
    } catch (ArithmeticException overflow) {
      throw new AccountAuthorityOutboxRepository.IdempotencyConflictException(
          "Tenant authority request identity was reused with different expected source state");
    }
    if (!canonicalRequestId.equals(stored.requestId())
        || !canonicalRequestId.equals(verified.requestId())
        || !(EVENT_ID_PREFIX + canonicalRequestId).equals(stored.eventId())
        || !Long.toString(resultingGeneration).equals(verified.tenantAuthorityGeneration())
        || !Long.toString(resultingSourceVersion).equals(verified.sourceVersion())) {
      throw new AccountAuthorityOutboxRepository.IdempotencyConflictException(
          "Tenant authority request identity was reused with different expected source state");
    }
    return verified;
  }

  private TenantGenerationAuthorityEvent verifyStoredEventWithoutRequest(
      Event stored, AuthorityScope scope) {
    if (stored == null) {
      throw new IllegalStateException("Tenant authority stored event is missing");
    }
    TenantGenerationAuthorityEvent verified =
        TenantGenerationAuthorityEventV1Codec.verify(
            new String(stored.payload(), StandardCharsets.UTF_8));
    String streamKey = streamKey(scope);
    String sourceScope = sourceScope(scope);
    if (!MessageDigest.isEqual(stored.payload(), verified.canonicalJsonUtf8())
        || !streamKey.equals(stored.outboxStreamKey())
        || !streamKey.equals(verified.outboxStreamKey())
        || !sourceScope.equals(verified.sourceScope())
        || !scope.tenantId().toString().equals(verified.tenantId())
        || !stored.requestId().equals(verified.requestId())
        || !stored.eventId().equals(verified.eventId())
        || !stored.eventDigest().equals(verified.eventDigest())
        || !Long.toString(stored.outboxSequence()).equals(verified.outboxSequence())
        || !(EVENT_ID_PREFIX + stored.requestId()).equals(stored.eventId())) {
      throw new IllegalStateException(
          "Tenant authority stored event payload or database columns contradict");
    }
    requireCanonicalStoredRequestId(stored.requestId());
    String expectedCounter =
        BigInteger.valueOf(stored.outboxSequence()).add(BigInteger.ONE).toString();
    if (!expectedCounter.equals(verified.tenantAuthorityGeneration())
        || !expectedCounter.equals(verified.sourceVersion())) {
      throw new IllegalStateException(
          "Tenant authority event counters do not match their uninterrupted outbox sequence");
    }
    return verified;
  }

  private void requireCanonicalStoredRequestId(String requestId) {
    try {
      UUID parsed = UUID.fromString(requestId);
      if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(requestId)) {
        throw new IllegalStateException("Tenant authority stored request ID is not canonical");
      }
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Tenant authority stored request ID is not a canonical UUID", malformed);
    }
  }

  private void requireScopeState(
      ScopeState state, AuthorityScope scope, long generation, long sourceVersion) {
    if (state == null
        || !scope.equals(state.scope())
        || state.issuanceFence() != null
        || state.generation() != generation
        || state.sourceVersion() != sourceVersion) {
      throw new IllegalStateException(
          "Persisted Account tenant source did not match its expected advance");
    }
  }

  private void requireCheckpointMatches(Checkpoint checkpoint, Event event, String streamKey) {
    if (checkpoint == null
        || event == null
        || !streamKey.equals(checkpoint.outboxStreamKey())
        || !checkpoint.outboxStreamKey().equals(event.outboxStreamKey())
        || checkpoint.outboxSequence() != event.outboxSequence()
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException(
          "Tenant authority checkpoint differs from its immutable event");
    }
  }

  private AuthorityScope tenantScope(UUID tenantId) {
    if (tenantId == null || new UUID(0L, 0L).equals(tenantId)) {
      throw new IllegalArgumentException("Canonical non-nil tenant UUID is required");
    }
    return AuthorityScope.tenant(tenantId);
  }

  private void requireRequestId(UUID requestId) {
    if (requestId == null || new UUID(0L, 0L).equals(requestId)) {
      throw new IllegalArgumentException(
          "Tenant authority request ID must be a canonical non-nil UUID");
    }
  }

  private String sourceScope(AuthorityScope scope) {
    return "tenant/" + scope.tenantId();
  }

  private String streamKey(AuthorityScope scope) {
    return AUTHORITY_STREAM_PREFIX + sourceScope(scope);
  }

  private void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Tenant authority producer must own its Account transaction without an ambient transaction");
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
      TenantGenerationAuthorityEvent first, TenantGenerationAuthorityEvent second) {
    return first != null
        && second != null
        && first.schemaVersion().equals(second.schemaVersion())
        && first.eventType().equals(second.eventType())
        && first.eventId().equals(second.eventId())
        && first.requestId().equals(second.requestId())
        && first.tenantId().equals(second.tenantId())
        && first.sourceScope().equals(second.sourceScope())
        && first.outboxStreamKey().equals(second.outboxStreamKey())
        && first.outboxSequence().equals(second.outboxSequence())
        && first.tenantAuthorityGeneration().equals(second.tenantAuthorityGeneration())
        && first.sourceVersion().equals(second.sourceVersion())
        && first.eventDigest().equals(second.eventDigest())
        && first.canonicalJson().equals(second.canonicalJson())
        && MessageDigest.isEqual(first.canonicalJsonUtf8(), second.canonicalJsonUtf8());
  }

  /** Current exact source evidence; the event is omitted when the proven checkpoint is zero. */
  public record TenantAuthoritySnapshot(
      UUID tenantId,
      long tenantAuthorityGeneration,
      long sourceVersion,
      String outboxStreamKey,
      long outboxSequence,
      Optional<TenantGenerationAuthorityEvent> latestEvent) {
    public TenantAuthoritySnapshot {
      if (tenantId == null
          || new UUID(0L, 0L).equals(tenantId)
          || tenantAuthorityGeneration <= 0L
          || sourceVersion <= 0L
          || outboxStreamKey == null
          || outboxSequence < 0L) {
        throw new IllegalArgumentException("Tenant authority snapshot fields are incomplete");
      }
      String expectedScope = "tenant/" + tenantId;
      String expectedStreamKey = AUTHORITY_STREAM_PREFIX + expectedScope;
      if (!expectedStreamKey.equals(outboxStreamKey)) {
        throw new IllegalArgumentException(
            "Tenant authority snapshot stream does not bind its tenant");
      }
      latestEvent = Objects.requireNonNull(latestEvent, "tenant latest event optional is required");
      if ((outboxSequence == 0L) != latestEvent.isEmpty()) {
        throw new IllegalArgumentException("Tenant sequence-zero snapshot must omit its event");
      }
      if (outboxSequence == 0L && (tenantAuthorityGeneration != 1L || sourceVersion != 1L)) {
        throw new IllegalArgumentException(
            "Tenant sequence-zero snapshot requires the positive 1/1 baseline");
      }
      if (outboxSequence > 0L
          && (tenantAuthorityGeneration == 1L
              || sourceVersion == 1L
              || tenantAuthorityGeneration != sourceVersion
              || outboxSequence != tenantAuthorityGeneration - 1L)) {
        throw new IllegalArgumentException(
            "Positive tenant snapshot requires uninterrupted sequence and counter progression");
      }
      latestEvent.ifPresent(
          event -> {
            if (!tenantId.toString().equals(event.tenantId())
                || !expectedScope.equals(event.sourceScope())
                || !outboxStreamKey.equals(event.outboxStreamKey())
                || !Long.toString(outboxSequence).equals(event.outboxSequence())
                || !Long.toString(tenantAuthorityGeneration)
                    .equals(event.tenantAuthorityGeneration())
                || !Long.toString(sourceVersion).equals(event.sourceVersion())) {
              throw new IllegalArgumentException(
                  "Tenant authority snapshot event differs from its checkpoint");
            }
          });
    }
  }

  /** Exact retained source event tied to the current tenant checkpoint snapshot. */
  public record TenantAuthorityEventReadback(
      TenantAuthoritySnapshot currentSnapshot, TenantGenerationAuthorityEvent requestedEvent) {
    public TenantAuthorityEventReadback {
      currentSnapshot =
          Objects.requireNonNull(currentSnapshot, "current tenant authority snapshot is required");
      requestedEvent =
          Objects.requireNonNull(requestedEvent, "requested tenant authority event is required");
      if (currentSnapshot.outboxSequence() <= 0L
          || !currentSnapshot.tenantId().toString().equals(requestedEvent.tenantId())
          || !("tenant/" + currentSnapshot.tenantId()).equals(requestedEvent.sourceScope())
          || !currentSnapshot.outboxStreamKey().equals(requestedEvent.outboxStreamKey())) {
        throw new IllegalArgumentException(
            "Requested tenant event does not bind a positive current tenant snapshot");
      }

      BigInteger requestedSequence = parsePositiveCounter(requestedEvent.outboxSequence());
      BigInteger currentSequence = BigInteger.valueOf(currentSnapshot.outboxSequence());
      BigInteger requestedGeneration =
          parsePositiveCounter(requestedEvent.tenantAuthorityGeneration());
      BigInteger requestedSourceVersion = parsePositiveCounter(requestedEvent.sourceVersion());
      if (requestedSequence.compareTo(currentSequence) > 0
          || requestedGeneration.compareTo(
                  BigInteger.valueOf(currentSnapshot.tenantAuthorityGeneration()))
              > 0
          || requestedSourceVersion.compareTo(BigInteger.valueOf(currentSnapshot.sourceVersion()))
              > 0) {
        throw new IllegalArgumentException(
            "Requested historical tenant event is ahead of the current snapshot");
      }
      BigInteger expectedCounter = requestedSequence.add(BigInteger.ONE);
      if (!expectedCounter.equals(requestedGeneration)
          || !expectedCounter.equals(requestedSourceVersion)) {
        throw new IllegalArgumentException(
            "Requested historical tenant event counters do not match its outbox sequence");
      }
      if (requestedSequence.equals(currentSequence)
          && (currentSnapshot.latestEvent().isEmpty()
              || !sameEvent(requestedEvent, currentSnapshot.latestEvent().orElseThrow()))) {
        throw new IllegalArgumentException(
            "Requested latest tenant event differs from the current snapshot checkpoint");
      }
    }

    private static BigInteger parsePositiveCounter(String value) {
      if (value == null || !value.matches("[1-9][0-9]*")) {
        throw new IllegalArgumentException(
            "Tenant event counters must be canonical positive integers");
      }
      return new BigInteger(value);
    }
  }

  private record LatestEvidence(
      long sequence, Optional<TenantGenerationAuthorityEvent> event, Optional<Event> outboxEvent) {
    private LatestEvidence {
      if (sequence < 0L) {
        throw new IllegalArgumentException("Tenant authority history sequence cannot be negative");
      }
      event = Objects.requireNonNull(event, "tenant history event optional is required");
      outboxEvent = Objects.requireNonNull(outboxEvent, "tenant outbox event optional is required");
      if ((sequence == 0L) != event.isEmpty() || event.isEmpty() != outboxEvent.isEmpty()) {
        throw new IllegalArgumentException("Tenant authority history evidence is incomplete");
      }
    }

    private static LatestEvidence empty() {
      return new LatestEvidence(0L, Optional.empty(), Optional.empty());
    }
  }
}
