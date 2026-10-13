package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.security.AccountJwtSignerMaterializerTlsInterceptor;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtSignerMaterializationService;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.transaction.PlatformTransactionManager;

/** Exercises Spring's interceptor composition without starting a server or supplying a bearer. */
class AccountJwtSignerMaterializationCompositionTest {
  @Test
  void missingProtectedBindingReachesTheExactTlsGuardForAllSixMethodsWithoutGlobalJwt() {
    assertDeniedForEveryMethod(new AccountJwtSignerMaterializerTrustBinding(true, ""));
  }

  @Test
  void configuredProtectedBindingWithoutTlsPeerReachesTheExactGuardWithoutGlobalJwt() {
    var bindingProvider = mock(AccountJwtSignerMaterializerTrustBinding.class);
    var binding =
        new Binding(
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer",
            List.of("a".repeat(64)),
            "revision-1",
            AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
                "revision-1",
                "prod",
                "prod-cluster-1",
                "firemud-prod",
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer",
                List.of("a".repeat(64))));
    when(bindingProvider.current()).thenReturn(Optional.of(binding));
    assertDeniedForEveryMethod(bindingProvider);
  }

  private static void assertDeniedForEveryMethod(
      AccountJwtSignerMaterializerTrustBinding bindingProvider) {
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "materialization-composition-test",
                  Map.of("firemud.account.jwt-signer.materialization.enabled", "true")));
      context.registerBean(AccountJwtSignerMaterializerTrustBinding.class, () -> bindingProvider);
      context.register(
          AccountJwtReadinessIsolatedGrpcRoutingConfiguration.class, MiddlewareConfiguration.class);
      context.refresh();

      var repository = mock(AccountJwtSignerDesiredStateRepository.class);
      var readinessRepository = mock(AccountJwtReadinessProbeRepository.class);
      var transactions = mock(PlatformTransactionManager.class);
      var service =
          new AccountJwtSignerMaterializationService(
              repository, readinessRepository, bindingProvider, transactions);
      var annotation =
          AccountJwtSignerMaterializationService.class.getAnnotation(GrpcService.class);
      assertThat(annotation.interceptorNames())
          .containsExactly("accountJwtSignerMaterializerTlsInterceptor");
      var factory =
          new ShadedNettyGrpcServerFactory(
              "127.0.0.1:0", List.of(), null, null, TlsServerCredentials.ClientAuth.REQUIRE);
      context.getBean(GrpcServerFactoryCustomizer.class).customize(factory);
      var configurer = new DefaultGrpcServiceConfigurer(context);
      configurer.afterPropertiesSet();
      var definition =
          configurer.configure(
              new GrpcServiceSpec(service, GrpcServiceInfo.from(annotation)), factory);
      var tls = context.getBean(AccountJwtSignerMaterializerTlsInterceptor.class);
      var jwt = context.getBean(AuthTokenInterceptor.class);

      assertThat(definition.getMethods()).hasSize(6);
      for (var method : definition.getMethods()) {
        clearInvocations(tls);
        assertStartDenied(method, Status.Code.PERMISSION_DENIED);
        verify(tls).interceptCall(any(), any(), any());
        verify(jwt, never()).interceptCall(any(), any(), any());
      }
      verifyNoInteractions(repository, readinessRepository, transactions);

      var ordinary =
          configurer.configure(
              new GrpcServiceSpec(new AccountServiceGrpc.AccountServiceImplBase() {}, null),
              factory);
      assertStartDenied(ordinary.getMethods().iterator().next(), Status.Code.UNAUTHENTICATED);
      verify(jwt).interceptCall(any(), any(), any());
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void assertStartDenied(ServerMethodDefinition method, Status.Code expected) {
    ServerCall call = mock(ServerCall.class);
    when(call.getMethodDescriptor()).thenReturn(method.getMethodDescriptor());
    when(call.getAttributes()).thenReturn(Attributes.EMPTY);
    method.getServerCallHandler().startCall(call, new Metadata());
    var status = ArgumentCaptor.forClass(Status.class);
    verify(call).close(status.capture(), any(Metadata.class));
    assertThat(status.getValue().getCode()).isEqualTo(expected);
  }

  @Configuration(proxyBeanMethods = false)
  static class MiddlewareConfiguration {
    @Bean
    AccountJwtSignerMaterializerTlsInterceptor accountJwtSignerMaterializerTlsInterceptor(
        AccountJwtSignerMaterializerTrustBinding bindingProvider) {
      return spy(new AccountJwtSignerMaterializerTlsInterceptor(bindingProvider));
    }

    @Bean
    @GlobalServerInterceptor
    AuthTokenInterceptor authTokenInterceptor() {
      return spy(new AuthTokenInterceptor(mock(JwtUtil.class)));
    }
  }
}
