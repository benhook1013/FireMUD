package net.firedevops.firemud.accountservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountTenantCapacityWorkloadGuardTest {
  private final AccountTenantCapacityWorkloadGuard guard =
      new AccountTenantCapacityWorkloadGuard("firemud");

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void onlyExactTlsGameSessionAndMethodCanInstallPrivateAssertion() throws Exception {
    var method = AccountServiceGrpc.getCommitTenantCapacityAdmissionMethod();
    assertThat(
            invoke(
                method,
                tls("firemud", "game-session-service"),
                "spiffe://firemud/ns/firemud/sa/game-session-service"))
        .isNull();
    assertThat(
            invoke(
                method,
                tls("firemud", "world-management-service"),
                "spiffe://firemud/ns/firemud/sa/world-management-service"))
        .isNull();
    assertThatThrownBy(guard::requireAuthenticatedCaller)
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(invoke(method, tls("other", "game-session-service")).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(invoke(method, tls("firemud", "account-service")).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            invoke(AccountServiceGrpc.getGetProfileMethod(), tls("firemud", "game-session-service"))
                .getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  @Test
  void arbitraryPeerContextAndSessionClaimsNeverAuthenticate() throws Exception {
    var method = AccountServiceGrpc.getCommitTenantCapacityAdmissionMethod();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/firemud/sa/game-session-service")
                .orElseThrow())
        .run(
            () -> {
              assertThat(invoke(method, null).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
              assertThatThrownBy(guard::requireAuthenticatedCaller)
                  .isInstanceOf(StatusRuntimeException.class);
            });
    for (boolean internal : List.of(true, false)) {
      if (internal)
        SessionContext.setContext(
            null, List.of(), Map.of(), true, "game-session-service", "instance");
      else SessionContext.setContext("caller", List.of(), Map.of());
      assertThat(invoke(method, tls("firemud", "game-session-service")).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
      SessionContext.clear();
    }
  }

  private Status invoke(MethodDescriptor<?, ?> method, SSLSession ssl) {
    return invoke(method, ssl, null);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Status invoke(MethodDescriptor method, SSLSession ssl, String expectedCallerUri) {
    ServerCall call = mock(ServerCall.class);
    when(call.getMethodDescriptor()).thenReturn(method);
    when(call.getAttributes())
        .thenReturn(
            ssl == null
                ? Attributes.EMPTY
                : Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, ssl).build());
    AtomicInteger starts = new AtomicInteger();
    ServerCallHandler next =
        (ignored, headers) -> {
          assertThat(guard.requireAuthenticatedCaller()).isEqualTo(expectedCallerUri);
          starts.incrementAndGet();
          return new ServerCall.Listener() {};
        };
    guard.interceptCall(call, new Metadata(), next);
    var captured = ArgumentCaptor.forClass(Status.class);
    verify(call, org.mockito.Mockito.atMostOnce()).close(captured.capture(), any(Metadata.class));
    assertThat(starts.get()).isEqualTo(captured.getAllValues().isEmpty() ? 1 : 0);
    return captured.getAllValues().isEmpty() ? null : captured.getValue();
  }

  private static SSLSession tls(String namespace, String service) throws Exception {
    var leaf = mock(X509Certificate.class);
    when(leaf.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(6, "spiffe://firemud/ns/" + namespace + "/sa/" + service)));
    var ssl = mock(SSLSession.class);
    when(ssl.getPeerCertificates()).thenReturn(new Certificate[] {leaf});
    return ssl;
  }
}
