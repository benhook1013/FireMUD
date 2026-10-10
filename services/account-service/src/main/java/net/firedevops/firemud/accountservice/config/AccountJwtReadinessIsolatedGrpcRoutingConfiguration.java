package net.firedevops.firemud.accountservice.config;

import java.util.HashSet;
import java.util.Set;
import net.firedevops.firemud.account.v1.AccountJwtReadinessPodReceiverServiceGrpc;
import net.firedevops.firemud.account.v1.AccountJwtReadinessProbeOwnerServiceGrpc;
import net.firedevops.firemud.account.v1.AccountJwtSignerMaterializationServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.DefaultGrpcServerFactory;

/** Excludes global middleware only from explicitly selected isolated readiness adapters. */
@Configuration(proxyBeanMethods = false)
public class AccountJwtReadinessIsolatedGrpcRoutingConfiguration {
  @Bean
  public GrpcServerFactoryCustomizer accountJwtReadinessIsolatedGrpcRoutingCustomizer(
      @Value("${firemud.account.jwt-readiness.probe-owner.enabled:false}") boolean ownerEnabled,
      @Value("${firemud.account.jwt-readiness.pod-receiver.enabled:false}") boolean receiverEnabled,
      @Value("${firemud.account.jwt-signer.materialization.enabled:false}")
          boolean materializationEnabled) {
    Set<String> isolatedServices = new HashSet<>();
    if (ownerEnabled) {
      isolatedServices.add(AccountJwtReadinessProbeOwnerServiceGrpc.SERVICE_NAME);
    }
    if (receiverEnabled) {
      isolatedServices.add(AccountJwtReadinessPodReceiverServiceGrpc.SERVICE_NAME);
    }
    if (materializationEnabled) {
      isolatedServices.add(AccountJwtSignerMaterializationServiceGrpc.SERVICE_NAME);
    }
    Set<String> selectedServices = Set.copyOf(isolatedServices);
    return factory -> {
      if (selectedServices.isEmpty()) {
        return;
      }
      if (!(factory instanceof DefaultGrpcServerFactory<?> supportedFactory)) {
        throw new IllegalStateException(
            "Isolated Account readiness requires interceptor filtering");
      }
      // Spring gRPC 1.0 filters globals before appending each adapter's named TLS interceptor.
      supportedFactory.setInterceptorFilter(
          (interceptor, service) ->
              !selectedServices.contains(service.getServiceDescriptor().getName()));
    };
  }
}
