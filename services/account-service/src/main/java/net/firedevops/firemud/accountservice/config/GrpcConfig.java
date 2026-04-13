package net.firedevops.firemud.accountservice.config;

import java.util.Set;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

@Configuration
public class GrpcConfig {
  @Bean
  @GlobalServerInterceptor
  public AuthTokenInterceptor authTokenInterceptor(
      net.firedevops.firemud.common.security.JwtUtil jwtUtil) {
    return new AuthTokenInterceptor(
        jwtUtil,
        Set.of(
            AccountServiceGrpc.getAuthenticateMethod().getFullMethodName(),
            AccountServiceGrpc.getPingMethod().getFullMethodName(),
            AccountServiceGrpc.getGetTenantMembershipForRuntimeMethod().getFullMethodName(),
            AccountServiceGrpc.getEnsurePublicProductionPlayerMembershipMethod()
                .getFullMethodName(),
            AccountServiceGrpc.getGetTenantEntitlementsForRuntimeMethod().getFullMethodName()));
  }
}
