package net.firedevops.firemud.accountservice.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired owner-local reader for current and selected historical Account authority source events.
 * Returned source evidence is not recipient authority.
 */
public final class AccountAuthoritySourceReader {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String ACCOUNT_STREAM_PREFIX = "account:auth-authority:v1:account/";

  private final AccountRepository accountRepository;
  private final AccountAuthorityGenerationRepository generationRepository;
  private final AccountAuthorityOutboxRepository outboxRepository;
  private final AccountAuthoritySourceEventReadback sourceEventReadback;
  private final TransactionTemplate ownerTransaction;

  public AccountAuthoritySourceReader(
      AccountRepository accountRepository,
      AccountAuthorityGenerationRepository generationRepository,
      AccountAuthorityOutboxRepository outboxRepository,
      AccountAuthoritySourceEventReadback sourceEventReadback,
      PlatformTransactionManager transactionManager) {
    this.accountRepository =
        Objects.requireNonNull(accountRepository, "Account repository is required");
    this.generationRepository =
        Objects.requireNonNull(generationRepository, "authority generation repository is required");
    this.outboxRepository =
        Objects.requireNonNull(outboxRepository, "authority outbox repository is required");
    this.sourceEventReadback =
        Objects.requireNonNull(sourceEventReadback, "source event readback is required");

    this.ownerTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /** Reads the current positive Account source and its exact latest event, if one exists. */
  public AccountSourceSnapshot readCurrent(UUID accountId) {
    requireCanonicalAccountId(accountId);
    requireNoAmbientTransaction();
    return requireTransactionResult(
        ownerTransaction.execute(
            status -> readCurrentSnapshot(loadLockedAccount(accountId), accountId)));
  }

  /**
   * Reads one positive immutable event from this Account stream together with the current source
   * snapshot that fences its use.
   */
  public AccountSourceEventReadback readCommittedEvent(UUID accountId, long outboxSequence) {
    requireCanonicalAccountId(accountId);
    if (outboxSequence <= 0L) {
      throw new IllegalArgumentException("A positive Account source event sequence is required");
    }
    requireNoAmbientTransaction();
    return requireTransactionResult(
        ownerTransaction.execute(
            status -> {
              Account account = loadLockedAccount(accountId);
              AccountSourceSnapshot current = readCurrentSnapshot(account, accountId);
              if (outboxSequence > current.outboxSequence()) {
                throw new IllegalStateException(
                    "Selected Account source event is ahead of its current checkpoint");
              }
              Event requested =
                  outboxRepository
                      .findEvent(current.outboxStreamKey(), outboxSequence)
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Selected Account source event is missing"));
              if (!current.outboxStreamKey().equals(requested.outboxStreamKey())
                  || requested.outboxSequence() != outboxSequence) {
                throw new IllegalStateException(
                    "Selected Account source event identity changed during readback");
              }
              sourceEventReadback.requireRetainedEvent(account, requested, current.sourceState());
              return new AccountSourceEventReadback(current, requested);
            }));
  }

  private AccountSourceSnapshot readCurrentSnapshot(Account account, UUID accountId) {
    AuthorityScope scope = AuthorityScope.account(accountId);
    ScopeState state = generationRepository.read(scope);
    if (state == null || !scope.equals(state.scope())) {
      throw new IllegalStateException("Current Account authority scope changed during readback");
    }
    AccountAuthoritySourceEventReadback.LatestSourceSnapshot latest =
        sourceEventReadback.requireCurrentLatest(account, state);
    return new AccountSourceSnapshot(
        accountId,
        state,
        accountStreamKey(accountId),
        latest.outboxSequence(),
        latest.latestEvent());
  }

  private Account loadLockedAccount(UUID accountId) {
    Account associated =
        accountRepository
            .findByAccountUuid(accountId)
            .orElseThrow(
                () -> new IllegalStateException("Persisted Account UUID association is missing"));
    requirePersistedAssociation(associated, accountId);

    Account locked =
        accountRepository
            .findByIdForUpdate(associated.getId())
            .orElseThrow(
                () -> new IllegalStateException("Persisted Account source row is missing"));
    requirePersistedAssociation(locked, accountId);
    if (!Objects.equals(associated.getId(), locked.getId())
        || !Objects.equals(associated.getAccountUuid(), locked.getAccountUuid())
        || associated.getAccountUuidProvenance() != locked.getAccountUuidProvenance()
        || !Objects.equals(
            associated.getAccountUuidSourceNumericId(), locked.getAccountUuidSourceNumericId())) {
      throw new IllegalStateException(
          "Locked Account row changed its exact UUID association or provenance");
    }
    return locked;
  }

  private void requirePersistedAssociation(Account account, UUID accountId) {
    AccountIdentityProvenance provenance =
        account == null ? null : account.getAccountUuidProvenance();
    if (account == null
        || account.getId() == null
        || account.getId() <= 0L
        || !accountId.equals(account.getAccountUuid())
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())
        || (provenance != AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            && provenance != AccountIdentityProvenance.ACCOUNT_V29_MIGRATION
            && provenance != AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT)) {
      throw new IllegalStateException("Verified persisted Account association is required");
    }
  }

