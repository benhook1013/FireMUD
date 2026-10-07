package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AcquirePublicGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AcquirePublicGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.GameplayAdmissionLeaseState;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.ReconcileGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ReconcileGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionAbortOwner;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionAbortOwnerTest;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionLeaseRepository;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** Transport/owner doubles only; no registration, actual TLS or source-authority proof. */
class AccountGameplayAdmissionLeaseGrpcServiceTest {
  @Test
  void encodesExactAbortedCarrierAndUnknownDecisionOnly() {
    var owner = mock(AccountGameplayAdmissionAbortOwner.class);
    UUID cleanup = UUID.randomUUID();
    when(owner.abort(any()))
        .thenReturn(
            new AccountGameplayAdmissionLeaseOperation(
                AccountGameplayAdmissionAbortOwnerTest.fixture(), State.ABORTED, null, cleanup));
    var observer = new Capture<AbortGameplayAdmissionLeaseResponse>();
    new AccountGameplayAdmissionLeaseGrpcService(owner)
        .abortGameplayAdmissionLease(AccountGameplayAdmissionAbortOwnerTest.request(), observer);
    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    var operation = observer.value.getOperation();
    assertThat(operation.getState())
        .isEqualTo(GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED);
    assertThat(operation.getPendingOrphanCleanup()).isTrue();
    assertThat(operation.hasBindingDecisionId()).isFalse();
    assertThat(operation.getOrphanCleanupId()).isEqualTo(cleanup.toString());
    assertThat(AccountGameplayAdmissionLeaseWireCodec.validateOperation(operation))
        .isEqualTo(AccountGameplayAdmissionAbortOwnerTest.fixture());
  }

  @Test
  void committedCannotEscapeAbortTransport() {
    var owner = mock(AccountGameplayAdmissionAbortOwner.class);
    when(owner.abort(any()))
        .thenReturn(
            new AccountGameplayAdmissionLeaseOperation(
                AccountGameplayAdmissionAbortOwnerTest.fixture(),
                State.COMMITTED,
                UUID.randomUUID(),
                null));
    var observer = new Capture<AbortGameplayAdmissionLeaseResponse>();
    new AccountGameplayAdmissionLeaseGrpcService(owner)
        .abortGameplayAdmissionLease(AccountGameplayAdmissionAbortOwnerTest.request(), observer);
    assertFailure(observer, Status.Code.UNAVAILABLE);
  }

  @Test
  void actualOwnerDomainDenialsAreTypedButStorageFailuresAreUnavailable() {
    var stored =
        new java.util.concurrent.atomic.AtomicReference<
            Optional<AccountGameplayAdmissionLeaseOperation>>(Optional.empty());
    var repository =
        mock(
            AccountGameplayAdmissionLeaseRepository.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("readExact")) return stored.get();
              return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    var service =
        new AccountGameplayAdmissionLeaseGrpcService(
            new AccountGameplayAdmissionAbortOwner(repository, manager, "test"));
    UUID decision = UUID.randomUUID();
    var aborted =
        new AccountGameplayAdmissionLeaseOperation(
            AccountGameplayAdmissionAbortOwnerTest.fixture(),
            State.ABORTED,
            decision,
            UUID.randomUUID());
    for (String code :
        List.of(
            "ADMISSION_LEASE_UNRESOLVED",
            "ADMISSION_LEASE_NOT_ABORTABLE",
            "IDEMPOTENCY_CONFLICT")) {
      if (code.equals("ADMISSION_LEASE_NOT_ABORTABLE"))
        stored.set(
            Optional.of(
                new AccountGameplayAdmissionLeaseOperation(
                    AccountGameplayAdmissionAbortOwnerTest.fixture(),
                    State.COMMITTED,
                    decision,
                    null)));
      if (code.equals("IDEMPOTENCY_CONFLICT")) stored.set(Optional.of(aborted));
      var observer = new Capture<AbortGameplayAdmissionLeaseResponse>();
      peer()
          .run(
              () ->
                  service.abortGameplayAdmissionLease(
                      AccountGameplayAdmissionAbortOwnerTest.request(), observer));
      assertThat(observer.failure).isNull();
      assertThat(observer.completed).isTrue();
      assertThat(observer.value.hasOperation()).isFalse();
      assertThat(observer.value.getDenied().getCode()).isEqualTo(code);
      assertThat(observer.value.getDenied().getMessage())
          .isEqualTo("Account admission abort denied");
    }
    var failingRepository =
        mock(
            AccountGameplayAdmissionLeaseRepository.class,
            invocation -> {
              throw new IllegalStateException("secret storage read failure");
            });
    var unavailable = new Capture<AbortGameplayAdmissionLeaseResponse>();
    var failingService =
        new AccountGameplayAdmissionLeaseGrpcService(
            new AccountGameplayAdmissionAbortOwner(failingRepository, manager, "test"));
    peer()
        .run(
            () ->
                failingService.abortGameplayAdmissionLease(
                    AccountGameplayAdmissionAbortOwnerTest.request(), unavailable));
    assertFailure(unavailable, Status.Code.UNAVAILABLE);
    assertThat(Status.fromThrowable(unavailable.failure).getDescription()).doesNotContain("secret");
  }

