package unit.net.firedevops.firemud.entitymanagement.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterPeerInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountBootstrapRosterPeerInterceptorTest {
  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void exactAccountCertificateIsAvailableBeforeMessageHandling() throws Exception {
    var call = call("spiffe://firemud/ns/test/sa/account-service");
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    when(next.startCall(any(), any()))
        .thenAnswer(
            ignored -> {
              assertThat(GrpcPeerIdentity.current().isService("account-service")).isTrue();
              assertThat(GrpcPeerIdentity.current().isInNamespace("test")).isTrue();
              return new ServerCall.Listener<String>() {};
            });
    new AccountBootstrapRosterPeerInterceptor("test").interceptCall(call, new Metadata(), next);
    verify(next).startCall(any(), any());
  }

  @Test
  void absentWrongNamespaceAndGameSessionPeersNeverStartMessageDecoding() throws Exception {
    for (String identity :
        new String[] {
          null,
          "spiffe://firemud/ns/other/sa/account-service",
          "spiffe://firemud/ns/test/sa/game-session-service"
        }) {
      var call = call(identity);
      ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
      new AccountBootstrapRosterPeerInterceptor("test").interceptCall(call, new Metadata(), next);
      verify(next, never()).startCall(any(), any());
      verify(call)
          .close(
              argThat(
                  status ->
                      status.getCode()
                          == (identity == null
                              ? Status.Code.UNAUTHENTICATED
                              : Status.Code.PERMISSION_DENIED)),
              any());
    }
  }

  @Test
  void callerRoleAndInternalServiceContextsCannotSupplyDiscoveryAuthority() throws Exception {
    for (boolean internal : List.of(false, true)) {
      SessionContext.setContext(
          internal ? null : "11111111-1111-4111-8111-111111111111",
          internal ? List.of() : List.of("platformAdmin"),
          Map.of(),
          internal,
          "account-service",
          "instance");
      var call = call("spiffe://firemud/ns/test/sa/account-service");
      ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
      new AccountBootstrapRosterPeerInterceptor("test").interceptCall(call, new Metadata(), next);
      verify(next, never()).startCall(any(), any());
      verify(call)
          .close(argThat(status -> status.getCode() == Status.Code.PERMISSION_DENIED), any());
      SessionContext.clear();
    }
  }

  private static ServerCall<String, String> call(String identity) throws Exception {
    ServerCall<String, String> call = mock(ServerCall.class);
    MethodDescriptor<String, String> descriptor = mock(MethodDescriptor.class);
    when(descriptor.getFullMethodName())
        .thenReturn(AccountBootstrapRosterPeerInterceptor.METHOD_NAME);
    when(call.getMethodDescriptor()).thenReturn(descriptor);
    var attributes = Attributes.EMPTY;
    if (identity != null) {
      X509Certificate leaf = mock(X509Certificate.class);
      when(leaf.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, identity)));
      SSLSession session = mock(SSLSession.class);
      when(session.getPeerCertificates()).thenReturn(new Certificate[] {leaf});
      attributes = Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();
    }
    when(call.getAttributes()).thenReturn(attributes);
    return call;
  }
}
