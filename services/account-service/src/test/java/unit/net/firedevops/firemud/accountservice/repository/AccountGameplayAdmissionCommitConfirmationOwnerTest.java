package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionCommitConfirmation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner and transaction doubles only; no PostgreSQL durability or live mTLS is established. */
class AccountGameplayAdmissionCommitConfirmationOwnerTest {
  private final AccountGameplayAdmissionCommitConfirmationRepository repository =
      mock(AccountGameplayAdmissionCommitConfirmationRepository.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final AccountGameplayAdmissionCommitConfirmationOwner owner =
      new AccountGameplayAdmissionCommitConfirmationOwner(repository, manager, "test");

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void confirmsOnlyAfterCreationCommitThenReadsExactReceiptInIndependentSerializableTransaction()
      throws Exception {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    var receipt = receipt(evidence, decision);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.confirmCommitted(evidence, decision)).thenReturn(receipt);
    when(repository.readCommitConfirmation(evidence, decision)).thenReturn(receipt);

    var returned = peer().call(() -> owner.confirm(request(evidence, decision)));
    var retry = peer().call(() -> owner.confirm(request(evidence, decision)));

    assertThat(returned).isSameAs(receipt);
    assertThat(retry).isSameAs(receipt);
    assertThat(returned.operation().evidence().canonicalJson()).isEqualTo(evidence.canonicalJson());
    assertThat(returned.operation().evidence().sha256()).isEqualTo(evidence.sha256());
    assertThat(returned.operation().state()).isEqualTo(State.COMMITTED);
    assertThat(returned.operation().bindingDecisionId()).isEqualTo(decision);
    assertThat(returned.committedBeforeMs()).isLessThan(returned.expiresAtMs());
    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    var ordered = inOrder(manager, repository);
    ordered.verify(manager).getTransaction(definition.capture());
    ordered.verify(repository).confirmCommitted(evidence, decision);
    ordered.verify(manager).commit(transactionStatus);
    ordered.verify(manager).getTransaction(definition.capture());
    ordered.verify(repository).readCommitConfirmation(evidence, decision);
    ordered.verify(manager).commit(transactionStatus);
    ordered.verify(manager).getTransaction(definition.capture());
    ordered.verify(repository).confirmCommitted(evidence, decision);
    ordered.verify(manager).commit(transactionStatus);
    ordered.verify(manager).getTransaction(definition.capture());
    ordered.verify(repository).readCommitConfirmation(evidence, decision);
    ordered.verify(manager).commit(transactionStatus);
    assertThat(definition.getAllValues())
        .allSatisfy(
            value -> {
              assertThat(value.getIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
              assertThat(value.getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              assertThat(value.isReadOnly()).isFalse();
            });
  }

  @Test
  void failedCreationOrLostCreationCommitNeverStartsIndependentReadOrReturnsProof() {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.confirmCommitted(evidence, decision)).thenReturn(receipt(evidence, decision));
    doThrow(new IllegalStateException("commit uncertain")).when(manager).commit(transactionStatus);
    assertStatus(
        Status.Code.UNAVAILABLE,
        () -> peer().call(() -> owner.confirm(request(evidence, decision))));
    verify(repository, never()).readCommitConfirmation(any(), any());
    verify(manager).commit(transactionStatus);

    TransactionSynchronizationManager.clear();
    org.mockito.Mockito.reset(manager, repository);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.confirmCommitted(evidence, decision))
        .thenThrow(new IllegalStateException("creation unavailable"));
    assertStatus(
        Status.Code.UNAVAILABLE,
        () -> peer().call(() -> owner.confirm(request(evidence, decision))));
    verify(repository, never()).readCommitConfirmation(any(), any());
  }

  @Test
  void changedIndependentReadAndReadFailureNeverExposeCreationResult() {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    var created = receipt(evidence, decision);
    var changed = receipt(evidence, UUID.randomUUID());
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.confirmCommitted(evidence, decision)).thenReturn(created);
    when(repository.readCommitConfirmation(evidence, decision)).thenReturn(changed);
    assertStatus(
        Status.Code.UNAVAILABLE,
        () -> peer().call(() -> owner.confirm(request(evidence, decision))));

