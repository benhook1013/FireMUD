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

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayAdmissionDecisionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayAdmissionDecisionReadGrpcService;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Controlled parser, repository, codec and callback failure tests; native owner composition is a
 * separate PostgreSQL test.
 */
class CanonicalGameplayAdmissionDecisionReadGrpcServiceTest {
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
