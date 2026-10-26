package net.firedevops.firemud.gamesession.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProtectedGameSessionLoginGrpcInterceptorTest {
  @Test
  void passesActualDedicatedTerminalProofMethodToItsOwnerGuard() {
    verifyBoundary(
        CanonicalInitialAdmissionOwnerProofReadServiceGrpc
            .getGetCanonicalInitialAdmissionOwnerProofMethod(),
        true);
  }

  @Test
  void passesOnlyTheActualPurposeSpecificAdmissionDecisionReadMethod() {
    verifyBoundary(
        CanonicalGameplayAdmissionDecisionReadServiceGrpc
            .getGetCanonicalGameplayAdmissionDecisionMethod(),
        true);
    verifyBoundary(
        CanonicalGameplayAdmissionDecisionReadServiceGrpc
            .getGetCanonicalGameplayAdmissionDecisionMethod()
            .toBuilder()
            .setFullMethodName(
                "game_session.v1.GameSessionControlPlaneService/GetCanonicalGameplayAdmissionDecision")
            .build(),
        false);
    verifyBoundary(
        CanonicalGameplayAdmissionDecisionReadServiceGrpc
            .getGetCanonicalGameplayAdmissionDecisionMethod()
            .toBuilder()
            .setFullMethodName(
                "game_session.v1.GameSessionControlPlaneService/GetPublishedRealmAdmissionOwnerRead")
            .build(),
        false);
  }

  @Test
  void deniesUndeclaredControlPlaneAliasAndPlayerStartMethod() {
    verifyBoundary(
        CanonicalInitialAdmissionOwnerProofReadServiceGrpc
            .getGetCanonicalInitialAdmissionOwnerProofMethod()
            .toBuilder()
            .setFullMethodName(
                "game_session.v1.GameSessionControlPlaneService/GetCanonicalInitialAdmissionOwnerProof")
            .build(),
        false);
    verifyBoundary(GameSessionServiceGrpc.getStartSessionMethod(), false);
  }

  @SuppressWarnings("unchecked")
  private static <Req, Resp> void verifyBoundary(
      MethodDescriptor<Req, Resp> method, boolean allowed) {
    ServerCall<Req, Resp> call = mock(ServerCall.class);
    ServerCallHandler<Req, Resp> handler = mock(ServerCallHandler.class);
    Metadata headers = new Metadata();
    when(call.getMethodDescriptor()).thenReturn(method);
    ServerCall.Listener<Req> expected = new ServerCall.Listener<>() {};
    when(handler.startCall(call, headers)).thenReturn(expected);
    var result =
        new ProtectedGameSessionLoginGrpcInterceptor().interceptCall(call, headers, handler);
    if (allowed) {
      assertThat(result).isSameAs(expected);
      verify(call, never()).close(any(), any());
      verify(handler).startCall(call, headers);
    } else {
      verify(handler, never()).startCall(any(), any());
      ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
      verify(call).close(status.capture(), any());
      assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
  }
}
