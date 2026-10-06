package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback.RetainedEventEvidence;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountEvent;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountPreimage;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountState;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerEvent;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerPreimage;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.SourceEvent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns explicit fresh source baselines and exact Account/issuer authority event append/readback.
 *
 * <p>A sequence-zero checkpoint is returned only when this repository's immutable provenance row
 * exactly matches the freshly inserted generation-one owner row and the retained event history is
 * empty. This class never enrolls an existing Account or repairs a missing baseline.
 */
@Repository
public class AccountAuthoritySourceEvidenceRepository {
  private static final String SOURCE_TABLE = "account_authority_source_records";
  private static final String STREAM_PREFIX =
      AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX;
  private static final String ACCOUNT_ISSUER = "firemud-account-service";

  private final DSLContext dsl;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountAuthorityOutboxRepository outbox;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve required injected collaborators; Spring must proxy this non-final repository.")
  public AccountAuthoritySourceEvidenceRepository(
      DSLContext dsl,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.generations = Objects.requireNonNull(generations, "generation repository is required");
    this.outbox = Objects.requireNonNull(outbox, "authority outbox repository is required");
  }

  /** Enrolls the canonical issuer only when the generation row is genuinely new. */
  @Transactional(propagation = Propagation.MANDATORY)
  public CurrentSourceEvidence initializeIssuerIfAbsent(String exactIssuerId) {
    AuthorityScope scope = AuthorityScope.issuer(exactIssuerId);
    var enrollment = generations.initializeIssuerIfAbsentForSourceEvidence(exactIssuerId);
    if (enrollment.inserted()) {
      insertFreshBaseline(scope, enrollment.state(), null);
    }
    return readCurrentSource(scope, enrollment.state());
  }

