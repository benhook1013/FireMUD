package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtReadinessProbeOwnerGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerProtoMapper;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.service.DefaultGrpcServiceConfigurer;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.grpc.server.service.GrpcServiceInfo;
import org.springframework.grpc.server.service.GrpcServiceSpec;

class AccountJwtReadinessProbeOwnerGrpcServiceTest {
  @Test
  void ownerLookupIsIndependentlyDefaultDeniedAndUsesTheGlobalPeerIdentityInterceptor() {
    GrpcService grpc =
        AccountJwtReadinessProbeOwnerGrpcService.class.getAnnotation(GrpcService.class);
    assertThat(grpc).isNotNull();
    assertThat(grpc.interceptorNames()).isEmpty();
    assertThat(grpc.interceptors()).isEmpty();

    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(GlobalPeerIdentityConfiguration.class)) {
      DefaultGrpcServiceConfigurer configurer = new DefaultGrpcServiceConfigurer(context);
      configurer.afterPropertiesSet();
      var service =
          new AccountJwtReadinessProbeOwnerGrpcService(
              mock(AccountJwtReadinessProbeOwnerService.class));
      var serviceDefinition =
          configurer.configure(new GrpcServiceSpec(service, GrpcServiceInfo.from(grpc)), null);
      var methodDefinition = serviceDefinition.getMethods().iterator().next();
      ServerCall serverCall = mock(ServerCall.class);
      when(serverCall.getMethodDescriptor()).thenReturn(methodDefinition.getMethodDescriptor());
      methodDefinition.getServerCallHandler().startCall(serverCall, new Metadata());

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
    var service = new AccountJwtReadinessProbeOwnerGrpcService(owner);
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
