package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessIsolatedGrpcRoutingConfiguration;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtReadinessProbeOwnerGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerProtoMapper;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverMetadataService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.ShadedNettyGrpcServerFactory;
import org.springframework.grpc.server.service.DefaultGrpcServiceConfigurer;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.grpc.server.service.GrpcServiceInfo;
import org.springframework.grpc.server.service.GrpcServiceSpec;

class AccountJwtReadinessProbeOwnerGrpcServiceTest {
  @Test
  void ownerLookupIsIndependentlyDefaultDeniedAndUsesTheExactPeerIdentityInterceptor()
      throws Exception {
    GrpcService grpc =
        AccountJwtReadinessProbeOwnerGrpcService.class.getAnnotation(GrpcService.class);
    assertThat(grpc).isNotNull();
    assertThat(grpc.interceptorNames()).containsExactly("grpcPeerIdentityInterceptor");
    assertThat(grpc.interceptors()).isEmpty();
    assertThat(grpc.blendWithGlobalInterceptors()).isFalse();

    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "isolated-readiness-test",
                  Map.of("firemud.account.jwt-readiness.probe-owner.enabled", "true")));
      context.register(
          GlobalPeerIdentityConfiguration.class,
          AccountJwtReadinessIsolatedGrpcRoutingConfiguration.class);
      context.refresh();
      DefaultGrpcServiceConfigurer configurer = new DefaultGrpcServiceConfigurer(context);
      configurer.afterPropertiesSet();
      var factory =
          new ShadedNettyGrpcServerFactory(
              "127.0.0.1:0", List.of(), null, null, TlsServerCredentials.ClientAuth.REQUIRE);
      context.getBean(GrpcServerFactoryCustomizer.class).customize(factory);
      var owner = mock(AccountJwtReadinessProbeOwnerService.class);
      AtomicReference<GrpcPeerIdentity> observedPeer = new AtomicReference<>();
      doAnswer(
              invocation -> {
                observedPeer.set(GrpcPeerIdentity.current());
                return GetCurrentReadinessProbeOwnerResponse.getDefaultInstance();
              })
          .when(owner)
          .getCurrentReadinessProbeOwner(org.mockito.ArgumentMatchers.any());
      var metadata = mock(AccountJwtReadinessReceiverMetadataService.class);
      AtomicReference<GrpcPeerIdentity> observedMetadataPeer = new AtomicReference<>();
      doAnswer(
              invocation -> {
                observedMetadataPeer.set(GrpcPeerIdentity.current());
                return GetCurrentReadinessReceiverMetadataResponse.getDefaultInstance();
              })
          .when(metadata)
          .getCurrentReadinessReceiverMetadata(org.mockito.ArgumentMatchers.any());
      var service = new AccountJwtReadinessProbeOwnerGrpcService(owner, metadata);
      var serviceDefinition =
          configurer.configure(new GrpcServiceSpec(service, GrpcServiceInfo.from(grpc)), factory);
      var methodDefinition =
          serviceDefinition.getMethods().stream()
              .filter(
                  method ->
                      method
                          .getMethodDescriptor()
                          .getFullMethodName()
                          .endsWith("/GetCurrentReadinessProbeOwner"))
              .findFirst()
              .orElseThrow();
      ServerCall serverCall = mock(ServerCall.class);
      when(serverCall.getMethodDescriptor()).thenReturn(methodDefinition.getMethodDescriptor());
      String peerUri = "spiffe://firemud/ns/firemud-prod/sa/game-session-service";
      X509Certificate certificate = mock(X509Certificate.class);
      when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, peerUri)));
      SSLSession sslSession = mock(SSLSession.class);
      when(sslSession.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
      when(serverCall.getAttributes())
          .thenReturn(
              Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build());
      ServerCall.Listener listener =
          methodDefinition.getServerCallHandler().startCall(serverCall, new Metadata());
      listener.onMessage(GetCurrentReadinessProbeOwnerRequest.getDefaultInstance());
      listener.onHalfClose();

      assertThat(context.getBean(GlobalPeerIdentityMarker.class).invocations()).hasValue(0);
      assertThat(observedPeer.get()).isEqualTo(GrpcPeerIdentity.parseUri(peerUri).orElseThrow());
      verify(sslSession, times(2)).getPeerCertificates();

      var metadataMethodDefinition =
          serviceDefinition.getMethods().stream()
              .filter(
                  method ->
                      method
                          .getMethodDescriptor()
                          .getFullMethodName()
                          .endsWith("/GetCurrentReadinessReceiverMetadata"))
              .findFirst()
              .orElseThrow();
      ServerCall metadataCall = mock(ServerCall.class);
      when(metadataCall.getMethodDescriptor())
          .thenReturn(metadataMethodDefinition.getMethodDescriptor());
      when(metadataCall.getAttributes())
          .thenReturn(
              Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build());
      ServerCall.Listener metadataListener =
          metadataMethodDefinition.getServerCallHandler().startCall(metadataCall, new Metadata());
      metadataListener.onMessage(GetCurrentReadinessReceiverMetadataRequest.getDefaultInstance());
      metadataListener.onHalfClose();

      assertThat(observedMetadataPeer.get())
          .isEqualTo(GrpcPeerIdentity.parseUri(peerUri).orElseThrow());
      verify(metadata).getCurrentReadinessReceiverMetadata(org.mockito.ArgumentMatchers.any());
      assertThat(context.getBean(GlobalPeerIdentityMarker.class).invocations()).hasValue(0);

      var ordinary =
          configurer.configure(
              new GrpcServiceSpec(new AccountServiceGrpc.AccountServiceImplBase() {}, null),
              factory);
      var ordinaryMethod = ordinary.getMethods().iterator().next();
      ServerCall ordinaryCall = mock(ServerCall.class);
      when(ordinaryCall.getMethodDescriptor()).thenReturn(ordinaryMethod.getMethodDescriptor());
      when(ordinaryCall.getAttributes()).thenReturn(Attributes.EMPTY);
      ordinaryMethod.getServerCallHandler().startCall(ordinaryCall, new Metadata());
      assertThat(context.getBean(GlobalPeerIdentityMarker.class).invocations()).hasValue(1);
    }

    ConditionalOnProperty condition =
        AccountJwtReadinessProbeOwnerGrpcService.class.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("firemud.account.jwt-readiness.probe-owner");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();
  }

  @Test
  void missingProtectedAccountBindingDeniesBeforeInventoryOrSqlAccess() {
    AccountJwtSignerMaterializerTrustBinding materializerBinding =
        mock(AccountJwtSignerMaterializerTrustBinding.class);
    when(materializerBinding.current()).thenReturn(Optional.empty());
    AccountJwtReadinessProbeService probeService = mock(AccountJwtReadinessProbeService.class);
    var guard =
        new AccountJwtReadinessProbeOwnerWorkloadGuard(
            materializerBinding, mock(AccountJwtJwksApiBinding.class), "firemud-prod");
    var owner =
        new AccountJwtReadinessProbeOwnerService(
            probeService,
            materializerBinding,
            guard,
            new AccountJwtReadinessProbeOwnerProtoMapper());
    var service =
        new AccountJwtReadinessProbeOwnerGrpcService(
            owner, mock(AccountJwtReadinessReceiverMetadataService.class));
    RecordingObserver response = new RecordingObserver();

    service.getCurrentReadinessProbeOwner(
        GetCurrentReadinessProbeOwnerRequest.newBuilder().setSchemaVersion(1).build(), response);

    assertThat(response.value.get()).isNull();
    assertThat(response.completed).isFalse();
    assertThat(Status.fromThrowable(response.failure.get()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(probeService);
  }

  private static final class RecordingObserver
      implements StreamObserver<GetCurrentReadinessProbeOwnerResponse> {
    private final AtomicReference<GetCurrentReadinessProbeOwnerResponse> value =
        new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean completed;

    @Override
    public void onNext(GetCurrentReadinessProbeOwnerResponse response) {
      value.set(response);
    }

    @Override
    public void onError(Throwable throwable) {
      failure.set(throwable);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class GlobalPeerIdentityConfiguration {
    @Bean
    @GlobalServerInterceptor
    GrpcPeerIdentityInterceptor grpcPeerIdentityInterceptor() {
      return new GrpcPeerIdentityInterceptor();
    }

    @Bean
    @GlobalServerInterceptor
    GlobalPeerIdentityMarker globalPeerIdentityMarker() {
      return new GlobalPeerIdentityMarker();
    }
  }

  private static final class GlobalPeerIdentityMarker implements ServerInterceptor {
    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
        ServerCall<RequestT, ResponseT> call,
        Metadata headers,
        ServerCallHandler<RequestT, ResponseT> next) {
      invocations.incrementAndGet();
      return next.startCall(call, headers);
    }

    private AtomicInteger invocations() {
      return invocations;
    }
  }
}
