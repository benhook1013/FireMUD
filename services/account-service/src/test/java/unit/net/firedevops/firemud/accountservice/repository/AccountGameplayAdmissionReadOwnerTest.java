package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owner/transaction doubles and fabricated carriers only; no current source authority is proven.
 */
class AccountGameplayAdmissionReadOwnerTest {
  private final AccountGameplayAdmissionLeaseRepository repository =
      mock(AccountGameplayAdmissionLeaseRepository.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final AccountGameplayAdmissionReadOwner owner =
      new AccountGameplayAdmissionReadOwner(repository, manager, "test");

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void returnsOnlyExactPendingOrAbortedHistoricalEvidenceInOwnedSerializableTransactions()
      throws Exception {
    var original = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID cleanup = UUID.randomUUID();
    var pending = new AccountGameplayAdmissionLeaseOperation(original, State.PENDING, null, null);
    var aborted =
        new AccountGameplayAdmissionLeaseOperation(original, State.ABORTED, null, cleanup);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.readExact(original)).thenReturn(Optional.of(pending), Optional.of(aborted));

    var first = peer().call(() -> owner.read(request(original)));
    var second = peer().call(() -> owner.read(request(original)));

    assertThat(first).isSameAs(pending);
    assertThat(second).isSameAs(aborted);
    assertThat(first.evidence().canonicalJson()).isEqualTo(original.canonicalJson());
    assertThat(first.evidence().sha256()).isEqualTo(original.sha256());
    assertThat(first.state()).isEqualTo(State.PENDING);
    assertThat(second.state()).isEqualTo(State.ABORTED);
    assertThat(second.bindingDecisionId()).isNull();
    assertThat(second.orphanCleanupId()).isEqualTo(cleanup);
    assertThat(second.hasPendingOrphanCleanup()).isTrue();
    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(manager, times(2)).getTransaction(definition.capture());
    assertThat(definition.getAllValues())
        .allSatisfy(
            value -> {
              assertThat(value.getIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
              assertThat(value.getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              assertThat(value.isReadOnly()).isFalse();
            });
    verify(manager, times(2)).commit(transactionStatus);
    verify(repository, times(2)).readExact(original);
    verify(repository, never()).recordCommitted(any(), any());
    verify(repository, never()).recordAborted(any(), any(), any());
  }

  @Test
  void rejectsMissingRowsCommittedStorageAndFullCarrierMismatchAsUnresolved() {
    var original = AccountGameplayAdmissionAbortOwnerTest.fixture();
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.readExact(original))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                new AccountGameplayAdmissionLeaseOperation(
                    original, State.COMMITTED, UUID.randomUUID(), null)))
        .thenThrow(new AccountGameplayAdmissionLeaseRepository.IdentityConflictException());
    var changedCarrier = new LinkedHashMap<>(original.carrier());
    changedCarrier.put("leaseFence", "2");
    var changed = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);
    when(repository.readExact(changed))
        .thenThrow(new AccountGameplayAdmissionLeaseRepository.IdentityConflictException());

    for (int attempt = 0; attempt < 3; attempt++) {
      assertStatus(
          Status.Code.FAILED_PRECONDITION, () -> peer().call(() -> owner.read(request(original))));
    }
    assertStatus(
        Status.Code.FAILED_PRECONDITION, () -> peer().call(() -> owner.read(request(changed))));
    verify(manager, times(4)).rollback(transactionStatus);
  }

  @Test
  void wrongPeerAndOriginalCallerNamespaceAreRejectedBeforeTransactionOrRepository() {
    var original = AccountGameplayAdmissionAbortOwnerTest.fixture();
    var changedCarrier = new LinkedHashMap<>(original.carrier());
    changedCarrier.put("targetNamespace", "other");
    changedCarrier.put("callerWorkload", "spiffe://firemud/ns/other/sa/game-session-service");
    var otherNamespace = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);

    assertStatus(Status.Code.UNAUTHENTICATED, () -> owner.read(request(original)));
    for (String uri :
        List.of(
            "spiffe://firemud/ns/test/sa/account-service",
            "spiffe://firemud/ns/other/sa/game-session-service")) {
      assertStatus(
          Status.Code.PERMISSION_DENIED, () -> peer(uri).call(() -> owner.read(request(original))));
    }
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        () -> peer().call(() -> owner.read(request(otherNamespace))));
    verifyNoInteractions(repository, manager);
  }

  @Test
  void rejectsMalformedEnvelopeAndOuterRequestMismatchBeforeTransactionOrRepository() {
    var original = AccountGameplayAdmissionAbortOwnerTest.fixture();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    var reference = AccountGameplayAdmissionLeaseWireCodec.encodeReference(original);
    for (var request :
        List.of(
            ReadGameplayAdmissionLeaseRequest.getDefaultInstance(),
            request(original).toBuilder().setUnknownFields(unknown).build(),
            request(original).toBuilder()
                .setExpectedLease(reference.toBuilder().setUnknownFields(unknown))
                .build(),
            request(original).toBuilder().setRequestId(UUID.randomUUID().toString()).build())) {
      assertThatThrownBy(() -> peer().call(() -> owner.read(request)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(repository, manager);
  }

  @Test
  void rejectsAmbientTransactionAndSynchronizationBeforeOpeningOwnedTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () ->
            peer()
                .call(() -> owner.read(request(AccountGameplayAdmissionAbortOwnerTest.fixture()))));
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.initSynchronization();
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () ->
            peer()
                .call(() -> owner.read(request(AccountGameplayAdmissionAbortOwnerTest.fixture()))));
    verifyNoInteractions(repository, manager);
  }

  private static ReadGameplayAdmissionLeaseRequest request(
      AccountGameplayAdmissionLeaseEvidence evidence) {
    return ReadGameplayAdmissionLeaseRequest.newBuilder()
        .setRequestId((String) evidence.carrier().get("requestId"))
        .setExpectedLease(AccountGameplayAdmissionLeaseWireCodec.encodeReference(evidence))
        .build();
  }

  private static void assertStatus(Status.Code expected, ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static Context peer() {
    return peer("spiffe://firemud/ns/test/sa/game-session-service");
  }

  private static Context peer(String uri) {
    return Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
  }
}