  /**
   * Establishes the canonical issuer and Account sequence-zero baselines only from the exact
   * AccountRepository insert/readback capability. Updates and retained Accounts cannot call it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void initializeFreshAccount(AccountRepository.FreshAccountInsert proof) {
    Objects.requireNonNull(proof, "fresh Account repository insert proof is required");
    initializeIssuerIfAbsent(ACCOUNT_ISSUER);
    long accountInsertTransactionId = requireFreshAccountReadback(proof);
    ScopeState accountState = generations.initializeAccountForFreshRepositoryInsert(proof);
    insertFreshBaseline(
        AuthorityScope.account(proof.accountUuid()),
        accountState,
        new AccountIdentitySource(proof.accountId(), accountInsertTransactionId));
    readCurrentSource(AuthorityScope.account(proof.accountUuid()), accountState);
  }

  /**
   * Appends a single event for the security-relevant fields changed by AccountRepository.save. The
   * row update already occurred under its Account transaction and is rolled back if this
   * generation/fence/event/readback sequence does not complete exactly.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAccountUpdate(AccountRepository.AccountUpdateEvidence proof) {
    Objects.requireNonNull(proof, "Account update proof is required");
    List<String> mutationKinds = mutationKinds(proof.before(), proof.after());
    if (mutationKinds.isEmpty()) return;
    requireAccountReadback(proof.accountUuid(), proof.accountId(), proof.after());

    AuthorityScope scope = AuthorityScope.account(proof.accountUuid());
    // Retained pre-V40 Accounts have no owner-proven baseline. Diagnose only that absence
    // before the generation read, without enrolling the Account or catching unrelated failures.
    if (dsl.fetchOne(
            "SELECT outbox_stream_key FROM " + SOURCE_TABLE + " WHERE outbox_stream_key = ?",
            streamKey(scope))
        == null) {
      throw new SourceEvidenceUnavailableException();
    }
    ScopeState current = generations.read(scope);
    // AccountRepository has already applied this exact update in the surrounding transaction.
    // Validate the event head against the immutable repository-captured before image here; normal
    // source reads and the post-append readback below still require equality with the live row.
    CurrentSourceEvidence currentEvidence =
        readCurrentSource(scope, current, Optional.of(proof.before().toEventState()));
    IssuanceFence expectedFence =
        Objects.requireNonNull(current.issuanceFence(), "Account source fence is required");
    ScopeState advanced = generations.advanceForSourceEvidence(current, expectedFence);
    IssuanceFence advancedFence =
        Objects.requireNonNull(advanced.issuanceFence(), "advanced Account fence is required");
    String streamKey = streamKey(scope);
    String requestId = proof.mutationRequestId();
    String eventId = requestId;
    AccountEvent[] candidate = new AccountEvent[1];
    Event appended =
        outbox.append(
            streamKey,
            requestId,
            sequence -> {
              if (sequence != currentEvidence.checkpoint().sequence() + 1L
                  || sequence != advanced.generation() - 1L) {
                throw new IllegalStateException("Account authority source sequence changed");
              }
              AccountEvent event =
                  AccountAuthoritySourceEventV1Codec.sealAccount(
                      new AccountPreimage(
                          eventId,
                          requestId,
                          streamKey,
                          Long.toString(sequence),
                          proof.accountUuid().toString(),
                          Long.toString(advanced.generation()),
                          Long.toString(advanced.sourceVersion()),
                          Long.toString(advancedFence.value()),
                          Long.toString(advancedFence.sourceVersion()),
                          mutationKinds,
                          proof.after().toEventState()));
              candidate[0] = event;
              return new EventEvidence(
                  event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
            });
    AccountEvent expected =
        Objects.requireNonNull(candidate[0], "Account source event candidate was not produced");
    requireExactAppendedEvent(appended, expected, streamKey, requestId);
    Event byRequest =
        outbox
            .findEvent(streamKey, requestId)
            .orElseThrow(() -> new SourceEvidenceUnavailableException());
    Event bySequence =
        outbox
            .findEvent(streamKey, expectedSequence(expected))
            .orElseThrow(() -> new SourceEvidenceUnavailableException());
    if (!appended.equals(byRequest) || !appended.equals(bySequence)) {
      throw new SourceEvidenceUnavailableException();
    }
    AccountEvent verified = verifyAccountEvent(byRequest, scope, advanced);
    updateSourceHead(currentEvidence, verified, advanced, advancedFence);
    CurrentSourceEvidence readback = readCurrentSource(scope, advanced);
    if (!readback.checkpoint().equals(checkpoint(verified))
        || !readback
            .accountSecurityCutoff()
            .orElseThrow()
            .equals(verified.accountSecurityCutoff())) {
      throw new SourceEvidenceUnavailableException();
    }
  }

  /**
   * Returns locked, codec-verified source evidence for the exact unscoped LOGIN profile. Callers
   * must include this API in the same mandatory Account transaction as their owner snapshot.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public IssuerAccountSourceSnapshot readCurrentIssuerAccountSources(
      String exactIssuerId, UUID accountUuid) {
    CompositeSnapshot snapshot =
        generations.readCompositeSnapshot(exactIssuerId, accountUuid, List.of(), List.of());
    CurrentSourceEvidence issuer = readCurrentSource(snapshot.issuer().scope(), snapshot.issuer());
    CurrentSourceEvidence account =
        readCurrentSource(snapshot.account().scope(), snapshot.account());
    if (account.issuanceFence() == null
        || !account.issuanceFence().equals(snapshot.issuanceFence())
        || !account.issuanceFence().equals(snapshot.account().issuanceFence())) {
      throw new SourceEvidenceUnavailableException();
    }
    AccountAuthoritySourceEventReadback.LatestSourceSnapshot closedSource =
        closedEventReadback()
            .requireCurrentLatest(
                readAccountIdentityForClosedReadback(accountUuid), snapshot.account());
    if (closedSource.outboxSequence() != account.checkpoint().sequence()) {
      throw new SourceEvidenceUnavailableException();
    }
    if (closedSource.outboxSequence() == 0L) {
      if (closedSource.latestEvent().isPresent()
          || account.checkpoint().sourceEventId().isPresent()
          || account.checkpoint().sourceEventDigest().isPresent()) {
        throw new SourceEvidenceUnavailableException();
      }
    } else {
      Event closedEvent = closedSource.latestEvent().orElseThrow();
      if (closedEvent.outboxSequence() != account.checkpoint().sequence()
          || !closedEvent.eventId().equals(account.checkpoint().sourceEventId().orElseThrow())
          || !closedEvent
              .eventDigest()
              .equals(account.checkpoint().sourceEventDigest().orElseThrow())) {
        throw new SourceEvidenceUnavailableException();
      }
    }
    AccountGenerationProjection accountProjection =
        AccountGenerationProjection.fromSource(
            new AccountSourceSnapshot(
                accountUuid,
                snapshot.account(),
                account.checkpoint().outboxStreamKey(),
                account.checkpoint().sequence(),
                closedSource.latestEvent()));
    return new IssuerAccountSourceSnapshot(
        issuer,
        account,
        snapshot.issuanceFence(),
        accountProjection,
        IssuerGenerationProjection.fromSource(
            new CanonicalIssuerSourceSnapshot(
                issuer,
                issuer.checkpoint().sequence() == 0L
                    ? Optional.empty()
                    : Optional.of(
                        outbox
                            .findEvent(
                                issuer.checkpoint().outboxStreamKey(),
                                issuer.checkpoint().sequence())
                            .orElseThrow(SourceEvidenceUnavailableException::new)))));
  }

  /**
   * Prepares exactly one Account-local advance against the complete current source readback. This
   * is not authorization or a committed receipt. The closed event, immutable operation receipt and
   * source head must all complete in this same owner transaction; deferred SQL consistency guards
   * reject an incomplete transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ScopeState prepareClosedAccountAdvance(UUID accountUuid, CurrentSourceEvidence expected) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || accountUuid == null
        || accountUuid.equals(new UUID(0L, 0L))
        || expected == null
        || !AuthorityScope.account(accountUuid).equals(expected.scope())
        || expected.issuanceFence() == null) {
      throw new SourceEvidenceUnavailableException();
    }
    AuthorityScope scope = AuthorityScope.account(accountUuid);
    ScopeState current = generations.read(scope);
    CurrentSourceEvidence actual = readCurrentSource(scope, current);
    if (!expected.equals(actual) || !expected.issuanceFence().equals(current.issuanceFence())) {
      throw new SourceEvidenceUnavailableException();
    }
    long nextGeneration = Math.addExact(current.generation(), 1L);
    long nextSourceVersion = Math.addExact(current.sourceVersion(), 1L);
    long nextFence = Math.addExact(current.issuanceFence().value(), 1L);
    long nextFenceVersion = Math.addExact(current.issuanceFence().sourceVersion(), 1L);
    ScopeState advanced = generations.advanceForSourceEvidence(current, current.issuanceFence());
    if (!scope.equals(advanced.scope())
        || advanced.generation() != nextGeneration
        || advanced.sourceVersion() != nextSourceVersion
        || advanced.issuanceFence() == null
        || !accountUuid.equals(advanced.issuanceFence().accountId())
        || advanced.issuanceFence().value() != nextFence
        || advanced.issuanceFence().sourceVersion() != nextFenceVersion) {
      throw new SourceEvidenceUnavailableException();
    }
    return advanced;
  }

  /**
   * Advances the canonical Account source head only after a closed operation's immutable owner
   * receipt and exact outbox event have been stored in this same owner transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CurrentSourceEvidence advanceClosedAccountHead(
      UUID accountUuid, ScopeState expected, ScopeState advanced, Event event) {
    Objects.requireNonNull(accountUuid, "Account UUID is required");
    Objects.requireNonNull(expected, "expected Account source state is required");
    Objects.requireNonNull(advanced, "advanced Account source state is required");
    Objects.requireNonNull(event, "closed Account source event is required");
    AuthorityScope scope = AuthorityScope.account(accountUuid);
    if (!scope.equals(expected.scope())
        || !scope.equals(advanced.scope())
        || expected.issuanceFence() == null
        || advanced.issuanceFence() == null
        || advanced.generation() != Math.addExact(expected.generation(), 1L)
        || advanced.sourceVersion() != Math.addExact(expected.sourceVersion(), 1L)
        || advanced.issuanceFence().value() != Math.addExact(expected.issuanceFence().value(), 1L)
        || advanced.issuanceFence().sourceVersion()
            != Math.addExact(expected.issuanceFence().sourceVersion(), 1L)) {
      throw new SourceEvidenceUnavailableException();
    }

    // The typed producer has already appended the event, but source_records must still contain
    // the exact old head. Read and authenticate that previous head while allowing only this one
    // pending outbox append; a normal current-source read would correctly reject the transaction's
    // temporarily advanced stream checkpoint.
    CurrentSourceEvidence before = readPreviousSourceForClosedAppend(scope, expected, event);
    long expectedSequence = Math.addExact(before.checkpoint().sequence(), 1L);
    RetainedEventEvidence receiptEvidence =
        closedEventReadback()
            .requireRetainedEvent(
                readAccountIdentityForClosedReadback(accountUuid), event, advanced);
    if (!streamKey(scope).equals(event.outboxStreamKey())
        || event.outboxSequence() != expectedSequence
        || advanced.generation() != receiptEvidence.accountAuthorityGeneration()
        || advanced.sourceVersion() != receiptEvidence.sourceVersion()
        || receiptEvidence.issuanceFence() != advanced.issuanceFence().value()
        || receiptEvidence.issuanceFenceSourceVersion()
            != advanced.issuanceFence().sourceVersion()) {
      throw new SourceEvidenceUnavailableException();
    }
    Event bySequence =
        outbox
            .findEvent(event.outboxStreamKey(), event.outboxSequence())
            .orElseThrow(SourceEvidenceUnavailableException::new);
    Event byRequest =
        outbox
            .findEvent(event.outboxStreamKey(), event.requestId())
            .orElseThrow(SourceEvidenceUnavailableException::new);
    if (!event.equals(bySequence) || !event.equals(byRequest)) {
      throw new SourceEvidenceUnavailableException();
    }
    Checkpoint checkpoint =
        outbox
            .readCheckpoint(event.outboxStreamKey())
            .orElseThrow(SourceEvidenceUnavailableException::new);
    if (checkpoint.outboxSequence() != expectedSequence
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())) {
      throw new SourceEvidenceUnavailableException();
    }
    var typedCutoff = receiptEvidence.accountSecurityCutoff();
    updateSourceHead(
        before,
        event,
        new AccountSecurityCutoff(
            typedCutoff.accountAuthorityGeneration(),
            typedCutoff.outboxStreamKey(),
            typedCutoff.outboxSequence()),
        advanced,
        advanced.issuanceFence());
    CurrentSourceEvidence current = readCurrentSource(scope, advanced);
    if (current.checkpoint().sequence() != event.outboxSequence()
        || !current.checkpoint().sourceEventId().orElseThrow().equals(event.eventId())
        || !current.checkpoint().sourceEventDigest().orElseThrow().equals(event.eventDigest())) {
      throw new SourceEvidenceUnavailableException();
    }
    Account latestAccount = readAccountIdentityForClosedReadback(accountUuid);
    AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest =
        closedEventReadback().requireCurrentLatest(latestAccount, advanced);
    if (latest.outboxSequence() != event.outboxSequence()
        || latest.latestEvent().isEmpty()
        || !event.equals(latest.latestEvent().orElseThrow())) {
      throw new SourceEvidenceUnavailableException();
    }
    return current;
  }

  /** Returns the actual current issuer source and its retained terminal event without mutation. */
  @Transactional(propagation = Propagation.MANDATORY)
  public CanonicalIssuerSourceSnapshot readCurrentCanonicalIssuerSource(String exactIssuerId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new SourceEvidenceUnavailableException();
    }
    AuthorityScope scope = AuthorityScope.issuer(exactIssuerId);
    CurrentSourceEvidence current = readCurrentSource(scope, generations.read(scope));
    Optional<Event> latest =
        current.checkpoint().sequence() == 0L
            ? Optional.empty()
            : Optional.of(
                outbox
                    .findEvent(
                        current.checkpoint().outboxStreamKey(), current.checkpoint().sequence())
                    .orElseThrow(SourceEvidenceUnavailableException::new));
    return new CanonicalIssuerSourceSnapshot(current, latest);
  }

  /** Records an explicit issuer-wide mutation with exact event readback and no Account cutoff. */
  @Transactional(propagation = Propagation.MANDATORY)
  public CurrentSourceEvidence appendIssuerAuthorityChange(
      String exactIssuerId, String mutationKind, String requestId) {
    AuthorityScope scope = AuthorityScope.issuer(exactIssuerId);
    // Composite source readers take FOR SHARE on this row. Lock exclusively before reading the
    // generation so concurrent issuer writers serialize without a pair of SHARE-to-UPDATE upgrades.
    ScopeState current = generations.readIssuerStateForSourceMutation(scope);
    CurrentSourceEvidence before = readCurrentSource(scope, current);
    ScopeState advanced = generations.advanceForSourceEvidence(current, null);
    String streamKey = streamKey(scope);
    String eventId = requireBoundedText(requestId, "issuer source request ID");
    IssuerEvent[] candidate = new IssuerEvent[1];
    Event appended =
        outbox.append(
            streamKey,
            requestId,
            sequence -> {
              if (sequence != before.checkpoint().sequence() + 1L
                  || sequence != advanced.generation() - 1L) {
                throw new IllegalStateException("Issuer authority source sequence changed");
              }
              IssuerEvent event =
                  AccountAuthoritySourceEventV1Codec.sealIssuer(
                      new IssuerPreimage(
                          eventId,
                          requestId,
                          streamKey,
                          Long.toString(sequence),
                          exactIssuerId,
                          Long.toString(advanced.generation()),
                          Long.toString(advanced.sourceVersion()),
                          mutationKind));
              candidate[0] = event;
              return new EventEvidence(
                  event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
            });
    IssuerEvent expected =
        Objects.requireNonNull(candidate[0], "issuer source event candidate was not produced");
    requireExactAppendedEvent(appended, expected, streamKey, requestId);
    Event readback =
        outbox
            .findEvent(streamKey, expectedSequence(expected))
            .orElseThrow(() -> new SourceEvidenceUnavailableException());
    IssuerEvent verified = verifyIssuerEvent(readback, scope, advanced);
    updateSourceHead(before, verified, advanced, null);
    return readCurrentSource(scope, advanced);
  }

  private void insertFreshBaseline(
      AuthorityScope scope, ScopeState state, AccountIdentitySource identitySource) {
    String key = streamKey(scope);
    int insertedStream =
        dsl.execute(
            "INSERT INTO account_authority_outbox_streams (outbox_stream_key, last_sequence) "
                + "VALUES (?, 0)",
            key);
    if (insertedStream != 1) {
      throw new SourceEvidenceUnavailableException();
    }
    int insertedSource =
        dsl.execute(
            "INSERT INTO "
                + SOURCE_TABLE
                + " (outbox_stream_key, scope_kind, issuer_id, account_uuid, "
                + "baseline_generation, baseline_source_version, baseline_issuance_fence, "
                + "initialization_provenance, account_source_numeric_id, account_uuid_provenance, "
                + "account_repository_insert_transaction_id, current_generation, current_source_version, current_issuance_fence, "
                + "current_issuance_fence_source_version, last_outbox_sequence) "
                + "VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
            key,
            scope.kind().name(),
            scope.issuerId(),
            scope.accountId(),
            identitySource == null ? null : 1L,
            identitySource == null ? "ISSUER_SCOPE_INSERT" : "ACCOUNT_REPOSITORY_INSERT",
            identitySource == null ? null : identitySource.accountSourceNumericId(),
            identitySource == null
                ? null
                : AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name(),
            identitySource == null ? null : identitySource.accountRepositoryInsertTransactionId(),
            state.generation(),
            state.sourceVersion(),
            state.issuanceFence() == null ? null : state.issuanceFence().value(),
            state.issuanceFence() == null ? null : state.issuanceFence().sourceVersion());
    if (insertedSource != 1) throw new SourceEvidenceUnavailableException();
  }

  private CurrentSourceEvidence readCurrentSource(AuthorityScope scope, ScopeState authority) {
    return readCurrentSource(scope, authority, Optional.empty(), false);
  }

  private CurrentSourceEvidence readCurrentSource(
      AuthorityScope scope,
      ScopeState authority,
      Optional<AccountState> expectedPreUpdateAccountState) {
    return readCurrentSource(scope, authority, expectedPreUpdateAccountState, false);
  }

  private CurrentSourceEvidence readPreviousSourceForClosedAppend(
      AuthorityScope scope, ScopeState authority, Event pendingEvent) {
    CurrentSourceEvidence before = readCurrentSource(scope, authority, Optional.empty(), true);
    if (!streamKey(scope).equals(pendingEvent.outboxStreamKey())
        || pendingEvent.outboxSequence() != Math.addExact(before.checkpoint().sequence(), 1L)) {
      throw new SourceEvidenceUnavailableException();
    }
    Event exactPending =
        outbox
            .findEvent(pendingEvent.outboxStreamKey(), pendingEvent.outboxSequence())
            .orElseThrow(SourceEvidenceUnavailableException::new);
    Event requestMatch =
        outbox
            .findEvent(pendingEvent.outboxStreamKey(), pendingEvent.requestId())
            .orElseThrow(SourceEvidenceUnavailableException::new);
    if (!pendingEvent.equals(exactPending) || !pendingEvent.equals(requestMatch)) {
      throw new SourceEvidenceUnavailableException();
    }
    return before;
  }

  private CurrentSourceEvidence readCurrentSource(
      AuthorityScope scope,
      ScopeState authority,
      Optional<AccountState> expectedPreUpdateAccountState,
      boolean allowOnePendingAppend) {
    String key = streamKey(scope);
    Record source =
        dsl.fetchOne(
            "SELECT scope_kind, issuer_id, account_uuid, baseline_generation, "
                + "baseline_source_version, baseline_issuance_fence, initialization_provenance, "
                + "account_source_numeric_id, account_uuid_provenance, "
                + "initialization_transaction_id, account_repository_insert_transaction_id, current_generation, "
                + "current_source_version, current_issuance_fence, "
                + "current_issuance_fence_source_version, last_outbox_sequence, last_event_id, "
                + "last_event_digest, cutoff_generation, cutoff_stream_key, cutoff_sequence "
                + "FROM "
                + SOURCE_TABLE
                + " WHERE outbox_stream_key = ? FOR SHARE",
            key);
    if (source == null) throw new SourceEvidenceUnavailableException();
    requireSourceScope(source, scope);
    long currentGeneration = positive(source, "current_generation");
    long currentSourceVersion = positive(source, "current_source_version");
    long sequence = nonnegative(source, "last_outbox_sequence");
    if (currentGeneration != authority.generation()
        || currentSourceVersion != authority.sourceVersion()
        || currentGeneration != sequence + 1L
        || currentSourceVersion != sequence + 1L) {
      throw new SourceEvidenceUnavailableException();
    }
    IssuanceFence fence = authority.issuanceFence();
    if (scope.kind() == ScopeKind.ACCOUNT) {
      if (fence == null
          || positive(source, "current_issuance_fence") != fence.value()
          || positive(source, "current_issuance_fence_source_version") != fence.sourceVersion()
          || fence.value() != sequence + 1L
          || fence.sourceVersion() != sequence + 1L) {
        throw new SourceEvidenceUnavailableException();
      }
    } else if (fence != null) {
      throw new SourceEvidenceUnavailableException();
    }

    Record stream =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_authority_outbox_streams "
                + "WHERE outbox_stream_key = ? FOR SHARE",
            key);
    long expectedStreamSequence = allowOnePendingAppend ? Math.addExact(sequence, 1L) : sequence;
    if (stream == null || nonnegative(stream, "last_sequence") != expectedStreamSequence) {
      throw new SourceEvidenceUnavailableException();
    }
    if (scope.kind() == ScopeKind.ACCOUNT) {
      Record eventCountRow =
          dsl.fetchOne(
              "SELECT count(*) AS event_count FROM account_authority_outbox_events "
                  + "WHERE outbox_stream_key = ?",
              key);
      Long eventCount = eventCountRow == null ? null : eventCountRow.get("event_count", Long.class);
      if (eventCount == null || eventCount != expectedStreamSequence) {
        throw new SourceEvidenceUnavailableException();
      }
    }

    SourceCheckpoint checkpoint;
    Optional<AccountSecurityCutoff> cutoff = Optional.empty();
    VerifiedSourceHistory history = null;
    if (sequence == 0L) {
      if (source.get("last_event_id", String.class) != null
          || source.get("last_event_digest", String.class) != null
          || source.get("cutoff_generation", Long.class) != null
          || source.get("cutoff_stream_key", String.class) != null
          || source.get("cutoff_sequence", Long.class) != null
          || positive(source, "baseline_generation") != 1L
          || positive(source, "baseline_source_version") != 1L) {
        throw new SourceEvidenceUnavailableException();
      }
      checkpoint = new SourceCheckpoint(key, 0L, Optional.empty(), Optional.empty());
    } else {
      if (scope.kind() == ScopeKind.ISSUER) {
        history = verifyLatestEvent(scope, key, sequence, authority, fence);
      } else {
        history = verifyEventHistory(scope, key, sequence, authority, fence);
      }
      Event latestEvent = history.latestEvent();
      if (!latestEvent.eventId().equals(source.get("last_event_id", String.class))
          || !latestEvent.eventDigest().equals(source.get("last_event_digest", String.class))) {
        throw new SourceEvidenceUnavailableException();
      }
      checkpoint =
          new SourceCheckpoint(
              key,
              sequence,
              Optional.of(latestEvent.eventId()),
              Optional.of(latestEvent.eventDigest()));
      if (scope.kind() == ScopeKind.ACCOUNT) {
        AccountSecurityCutoff exactCutoff =
            history
                .accountSecurityCutoff()
                .orElseThrow(() -> new SourceEvidenceUnavailableException());
        if (positive(source, "cutoff_generation") != currentGeneration
            || !key.equals(source.get("cutoff_stream_key", String.class))
            || positive(source, "cutoff_sequence") != sequence
            || !exactCutoff.accountAuthorityGeneration().equals(Long.toString(currentGeneration))
            || !exactCutoff.outboxStreamKey().equals(key)
            || !exactCutoff.outboxSequence().equals(Long.toString(sequence))) {
          throw new SourceEvidenceUnavailableException();
        }
        cutoff = Optional.of(exactCutoff);
      } else if (source.get("cutoff_generation", Long.class) != null
          || source.get("cutoff_stream_key", String.class) != null
          || source.get("cutoff_sequence", Long.class) != null) {
        throw new SourceEvidenceUnavailableException();
      }
    }
    if (scope.kind() == ScopeKind.ACCOUNT) {
      requireAccountSourceIdentity(source, scope);
      if (history != null && history.latestLegacyAccountEvent().isPresent()) {
        AccountEvent accountEvent = history.latestLegacyAccountEvent().orElseThrow();
        AccountState expectedAccountState =
            expectedPreUpdateAccountState.orElseGet(() -> readAccountState(scope.accountId()));
        if (!accountEvent.accountState().equals(expectedAccountState)) {
          throw new SourceEvidenceUnavailableException();
        }
      }
    }
    return new CurrentSourceEvidence(
        scope,
        authority.generation(),
        authority.sourceVersion(),
        fence,
        checkpoint,
        cutoff,
        source.get("initialization_provenance", String.class),
        source.get("account_source_numeric_id", Long.class),
        source.get("account_uuid_provenance", String.class),
        positive(source, "initialization_transaction_id"),
        source.get("account_repository_insert_transaction_id", Long.class));
  }

  private VerifiedSourceHistory verifyLatestEvent(
      AuthorityScope scope,
      String streamKey,
      long sequence,
      ScopeState authority,
      IssuanceFence fence) {
    // V38/V40 enforce immutable events, contiguous stream advances, and committed head/history
    // consistency. The read path therefore verifies the exact current event rather than replaying
    // an ever-growing retained history.
    Event stored =
        outbox
            .findEvent(streamKey, sequence)
            .orElseThrow(() -> new SourceEvidenceUnavailableException());
    final SourceEvent latest;
    try {
      latest =
          AccountAuthoritySourceEventV1Codec.verify(
              new String(stored.payload(), StandardCharsets.UTF_8));
    } catch (RuntimeException malformed) {
      throw new SourceEvidenceUnavailableException();
    }
    if (!stored.eventId().equals(latest.eventId())
        || !stored.requestId().equals(latest.requestId())
        || !stored.outboxStreamKey().equals(latest.outboxStreamKey())
        || stored.outboxSequence() != Long.parseLong(latest.outboxSequence())
        || !stored.eventDigest().equals(latest.eventDigest())
        || (scope.kind() == ScopeKind.ISSUER && !(latest instanceof IssuerEvent))
        || (scope.kind() == ScopeKind.ACCOUNT && !(latest instanceof AccountEvent))) {
      throw new SourceEvidenceUnavailableException();
    }
    if (latest instanceof IssuerEvent issuer
        && (!issuer.issuerId().equals(scope.issuerId())
            || Long.parseLong(issuer.outboxSequence()) != sequence
            || Long.parseLong(issuer.issuerAuthGeneration()) != sequence + 1L
            || Long.parseLong(issuer.sourceVersion()) != sequence + 1L)) {
      throw new SourceEvidenceUnavailableException();
    }
    if (latest instanceof AccountEvent account
        && (!account.accountId().equals(scope.accountId().toString())
            || Long.parseLong(account.outboxSequence()) != sequence
            || Long.parseLong(account.accountAuthorityGeneration()) != sequence + 1L
            || Long.parseLong(account.sourceVersion()) != sequence + 1L
            || Long.parseLong(account.issuanceFence()) != sequence + 1L
            || Long.parseLong(account.issuanceFenceSourceVersion()) != sequence + 1L)) {
      throw new SourceEvidenceUnavailableException();
    }
    if (authority.generation() != sequence + 1L
        || authority.sourceVersion() != sequence + 1L
        || (scope.kind() == ScopeKind.ACCOUNT
            && (fence == null
                || fence.value() != sequence + 1L
                || fence.sourceVersion() != sequence + 1L))) {
      throw new SourceEvidenceUnavailableException();
    }
    return new VerifiedSourceHistory(stored, Optional.empty(), Optional.empty());
  }

  private VerifiedSourceHistory verifyEventHistory(
      AuthorityScope scope,
      String streamKey,
      long sequence,
      ScopeState authority,
      IssuanceFence fence) {
    Event latest = null;
    AccountEvent latestLegacyAccountEvent = null;
    AccountSecurityCutoff latestCutoff = null;
    Account sourceAccount =
        scope.kind() == ScopeKind.ACCOUNT
            ? readAccountIdentityForClosedReadback(scope.accountId())
            : null;
    for (long current = 1L; current <= sequence; current++) {
      Event stored =
          outbox
              .findEvent(streamKey, current)
              .orElseThrow(() -> new SourceEvidenceUnavailableException());
      SourceEvent event = null;
      try {
        event =
            AccountAuthoritySourceEventV1Codec.verify(
                new String(stored.payload(), StandardCharsets.UTF_8));
      } catch (RuntimeException notLegacyEvent) {
        // Closed RESET/LOGOUT/security events are verified by their operation-specific receipts
        // below. Generic source history remains readable but is not accepted as current authority.
      }
      if (event != null) {
        if (!stored.eventId().equals(event.eventId())
            || !stored.requestId().equals(event.requestId())
            || !stored.outboxStreamKey().equals(event.outboxStreamKey())
            || stored.outboxSequence() != Long.parseLong(event.outboxSequence())
            || !stored.eventDigest().equals(event.eventDigest())
            || (scope.kind() == ScopeKind.ISSUER && !(event instanceof IssuerEvent))
            || (scope.kind() == ScopeKind.ACCOUNT && !(event instanceof AccountEvent))) {
          throw new SourceEvidenceUnavailableException();
        }
        if (event instanceof IssuerEvent issuer
            && (!issuer.issuerId().equals(scope.issuerId())
                || Long.parseLong(issuer.issuerAuthGeneration()) != current + 1L
                || Long.parseLong(issuer.sourceVersion()) != current + 1L)) {
          throw new SourceEvidenceUnavailableException();
        }
        if (event instanceof AccountEvent account
            && (!account.accountId().equals(scope.accountId().toString())
                || Long.parseLong(account.accountAuthorityGeneration()) != current + 1L
                || Long.parseLong(account.sourceVersion()) != current + 1L
                || Long.parseLong(account.issuanceFence()) != current + 1L
                || Long.parseLong(account.issuanceFenceSourceVersion()) != current + 1L)) {
          throw new SourceEvidenceUnavailableException();
        }
        latestLegacyAccountEvent = event instanceof AccountEvent account ? account : null;
        latestCutoff =
            event instanceof AccountEvent account ? account.accountSecurityCutoff() : null;
      } else if (scope.kind() == ScopeKind.ACCOUNT) {
        final RetainedEventEvidence receiptEvidence;
        try {
          receiptEvidence =
              closedEventReadback().requireRetainedEvent(sourceAccount, stored, authority);
        } catch (RuntimeException missingOrInvalidReceipt) {
          throw new SourceEvidenceUnavailableException();
        }
        if (!streamKey.equals(receiptEvidence.outboxStreamKey())
            || receiptEvidence.outboxSequence() != current
            || receiptEvidence.accountAuthorityGeneration() != current + 1L
            || receiptEvidence.sourceVersion() != current + 1L
            || receiptEvidence.issuanceFence() != current + 1L
            || receiptEvidence.issuanceFenceSourceVersion() != current + 1L) {
          throw new SourceEvidenceUnavailableException();
        }
        var typedCutoff = receiptEvidence.accountSecurityCutoff();
        latestCutoff =
            new AccountSecurityCutoff(
                typedCutoff.accountAuthorityGeneration(),
                typedCutoff.outboxStreamKey(),
                typedCutoff.outboxSequence());
        latestLegacyAccountEvent = null;
      } else {
        throw new SourceEvidenceUnavailableException();
      }
      latest = stored;
    }
    if (latest == null
        || latest.outboxSequence() != sequence
        || authority.generation() != sequence + 1L
        || authority.sourceVersion() != sequence + 1L
        || (scope.kind() == ScopeKind.ACCOUNT
            && (fence == null
                || fence.value() != sequence + 1L
                || fence.sourceVersion() != sequence + 1L))) {
      throw new SourceEvidenceUnavailableException();
    }
    return new VerifiedSourceHistory(
        latest, Optional.ofNullable(latestLegacyAccountEvent), Optional.ofNullable(latestCutoff));
  }

  private void updateSourceHead(
      CurrentSourceEvidence before,
      SourceEvent event,
      ScopeState advanced,
      IssuanceFence advancedFence) {
    AccountSecurityCutoff cutoff =
        event instanceof AccountEvent account ? account.accountSecurityCutoff() : null;
    updateSourceHead(
        before,
        event.outboxSequence(),
        event.eventId(),
        event.eventDigest(),
        cutoff,
        advanced,
        advancedFence);
  }

  private void updateSourceHead(
      CurrentSourceEvidence before,
      Event event,
      AccountSecurityCutoff cutoff,
      ScopeState advanced,
      IssuanceFence advancedFence) {
    updateSourceHead(
        before,
        Long.toString(event.outboxSequence()),
        event.eventId(),
        event.eventDigest(),
        cutoff,
        advanced,
        advancedFence);
  }

  private void updateSourceHead(
      CurrentSourceEvidence before,
      String sequenceText,
      String eventId,
      String eventDigest,
      AccountSecurityCutoff cutoff,
      ScopeState advanced,
      IssuanceFence advancedFence) {
    Record changed =
        dsl.fetchOne(
            "UPDATE "
                + SOURCE_TABLE
                + " SET current_generation = ?, current_source_version = ?, "
                + "current_issuance_fence = ?, current_issuance_fence_source_version = ?, "
                + "last_outbox_sequence = ?, last_event_id = ?, last_event_digest = ?, "
                + "cutoff_generation = ?, cutoff_stream_key = ?, cutoff_sequence = ? "
                + "WHERE outbox_stream_key = ? AND current_generation = ? "
                + "AND current_source_version = ? AND last_outbox_sequence = ? "
                + "AND current_issuance_fence IS NOT DISTINCT FROM ? "
                + "AND current_issuance_fence_source_version IS NOT DISTINCT FROM ? "
                + "RETURNING current_generation",
            advanced.generation(),
            advanced.sourceVersion(),
            advancedFence == null ? null : advancedFence.value(),
            advancedFence == null ? null : advancedFence.sourceVersion(),
            Long.parseLong(sequenceText),
            eventId,
            eventDigest,
            cutoff == null ? null : Long.parseLong(cutoff.accountAuthorityGeneration()),
            cutoff == null ? null : cutoff.outboxStreamKey(),
            cutoff == null ? null : Long.parseLong(cutoff.outboxSequence()),
            before.checkpoint().outboxStreamKey(),
            before.generation(),
            before.sourceVersion(),
            before.checkpoint().sequence(),
            before.issuanceFence() == null ? null : before.issuanceFence().value(),
            before.issuanceFence() == null ? null : before.issuanceFence().sourceVersion());
    if (changed == null || positive(changed, "current_generation") != advanced.generation()) {
      throw new SourceEvidenceUnavailableException();
    }
  }

  private SourceEvent verifyStoredEvent(Event event, AuthorityScope scope, ScopeState authority) {
    final SourceEvent verified;
    try {
      verified =
          AccountAuthoritySourceEventV1Codec.verify(
              new String(event.payload(), StandardCharsets.UTF_8));
    } catch (RuntimeException malformed) {
      throw new SourceEvidenceUnavailableException();
    }
    if (!event.eventId().equals(verified.eventId())
        || !event.requestId().equals(verified.requestId())
        || !event.outboxStreamKey().equals(verified.outboxStreamKey())
        || event.outboxSequence() != Long.parseLong(verified.outboxSequence())
        || !event.eventDigest().equals(verified.eventDigest())
        || authority.generation() != Long.parseLong(eventGeneration(verified))
        || authority.sourceVersion() != Long.parseLong(eventSourceVersion(verified))) {
      throw new SourceEvidenceUnavailableException();
    }
    if (scope.kind() == ScopeKind.ISSUER
        && !(verified instanceof IssuerEvent issuer
            && issuer.issuerId().equals(scope.issuerId()))) {
      throw new SourceEvidenceUnavailableException();
    }
    if (scope.kind() == ScopeKind.ACCOUNT
        && !(verified instanceof AccountEvent account
            && account.accountId().equals(scope.accountId().toString()))) {
      throw new SourceEvidenceUnavailableException();
    }
    return verified;
  }

  private static AccountEvent verifyAccountEvent(
      Event event, AuthorityScope scope, ScopeState state) {
    SourceEvent verified =
        AccountAuthoritySourceEventV1Codec.verify(
            new String(event.payload(), StandardCharsets.UTF_8));
    if (!(verified instanceof AccountEvent account)
        || !account.accountId().equals(scope.accountId().toString())
        || !event.eventId().equals(account.eventId())
        || !event.requestId().equals(account.requestId())
        || !event.outboxStreamKey().equals(account.outboxStreamKey())
        || event.outboxSequence() != Long.parseLong(account.outboxSequence())
        || !event.eventDigest().equals(account.eventDigest())
        || state.generation() != Long.parseLong(account.accountAuthorityGeneration())
        || state.sourceVersion() != Long.parseLong(account.sourceVersion())) {
      throw new SourceEvidenceUnavailableException();
    }
    return account;
  }

  private static IssuerEvent verifyIssuerEvent(
      Event event, AuthorityScope scope, ScopeState state) {
    SourceEvent verified =
        AccountAuthoritySourceEventV1Codec.verify(
            new String(event.payload(), StandardCharsets.UTF_8));
    if (!(verified instanceof IssuerEvent issuer)
        || !issuer.issuerId().equals(scope.issuerId())
        || !event.eventId().equals(issuer.eventId())
        || !event.requestId().equals(issuer.requestId())
        || !event.outboxStreamKey().equals(issuer.outboxStreamKey())
        || event.outboxSequence() != Long.parseLong(issuer.outboxSequence())
        || !event.eventDigest().equals(issuer.eventDigest())
        || state.generation() != Long.parseLong(issuer.issuerAuthGeneration())
        || state.sourceVersion() != Long.parseLong(issuer.sourceVersion())) {
      throw new SourceEvidenceUnavailableException();
    }
    return issuer;
  }

  private long requireFreshAccountReadback(AccountRepository.FreshAccountInsert proof) {
    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id, "
                + "account_repository_insert_transaction_id, txid_current() AS current_xid "
                + "FROM accounts WHERE id = ? AND account_uuid = ? FOR UPDATE",
            proof.accountId(),
            proof.accountUuid());
    if (account == null
        || positive(account, "id") != proof.accountId()
        || !proof.accountUuid().equals(account.get("account_uuid", UUID.class))
        || positive(account, "account_uuid_source_numeric_id") != proof.accountId()
        || !AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            .name()
            .equals(account.get("account_uuid_provenance", String.class))
        || positive(account, "account_repository_insert_transaction_id")
            != positive(account, "current_xid")) {
      throw new SourceEvidenceUnavailableException();
    }
    return positive(account, "account_repository_insert_transaction_id");
  }

  private void requireAccountSourceIdentity(Record source, AuthorityScope scope) {
    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id, "
                + "account_repository_insert_transaction_id, password_hash, email_verified, "
                + "login_auth_modes, role, lifecycle_state "
                + "FROM accounts WHERE account_uuid = ? FOR SHARE",
            scope.accountId());
    if (account == null
        || positive(account, "id") != positive(source, "account_source_numeric_id")
        || positive(account, "account_uuid_source_numeric_id")
            != positive(source, "account_source_numeric_id")
        || positive(account, "account_repository_insert_transaction_id")
            != positive(source, "account_repository_insert_transaction_id")
        || positive(source, "initialization_transaction_id")
            != positive(source, "account_repository_insert_transaction_id")
        || !scope.accountId().equals(account.get("account_uuid", UUID.class))
        || !AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            .name()
            .equals(account.get("account_uuid_provenance", String.class))) {
      throw new SourceEvidenceUnavailableException();
    }
  }

  private AccountState readAccountState(UUID accountUuid) {
    Record account =
        dsl.fetchOne(
            "SELECT email_verified, login_auth_modes, role, lifecycle_state "
                + "FROM accounts WHERE account_uuid = ? FOR SHARE",
            accountUuid);
    if (account == null) throw new SourceEvidenceUnavailableException();
    return accountState(account);
  }

  private Account readAccountIdentityForClosedReadback(UUID accountUuid) {
    Record row =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id, "
                + "password_hash FROM accounts WHERE account_uuid = ? FOR SHARE",
            accountUuid);
    if (row == null) throw new SourceEvidenceUnavailableException();
    Account account = new Account();
    account.setId(positive(row, "id"));
    account.setAccountUuid(row.get("account_uuid", UUID.class));
    account.setAccountUuidProvenance(
        AccountIdentityProvenance.fromStorageValue(
            row.get("account_uuid_provenance", String.class)));
    account.setAccountUuidSourceNumericId(positive(row, "account_uuid_source_numeric_id"));
    account.setPasswordHash(row.get("password_hash", String.class));
    return account;
  }

  private AccountAuthoritySourceEventReadback closedEventReadback() {
    // Construct at use, not in the repository constructor: the security-state storage owner
    // itself depends on this source owner. Every invocation still requires every exact receipt.
    return new AccountAuthoritySourceEventReadback(
        outbox,
        new AccountPasswordResetOperationRepository(dsl),
        new AccountLogoutAllOperationRepository(dsl),
        new AccountSecurityStateOperationRepository(dsl));
  }

  private void requireAccountReadback(
      UUID accountUuid, long accountId, AccountRepository.AccountAuthorityState expected) {
    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id, "
                + "password_hash, email_verified, login_auth_modes, role, lifecycle_state "
                + "FROM accounts WHERE id = ? AND account_uuid = ? FOR UPDATE",
            accountId,
            accountUuid);
    if (account == null
        || positive(account, "account_uuid_source_numeric_id") != accountId
        || !AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            .name()
            .equals(account.get("account_uuid_provenance", String.class))
        || !Objects.equals(expected.passwordHash(), account.get("password_hash", String.class))
        || !expected.toEventState().equals(accountState(account))) {
      throw new SourceEvidenceUnavailableException();
    }
  }

  private static AccountState accountState(Record row) {
    String storedModes = row.get("login_auth_modes", String.class);
    List<String> loginModes =
        AccountLoginAuthModes.read(storedModes).stream().map(Enum::name).sorted().toList();
    String lifecycle = row.get("lifecycle_state", String.class);
    if (lifecycle == null) throw new SourceEvidenceUnavailableException();
    return new AccountState(
        Boolean.TRUE.equals(row.get("email_verified", Boolean.class)),
        loginModes,
        row.get("role", String.class),
        lifecycle.trim().toUpperCase(java.util.Locale.ROOT));
  }

  private static List<String> mutationKinds(
      AccountRepository.AccountAuthorityState before,
      AccountRepository.AccountAuthorityState after) {
    List<String> result = new ArrayList<>();
    if (!Objects.equals(before.passwordHash(), after.passwordHash())) result.add("PASSWORD_RESET");
    if (before.emailVerified() != after.emailVerified()) {
      result.add("EMAIL_LOGIN_ELIGIBILITY_CHANGED");
    }
    if (!Objects.equals(before.loginAuthModes(), after.loginAuthModes())) {
      result.add("LOGIN_AUTH_MODES_CHANGED");
    }
    if (!Objects.equals(before.globalRole(), after.globalRole())) result.add("GLOBAL_ROLE_CHANGED");
    if (!Objects.equals(before.lifecycleState(), after.lifecycleState())) {
      result.add("LIFECYCLE_STATE_CHANGED");
    }
    result.sort(String::compareTo);
    return List.copyOf(result);
  }

  private void requireSourceScope(Record source, AuthorityScope expected) {
    if (!expected.kind().name().equals(source.get("scope_kind", String.class))
        || !Objects.equals(expected.issuerId(), source.get("issuer_id", String.class))
        || !Objects.equals(expected.accountId(), source.get("account_uuid", UUID.class))
        || source.get("baseline_generation", Long.class) == null
        || source.get("baseline_generation", Long.class) != 1L
        || source.get("baseline_source_version", Long.class) == null
        || source.get("baseline_source_version", Long.class) != 1L
        || !Objects.equals(
            expected.kind() == ScopeKind.ISSUER
                ? "ISSUER_SCOPE_INSERT"
                : "ACCOUNT_REPOSITORY_INSERT",
            source.get("initialization_provenance", String.class))) {
      throw new SourceEvidenceUnavailableException();
    }
    if (expected.kind() == ScopeKind.ACCOUNT) {
      if (positive(source, "initialization_transaction_id") <= 0L
          || positive(source, "baseline_issuance_fence") != 1L
          || positive(source, "account_source_numeric_id") <= 0L
          || positive(source, "account_repository_insert_transaction_id") <= 0L
          || !AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
              .name()
              .equals(source.get("account_uuid_provenance", String.class))) {
        throw new SourceEvidenceUnavailableException();
      }
    } else if (positive(source, "initialization_transaction_id") <= 0L
        || source.get("account_source_numeric_id", Long.class) != null
        || source.get("account_repository_insert_transaction_id", Long.class) != null
        || source.get("account_uuid_provenance", String.class) != null) {
      throw new SourceEvidenceUnavailableException();
    }
  }

  private static String streamKey(AuthorityScope scope) {
    return switch (scope.kind()) {
      case ISSUER -> STREAM_PREFIX + "issuer/" + scope.issuerId();
      case ACCOUNT -> STREAM_PREFIX + "account/" + scope.accountId();
      default -> throw new IllegalArgumentException("Issuer or Account source scope is required");
    };
  }

  private static long expectedSequence(AccountEvent event) {
    return Long.parseLong(event.outboxSequence());
  }

  private static long expectedSequence(IssuerEvent event) {
    return Long.parseLong(event.outboxSequence());
  }

  private static SourceCheckpoint checkpoint(AccountEvent event) {
    return new SourceCheckpoint(
        event.outboxStreamKey(),
        Long.parseLong(event.outboxSequence()),
        Optional.of(event.eventId()),
        Optional.of(event.eventDigest()));
  }

  private static void requireExactAppendedEvent(
      Event appended, SourceEvent expected, String streamKey, String requestId) {
    if (!appended.outboxStreamKey().equals(streamKey)
        || !appended.requestId().equals(requestId)
        || appended.outboxSequence() != Long.parseLong(expected.outboxSequence())
        || !appended.eventId().equals(expected.eventId())
        || !appended.eventDigest().equals(expected.eventDigest())
        || !java.util.Arrays.equals(appended.payload(), expected.canonicalJsonUtf8())) {
      throw new SourceEvidenceUnavailableException();
    }
  }

  private static String eventGeneration(SourceEvent event) {
    return event instanceof IssuerEvent issuer
        ? issuer.issuerAuthGeneration()
        : ((AccountEvent) event).accountAuthorityGeneration();
  }

  private static String eventSourceVersion(SourceEvent event) {
    return event instanceof IssuerEvent issuer
        ? issuer.sourceVersion()
        : ((AccountEvent) event).sourceVersion();
  }

  private static long positive(Record record, String field) {
    Long value = record.get(field, Long.class);
    if (value == null || value <= 0L) throw new SourceEvidenceUnavailableException();
    return value;
  }

  private static long nonnegative(Record record, String field) {
    Long value = record.get(field, Long.class);
    if (value == null || value < 0L) throw new SourceEvidenceUnavailableException();
    return value;
  }

  private static String requireBoundedText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 512 || !value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return value;
  }

  record AccountIdentitySource(
      long accountSourceNumericId, long accountRepositoryInsertTransactionId) {}

  public record SourceCheckpoint(
      String outboxStreamKey,
      long sequence,
      Optional<String> sourceEventId,
      Optional<String> sourceEventDigest) {
    public SourceCheckpoint {
      Objects.requireNonNull(outboxStreamKey);
      Objects.requireNonNull(sourceEventId);
      Objects.requireNonNull(sourceEventDigest);
      if (sequence < 0
          || (sequence == 0 && (sourceEventId.isPresent() || sourceEventDigest.isPresent()))
          || (sequence > 0 && (sourceEventId.isEmpty() || sourceEventDigest.isEmpty()))) {
        throw new IllegalArgumentException("Account source checkpoint shape is invalid");
      }
    }
  }

  private record VerifiedSourceHistory(
      Event latestEvent,
      Optional<AccountEvent> latestLegacyAccountEvent,
      Optional<AccountSecurityCutoff> accountSecurityCutoff) {
    private VerifiedSourceHistory {
      Objects.requireNonNull(latestEvent);
      Objects.requireNonNull(latestLegacyAccountEvent);
      Objects.requireNonNull(accountSecurityCutoff);
    }
  }

  public record CurrentSourceEvidence(
      AuthorityScope scope,
      long generation,
      long sourceVersion,
      IssuanceFence issuanceFence,
      SourceCheckpoint checkpoint,
      Optional<AccountSecurityCutoff> accountSecurityCutoff,
      String initializationProvenance,
      Long accountSourceNumericId,
      String accountUuidProvenance,
      long initializationTransactionId,
      Long accountRepositoryInsertTransactionId) {
    public CurrentSourceEvidence {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(checkpoint);
      Objects.requireNonNull(accountSecurityCutoff);
      if (generation <= 0 || sourceVersion <= 0 || initializationTransactionId <= 0L) {
        throw new IllegalArgumentException("Account source current values must be positive");
      }
      String expectedStreamKey =
          switch (scope.kind()) {
            case ISSUER -> STREAM_PREFIX + "issuer/" + scope.issuerId();
            case ACCOUNT -> STREAM_PREFIX + "account/" + scope.accountId();
            default ->
                throw new IllegalArgumentException(
                    "Account source evidence is limited to issuer and Account");
          };
      if (generation != sourceVersion
          || checkpoint.sequence() != generation - 1L
          || !expectedStreamKey.equals(checkpoint.outboxStreamKey())) {
        throw new IllegalArgumentException(
            "Account source checkpoint does not match its owner head");
      }
      if (scope.kind() == ScopeKind.ISSUER) {
        if (issuanceFence != null
            || accountSourceNumericId != null
            || accountUuidProvenance != null
            || accountRepositoryInsertTransactionId != null
            || accountSecurityCutoff.isPresent()
            || !"ISSUER_SCOPE_INSERT".equals(initializationProvenance)) {
          throw new IllegalArgumentException("Issuer source evidence identity is invalid");
        }
      } else if (scope.kind() == ScopeKind.ACCOUNT) {
        if (issuanceFence == null
            || !scope.accountId().equals(issuanceFence.accountId())
            || accountSourceNumericId == null
            || accountSourceNumericId <= 0L
            || !AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
                .name()
                .equals(accountUuidProvenance)
            || accountRepositoryInsertTransactionId == null
            || accountRepositoryInsertTransactionId <= 0L
            || initializationTransactionId != accountRepositoryInsertTransactionId
            || issuanceFence.value() != generation
            || issuanceFence.sourceVersion() != sourceVersion
            || (checkpoint.sequence() == 0L && accountSecurityCutoff.isPresent())
            || (checkpoint.sequence() > 0L
                && (accountSecurityCutoff.isEmpty()
                    || !Long.toString(generation)
                        .equals(accountSecurityCutoff.orElseThrow().accountAuthorityGeneration())
                    || !checkpoint
                        .outboxStreamKey()
                        .equals(accountSecurityCutoff.orElseThrow().outboxStreamKey())
                    || !Long.toString(checkpoint.sequence())
                        .equals(accountSecurityCutoff.orElseThrow().outboxSequence())))
            || !"ACCOUNT_REPOSITORY_INSERT".equals(initializationProvenance)) {
          throw new IllegalArgumentException("Account source evidence identity is invalid");
        }
      }
    }
  }

  public record CanonicalIssuerSourceSnapshot(
      CurrentSourceEvidence source, Optional<Event> latestEvent) {
    public CanonicalIssuerSourceSnapshot {
      Objects.requireNonNull(source);
      Objects.requireNonNull(latestEvent);
      if (source.scope().kind() != ScopeKind.ISSUER
          || (source.checkpoint().sequence() == 0L) != latestEvent.isEmpty()) {
        throw new SourceEvidenceUnavailableException();
      }
    }
  }

  public record IssuerAccountSourceSnapshot(
      CurrentSourceEvidence issuer,
      CurrentSourceEvidence account,
      IssuanceFence issuanceFence,
      AccountGenerationProjection canonicalAccountProjection,
      IssuerGenerationProjection canonicalIssuerProjection) {
    public IssuerAccountSourceSnapshot(
        CurrentSourceEvidence issuer,
        CurrentSourceEvidence account,
        IssuanceFence issuanceFence,
        AccountGenerationProjection canonicalAccountProjection) {
      this(
          issuer,
          account,
          issuanceFence,
          canonicalAccountProjection,
          issuer != null && issuer.checkpoint().sequence() == 0L
              ? IssuerGenerationProjection.fromSource(issuer)
              : null);
    }

    /**
     * Preserves lightweight source-state fixtures while refusing to invent advanced event bytes.
     * Production snapshots are created by {@link #readCurrentIssuerAccountSources} with the exact
     * receipt-verified latest event from that same composite owner read.
     */
    public IssuerAccountSourceSnapshot(
        CurrentSourceEvidence issuer, CurrentSourceEvidence account, IssuanceFence issuanceFence) {
      this(
          issuer,
          account,
          issuanceFence,
          account != null && account.checkpoint().sequence() == 0L
              ? new AccountGenerationProjection(
                  account.scope().accountId().toString(),
                  Long.toString(account.generation()),
                  Long.toString(account.sourceVersion()),
                  account.checkpoint().outboxStreamKey(),
                  "0",
                  Optional.empty())
              : null);
    }

    public IssuerAccountSourceSnapshot {
      Objects.requireNonNull(issuer);
      Objects.requireNonNull(account);
      Objects.requireNonNull(issuanceFence);
      if (issuer.scope().kind() != ScopeKind.ISSUER
          || account.scope().kind() != ScopeKind.ACCOUNT
          || account.issuanceFence() == null
          || !account.issuanceFence().equals(issuanceFence)) {
        throw new IllegalArgumentException("Issuer/Account source snapshot scope is invalid");
      }
      if (canonicalAccountProjection != null
          && (!account.scope().accountId().toString().equals(canonicalAccountProjection.accountId())
              || !Long.toString(account.generation())
                  .equals(canonicalAccountProjection.accountAuthorityGeneration())
              || !Long.toString(account.sourceVersion())
                  .equals(canonicalAccountProjection.sourceVersion())
              || !account
                  .checkpoint()
                  .outboxStreamKey()
                  .equals(canonicalAccountProjection.outboxStreamKey())
              || !Long.toString(account.checkpoint().sequence())
                  .equals(canonicalAccountProjection.outboxSequence()))) {
        throw new IllegalArgumentException(
            "Canonical Account source projection differs from its snapshot");
      }
      if (canonicalIssuerProjection != null
          && (!issuer.scope().issuerId().equals(canonicalIssuerProjection.issuerId())
              || !Long.toString(issuer.generation())
                  .equals(canonicalIssuerProjection.issuerAuthGeneration())
              || !Long.toString(issuer.sourceVersion())
                  .equals(canonicalIssuerProjection.sourceVersion())
              || !issuer
                  .checkpoint()
                  .outboxStreamKey()
                  .equals(canonicalIssuerProjection.outboxStreamKey())
              || !Long.toString(issuer.checkpoint().sequence())
                  .equals(canonicalIssuerProjection.lastAppliedSourceOutboxSequence())
              || !issuer
                  .checkpoint()
                  .sourceEventId()
                  .equals(canonicalIssuerProjection.lastAppliedSourceEventId())
              || !issuer
                  .checkpoint()
                  .sourceEventDigest()
                  .equals(canonicalIssuerProjection.lastAppliedSourceEventDigest()))) {
        throw new IllegalArgumentException(
            "Canonical issuer source projection differs from its snapshot");
      }
    }
  }

  public static final class SourceEvidenceUnavailableException extends IllegalStateException {
    public SourceEvidenceUnavailableException() {
      super("Account authority source evidence is unavailable or inconsistent");
    }
  }
}
