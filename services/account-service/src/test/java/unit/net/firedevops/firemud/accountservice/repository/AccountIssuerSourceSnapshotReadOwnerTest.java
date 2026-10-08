package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotEvidence;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountIssuerSourceSnapshotReadOwnerTest {
  private static final String NAMESPACE = "test";
  private static final String CALLER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ISSUER = AccountIssuerSourceSnapshotEvidence.ISSUER_ID;
  private static final String STREAM = AccountIssuerSourceSnapshotEvidence.ISSUER_STREAM_KEY;

  private final AccountAuthoritySourceEvidenceRepository sourceRepository =
      mock(AccountAuthoritySourceEvidenceRepository.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final AccountIssuerSourceSnapshotReadOwner owner =
      new AccountIssuerSourceSnapshotReadOwner(sourceRepository, transactionManager, NAMESPACE);

  @BeforeEach
  void installTransactionManagerBehavior() {
    when(transactionManager.getTransaction(any()))
        .thenAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
              return transactionStatus;
            });
    doAnswer(
            invocation -> {
              clearTransactionMarkers();
              return null;
            })
        .when(transactionManager)
        .commit(transactionStatus);
    doAnswer(
            invocation -> {
              clearTransactionMarkers();
              return null;
            })
        .when(transactionManager)
        .rollback(transactionStatus);
  }

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void missingWrongAndCrossNamespacePeersAreRejectedBeforeRequestParsingOrStorage() {
    assertStatus(Status.Code.UNAUTHENTICATED, () -> owner.read(null));
    for (String uri :
        List.of(
            "spiffe://firemud/ns/test/sa/account-service",
            "spiffe://firemud/ns/other/sa/game-session-service")) {
      assertStatus(Status.Code.PERMISSION_DENIED, () -> peer(uri).call(() -> owner.read(null)));
    }
    verifyNoInteractions(sourceRepository, transactionManager);
  }

  @Test
  void rejectsAmbientTransactionSynchronizationAndReadOnlyContextBeforeStorage() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> peer().call(() -> owner.read(request(UUID.randomUUID()))));
    clearTransactionMarkers();

    TransactionSynchronizationManager.initSynchronization();
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> peer().call(() -> owner.read(request(UUID.randomUUID()))));
    TransactionSynchronizationManager.clearSynchronization();

    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> peer().call(() -> owner.read(request(UUID.randomUUID()))));
    verifyNoInteractions(sourceRepository, transactionManager);
  }

  @Test
  void readsZeroAndAdvancedOwnerSnapshotsAndRejectsChangedExpectedSource() throws Exception {
    UUID operation = UUID.randomUUID();
    UUID freshOperation = UUID.randomUUID();
    var zero = sourceSnapshot(1, Optional.empty(), Optional.empty());
    var advanced = sourceSnapshot(2, Optional.of(issuerEvent()), Optional.of(issuerEvent()));
    var eventDrift =
        sourceSnapshot(
            2,
            Optional.of(issuerEvent("different-historical-event-18")),
            Optional.of(issuerEvent("different-historical-event-18")));
    when(sourceRepository.readCurrentCanonicalIssuerSource(ISSUER))
        .thenReturn(zero, zero, advanced, advanced, advanced, eventDrift);

    var captured = peer().call(() -> owner.read(request(operation)));
    assertThat(captured.evidence().issuerAuthGeneration()).isEqualTo("1");
    assertThat(captured.evidence().sourceVersion()).isEqualTo("1");
    assertThat(captured.evidence().lastCommittedOutboxSequence()).isEqualTo("0");
    assertThat(captured.evidence().sourceEvent()).isEmpty();

    var exactRetry =
        peer().call(() -> owner.read(request(operation, Optional.of(captured.evidence()))));
    assertThat(exactRetry.evidence()).isEqualTo(captured.evidence());

    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> peer().call(() -> owner.read(request(operation, Optional.of(captured.evidence())))));

    var newCapture = peer().call(() -> owner.read(request(freshOperation)));
    assertThat(newCapture.evidence().issuerAuthGeneration()).isEqualTo("2");
    assertThat(newCapture.evidence().sourceVersion()).isEqualTo("2");
    assertThat(newCapture.evidence().lastCommittedOutboxSequence()).isEqualTo("1");
    assertThat(newCapture.evidence().reconciliationOperationId()).isEqualTo(freshOperation);
    var retained =
        (AccountAuthoritySourceEventV1Codec.IssuerEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                newCapture.evidence().sourceEvent().orElseThrow());
    assertThat(retained.eventId()).isEqualTo("historical-issuer-event-17");
    assertThat(retained.requestId()).isEqualTo("historical-issuer-event-17");
    assertThat(retained.eventId()).isNotEqualTo(freshOperation.toString());

    var exactAdvancedRetry =
        peer().call(() -> owner.read(request(freshOperation, Optional.of(newCapture.evidence()))));
    assertThat(exactAdvancedRetry.evidence()).isEqualTo(newCapture.evidence());
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () ->
            peer()
                .call(
                    () -> owner.read(request(freshOperation, Optional.of(newCapture.evidence())))));

    ArgumentCaptor<TransactionDefinition> definitions =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager, times(6)).getTransaction(definitions.capture());
    assertThat(definitions.getAllValues())
        .allSatisfy(
            definition -> {
              assertThat(definition.getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              assertThat(definition.getIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
              assertThat(definition.isReadOnly()).isFalse();
            });
    verify(transactionManager, times(4)).commit(transactionStatus);
    verify(transactionManager, times(2)).rollback(transactionStatus);
  }

  @Test
  void malformedUnknownUnsupportedAndMismatchedExpectedRequestsFailBeforeSql() {
    UUID operation = UUID.randomUUID();
    var valid = request(operation);
    var unknown =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    var wrongNamespace = valid.toBuilder().setTargetNamespace("other").build();
    var unsupportedIssuer = valid.toBuilder().setIssuerId("other-issuer").build();
    var wrongExpected =
        valid.toBuilder()
            .setExpectedSource(
                AccountIssuerSourceSnapshotGrpcCodec.toWire(
                    AccountIssuerSourceSnapshotEvidenceForTest.zero(UUID.randomUUID())))
            .build();

    for (ReadCurrentIssuerAuthoritySourceRequest request :
        List.of(unknown, wrongNamespace, unsupportedIssuer, wrongExpected)) {
      assertThatThrownBy(() -> peer().call(() -> owner.read(request)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(sourceRepository, transactionManager);
  }

  @Test
  void sourceOutageMapsUnavailableAndContradictoryRetainedEventMapsFailedPrecondition() {
    when(sourceRepository.readCurrentCanonicalIssuerSource(ISSUER))
        .thenThrow(new DataAccessException("database offline"));
    assertStatus(
        Status.Code.UNAVAILABLE, () -> peer().call(() -> owner.read(request(UUID.randomUUID()))));

    var corrupt = sourceSnapshot(2, Optional.of(issuerEvent()), Optional.of(issuerEvent()));
    var source = corrupt.source();
    Event validEvent = corrupt.latestEvent().orElseThrow();
    Event malformedEvent =
        new Event(
            validEvent.outboxStreamKey(),
            validEvent.requestId(),
            validEvent.outboxSequence(),
            validEvent.eventId(),
            validEvent.eventDigest(),
            "not-json".getBytes(StandardCharsets.UTF_8));
    var badHistory =
        new AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot(
            source, Optional.of(malformedEvent));
    doReturn(badHistory).when(sourceRepository).readCurrentCanonicalIssuerSource(ISSUER);
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> peer().call(() -> owner.read(request(UUID.randomUUID()))));
  }

  @Test
  void missingCanonicalIssuerDeniesWithoutCreatingOrReadingAnotherScope() {
    when(sourceRepository.readCurrentCanonicalIssuerSource(ISSUER))
        .thenThrow(
            new AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException());
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> peer().call(() -> owner.read(request(UUID.randomUUID()))));
    verify(sourceRepository).readCurrentCanonicalIssuerSource(ISSUER);
    verify(sourceRepository, never()).initializeIssuerIfAbsent(any());
  }

  private static AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot
      sourceSnapshot(
          long generation,
          Optional<AccountAuthoritySourceEventV1Codec.IssuerEvent> event,
          Optional<AccountAuthoritySourceEventV1Codec.IssuerEvent> eventForOutbox) {
    long sequence = generation - 1L;
    Optional<String> eventId = event.map(AccountAuthoritySourceEventV1Codec.IssuerEvent::eventId);
    Optional<String> digest =
        event.map(AccountAuthoritySourceEventV1Codec.IssuerEvent::eventDigest);
    var checkpoint =
        new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
            STREAM, sequence, eventId, digest);
    var current =
        new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            AuthorityScope.issuer(ISSUER),
            generation,
            generation,
            null,
            checkpoint,
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            9L,
            null);
    Optional<Event> latest =
        eventForOutbox.map(
            sourceEvent ->
                new Event(
                    STREAM,
                    sourceEvent.requestId(),
                    sequence,
                    sourceEvent.eventId(),
                    sourceEvent.eventDigest(),
                    sourceEvent.canonicalJsonUtf8()));
    return new AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot(
        current, latest);
  }

  private static AccountAuthoritySourceEventV1Codec.IssuerEvent issuerEvent() {
    return issuerEvent("historical-issuer-event-17");
  }

  private static AccountAuthoritySourceEventV1Codec.IssuerEvent issuerEvent(String eventId) {
    return AccountAuthoritySourceEventV1Codec.sealIssuer(
        new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
            eventId, eventId, STREAM, "1", ISSUER, "2", "2", "SIGNER_COMPROMISE"));
  }

  private static ReadCurrentIssuerAuthoritySourceRequest request(UUID operation) {
    return request(operation, Optional.empty());
  }

  private static ReadCurrentIssuerAuthoritySourceRequest request(
      UUID operation, Optional<AccountIssuerSourceSnapshotEvidence> expected) {
    var typed =
        new AccountIssuerSourceSnapshotGrpcCodec.ReadRequest(
            operation, NAMESPACE, ISSUER, expected);
    return AccountIssuerSourceSnapshotGrpcCodec.toRequest(typed, NAMESPACE, CALLER);
  }

  private static void assertStatus(
      Status.Code expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static Context peer() {
    return peer(CALLER);
  }

  private static Context peer(String uri) {
    return Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
  }

  private static void clearTransactionMarkers() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
  }

  /** Builds a fully typed initial source for request-shape tests without asserting authority. */
  private static final class AccountIssuerSourceSnapshotEvidenceForTest {
    private static AccountIssuerSourceSnapshotEvidence zero(UUID operation) {
      return new AccountIssuerSourceSnapshotEvidence(
          operation, NAMESPACE, CALLER, ISSUER, "1", "1", STREAM, "0", Optional.empty());
    }
  }
}
