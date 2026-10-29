package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionNeverAdmittedGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayAdmissionDecisionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayAdmissionDecisionReadGrpcService;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import net.firedevops.firemud.gamesession.v1.GetOriginalNewNeverAdmittedDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetOriginalNewNeverAdmittedDecisionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled parser, repository, codec and callback failure tests; native owner composition is a
 * separate PostgreSQL test.
 */
class CanonicalGameplayAdmissionDecisionReadGrpcServiceTest {
  @Test
  void deniesNonAccountCallerBeforeParsingOrRepositoryRead() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    doThrow(Status.PERMISSION_DENIED.asRuntimeException()).when(guard).requireAccountCaller();
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
          .getCanonicalGameplayAdmissionDecision(request, observer);
      codec.verify(
          () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request), never());
    }

    assertFailure(observer, Status.Code.PERMISSION_DENIED, null);
    verify(guard).requireAccountCaller();
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsAmbientTransactionBeforeParsingOrRepositoryRead() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
      try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
        new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
            .getCanonicalGameplayAdmissionDecision(request, observer);
        codec.verify(
            () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request), never());
      }
      assertFailure(observer, Status.Code.FAILED_PRECONDITION, null);
      verify(guard).requireAccountCaller();
      verifyNoInteractions(repository);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void rejectsSynchronizationWithoutTransactionBeforeParsingOrRepositoryRead() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    try {
      TransactionSynchronizationManager.initSynchronization();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
        new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
            .getCanonicalGameplayAdmissionDecision(request, observer);
        codec.verify(
            () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request), never());
      }
      assertFailure(observer, Status.Code.FAILED_PRECONDITION, null);
      verify(guard).requireAccountCaller();
      verifyNoInteractions(repository);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void invalidAndOversizedRequestsFailBeforeRepositoryRead() {
    var invalid = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    var oversized =
        GetCanonicalGameplayAdmissionDecisionRequest.newBuilder()
            .mergeUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        100,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(
                                ByteString.copyFrom(
                                    new byte
                                        [CanonicalGameplayAdmissionDecisionReadGrpcCodec
                                                .MAX_REQUEST_WIRE_BYTES
                                            + 1]))
                            .build())
                    .build())
            .build();

    for (var request : new GetCanonicalGameplayAdmissionDecisionRequest[] {invalid, oversized}) {
      var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
      var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
      var observer = observer();
      new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
          .getCanonicalGameplayAdmissionDecision(request, observer);

      assertFailure(observer, Status.Code.INVALID_ARGUMENT, null);
      verify(guard).requireAccountCaller();
      verifyNoInteractions(repository);
    }
  }

  @Test
  void negativeDecisionRejectsNullAndOversizedRequestsBeforeParsingOrOwnerReads() {
    var oversized =
        GetOriginalNewNeverAdmittedDecisionRequest.newBuilder()
            .mergeUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        100,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(
                                ByteString.copyFrom(
                                    new byte
                                        [AccountGameplayAdmissionNeverAdmittedGrpcCodec
                                                .MAX_WIRE_BYTES
                                            + 1]))
                            .build())
                    .build())
            .build();

    for (var request : new GetOriginalNewNeverAdmittedDecisionRequest[] {null, oversized}) {
      var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
      var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
      var closureOwner = mock(CanonicalGameplayOriginalNewCandidateClosureRedisOwner.class);
      var observer = negativeObserver();
      try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
        new CanonicalGameplayAdmissionDecisionReadGrpcService(
                repository, guard, "test", closureOwner)
            .getOriginalNewNeverAdmittedDecision(request, observer);
        codec.verify(
            () -> AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(request),
            never());
      }

      assertNegativeFailure(observer, Status.Code.INVALID_ARGUMENT, null);
      verify(guard).requireAccountCaller();
      verifyNoInteractions(repository, closureOwner);
    }
  }

  @Test
  void unexpectedNegativeDecisionParserFailureIsSanitizedInternalBeforeOwnerReads() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var closureOwner = mock(CanonicalGameplayOriginalNewCandidateClosureRedisOwner.class);
    var observer = negativeObserver();
    var request = GetOriginalNewNeverAdmittedDecisionRequest.getDefaultInstance();
    try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
      codec
          .when(() -> AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(request))
          .thenThrow(new IllegalStateException("private negative parser details"));
      new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test", closureOwner)
          .getOriginalNewNeverAdmittedDecision(request, observer);
    }

    assertNegativeFailure(observer, Status.Code.INTERNAL, "private negative parser details");
    verify(guard).requireAccountCaller();
    verifyNoInteractions(repository, closureOwner);
  }

  @Test
  void rejectsMismatchedLeaseKindNamespaceAndCallerBeforeRepositoryRead() {
    var cases =
        new Runnable[] {
          () -> {
            var fixture = validReadFixture();
            when(fixture.selector().expectedLeaseEvidence().leaseKind())
                .thenReturn(
                    net.firedevops.firemud.common.account.admission
                        .AccountGameplayAdmissionLeaseEvidence.LeaseKind.RESUME);
            assertDeniedLease(fixture);
          },
          () -> {
            var fixture = validReadFixture();
            when(fixture.selector().expectedLeaseEvidence().carrier())
                .thenReturn(Map.of("targetNamespace", "other", "callerWorkload", accountPeer()));
            assertDeniedLease(fixture);
          },
          () -> {
            var fixture = validReadFixture();
            when(fixture.selector().expectedLeaseEvidence().carrier())
                .thenReturn(Map.of("targetNamespace", "test", "callerWorkload", "other"));
            assertDeniedLease(fixture);
          }
        };

    for (var testCase : cases) testCase.run();
  }

  @Test
  void unexpectedRepositoryFailureTerminatesOnceWithSanitizedInternal() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenThrow(new IllegalArgumentException("private repository programming details"));
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(
        fixture.observer(), Status.Code.INTERNAL, "private repository programming details");
    verify(fixture.repository())
        .readAdmissionDecision(
            fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence());
  }

  @Test
  void repositoryConflictRemainsFailedPrecondition() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenThrow(new CanonicalGameplayBindingInventoryConflictException("decision changed"));
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.FAILED_PRECONDITION, null);
  }

  @Test
  void repositoryIllegalStateConflictRemainsFailedPrecondition() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenThrow(new IllegalStateException("stale decision"));
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.FAILED_PRECONDITION, null);
  }

  @Test
  void databaseOutageRemainsUnavailable() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.UNAVAILABLE, null);
  }

  @Test
  void unexpectedResponseEncodingFailureTerminatesOnceWithInternalWithoutAnswer() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenReturn(fixture.decision());
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toResponse(
                      any(), any(), any(), any(), any(), any(), any(), any()))
          .thenThrow(new IllegalStateException("private response codec details"));
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.INTERNAL, "private response codec details");
    verify(fixture.repository())
        .readAdmissionDecision(
            fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence());
  }

  @Test
  void unexpectedParserFailureTerminatesOnceWithSanitizedInternalBeforeRepositoryRead() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(() -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request))
          .thenThrow(new IllegalStateException("private parser details"));
      new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
          .getCanonicalGameplayAdmissionDecision(request, observer);
    }
    var failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(failure.getValue()).hasMessageNotContaining("private parser details");
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
    verifyNoInteractions(repository);
  }

  @Test
  void onNextFailureSignalsOneErrorButCompletionFailureEscapesWithoutSecondTerminal() {
    for (boolean completionFailure : new boolean[] {false, true}) {
      var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
      var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
      var observer = observer();
      var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
      var source = CanonicalGameplayAdmissionAbortOwnerTest.source(false);
      var decision = source.originalDecision();
      var lease = decision.leaseEvidence();
      when(lease.carrier())
          .thenReturn(
              Map.of(
                  "targetNamespace",
                  "test",
                  "callerWorkload",
                  "spiffe://firemud/ns/test/sa/game-session-service"));
      var selector = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence.class);
      when(selector.expectedLeaseEvidence()).thenReturn(lease);
      var decisionId = decision.bindingDecisionId();
      when(selector.bindingDecisionId()).thenReturn(decisionId);
      when(repository.readAdmissionDecision(decision.bindingDecisionId(), lease))
          .thenReturn(decision);
      var response = GetCanonicalGameplayAdmissionDecisionResponse.getDefaultInstance();
      var callbackFailure = new IllegalStateException("observer callback failed");
      if (completionFailure) doThrow(callbackFailure).when(observer).onCompleted();
      else doThrow(callbackFailure).when(observer).onNext(response);
      try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
        codec
            .when(() -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request))
            .thenReturn(selector);
        codec
            .when(
                () ->
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.toResponse(
                        any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(response);
        Runnable receive =
            () ->
                new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
                    .getCanonicalGameplayAdmissionDecision(request, observer);
        if (completionFailure) assertThatThrownBy(receive::run).isSameAs(callbackFailure);
        else receive.run();
      }
      verify(observer).onNext(response);
      if (completionFailure) {
        verify(observer).onCompleted();
        verify(observer, never()).onError(any());
      } else {
        verify(observer).onError(any());
        verify(observer, never()).onCompleted();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static StreamObserver<GetCanonicalGameplayAdmissionDecisionResponse> observer() {
    return mock(StreamObserver.class);
  }

  private static ReadFixture validReadFixture() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    var decision = CanonicalGameplayAdmissionAbortOwnerTest.source(false).originalDecision();
    var lease = decision.leaseEvidence();
    when(lease.carrier())
        .thenReturn(
            Map.of(
                "targetNamespace",
                "test",
                "callerWorkload",
                "spiffe://firemud/ns/test/sa/game-session-service"));
    var selector = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence.class);
    when(selector.expectedLeaseEvidence()).thenReturn(lease);
    var decisionId = decision.bindingDecisionId();
    when(selector.bindingDecisionId()).thenReturn(decisionId);
    return new ReadFixture(repository, guard, observer, request, selector, decision);
  }

  private static void assertDeniedLease(ReadFixture fixture) {
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.PERMISSION_DENIED, null);
    verify(fixture.guard()).requireAccountCaller();
    verifyNoInteractions(fixture.repository());
  }

  private static String accountPeer() {
    return "spiffe://firemud/ns/test/sa/game-session-service";
  }

  @SuppressWarnings("unchecked")
  private static StreamObserver<GetOriginalNewNeverAdmittedDecisionResponse> negativeObserver() {
    return mock(StreamObserver.class);
  }

  private static void assertNegativeFailure(
      StreamObserver<GetOriginalNewNeverAdmittedDecisionResponse> observer,
      Status.Code expectedCode,
      String privateDetail) {
    var failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode()).isEqualTo(expectedCode);
    if (privateDetail != null) {
      assertThat(failure.getValue()).hasMessageNotContaining(privateDetail);
    }
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
  }

  private static void assertFailure(
      StreamObserver<GetCanonicalGameplayAdmissionDecisionResponse> observer,
      Status.Code expectedCode,
      String privateDetail) {
    var failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode()).isEqualTo(expectedCode);
    if (privateDetail != null) {
      assertThat(failure.getValue()).hasMessageNotContaining(privateDetail);
    }
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
  }

  private record ReadFixture(
      CanonicalGameplayBindingInventoryRepository repository,
      CanonicalGameplayAdmissionDecisionReadWorkloadGuard guard,
      StreamObserver<GetCanonicalGameplayAdmissionDecisionResponse> observer,
      GetCanonicalGameplayAdmissionDecisionRequest request,
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence selector,
      CanonicalGameplayAdmissionDecision decision) {}
}
