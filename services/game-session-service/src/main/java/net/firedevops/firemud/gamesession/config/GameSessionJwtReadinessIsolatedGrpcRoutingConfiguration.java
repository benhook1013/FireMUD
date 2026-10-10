package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.gamesession.v1.GameSessionJwtReadinessReceiverServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.DefaultGrpcServerFactory;

/** Keeps the selected readiness receiver separate from global application middleware. */
@Configuration(proxyBeanMethods = false)
public class GameSessionJwtReadinessIsolatedGrpcRoutingConfiguration {
  @Bean
  public GrpcServerFactoryCustomizer gameSessionJwtReadinessIsolatedGrpcRoutingCustomizer(
      @Value("${firemud.game-session.jwt-readiness.receiver.enabled:false}") boolean enabled) {
    return factory -> {
      if (!enabled) {
        return;
      }
      if (!(factory instanceof DefaultGrpcServerFactory<?> supportedFactory)) {
        throw new IllegalStateException(
            "Isolated Game Session readiness requires interceptor filtering");
      }
      // Explicitly named peer extraction is appended after this global-interceptor filter.
      supportedFactory.setInterceptorFilter(
          (interceptor, service) ->
              !GameSessionJwtReadinessReceiverServiceGrpc.SERVICE_NAME.equals(
                  service.getServiceDescriptor().getName()));
    };
  }
}
