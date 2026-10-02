package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountIssuerAuthorityEventProducerTest {
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String OTHER_ISSUER_ID = "https://other.example.test/issuer";
  private static final String STREAM_KEY = "account:auth-authority:v1:issuer/" + ISSUER_ID;

  @Test
  void requestedIssuerMismatchIsRejectedBeforeDatabaseOrTransactionManagerInteraction() {
    Collaborators collaborators = new Collaborators();
    AccountIssuerAuthorityEventProducer producer = newProducer(ISSUER_ID, collaborators);

    assertThatThrownBy(() -> producer.advance(OTHER_ISSUER_ID, UUID.randomUUID(), 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact configured issuer");
    assertThatThrownBy(() -> producer.readCurrent(OTHER_ISSUER_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact configured issuer");

    collaborators.verifyUnused();
  }

  @Test
  void nilRequestAndNonpositiveExpectedCountersAreRejectedBeforePersistence() {
    Collaborators collaborators = new Collaborators();
    AccountIssuerAuthorityEventProducer producer = newProducer(ISSUER_ID, collaborators);

    assertThatThrownBy(() -> producer.advance(ISSUER_ID, new UUID(0L, 0L), 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");
    assertThatThrownBy(() -> producer.advance(ISSUER_ID, UUID.randomUUID(), 0L, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
    assertThatThrownBy(() -> producer.advance(ISSUER_ID, UUID.randomUUID(), 1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");

    collaborators.verifyUnused();
  }

  @Test
  void missingConstructorCollaboratorsAreRejectedWithoutCallingOtherCollaborators() {
    Collaborators collaborators = new Collaborators();

    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityEventProducer(
                    ISSUER_ID,
                    null,
                    collaborators.outboxRepository(),
                    collaborators.dsl(),
                    collaborators.transactionManager()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("authority-generation repository");
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityEventProducer(
                    ISSUER_ID,
                    collaborators.generationRepository(),
                    null,
                    collaborators.dsl(),
                    collaborators.transactionManager()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("authority outbox repository");
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityEventProducer(
                    ISSUER_ID,
                    collaborators.generationRepository(),
                    collaborators.outboxRepository(),
                    null,
                    collaborators.transactionManager()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("transaction-aware DSLContext");
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityEventProducer(
                    ISSUER_ID,
                    collaborators.generationRepository(),
                    collaborators.outboxRepository(),
                    collaborators.dsl(),
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Account transaction manager");

    collaborators.verifyUnused();
  }

  @Test
  void ambientActualTransactionIsRejectedAndThreadStateIsRestored() {
    Collaborators collaborators = new Collaborators();
    AccountIssuerAuthorityEventProducer producer = newProducer(ISSUER_ID, collaborators);
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      assertThatThrownBy(() -> producer.readCurrent(ISSUER_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("without an ambient transaction");
      assertThatThrownBy(() -> producer.advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("without an ambient transaction");
      collaborators.verifyUnused();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void sequenceZeroSnapshotContainsOnlyProvenBaselineAndEmptyEventOptional() {
    AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot snapshot =
        new AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot(
            ISSUER_ID, 1L, 1L, STREAM_KEY, 0L, Optional.empty());

    assertThat(snapshot.latestEvent()).isEmpty();
    assertThat(snapshot.outboxSequence()).isZero();
  }

  @Test
  void sequenceZeroSnapshotRejectsWrongIssuerStreamAndUnprovenCounters() {
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot(
                    ISSUER_ID,
                    1L,
                    1L,
                    "account:auth-authority:v1:issuer/" + OTHER_ISSUER_ID,
                    0L,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not bind its issuer");
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot(
                    ISSUER_ID, 2L, 1L, STREAM_KEY, 0L, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive 1/1 baseline");
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot(
                    ISSUER_ID, 1L, 2L, STREAM_KEY, 0L, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive 1/1 baseline");
  }

  @Test
  void positiveSnapshotRejectsMissingOrMismatchedLatestEvent() {
    UUID requestId = UUID.randomUUID();
    IssuerGenerationAuthorityEvent matchingEvent = event(ISSUER_ID, requestId, 1L, 2L, 2L);
    IssuerGenerationAuthorityEvent wrongCounterEvent =
        event(ISSUER_ID, UUID.randomUUID(), 1L, 2L, 3L);

    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot(
                    ISSUER_ID, 2L, 2L, STREAM_KEY, 1L, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must omit its event");
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot(
                    ISSUER_ID, 2L, 2L, STREAM_KEY, 1L, Optional.of(wrongCounterEvent)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from its checkpoint");

    AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot validShape =
        new AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot(
            ISSUER_ID, 2L, 2L, STREAM_KEY, 1L, Optional.of(matchingEvent));
    assertThat(validShape.latestEvent()).isPresent();
    assertThat(validShape.latestEvent().orElseThrow()).isSameAs(matchingEvent);
  }

  private AccountIssuerAuthorityEventProducer newProducer(
      String issuerId, Collaborators collaborators) {
    return new AccountIssuerAuthorityEventProducer(
        issuerId,
        collaborators.generationRepository(),
        collaborators.outboxRepository(),
        collaborators.dsl(),
        collaborators.transactionManager());
  }

  private IssuerGenerationAuthorityEvent event(
      String issuerId, UUID requestId, long sequence, long issuerGeneration, long sourceVersion) {
    String requestText = requestId.toString();
    String sourceScope = "issuer/" + issuerId;
    String streamKey = "account:auth-authority:v1:" + sourceScope;
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion",
            IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType",
            IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId",
            "account-issuer-authority-event-v1:" + requestText,
            "requestId",
            requestText,
            "issuerId",
            issuerId,
            "sourceScope",
            sourceScope,
            "outboxStreamKey",
            streamKey,
            "outboxSequence",
            Long.toString(sequence),
            "issuerAuthGeneration",
            Long.toString(issuerGeneration),
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
