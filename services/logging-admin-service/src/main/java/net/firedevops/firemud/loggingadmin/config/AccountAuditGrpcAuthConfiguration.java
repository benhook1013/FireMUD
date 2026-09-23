package net.firedevops.firemud.loggingadmin.config;

import java.util.HashSet;
import java.util.Set;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

/** Allows mTLS-guarded Account audit methods to run without a JWT token. */
@Configuration(proxyBeanMethods = false)
public class AccountAuditGrpcAuthConfiguration {
  private static final Set<String> ACCOUNT_AUDIT_METHODS =
      Set.of(
          "logging_admin.v1.LoggingAdminService/CreateLogEvent",
          "logging_admin.v1.LoggingAdminService/ReadLogEventReceipt");

  @Bean
  @GlobalServerInterceptor
  @ConditionalOnProperty(
      prefix = "firemud.auth.grpc",
      name = "interceptor-enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(AuthTokenInterceptor.class)
  public AuthTokenInterceptor accountAuditAuthTokenInterceptor(
      @Qualifier("jwtUtil") JwtUtil jwtUtil, GrpcAuthProperties properties) {
    Set<String> unauthenticatedMethods = new HashSet<>(properties.getPublicMethods());
    unauthenticatedMethods.addAll(ACCOUNT_AUDIT_METHODS);
    return new AuthTokenInterceptor(jwtUtil, unauthenticatedMethods);
  }
}
