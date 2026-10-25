package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.grpc.server.autoconfigure.ConditionalOnGrpcServerEnabled;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GameSessionGrpcRequestByteConfiguration {
  @Bean
  public GrpcRequestBytePolicy gameSessionGrpcRequestBytePolicy() {
    return GrpcRequestBytePolicy.createdRosterRetirement();
  }

  @Bean
  @ConditionalOnGrpcServerEnabled
  SmartInitializingSingleton requireGameSessionBoundedGrpcServerFactory(
      BoundedGrpcServerFactory factory) {
    // A required typed bean dependency prevents plain Spring factory fallback if shared
    // request-bound auto-configuration is accidentally omitted or excluded.
    return () -> java.util.Objects.requireNonNull(factory);
  }
}
