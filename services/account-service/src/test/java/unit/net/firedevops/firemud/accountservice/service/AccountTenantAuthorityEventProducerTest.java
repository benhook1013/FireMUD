package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.service.AccountTenantAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountTenantAuthorityEventProducerTest {
  private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_TENANT_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final String STREAM_KEY = "account:auth-authority:v1:tenant/" + TENANT_ID;

  @Test
  void nilTenantAndRequestIdentitiesAreRejectedBeforePersistence() {
    Collaborators collaborators = new Collaborators();
    AccountTenantAuthorityEventProducer producer = newProducer(collaborators);

    assertThatThrownBy(() -> producer.advance(null, UUID.randomUUID(), 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant UUID");
    assertThatThrownBy(() -> producer.readCurrent(new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant UUID");
    assertThatThrownBy(() -> producer.advance(TENANT_ID, new UUID(0L, 0L), 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");
    assertThatThrownBy(() -> producer.advance(TENANT_ID, UUID.randomUUID(), 0L, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
    assertThatThrownBy(() -> producer.advance(TENANT_ID, UUID.randomUUID(), 1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
    assertThatThrownBy(() -> producer.readCommittedEvent(TENANT_ID, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence must be positive");

    collaborators.verifyUnused();
  }

  @Test
  void missingConstructorCollaboratorsAreRejectedBeforeOtherInteractions() {
    Collaborators collaborators = new Collaborators();

    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer(
                    null,
                    collaborators.outboxRepository(),
                    collaborators.dsl(),
                    collaborators.transactionManager()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("authority-generation repository");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer(
                    collaborators.generationRepository(),
                    null,
                    collaborators.dsl(),
                    collaborators.transactionManager()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("authority outbox repository");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer(
                    collaborators.generationRepository(),
                    collaborators.outboxRepository(),
                    null,
                    collaborators.transactionManager()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("transaction-aware DSLContext");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer(
                    collaborators.generationRepository(),
                    collaborators.outboxRepository(),
                    collaborators.dsl(),
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Account transaction manager");

    collaborators.verifyUnused();
  }

  @Test
  void ambientTransactionIsRejectedForEveryPublicOperation() {
    Collaborators collaborators = new Collaborators();
    AccountTenantAuthorityEventProducer producer = newProducer(collaborators);
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      assertThatThrownBy(() -> producer.readCurrent(TENANT_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("without an ambient transaction");
      assertThatThrownBy(() -> producer.readCommittedEvent(TENANT_ID, 1L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("without an ambient transaction");
      assertThatThrownBy(() -> producer.advance(TENANT_ID, UUID.randomUUID(), 1L, 1L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("without an ambient transaction");
      collaborators.verifyUnused();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void sequenceZeroSnapshotRequiresThePositiveOriginalBaselineAndExactStream() {
    AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot snapshot =
        new AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot(
            TENANT_ID, 1L, 1L, STREAM_KEY, 0L, Optional.empty());

    assertThat(snapshot.latestEvent()).isEmpty();
    assertThat(snapshot.outboxSequence()).isZero();

    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot(
                    TENANT_ID,
                    1L,
                    1L,
                    "account:auth-authority:v1:tenant/" + OTHER_TENANT_ID,
                    0L,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not bind its tenant");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot(
                    TENANT_ID, 2L, 1L, STREAM_KEY, 0L, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive 1/1 baseline");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot(
                    TENANT_ID, 1L, 2L, STREAM_KEY, 0L, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive 1/1 baseline");
  }

  @Test
  void positiveSnapshotRequiresTheExactLatestEventAndCurrentCounters() {
    UUID requestId = UUID.randomUUID();
    TenantGenerationAuthorityEvent matchingEvent = event(TENANT_ID, requestId, 1L, 2L, 2L);
    TenantGenerationAuthorityEvent wrongCounterEvent =
        event(TENANT_ID, UUID.randomUUID(), 1L, 2L, 3L);

    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot(
                    TENANT_ID, 2L, 2L, STREAM_KEY, 1L, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must omit its event");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot(
                    TENANT_ID, 2L, 2L, STREAM_KEY, 1L, Optional.of(wrongCounterEvent)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from its checkpoint");

    AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot valid =
        new AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot(
            TENANT_ID, 2L, 2L, STREAM_KEY, 1L, Optional.of(matchingEvent));
    assertThat(valid.latestEvent()).containsSame(matchingEvent);
  }

  @Test
  void positiveHeadRejectsPristineOrOneSidedCountersBeforeAnyEventLookup() {
    for (long[] counters : new long[][] {{1L, 1L}, {2L, 1L}, {1L, 2L}}) {
      Collaborators collaborators = new Collaborators();
      TransactionStatus status = new SimpleTransactionStatus();
      when(collaborators.transactionManager().getTransaction(any(TransactionDefinition.class)))
          .thenReturn(status);
      doNothing().when(collaborators.transactionManager()).commit(status);
      AccountAuthorityGenerationRepository.ScopeState scopeState =
          new AccountAuthorityGenerationRepository.ScopeState(
              AccountAuthorityGenerationRepository.AuthorityScope.tenant(TENANT_ID),
              counters[0],
              counters[1],
              null);
      when(collaborators.generationRepository().read(scopeState.scope())).thenReturn(scopeState);
      Record stream = mock(Record.class);
      when(stream.get("last_sequence", Long.class)).thenReturn(1L);
      when(collaborators.dsl().fetchOne(contains("SELECT last_sequence"), eq(STREAM_KEY)))
          .thenReturn(stream);
      AccountTenantAuthorityEventProducer producer = newProducer(collaborators);

      assertThatThrownBy(() -> producer.readCurrent(TENANT_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("advanced together");
      verify(collaborators.outboxRepository(), never()).readCheckpoint(any());
      verify(collaborators.outboxRepository(), never()).findEvent(anyString(), anyLong());
      verify(collaborators.outboxRepository(), never()).findEvent(anyString(), anyString());
    }
  }

  @Test
  void tenantGenerationAndSourceVersionOverflowFailBeforeMutation() {
    for (long[] counters :
        new long[][] {
          {Long.MAX_VALUE, 1L},
          {1L, Long.MAX_VALUE},
          {Long.MAX_VALUE, Long.MAX_VALUE}
        }) {
      Collaborators collaborators = new Collaborators();
      TransactionStatus status = new SimpleTransactionStatus();
      when(collaborators.transactionManager().getTransaction(any(TransactionDefinition.class)))
          .thenReturn(status);
      doNothing().when(collaborators.transactionManager()).commit(status);
      AccountAuthorityGenerationRepository.ScopeState scopeState =
          new AccountAuthorityGenerationRepository.ScopeState(
              AccountAuthorityGenerationRepository.AuthorityScope.tenant(TENANT_ID),
              counters[0],
              counters[1],
              null);
      when(collaborators.generationRepository().read(scopeState.scope())).thenReturn(scopeState);
      UUID requestId = UUID.randomUUID();
      when(collaborators.outboxRepository().findEvent(STREAM_KEY, requestId.toString()))
          .thenReturn(Optional.empty());
      AccountTenantAuthorityEventProducer producer = newProducer(collaborators);

      assertThatThrownBy(() -> producer.advance(TENANT_ID, requestId, counters[0], counters[1]))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("is exhausted");

      verify(collaborators.generationRepository(), never())
          .advance(any(AccountAuthorityGenerationRepository.ScopeState.class), isNull());
      verify(collaborators.outboxRepository(), never())
          .append(eq(STREAM_KEY), eq(requestId.toString()), any());
      verify(collaborators.outboxRepository(), never()).readCheckpoint(anyString());
      verify(collaborators.outboxRepository(), never()).findEvent(eq(STREAM_KEY), anyLong());
    }
  }

  @Test
  void outboxSequenceGapIsRejectedBeforeEventReadbackOrMutation() {
    Collaborators collaborators = new Collaborators();
    TransactionStatus status = new SimpleTransactionStatus();
    when(collaborators.transactionManager().getTransaction(any(TransactionDefinition.class)))
        .thenReturn(status);
    doNothing().when(collaborators.transactionManager()).commit(status);
    AccountAuthorityGenerationRepository.ScopeState scopeState =
        new AccountAuthorityGenerationRepository.ScopeState(
            AccountAuthorityGenerationRepository.AuthorityScope.tenant(TENANT_ID), 3L, 3L, null);
    when(collaborators.generationRepository().read(scopeState.scope())).thenReturn(scopeState);
    Record stream = mock(Record.class);
    when(stream.get("last_sequence", Long.class)).thenReturn(2L);
    when(collaborators.dsl().fetchOne(contains("SELECT last_sequence"), eq(STREAM_KEY)))
        .thenReturn(stream);
    Record eventRange = mock(Record.class);
    when(eventRange.get("event_count", Long.class)).thenReturn(1L);
    when(eventRange.get("first_sequence", Long.class)).thenReturn(2L);
    when(eventRange.get("last_sequence", Long.class)).thenReturn(2L);
    when(collaborators.dsl().fetchOne(contains("COUNT(*) AS event_count"), eq(STREAM_KEY)))
        .thenReturn(eventRange);
    UUID requestId = UUID.randomUUID();
    when(collaborators.outboxRepository().findEvent(STREAM_KEY, requestId.toString()))
        .thenReturn(Optional.empty());
    AccountTenantAuthorityEventProducer producer = newProducer(collaborators);

    assertThatThrownBy(() -> producer.advance(TENANT_ID, requestId, 3L, 3L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sequence gap or dangling head");

    verify(collaborators.generationRepository(), never())
        .advance(any(AccountAuthorityGenerationRepository.ScopeState.class), isNull());
    verify(collaborators.outboxRepository(), never())
        .append(eq(STREAM_KEY), eq(requestId.toString()), any());
    verify(collaborators.outboxRepository(), never()).readCheckpoint(anyString());
    verify(collaborators.outboxRepository(), never()).findEvent(anyString(), anyLong());
  }

  @Test
  void historicalReadbackBindsAnOlderExactEventToTheCurrentTenantSnapshot() {
    TenantGenerationAuthorityEvent historical = event(TENANT_ID, UUID.randomUUID(), 1L, 2L, 2L);
    TenantGenerationAuthorityEvent latest = event(TENANT_ID, UUID.randomUUID(), 3L, 4L, 4L);
    AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot current =
        new AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot(
            TENANT_ID, 4L, 4L, STREAM_KEY, 3L, Optional.of(latest));

    AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback readback =
        new AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback(current, historical);

    assertThat(readback.currentSnapshot()).isSameAs(current);
    assertThat(readback.requestedEvent()).isSameAs(historical);
  }

  @Test
  void historicalReadbackRejectsAnotherTenantAheadEventOrChangedLatestPayload() {
    TenantGenerationAuthorityEvent latest = event(TENANT_ID, UUID.randomUUID(), 2L, 3L, 3L);
    TenantGenerationAuthorityEvent differentLatest =
        event(TENANT_ID, UUID.randomUUID(), 2L, 3L, 3L);
    AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot current =
        new AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot(
            TENANT_ID, 3L, 3L, STREAM_KEY, 2L, Optional.of(latest));

    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback(
                    current, event(OTHER_TENANT_ID, UUID.randomUUID(), 1L, 2L, 2L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not bind");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback(
                    current, event(TENANT_ID, UUID.randomUUID(), 3L, 3L, 4L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ahead of the current snapshot");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback(
                    current, event(TENANT_ID, UUID.randomUUID(), 1L, 4L, 4L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ahead of the current snapshot");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback(
                    current, event(TENANT_ID, UUID.randomUUID(), 1L, 3L, 3L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("counters do not match its outbox sequence");
    assertThatThrownBy(
            () ->
                new AccountTenantAuthorityEventProducer.TenantAuthorityEventReadback(
                    current, differentLatest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from the current snapshot checkpoint");
  }

  private AccountTenantAuthorityEventProducer newProducer(Collaborators collaborators) {
    return new AccountTenantAuthorityEventProducer(
        collaborators.generationRepository(),
        collaborators.outboxRepository(),
        collaborators.dsl(),
        collaborators.transactionManager());
  }

  private TenantGenerationAuthorityEvent event(
      UUID tenantId, UUID requestId, long sequence, long generation, long sourceVersion) {
    String requestText = requestId.toString();
    String sourceScope = "tenant/" + tenantId;
    String streamKey = "account:auth-authority:v1:" + sourceScope;
    return TenantGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion",
            TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType",
            TenantGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId",
            "account-tenant-generation-event-v1:" + requestText,
            "requestId",
            requestText,
            "tenantId",
            tenantId.toString(),
            "sourceScope",
            sourceScope,
            "outboxStreamKey",
            streamKey,
            "outboxSequence",
            Long.toString(sequence),
            "tenantAuthorityGeneration",
            Long.toString(generation),
            "sourceVersion",
            Long.toString(sourceVersion)));
  }

  private record Collaborators(
      AccountAuthorityGenerationRepository generationRepository,
      AccountAuthorityOutboxRepository outboxRepository,
      DSLContext dsl,
      PlatformTransactionManager transactionManager) {
    private Collaborators() {
      this(
          mock(AccountAuthorityGenerationRepository.class),
          mock(AccountAuthorityOutboxRepository.class),
          mock(DSLContext.class),
          mock(PlatformTransactionManager.class));
    }

    private void verifyUnused() {
      verifyNoInteractions(generationRepository, outboxRepository, dsl, transactionManager);
    }
  }
}