  @Test
  void rejectsMalformedOuterReferenceAndClosedJsonBeforeSql() {
    var repository = mock(AccountGameplayAdmissionLeaseRepository.class);
    var manager = mock(PlatformTransactionManager.class);
    var service =
        new AccountGameplayAdmissionLeaseGrpcService(
            new AccountGameplayAdmissionAbortOwner(repository, manager, "test"));
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    var original = AccountGameplayAdmissionAbortOwnerTest.request();
    var reference = original.getLease();
    for (var request :
        List.of(
            AbortGameplayAdmissionLeaseRequest.getDefaultInstance(),
            original.toBuilder().setUnknownFields(unknown).build(),
            original.toBuilder().setLease(reference.toBuilder().setUnknownFields(unknown)).build(),
            original.toBuilder()
                .setLease(reference.toBuilder().setRequestId(UUID.randomUUID().toString()))
                .build(),
            original.toBuilder()
                .setLease(reference.toBuilder().setEvidenceSha256("0".repeat(64)))
                .build(),
            original.toBuilder()
                .setLease(
                    reference.toBuilder().setEvidenceCanonicalJson(ByteString.copyFromUtf8("{}")))
                .build(),
            original.toBuilder()
                .setLease(
                    reference.toBuilder()
                        .setEvidenceCanonicalJson(
                            ByteString.copyFromUtf8(
                                reference
                                    .getEvidenceCanonicalJson()
                                    .toStringUtf8()
                                    .replace(
                                        "\"schemaVersion\":\"1\"", "\"schemaVersion\":\"2\""))))
                .build())) {
      var observer = new Capture<AbortGameplayAdmissionLeaseResponse>();
      peer().run(() -> service.abortGameplayAdmissionLease(request, observer));
      assertFailure(observer, Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(repository, manager);
  }

  @Test
  void returnsBoundedRedactedErrorsWithoutSuccessfulOutcome() {
    var owner = mock(AccountGameplayAdmissionAbortOwner.class);
    var service = new AccountGameplayAdmissionLeaseGrpcService(owner);
    for (var failure :
        List.of(
            new IllegalArgumentException("secret evidence"),
            new IllegalStateException("secret evidence"),
            new RuntimeException("secret evidence"))) {
      doThrow(failure).when(owner).abort(any());
      var observer = new Capture<AbortGameplayAdmissionLeaseResponse>();
      service.abortGameplayAdmissionLease(
          AccountGameplayAdmissionAbortOwnerTest.request(), observer);
      assertThat(observer.value).isNull();
      assertThat(observer.completed).isFalse();
      assertThat(Status.fromThrowable(observer.failure).getDescription())
          .doesNotContain("secret evidence");
      assertThat(observer.failure.getCause()).isNull();
    }
  }

  @Test
  void everyOtherMethodRemainsUnimplemented() {
    var owner = mock(AccountGameplayAdmissionAbortOwner.class);
    var service = new AccountGameplayAdmissionLeaseGrpcService(owner);
    var acquire = new Capture<AcquirePublicGameplayAdmissionLeaseResponse>();
    var finalize = new Capture<FinalizeGameplayAdmissionLeaseResponse>();
    var read = new Capture<ReadGameplayAdmissionLeaseResponse>();
    var reconcile = new Capture<ReconcileGameplayAdmissionLeaseResponse>();
    service.acquirePublicGameplayAdmissionLease(
        AcquirePublicGameplayAdmissionLeaseRequest.getDefaultInstance(), acquire);
    service.finalizeGameplayAdmissionLease(
        FinalizeGameplayAdmissionLeaseRequest.getDefaultInstance(), finalize);
    service.readGameplayAdmissionLease(
        ReadGameplayAdmissionLeaseRequest.getDefaultInstance(), read);
    service.reconcileGameplayAdmissionLease(
        ReconcileGameplayAdmissionLeaseRequest.getDefaultInstance(), reconcile);
    for (var observer : List.of(acquire, finalize, read, reconcile))
      assertFailure(observer, Status.Code.UNIMPLEMENTED);
    verifyNoInteractions(owner);
  }

  private static Context peer() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service")
                .orElseThrow());
  }

  private static void assertFailure(Capture<?> observer, Status.Code code) {
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    assertThat(Status.fromThrowable(observer.failure).getCode()).isEqualTo(code);
  }

  private static final class Capture<T> implements StreamObserver<T> {
    private T value;
    private Throwable failure;
    private boolean completed;

    @Override
    public void onNext(T value) {
      this.value = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "Test observer retains the exact supplied failure for redaction assertions.")
    public void onError(Throwable failure) {
      this.failure = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
