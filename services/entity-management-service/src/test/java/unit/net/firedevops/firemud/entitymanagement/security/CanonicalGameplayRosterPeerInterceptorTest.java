package net.firedevops.firemud.entitymanagement.security;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

class CanonicalGameplayRosterPeerInterceptorTest {
  private static final String WORKLOAD_NAMESPACE = "gameplay";

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void acceptsOnlyExactSameNamespaceGameSessionCertificate() {
    CanonicalGameplayRosterPeerInterceptor interceptor =
        new CanonicalGameplayRosterPeerInterceptor(WORKLOAD_NAMESPACE);
    ServerCall<String, String> call =
        callFor(
            CanonicalGameplayRosterPeerInterceptor.METHOD_NAME,
            peerSession("spiffe://firemud/ns/gameplay/sa/game-session-service"));
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    ServerCall.Listener<String> listener = new ServerCall.Listener<>() {};
    when(next.startCall(any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(CanonicalGameplayRosterPeerInterceptor.currentVerifiedPeer())
                  .isNotNull()
                  .satisfies(
                      peer -> {
                        assertThat(peer.isService("game-session-service")).isTrue();
                        assertThat(peer.isInNamespace(WORKLOAD_NAMESPACE)).isTrue();
                      });
              return listener;
            });

    interceptor.interceptCall(call, new Metadata(), next);

    verify(next).startCall(any(), any());
  }

  @Test
  void selectedAssignmentMethodUsesTheSameNamespaceAndEndUserContextGuard() {
    CanonicalGameplayRosterPeerInterceptor interceptor =
        new CanonicalGameplayRosterPeerInterceptor(WORKLOAD_NAMESPACE);
    ServerCall<String, String> trustedCall =
        callFor(
            CanonicalGameplayRosterPeerInterceptor.SELECTED_ASSIGNMENT_METHOD_NAME,
            peerSession("spiffe://firemud/ns/gameplay/sa/game-session-service"));
    ServerCallHandler<String, String> trustedNext = mock(ServerCallHandler.class);
    when(trustedNext.startCall(any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(CanonicalGameplayRosterPeerInterceptor.currentVerifiedPeer()).isNotNull();
              return new ServerCall.Listener<>() {};
            });

    interceptor.interceptCall(trustedCall, new Metadata(), trustedNext);

    verify(trustedNext).startCall(any(), any());

    ServerCall<String, String> wrongNamespaceCall =
        callFor(
            CanonicalGameplayRosterPeerInterceptor.SELECTED_ASSIGNMENT_METHOD_NAME,
            peerSession("spiffe://firemud/ns/other/sa/game-session-service"));
    ServerCallHandler<String, String> wrongNamespaceNext = mock(ServerCallHandler.class);
    interceptor.interceptCall(wrongNamespaceCall, new Metadata(), wrongNamespaceNext);
    verify(wrongNamespaceCall)
        .close(
            org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == Status.Code.PERMISSION_DENIED),
            any(Metadata.class));
    verify(wrongNamespaceNext, never()).startCall(any(), any());

    ServerCall<String, String> endUserCall =
        callFor(
            CanonicalGameplayRosterPeerInterceptor.SELECTED_ASSIGNMENT_METHOD_NAME,
            peerSession("spiffe://firemud/ns/gameplay/sa/game-session-service"));
    ServerCallHandler<String, String> endUserNext = mock(ServerCallHandler.class);
    SessionContext.setContext("player-claim", List.of(), java.util.Map.of());
    interceptor.interceptCall(endUserCall, new Metadata(), endUserNext);
    verify(endUserCall)
        .close(
            org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == Status.Code.PERMISSION_DENIED),
            any(Metadata.class));
    verify(endUserNext, never()).startCall(any(), any());
  }

  @Test
  void applicationYamlBypassesJwtForOnlyTheTwoExactRosterMethods() {
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
    Properties properties = yaml.getObject();
    assertThat(properties).isNotNull();
    List<String> publicMethods =
        properties.stringPropertyNames().stream()
            .filter(name -> name.startsWith("firemud.auth.grpc.public-methods["))
            .map(name -> name.substring(name.indexOf('[') + 1, name.indexOf(']')))
            .map(Integer::parseInt)
            .sorted(Comparator.naturalOrder())
            .map(index -> properties.getProperty("firemud.auth.grpc.public-methods[" + index + "]"))
            .toList();

    assertThat(
            publicMethods.stream()
                .filter(
                    method ->
                        method.startsWith("entity_management.v1.CanonicalGameplayRosterService/")))
        .containsExactly(
            CanonicalGameplayRosterPeerInterceptor.METHOD_NAME,
            CanonicalGameplayRosterPeerInterceptor.SELECTED_ASSIGNMENT_METHOD_NAME);
    assertThat(publicMethods).noneMatch(method -> method.contains("*"));
  }

  @Test
  void rejectsAbsentWrongNamespaceAndNonGameSessionCertificates() {
    CanonicalGameplayRosterPeerInterceptor interceptor =
        new CanonicalGameplayRosterPeerInterceptor(WORKLOAD_NAMESPACE);
    for (SSLSession session :
        Arrays.asList(
            null,
            peerSession("spiffe://firemud/ns/other/sa/game-session-service"),
            peerSession("spiffe://firemud/ns/gameplay/sa/account-service"))) {
      ServerCall<String, String> call =
          callFor(CanonicalGameplayRosterPeerInterceptor.METHOD_NAME, session);
      ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

      interceptor.interceptCall(call, new Metadata(), next);

      verify(call)
          .close(
              org.mockito.ArgumentMatchers.argThat(
                  status ->
                      status.getCode() == Status.Code.PERMISSION_DENIED
                          && "Trusted Game Session peer required".equals(status.getDescription())),
              any(Metadata.class));
      verify(next, never()).startCall(any(), any());
    }
  }

  @Test
  void rejectsBearerPlayerContextEvenWhenCertificateIsTrusted() {
    CanonicalGameplayRosterPeerInterceptor interceptor =
        new CanonicalGameplayRosterPeerInterceptor(WORKLOAD_NAMESPACE);
    ServerCall<String, String> call =
        callFor(
            CanonicalGameplayRosterPeerInterceptor.METHOD_NAME,
            peerSession("spiffe://firemud/ns/gameplay/sa/game-session-service"));
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    SessionContext.setContext("player-claim", List.of(), java.util.Map.of());

    interceptor.interceptCall(call, new Metadata(), next);

    verify(call)
        .close(
            org.mockito.ArgumentMatchers.argThat(
                status ->
                    status.getCode() == Status.Code.PERMISSION_DENIED
                        && "Trusted Game Session peer required".equals(status.getDescription())),
            any(Metadata.class));
    verify(next, never()).startCall(any(), any());
  }

  @Test
  void unrelatedRpcPassesThroughWithoutRosterCertificateGuard() {
    CanonicalGameplayRosterPeerInterceptor interceptor =
        new CanonicalGameplayRosterPeerInterceptor(WORKLOAD_NAMESPACE);
    ServerCall<String, String> call =
        callFor("entity_management.v1.EntityManagementService/Ping", null);
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
