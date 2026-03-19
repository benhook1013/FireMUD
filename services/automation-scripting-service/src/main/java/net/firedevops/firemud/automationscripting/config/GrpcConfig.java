package net.firedevops.firemud.automationscripting.config;

import net.firedevops.firemud.automationscripting.security.GrpcJwtAuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

@Configuration
public class GrpcConfig {
  @Bean
  @GlobalServerInterceptor
  public GrpcJwtAuthInterceptor grpcJwtAuthInterceptor(
      net.firedevops.firemud.common.security.JwtUtil jwtUtil) {
    return new GrpcJwtAuthInterceptor(jwtUtil);
  }
}
