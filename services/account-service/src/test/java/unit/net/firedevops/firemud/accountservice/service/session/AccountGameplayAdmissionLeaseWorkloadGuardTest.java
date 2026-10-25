package net.firedevops.firemud.accountservice.service.session;

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
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseServiceGrpc;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountGameplayAdmissionLeaseWorkloadGuardTest {
  private static final String NAMESPACE = "game-session-admission-test";
  private final AccountGameplayAdmissionLeaseWorkloadGuard guard =
      new AccountGameplayAdmissionLeaseWorkloadGuard(NAMESPACE);

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void authenticatesBeforeStartingAnyOfTheFourAllowedHandlers() {
    for (MethodDescriptor<?, ?> method : leaseMethods()) {
      Invocation missing = invoke(method, null);
      assertThat(missing.nextStarts()).isZero();
      assertThat(missing.status().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThat(missing.status().getDescription())
          .isEqualTo("Exact same-namespace Game Session workload required");
      assertThat(missing.status().getCause()).isNull();

      Invocation wrongService = invoke(method, peer(NAMESPACE, "account-service"));
      assertThat(wrongService.nextStarts()).isZero();
      assertThat(wrongService.status().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);

      Invocation wrongNamespace = invoke(method, peer("other", "game-session-service"));
      assertThat(wrongNamespace.nextStarts()).isZero();
      assertThat(wrongNamespace.status().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
  }

  @Test
  void onlyTheFourExactGeneratedLeaseDescriptorsReachTheNextHandler() {
    for (MethodDescriptor<?, ?> method : leaseMethods()) {
      Invocation accepted = invoke(method, peer(NAMESPACE, "game-session-service"));
      assertThat(accepted.nextStarts()).isEqualTo(1);
      assertThat(accepted.status()).isNull();
    }

    MethodDescriptor<?, ?> otherPurpose = AccountServiceGrpc.getGetProfileMethod();
    Invocation rejected = invoke(otherPurpose, peer(NAMESPACE, "game-session-service"));
    assertThat(rejected.nextStarts()).isZero();
    assertThat(rejected.status().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(rejected.status().getDescription())
        .isEqualTo("Account gameplay admission lease method required");
    assertThat(rejected.status().getCause()).isNull();
  }

  @Test
  void rejectsEndUserAndGenericInternalSessionContextsBeforeStartingHandler() {
    for (boolean internal : List.of(false, true)) {
      if (internal) {
        SessionContext.setContext(
            null, List.of(), Map.of(), true, "game-session-service", "instance");
      } else {
        SessionContext.setContext("123", List.of(), Map.of());
      }
      Invocation rejected =
          invoke(
              AccountGameplayAdmissionLeaseServiceGrpc.getReadGameplayAdmissionLeaseMethod(),
              peer(NAMESPACE, "game-session-service"));
      assertThat(rejected.nextStarts()).isZero();
      assertThat(rejected.status().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
      SessionContext.clear();
    }
  }

  private static List<MethodDescriptor<?, ?>> leaseMethods() {
    return List.of(
        AccountGameplayAdmissionLeaseServiceGrpc.getAcquireGameplayAdmissionLeaseMethod(),
        AccountGameplayAdmissionLeaseServiceGrpc.getFinalizeGameplayAdmissionLeaseMethod(),
        AccountGameplayAdmissionLeaseServiceGrpc.getReadGameplayAdmissionLeaseMethod(),
        AccountGameplayAdmissionLeaseServiceGrpc.getAbortGameplayAdmissionLeaseMethod());
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
    org.mockito.ArgumentCaptor<Status> status = org.mockito.ArgumentCaptor.forClass(Status.class);
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
