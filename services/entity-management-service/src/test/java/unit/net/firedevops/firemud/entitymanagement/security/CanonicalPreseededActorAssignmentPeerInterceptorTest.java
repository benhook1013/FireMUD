package unit.net.firedevops.firemud.entitymanagement.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.security.CanonicalPreseededActorAssignmentPeerInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CanonicalPreseededActorAssignmentPeerInterceptorTest {
  private static final String NAMESPACE = "gameplay";
  private static final String EXACT_PEER = "spiffe://firemud/ns/gameplay/sa/game-session-service";
  private static final String EXACT_WORLD_PEER =
      "spiffe://firemud/ns/gameplay/sa/world-management-service";

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void rejectsInvalidTrustedNamespaceAtConstruction() {
    assertThatThrownBy(() -> new CanonicalPreseededActorAssignmentPeerInterceptor("bad/ns"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CanonicalPreseededActorAssignmentPeerInterceptor(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsOnlyTheExactSameNamespaceGameSessionCertificateForTheWriteMethod() {
    var interceptor = new CanonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE);
    var call =
        callFor(
            CanonicalPreseededActorAssignmentPeerInterceptor.METHOD_NAME, peerSession(EXACT_PEER));
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    when(next.startCall(any(), any()))
        .thenAnswer(
            invocation -> {
              var peer = CanonicalPreseededActorAssignmentPeerInterceptor.currentVerifiedPeer();
              assertThat(peer).isNotNull();
              assertThat(peer.uri()).isEqualTo(EXACT_PEER);
              assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
              assertThat(SessionContext.isInternalService()).isFalse();
              return new ServerCall.Listener<>() {};
            });

    interceptor.interceptCall(call, new Metadata(), next);

    verify(next).startCall(any(), any());
  }

  @Test
  void acceptsOnlyTheExactSameNamespaceWorldCertificateForTheReceiptReadMethod() {
    var interceptor = new CanonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE);
    var call =
        callFor(
            CanonicalPreseededActorAssignmentPeerInterceptor.RECEIPT_READ_METHOD_NAME,
            peerSession(EXACT_WORLD_PEER));
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    when(next.startCall(any(), any()))
        .thenAnswer(
            invocation -> {
              var peer =
                  CanonicalPreseededActorAssignmentPeerInterceptor.currentVerifiedWorldPeer();
              assertThat(peer).isNotNull();
              assertThat(peer.uri()).isEqualTo(EXACT_WORLD_PEER);
              assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
              assertThat(SessionContext.isInternalService()).isFalse();
              return new ServerCall.Listener<>() {};
            });

    interceptor.interceptCall(call, new Metadata(), next);

    verify(next).startCall(any(), any());
  }

  @Test
  void rejectsAbsentWrongServiceAndCrossNamespaceCertificatesBeforeHandler() {
    var interceptor = new CanonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE);
    for (SSLSession session :
        Arrays.asList(
            null,
            peerSession("spiffe://firemud/ns/other/sa/game-session-service"),
            peerSession("spiffe://firemud/ns/gameplay/sa/entity-management-service"))) {
      var call = callFor(CanonicalPreseededActorAssignmentPeerInterceptor.METHOD_NAME, session);
      ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

      interceptor.interceptCall(call, new Metadata(), next);

      verify(call)
          .close(
              org.mockito.ArgumentMatchers.argThat(
                  status -> status.getCode() == Status.Code.PERMISSION_DENIED),
              any(Metadata.class));
      verify(next, never()).startCall(any(), any());
    }
  }

  @Test
  void keepsWorldReadAndGameSessionWriteRolesDisjoint() {
    var interceptor = new CanonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE);
    for (String uri :
        List.of(
            EXACT_PEER,
            "spiffe://firemud/ns/gameplay/sa/account-service",
            "spiffe://firemud/ns/other/sa/world-management-service")) {
      var readCall =
          callFor(
              CanonicalPreseededActorAssignmentPeerInterceptor.RECEIPT_READ_METHOD_NAME,
              peerSession(uri));
      ServerCallHandler<String, String> readNext = mock(ServerCallHandler.class);

      interceptor.interceptCall(readCall, new Metadata(), readNext);

      verify(readCall)
          .close(
              org.mockito.ArgumentMatchers.argThat(
                  status -> status.getCode() == Status.Code.PERMISSION_DENIED),
              any(Metadata.class));
      verify(readNext, never()).startCall(any(), any());
    }

    var writeCall =
        callFor(
            CanonicalPreseededActorAssignmentPeerInterceptor.METHOD_NAME,
            peerSession(EXACT_WORLD_PEER));
    ServerCallHandler<String, String> writeNext = mock(ServerCallHandler.class);

    interceptor.interceptCall(writeCall, new Metadata(), writeNext);

    verify(writeCall)
        .close(
            org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == Status.Code.PERMISSION_DENIED),
            any(Metadata.class));
    verify(writeNext, never()).startCall(any(), any());
  }

  @Test
  void deniesEndUserAndInternalServiceJwtContextsEvenWithTrustedCertificate() {
    var interceptor = new CanonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE);
    for (boolean internal : List.of(false, true)) {
      SessionContext.setContext(
          internal ? null : "account-claim",
          internal ? List.of() : List.of("player"),
          java.util.Map.of(),
          internal,
          internal ? "game-session-service" : null,
          internal ? "fixture" : null);
      var call =
          callFor(
              CanonicalPreseededActorAssignmentPeerInterceptor.METHOD_NAME,
              peerSession(EXACT_PEER));
      ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

      interceptor.interceptCall(call, new Metadata(), next);

      verify(call)
          .close(
              org.mockito.ArgumentMatchers.argThat(
                  status -> status.getCode() == Status.Code.PERMISSION_DENIED),
              any(Metadata.class));
      verify(next, never()).startCall(any(), any());

      var receiptReadCall =
          callFor(
              CanonicalPreseededActorAssignmentPeerInterceptor.RECEIPT_READ_METHOD_NAME,
              peerSession(EXACT_WORLD_PEER));
      ServerCallHandler<String, String> receiptReadNext = mock(ServerCallHandler.class);

      interceptor.interceptCall(receiptReadCall, new Metadata(), receiptReadNext);

      verify(receiptReadCall)
          .close(
              org.mockito.ArgumentMatchers.argThat(
                  status -> status.getCode() == Status.Code.PERMISSION_DENIED),
              any(Metadata.class));
      verify(receiptReadNext, never()).startCall(any(), any());
      SessionContext.clear();
    }
  }

  @Test
  void unrelatedRpcPassesThroughWithoutRequiringTheAssignmentCertificate() {
    var interceptor = new CanonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE);
    var call = callFor("entity_management.v1.EntityManagementService/Ping", null);
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

    interceptor.interceptCall(call, new Metadata(), next);

    verify(next).startCall(any(), any());
  }

  private static ServerCall<String, String> callFor(String methodName, SSLSession session) {
    ServerCall<String, String> call = mock(ServerCall.class);
    MethodDescriptor<String, String> descriptor = mock(MethodDescriptor.class);
    when(descriptor.getFullMethodName()).thenReturn(methodName);
    when(call.getMethodDescriptor()).thenReturn(descriptor);
    Attributes attributes =
        session == null
            ? Attributes.EMPTY
            : Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();
    when(call.getAttributes()).thenReturn(attributes);
    return call;
  }

  private static SSLSession peerSession(String uri) {
    X509Certificate leaf = mock(X509Certificate.class);
    try {
      when(leaf.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, uri)));
      SSLSession session = mock(SSLSession.class);
      when(session.getPeerCertificates()).thenReturn(new Certificate[] {leaf});
      return session;
    } catch (java.security.cert.CertificateParsingException
        | javax.net.ssl.SSLPeerUnverifiedException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
