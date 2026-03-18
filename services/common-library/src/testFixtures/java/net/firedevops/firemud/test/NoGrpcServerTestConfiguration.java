package net.firedevops.firemud.test;

import static org.mockito.Mockito.mock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.service.GrpcServiceDiscoverer;

/** Shared test configuration for contexts that should not start or discover real gRPC servers. */
@TestConfiguration(proxyBeanMethods = false)
public class NoGrpcServerTestConfiguration {

  @Bean
  @Primary
  GrpcServerLifecycle grpcServerLifecycle() {
    return mock(GrpcServerLifecycle.class);
  }

  @Bean
  @Primary
  GrpcServiceDiscoverer grpcServiceDiscoverer() {
    return mock(GrpcServiceDiscoverer.class);
  }
}
