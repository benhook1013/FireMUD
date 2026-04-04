package net.firedevops.firemud.springcloudgateway.config;

import java.util.Set;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gateway.v1.GatewayManagementServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

@Configuration
public class GrpcConfig {
  @Bean
  @GlobalServerInterceptor
  public AuthTokenInterceptor authTokenInterceptor(JwtUtil jwtUtil) {
    return new AuthTokenInterceptor(
        jwtUtil, Set.of(GatewayManagementServiceGrpc.getPingMethod().getFullMethodName()));
  }
}
