package unit.net.firedevops.firemud.gamesession.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayRosterOwnerReadGrpcAdapter;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * Exercises the configured JWT and TLS-peer gRPC interceptors around the Entity-only owner read.
 */
class CanonicalGameplayRosterOwnerReadInterceptorChainTest {
  private static final String NAMESPACE = "gameplay";
  private static final String ENTITY_SERVICE = "entity-management-service";
  private static final String METHOD =
      CanonicalGameplayRosterOwnerReadServiceGrpc.getGetCanonicalGameplayRosterOwnerReadMethod()
          .getFullMethodName();
  private static final UUID ACCOUNT = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");

  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void exactEntityCertificatePassesJwtBypassAndAbsentSourceRemainsUnavailable() {
    var provider = sourceReaderProvider();
    var response = new CapturingObserver();

    invokeThroughConfiguredChain(entityIdentity(NAMESPACE), adapter(provider), request(), response);

    assertThat(response.errorWasStatusRuntimeException).isTrue();
    assertThat(response.errorCode).isEqualTo(Status.Code.UNAVAILABLE);
    verify(provider).getIfAvailable();
  }

  @Test
  void absentWrongServiceCrossNamespaceAndPlayerCallerContextAreDenied() {
    var provider = sourceReaderProvider();
    var adapter = adapter(provider);

    var absent = new CapturingObserver();
    invokeThroughConfiguredChain(null, adapter, request(), absent);
    assertPermissionDenied(absent);

    var wrongService = new CapturingObserver();
    invokeThroughConfiguredChain(
        peerIdentity(NAMESPACE, "game-session-service"), adapter, request(), wrongService);
    assertPermissionDenied(wrongService);

    var otherNamespace = new CapturingObserver();
    invokeThroughConfiguredChain(entityIdentity("other"), adapter, request(), otherNamespace);
    assertPermissionDenied(otherNamespace);

    SessionContext.setContext(ACCOUNT.toString(), List.of("player"), java.util.Map.of());
    var callerContext = new CapturingObserver();
    invokeThroughConfiguredChain(entityIdentity(NAMESPACE), adapter, request(), callerContext);
    assertPermissionDenied(callerContext);

    verifyNoInteractions(provider);
  }

  private static ObjectProvider<CanonicalPublishedPlayerRouteReadService> sourceReaderProvider() {
    @SuppressWarnings("unchecked")
    ObjectProvider<CanonicalPublishedPlayerRouteReadService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }

  private static CanonicalGameplayRosterOwnerReadGrpcAdapter adapter(
      ObjectProvider<CanonicalPublishedPlayerRouteReadService> provider) {
    return new CanonicalGameplayRosterOwnerReadGrpcAdapter(provider, NAMESPACE);
  }