    org.mockito.Mockito.reset(manager, repository);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.confirmCommitted(evidence, decision)).thenReturn(created);
    when(repository.readCommitConfirmation(evidence, decision))
        .thenThrow(new IllegalStateException("read unavailable"));
    assertStatus(
        Status.Code.UNAVAILABLE,
        () -> peer().call(() -> owner.confirm(request(evidence, decision))));
  }

  @Test
  void dependencyUnavailableDescriptionAndTrailersAreReplacedWithBoundedOwnerFailure() {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    Metadata trailers = new Metadata();
    Metadata.Key<String> privateKey =
        Metadata.Key.of("private-detail", Metadata.ASCII_STRING_MARSHALLER);
    trailers.put(privateKey, "private dependency trailer");
    var dependencyFailure =
        Status.UNAVAILABLE
            .withDescription("private dependency description")
            .asRuntimeException(trailers);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.confirmCommitted(evidence, decision)).thenThrow(dependencyFailure);

    assertUnavailableIsRedacted(
        () -> peer().call(() -> owner.confirm(request(evidence, decision))));

    TransactionSynchronizationManager.clear();
    org.mockito.Mockito.reset(manager, repository);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.confirmCommitted(evidence, decision)).thenReturn(receipt(evidence, decision));
    when(repository.readCommitConfirmation(evidence, decision)).thenThrow(dependencyFailure);
    assertUnavailableIsRedacted(
        () -> peer().call(() -> owner.confirm(request(evidence, decision))));

    TransactionSynchronizationManager.clear();
    org.mockito.Mockito.reset(manager, repository);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.readCommitConfirmation(evidence, decision)).thenThrow(dependencyFailure);
    assertUnavailableIsRedacted(() -> peer().call(() -> owner.read(request(evidence, decision))));
  }

  @Test
  void readReturnsHistoricalTemporalProofWithoutCreatingOrReevaluatingItsDeadline()
      throws Exception {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    var receipt = receipt(evidence, decision);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.readCommitConfirmation(evidence, decision)).thenReturn(receipt);

    var returned = peer().call(() -> owner.read(request(evidence, decision)));

    assertThat(returned).isSameAs(receipt);
    assertThat(returned.expiresAtMs()).isEqualTo(1_015_000L);
    assertThat(returned.committedBeforeMs()).isLessThan(returned.expiresAtMs());
    verify(repository).readCommitConfirmation(evidence, decision);
    verify(repository, never()).confirmCommitted(any(), any());
    verify(manager).commit(transactionStatus);
  }

  @Test
  void rejectsMissingWrongPeerAndNamespaceBeforeRequestParsingOrTransaction() {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    var malformed = FinalizeGameplayAdmissionLeaseRequest.getDefaultInstance();
    assertStatus(Status.Code.UNAUTHENTICATED, () -> owner.confirm(malformed));
    for (String uri :
        List.of(
            "spiffe://firemud/ns/test/sa/account-service",
            "spiffe://firemud/ns/other/sa/game-session-service")) {
      assertStatus(
          Status.Code.PERMISSION_DENIED, () -> peer(uri).call(() -> owner.read(malformed)));
    }
    assertStatus(
        Status.Code.UNAUTHENTICATED, () -> owner.read(request(evidence, UUID.randomUUID())));
    verifyNoInteractions(repository, manager);
  }

  @Test
  void rejectsMalformedEnvelopeDecisionUnknownFieldsAndCallerBindingBeforeTransaction() {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    var reference = AccountGameplayAdmissionLeaseWireCodec.encodeReference(evidence);
    for (var request :
        List.of(
            FinalizeGameplayAdmissionLeaseRequest.getDefaultInstance(),
            request(evidence, decision).toBuilder().setUnknownFields(unknown).build(),
            request(evidence, decision).toBuilder()
                .setLease(reference.toBuilder().setUnknownFields(unknown))
                .build(),
            request(evidence, decision).toBuilder()
                .setLease(
                    reference.toBuilder().setEvidenceCanonicalJson(ByteString.copyFromUtf8("{}")))
                .build(),
            request(evidence, UUID.randomUUID()).toBuilder()
                .setBindingDecisionId("not-a-uuid")
                .build(),
            request(evidence, decision).toBuilder()
                .setBindingDecisionId(decision.toString().toUpperCase())
                .build())) {
      assertStatus(Status.Code.INVALID_ARGUMENT, () -> peer().call(() -> owner.confirm(request)));
    }
    var changedCarrier = new LinkedHashMap<>(evidence.carrier());
    changedCarrier.put("targetNamespace", "other");
    changedCarrier.put("callerWorkload", "spiffe://firemud/ns/other/sa/game-session-service");
    var otherCaller = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        () -> peer().call(() -> owner.read(request(otherCaller, decision))));
    verifyNoInteractions(repository, manager);
  }

  @Test
  void rejectsAmbientTransactionAndSynchronizationBeforeOwnedTransaction() {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> peer().call(() -> owner.confirm(request(evidence, decision))));
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.initSynchronization();
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> peer().call(() -> owner.read(request(evidence, decision))));
    verifyNoInteractions(repository, manager);
  }

  private static AccountGameplayAdmissionCommitConfirmation receipt(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decision) {
    var operation =
        new AccountGameplayAdmissionLeaseOperation(evidence, State.COMMITTED, decision, null);
    return new AccountGameplayAdmissionCommitConfirmation(
        operation,
        (short) 1,
        UUID.fromString((String) evidence.carrier().get("requestId")),
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString((String) evidence.carrier().get("leaseId")),
        1L,
        evidence.sha256(),
        decision,
        1_015_000L,
        "123456",
        "1/20",
        "1/21",
        1_014_999L,
        "123457");
  }

  private static FinalizeGameplayAdmissionLeaseRequest request(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decision) {
    return FinalizeGameplayAdmissionLeaseRequest.newBuilder()
        .setLease(AccountGameplayAdmissionLeaseWireCodec.encodeReference(evidence))
        .setBindingDecisionId(decision.toString())
        .build();
  }

  private static Context peer() {
    return peer("spiffe://firemud/ns/test/sa/game-session-service");
  }

  private static Context peer(String uri) {
    return Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
  }

  private static void assertStatus(
      Status.Code expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static void assertUnavailableIsRedacted(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> {
              var statusFailure = (StatusRuntimeException) failure;
              assertThat(Status.fromThrowable(failure).getCode())
                  .isEqualTo(Status.Code.UNAVAILABLE);
              assertThat(Status.fromThrowable(failure).getDescription())
                  .isEqualTo("Durable Account admission commit confirmation unavailable");
              assertThat(statusFailure.getTrailers()).isNull();
              assertThat(statusFailure.getCause()).isNull();
            });
  }
}
