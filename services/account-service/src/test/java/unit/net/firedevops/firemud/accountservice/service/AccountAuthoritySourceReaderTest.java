package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountAuthoritySourceReaderTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("c980fa44-619e-4ca4-8ad6-75b0538a66a3");
  private static final String STREAM_KEY = "account:auth-authority:v1:account/" + ACCOUNT_UUID;

  @Test
  void currentReadLocksAndRechecksThePersistedAssociationBeforeReadingAuthority() {
    Collaborators collaborators = collaborators();
    Account account = account(11L, ACCOUNT_UUID);
    ScopeState state = state(1L, 1L, 7L, 4L);
    when(collaborators.accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
    when(collaborators.accounts.findByIdForUpdate(11L)).thenReturn(Optional.of(account));
    when(collaborators.generations.read(AuthorityScope.account(ACCOUNT_UUID))).thenReturn(state);
    when(collaborators.sourceReadback.requireCurrentLatest(account, state))
        .thenReturn(
            new AccountAuthoritySourceEventReadback.LatestSourceSnapshot(0L, Optional.empty()));

    AccountSourceSnapshot snapshot = newReader(collaborators).readCurrent(ACCOUNT_UUID);

    assertThat(snapshot.accountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(snapshot.sourceState()).isEqualTo(state);
    assertThat(snapshot.outboxStreamKey()).isEqualTo(STREAM_KEY);
    assertThat(snapshot.outboxSequence()).isZero();
    assertThat(snapshot.latestEvent()).isEmpty();
    var order =
        inOrder(collaborators.accounts, collaborators.generations, collaborators.sourceReadback);
    order.verify(collaborators.accounts).findByAccountUuid(ACCOUNT_UUID);
    order.verify(collaborators.accounts).findByIdForUpdate(11L);
    order.verify(collaborators.generations).read(AuthorityScope.account(ACCOUNT_UUID));
    order.verify(collaborators.sourceReadback).requireCurrentLatest(account, state);
    assertTransactionDefinitionIsWritableReadCommitted(collaborators.transactionManager);
  }

  @Test
  void currentSequenceZeroAllowsAnAdvancedPositiveFenceAtTheOriginalSourceBaseline() {
    Collaborators collaborators = collaborators();
    Account account = account(11L, ACCOUNT_UUID);
    ScopeState baseline = state(1L, 1L, 7L, 4L);
    when(collaborators.accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
    when(collaborators.accounts.findByIdForUpdate(11L)).thenReturn(Optional.of(account));
    when(collaborators.generations.read(AuthorityScope.account(ACCOUNT_UUID))).thenReturn(baseline);
    when(collaborators.sourceReadback.requireCurrentLatest(account, baseline))
        .thenReturn(
            new AccountAuthoritySourceEventReadback.LatestSourceSnapshot(0L, Optional.empty()));

    AccountSourceSnapshot snapshot = newReader(collaborators).readCurrent(ACCOUNT_UUID);

    assertThat(snapshot.sourceState().generation()).isEqualTo(1L);
    assertThat(snapshot.sourceState().sourceVersion()).isEqualTo(1L);
    assertThat(snapshot.sourceState().issuanceFence())
        .isEqualTo(new IssuanceFence(ACCOUNT_UUID, 7L, 4L));
    assertThat(snapshot.outboxSequence()).isZero();
  }

  @Test
  void selectedReadReturnsTheExactHistoricalEventWithTheCurrentSnapshot() {
    Collaborators collaborators = collaborators();
    Account account = account(11L, ACCOUNT_UUID);
    ScopeState state = state(3L, 3L, 8L, 7L);
    Event latest = event(2L);
    Event selected = event(1L);
    when(collaborators.accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
    when(collaborators.accounts.findByIdForUpdate(11L)).thenReturn(Optional.of(account));
    when(collaborators.generations.read(AuthorityScope.account(ACCOUNT_UUID))).thenReturn(state);
    when(collaborators.sourceReadback.requireCurrentLatest(account, state))
        .thenReturn(
            new AccountAuthoritySourceEventReadback.LatestSourceSnapshot(2L, Optional.of(latest)));
    when(collaborators.outbox.findEvent(STREAM_KEY, 1L)).thenReturn(Optional.of(selected));

    AccountSourceEventReadback result =
        newReader(collaborators).readCommittedEvent(ACCOUNT_UUID, 1L);

    assertThat(result.currentSnapshot().outboxSequence()).isEqualTo(2L);
    assertThat(result.currentSnapshot().latestEvent()).contains(latest);
    assertThat(result.requestedEvent()).isEqualTo(selected);
    var order = inOrder(collaborators.sourceReadback, collaborators.outbox);
    order.verify(collaborators.sourceReadback).requireCurrentLatest(account, state);
    order.verify(collaborators.outbox).findEvent(STREAM_KEY, 1L);
    order.verify(collaborators.sourceReadback).requireRetainedEvent(account, selected, state);
  }

  @Test
  void resultRecordsRejectLatestOrSelectedEventsWhosePayloadLeadsCurrentCounters() {
    ScopeState currentState = state(3L, 3L, 8L, 7L);
    Event latest = event(2L);
    AccountSourceSnapshot current =
        new AccountSourceSnapshot(ACCOUNT_UUID, currentState, STREAM_KEY, 2L, Optional.of(latest));

    assertThatThrownBy(
            () ->
                new AccountSourceSnapshot(
                    ACCOUNT_UUID, currentState, STREAM_KEY, 2L, Optional.of(event(2L, 2L, 3L))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("contradict");
    assertThatThrownBy(() -> new AccountSourceEventReadback(current, event(1L, 4L, 2L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lead the current snapshot");
  }

  @Test
  void selectedReadRejectsAnEventAheadOfTheCurrentCheckpointBeforeLookingItUp() {
    Collaborators collaborators = collaborators();
    Account account = account(11L, ACCOUNT_UUID);
    ScopeState state = state(2L, 2L, 5L, 4L);
    when(collaborators.accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
    when(collaborators.accounts.findByIdForUpdate(11L)).thenReturn(Optional.of(account));
    when(collaborators.generations.read(AuthorityScope.account(ACCOUNT_UUID))).thenReturn(state);
    when(collaborators.sourceReadback.requireCurrentLatest(account, state))
        .thenReturn(
            new AccountAuthoritySourceEventReadback.LatestSourceSnapshot(
                1L, Optional.of(event(1L))));

    assertThatThrownBy(() -> newReader(collaborators).readCommittedEvent(ACCOUNT_UUID, 2L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ahead of its current checkpoint");

    verify(collaborators.outbox, never()).findEvent(any(String.class), anyLong());
  }

  @Test
  void selectedReadRejectsMissingRetainedEventEvidence() {
    Collaborators collaborators = collaborators();
    Account account = account(11L, ACCOUNT_UUID);
    ScopeState state = state(2L, 2L, 5L, 4L);
    when(collaborators.accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
    when(collaborators.accounts.findByIdForUpdate(11L)).thenReturn(Optional.of(account));
    when(collaborators.generations.read(AuthorityScope.account(ACCOUNT_UUID))).thenReturn(state);
    when(collaborators.sourceReadback.requireCurrentLatest(account, state))
        .thenReturn(
            new AccountAuthoritySourceEventReadback.LatestSourceSnapshot(
                1L, Optional.of(event(1L))));
    when(collaborators.outbox.findEvent(STREAM_KEY, 1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> newReader(collaborators).readCommittedEvent(ACCOUNT_UUID, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Selected Account source event is missing");

    verify(collaborators.sourceReadback, never())
        .requireRetainedEvent(any(Account.class), any(Event.class), any(ScopeState.class));
  }

  @Test
  void nilIdentityAndAmbientTransactionsFailBeforeRepositoryAccess() {
    Collaborators collaborators = collaborators();
    AccountAuthoritySourceReader reader = newReader(collaborators);

    assertThatThrownBy(() -> reader.readCurrent(new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> reader.readCommittedEvent(ACCOUNT_UUID, 0L))
        .isInstanceOf(IllegalArgumentException.class);

    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> reader.readCurrent(ACCOUNT_UUID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("without an ambient transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
    verifyNoInteractions(collaborators.accounts, collaborators.generations, collaborators.outbox);
    verify(collaborators.transactionManager, never())
        .getTransaction(any(TransactionDefinition.class));
  }

  @Test
  void lockedAccountMustRetainTheExactUuidAndProvenance() {
    Collaborators collaborators = collaborators();
    Account associated = account(11L, ACCOUNT_UUID);
    Account changed = account(12L, ACCOUNT_UUID);
    when(collaborators.accounts.findByAccountUuid(ACCOUNT_UUID))
        .thenReturn(Optional.of(associated));
    when(collaborators.accounts.findByIdForUpdate(11L)).thenReturn(Optional.of(changed));

    assertThatThrownBy(() -> newReader(collaborators).readCurrent(ACCOUNT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact UUID association or provenance");

    verifyNoInteractions(
        collaborators.generations, collaborators.outbox, collaborators.sourceReadback);
  }

  private void assertTransactionDefinitionIsWritableReadCommitted(
      PlatformTransactionManager transactionManager) {
    var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definition.capture());
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(definition.getValue().isReadOnly()).isFalse();
  }

  private AccountAuthoritySourceReader newReader(Collaborators collaborators) {
    return new AccountAuthoritySourceReader(
        collaborators.accounts,
        collaborators.generations,
        collaborators.outbox,
        collaborators.sourceReadback,
        collaborators.transactionManager);
  }

  private Collaborators collaborators() {
    AccountRepository accounts = mock(AccountRepository.class);
    AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountAuthoritySourceEventReadback sourceReadback =
        mock(AccountAuthoritySourceEventReadback.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
    return new Collaborators(accounts, generations, outbox, sourceReadback, transactionManager);
  }

  private Account account(long id, UUID uuid) {
    Account account = new Account();
    account.setId(id);
    account.setAccountUuid(uuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    account.setAccountUuidSourceNumericId(id);
    account.setPasswordHash("credential-verifier-is-not-returned");
    return account;
  }

  private ScopeState state(
      long generation, long sourceVersion, long fence, long fenceSourceVersion) {
    return new ScopeState(
        AuthorityScope.account(ACCOUNT_UUID),
        generation,
        sourceVersion,
        new IssuanceFence(ACCOUNT_UUID, fence, fenceSourceVersion));
  }

  private Event event(long sequence) {
    long generation = sequence + 1L;
    return event(sequence, generation, generation);
  }

  private Event event(long sequence, long generation, long sourceVersion) {
    UUID requestId =
        UUID.nameUUIDFromBytes(("request-" + sequence).getBytes(StandardCharsets.UTF_8));
    String eventId = "account-logout-all-event-v1:" + requestId;
    String generationCounter = Long.toString(generation);
    String sourceVersionCounter = Long.toString(sourceVersion);
    var sealed =
        AccountLogoutAllAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", eventId),
                Map.entry("requestId", requestId.toString()),
                Map.entry("accountId", ACCOUNT_UUID.toString()),
                Map.entry("sourceScope", "account/" + ACCOUNT_UUID),
                Map.entry("outboxStreamKey", STREAM_KEY),
                Map.entry("outboxSequence", Long.toString(sequence)),
                Map.entry("accountAuthorityGeneration", generationCounter),
                Map.entry("sourceVersion", sourceVersionCounter),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", generationCounter,
                        "outboxStreamKey", STREAM_KEY,
                        "outboxSequence", Long.toString(sequence)))));
    return new Event(
        STREAM_KEY,
        requestId.toString(),
        sequence,
        eventId,
        sealed.eventDigest(),
        sealed.canonicalJsonUtf8());
  }

  private record Collaborators(
      AccountRepository accounts,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox,
      AccountAuthoritySourceEventReadback sourceReadback,
      PlatformTransactionManager transactionManager) {}
}
