package net.firedevops.firemud.entitymanagement.config;

import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfig {
  @Bean
  public AuthTokenInterceptor authTokenInterceptor(JwtUtil jwtUtil) {
    return new AuthTokenInterceptor(
        jwtUtil,
        java.util.Set.of(
            EntityManagementServiceGrpc.getPingMethod().getFullMethodName(),
            EntityManagementServiceGrpc.getListRoomEntitiesMethod().getFullMethodName()));
  }
}
