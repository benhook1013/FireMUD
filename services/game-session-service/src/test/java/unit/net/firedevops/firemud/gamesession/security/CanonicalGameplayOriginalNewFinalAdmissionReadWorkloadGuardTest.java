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
import net.firedevops.firemud.gamesession.v1.OriginalNewFinalAdmissionReadServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Controlled identity/context fixtures for the manually composed workload guard. */
class CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuardTest {
  private static final String NAMESPACE = "game-session-final-read-test";
  private final CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard guard =
      new CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard(NAMESPACE);

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void acceptsOnlyTheExactSameNamespaceAccountMethod() {
    var method = OriginalNewFinalAdmissionReadServiceGrpc.getReadOriginalNewFinalAdmissionMethod();
    Invocation accepted = invoke(method, peer(NAMESPACE, "account-service"));
    assertThat(accepted.nextStarts()).isEqualTo(1);
    assertThat(accepted.status()).isNull();

    Invocation wrongNamespace = invoke(method, peer("other-namespace", "account-service"));
    assertDenied(wrongNamespace, Status.Code.PERMISSION_DENIED);

    MethodDescriptor<?, ?> wrongMethod =
        method.toBuilder()
            .setFullMethodName(
                "game_session.v1.GameSessionControlPlaneService/ReadOriginalNewFinalAdmission")
            .build();
    assertDenied(
        invoke(wrongMethod, peer(NAMESPACE, "account-service")), Status.Code.PERMISSION_DENIED);
  }

  @Test
  void refusesMissingForeignAndPlayerOrSyntheticCallerContexts() {
    var method = OriginalNewFinalAdmissionReadServiceGrpc.getReadOriginalNewFinalAdmissionMethod();
    assertDenied(invoke(method, null), Status.Code.UNAUTHENTICATED);
    assertDenied(
        invoke(method, peer(NAMESPACE, "world-management-service")), Status.Code.PERMISSION_DENIED);

    SessionContext.setContext("account-1", List.of(), Map.of());
    assertDenied(invoke(method, peer(NAMESPACE, "account-service")), Status.Code.PERMISSION_DENIED);
    SessionContext.clear();
    SessionContext.setContext(null, List.of(), Map.of(), true, "account-service", "instance");
    assertDenied(invoke(method, peer(NAMESPACE, "account-service")), Status.Code.PERMISSION_DENIED);
  }

  private static void assertDenied(Invocation invocation, Status.Code code) {
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
