package net.firedevops.firemud.gamesession.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CanonicalGameplayAdmissionDecisionReadWorkloadGuardTest {
  private static final String NAMESPACE = "game-session-admission-test";
  private final CanonicalGameplayAdmissionDecisionReadWorkloadGuard guard =
      new CanonicalGameplayAdmissionDecisionReadWorkloadGuard(NAMESPACE);

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void acceptsOnlyTheExactSameNamespaceAccountMethod() {
    Invocation accepted =
        invoke(
            CanonicalGameplayAdmissionDecisionReadServiceGrpc
                .getGetCanonicalGameplayAdmissionDecisionMethod(),
            peer(NAMESPACE, "account-service"));
    assertThat(accepted.nextStarts()).isEqualTo(1);
    assertThat(accepted.status()).isNull();

    for (var method :
        List.of(
            GameSessionServiceGrpc.getStartSessionMethod(),
            CanonicalGameplayAdmissionDecisionReadServiceGrpc
                .getGetCanonicalGameplayAdmissionDecisionMethod()
                .toBuilder()
                .setFullMethodName(
                    "game_session.v1.GameSessionControlPlaneService/GetCanonicalGameplayAdmissionDecision")
                .build())) {
      Invocation rejected = invoke(method, peer(NAMESPACE, "account-service"));
      assertThat(rejected.nextStarts()).isZero();
      assertThat(rejected.status().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
  }

  @Test
  void rejectsMissingForeignOrSessionContextIdentityBeforeHandler() {
    var method =
        CanonicalGameplayAdmissionDecisionReadServiceGrpc
            .getGetCanonicalGameplayAdmissionDecisionMethod();
    assertRejected(invoke(method, null), Status.Code.UNAUTHENTICATED);
    assertRejected(
        invoke(method, peer(NAMESPACE, "world-management-service")), Status.Code.PERMISSION_DENIED);
    assertRejected(invoke(method, peer("other", "account-service")), Status.Code.PERMISSION_DENIED);

    SessionContext.setContext("account-1", List.of(), Map.of());
    assertRejected(
        invoke(method, peer(NAMESPACE, "account-service")), Status.Code.PERMISSION_DENIED);
    SessionContext.setContext(null, List.of(), Map.of(), true, "account-service", "instance");
    assertRejected(
        invoke(method, peer(NAMESPACE, "account-service")), Status.Code.PERMISSION_DENIED);
  }

  private void assertRejected(Invocation invocation, Status.Code code) {
    assertThat(invocation.nextStarts()).isZero();
    assertThat(invocation.status().getCode()).isEqualTo(code);
  }

  private Invocation invoke(MethodDescriptor<?, ?> method, GrpcPeerIdentity peer) {
    return invokeTyped(method, peer);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Invocation invokeTyped(MethodDescriptor method, GrpcPeerIdentity peer) {
    ServerCall call = mock(ServerCall.class);
    org.mockito.Mockito.when(call.getMethodDescriptor()).thenReturn(method);
    AtomicInteger starts = new AtomicInteger();
    ServerCallHandler next =
        (ignoredCall, ignoredHeaders) -> {
          starts.incrementAndGet();
          return new ServerCall.Listener() {};
        };
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> guard.interceptCall(call, new Metadata(), next));
    ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(call, org.mockito.Mockito.atMostOnce())
        .close(status.capture(), any(Metadata.class));
    return new Invocation(starts.get(), status.getAllValues().isEmpty() ? null : status.getValue());
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private record Invocation(int nextStarts, Status status) {}
}