  private static AuthTokenInterceptor configuredAuthInterceptor() {
    Properties properties = applicationProperties();
    List<String> configured = publicMethods(properties);
    assertThat(configured)
        .containsExactly(
            "game_session.v1.GameSessionService/Ping",
            METHOD,
            "game_session.v1.CanonicalGameplayRosterOwnerReadService/GetPreseededActorAssignmentOwnerRead")
        .doesNotContain("game_session.v1.*");
    Set<String> publicMethods = Set.copyOf(configured);
    return new AuthTokenInterceptor(
        new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L), publicMethods);
  }

  private static List<String> publicMethods(Properties properties) {
    List<Integer> indexes =
        properties.stringPropertyNames().stream()
            .filter(name -> name.startsWith("firemud.auth.grpc.public-methods["))
            .map(name -> name.substring(name.indexOf('[') + 1, name.indexOf(']')))
            .map(Integer::parseInt)
            .sorted(Comparator.naturalOrder())
            .toList();
    return indexes.stream()
        .map(index -> properties.getProperty("firemud.auth.grpc.public-methods[" + index + "]"))
        .toList();
  }

  private static Properties applicationProperties() {
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new FileSystemResource(Path.of("src/main/resources/application.yml")));
    Properties properties = yaml.getObject();
    if (properties == null) {
      throw new IllegalStateException("Game Session application properties are unavailable");
    }
    return properties;
  }

  private static void invokeThroughConfiguredChain(
      String peerUri,
      CanonicalGameplayRosterOwnerReadGrpcAdapter adapter,
      GetCanonicalGameplayRosterOwnerReadRequest request,
      CapturingObserver response) {
    ServerCall<Object, Object> call = new TestServerCall(METHOD, sslSession(peerUri));
    Metadata headers = new Metadata();
    ServerInterceptor peer = new GrpcPeerIdentityInterceptor();
    ServerCallHandler<Object, Object> handler =
        (nextCall, ignoredHeaders) -> {
          adapter.getCanonicalGameplayRosterOwnerRead(request, response);
          return new ServerCall.Listener<>() {};
        };
    configuredAuthInterceptor()
        .interceptCall(
            call,
            headers,
            (authCall, authHeaders) -> peer.interceptCall(authCall, authHeaders, handler));
  }

  private static GetCanonicalGameplayRosterOwnerReadRequest request() {
    return GetCanonicalGameplayRosterOwnerReadRequest.newBuilder()
        .setSchemaVersion(1)
        .setRequestUuid(uuid("66666666-6666-4666-8666-666666666666").toString())
        .setCanonicalAccountUuid(ACCOUNT.toString())
        .setTargetNamespace(NAMESPACE)
        .setCanonicalTenantUuid(TENANT.toString())
        .setWorldSlug("earth")
        .setRealmUuid(REALM.toString())
        .setRealmSlug("main")
        .setPlayableStateNamespaceUuid(PLAYABLE_NAMESPACE.toString())
        .setPlayableStateScope("SHARED")
        .setCanonicalGameInstanceUuid(INSTANCE.toString())
        .setCanonicalVersionUuid(VERSION.toString())
        .setExpectedCatalogRevision(71L)
        .setExpectedPointerVersion(73L)
        .setExpectedActiveWorldEpoch(82L)
        .build();
  }

  private static String entityIdentity(String namespace) {
    return peerIdentity(namespace, ENTITY_SERVICE);
  }

  private static String peerIdentity(String namespace, String service) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }

  private static SSLSession sslSession(String peerUri) {
    SSLSession session = mock(SSLSession.class);
    if (peerUri == null) {
      try {
        when(session.getPeerCertificates())
            .thenThrow(new javax.net.ssl.SSLPeerUnverifiedException("No peer certificate"));
      } catch (javax.net.ssl.SSLPeerUnverifiedException impossible) {
        throw new IllegalStateException(impossible);
      }
      return session;
    }
    X509Certificate certificate = mock(X509Certificate.class);
    try {
      when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, peerUri)));
    } catch (java.security.cert.CertificateParsingException impossible) {
      throw new IllegalStateException(impossible);
    }
    try {
      when(session.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
    } catch (javax.net.ssl.SSLPeerUnverifiedException impossible) {
      throw new IllegalStateException(impossible);
    }
    return session;
  }

  private static void assertPermissionDenied(CapturingObserver observer) {
    assertThat(observer.errorWasStatusRuntimeException).isTrue();
    assertThat(observer.errorCode).isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class CapturingObserver
      implements StreamObserver<GetCanonicalGameplayRosterOwnerReadResponse> {
    private boolean errorWasStatusRuntimeException;
    private Status.Code errorCode;

    @Override
    public void onNext(GetCanonicalGameplayRosterOwnerReadResponse response) {}

    @Override
    public void onError(Throwable throwable) {
      errorWasStatusRuntimeException = throwable instanceof StatusRuntimeException;
      errorCode = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {}
  }

  private static final class TestServerCall extends ServerCall<Object, Object> {
    private static final MethodDescriptor.Marshaller<Object> NOOP_MARSHALLER =
        new MethodDescriptor.Marshaller<>() {
          @Override
          public java.io.InputStream stream(Object value) {
            return new ByteArrayInputStream(new byte[0]);
          }

          @Override
          public Object parse(java.io.InputStream stream) {
            return new Object();
          }
        };

    private final String fullMethodName;
    private final Attributes attributes;

    private TestServerCall(String fullMethodName, SSLSession peerSession) {
      this.fullMethodName = fullMethodName;
      this.attributes =
          peerSession == null
              ? Attributes.EMPTY
              : Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, peerSession).build();
    }

    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(Object message) {}

    @Override
    public void close(Status status, Metadata trailers) {}

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Override
    public MethodDescriptor<Object, Object> getMethodDescriptor() {
      return MethodDescriptor.<Object, Object>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(fullMethodName)
          .setRequestMarshaller(NOOP_MARSHALLER)
          .setResponseMarshaller(NOOP_MARSHALLER)
          .build();
    }
  }
}