  private static void requireCanonicalAccountId(UUID accountId) {
    if (accountId == null || NIL_UUID.equals(accountId)) {
      throw new IllegalArgumentException("A canonical non-nil Account UUID is required");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account authority source reader must own its transaction without an ambient transaction");
    }
  }

  private static <T> T requireTransactionResult(T result) {
    if (result == null) {
      throw new IllegalStateException("Account authority source readback returned no result");
    }
    return result;
  }

  private static String accountStreamKey(UUID accountId) {
    return ACCOUNT_STREAM_PREFIX + accountId;
  }

  /** Immutable current owner-local source snapshot; it carries no credential or recipient tuple. */
  public record AccountSourceSnapshot(
      UUID accountId,
      ScopeState sourceState,
      String outboxStreamKey,
      long outboxSequence,
      Optional<Event> latestEvent) {
    public AccountSourceSnapshot {
      requireCanonicalAccountId(accountId);
      Objects.requireNonNull(sourceState, "Account source state is required");
      Objects.requireNonNull(outboxStreamKey, "Account source stream key is required");
      latestEvent = Objects.requireNonNull(latestEvent, "latest source event optional is required");
      AuthorityScope expectedScope = AuthorityScope.account(accountId);
      if (!expectedScope.equals(sourceState.scope())
          || sourceState.generation() <= 0L
          || sourceState.sourceVersion() <= 0L
          || !outboxStreamKey.equals(accountStreamKey(accountId))
          || sourceState.issuanceFence() == null
          || !accountId.equals(sourceState.issuanceFence().accountId())
          || sourceState.issuanceFence().value() <= 0L
          || sourceState.issuanceFence().sourceVersion() <= 0L
          || outboxSequence < 0L
          || (outboxSequence == 0L
              && (sourceState.generation() != 1L
                  || sourceState.sourceVersion() != 1L
                  || latestEvent.isPresent()))
          || (outboxSequence > 0L
              && (sourceState.generation() <= 1L
                  || sourceState.sourceVersion() <= 1L
                  || latestEvent.isEmpty()
                  || latestEvent.orElseThrow().outboxSequence() != outboxSequence
                  || !outboxStreamKey.equals(latestEvent.orElseThrow().outboxStreamKey())))) {
        throw new IllegalArgumentException("Account source snapshot is inconsistent");
      }
      latestEvent.ifPresent(
          event ->
              AccountAuthoritySourceEventReadback.requireEventMatchesSnapshot(
                  event, accountId, sourceState, true));
    }
  }

  /** Immutable selected event paired with the current Account source fence. */
  public record AccountSourceEventReadback(
      AccountSourceSnapshot currentSnapshot, Event requestedEvent) {
    public AccountSourceEventReadback {
      Objects.requireNonNull(currentSnapshot, "current Account source snapshot is required");
      Objects.requireNonNull(requestedEvent, "selected Account source event is required");
      if (requestedEvent.outboxSequence() <= 0L
          || requestedEvent.outboxSequence() > currentSnapshot.outboxSequence()
          || !currentSnapshot.outboxStreamKey().equals(requestedEvent.outboxStreamKey())) {
        throw new IllegalArgumentException("Selected Account source event is inconsistent");
      }
      AccountAuthoritySourceEventReadback.requireEventMatchesSnapshot(
          requestedEvent, currentSnapshot.accountId(), currentSnapshot.sourceState(), false);
    }
  }
}
