package net.firedevops.firemud.common.config;

import io.grpc.stub.AbstractStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.GrpcClientAuth;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtSecretWatcher;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.ReloadableJwtUtil;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.grpc.server.GlobalServerInterceptor;

@AutoConfiguration
public class CommonSecurityAutoConfiguration {
  private static final Logger logger = LoggingUtil.getLogger(CommonSecurityAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(JwtAuthProperties.class)
  @ConfigurationProperties(prefix = "firemud.auth")
  JwtAuthProperties jwtAuthProperties() {
    return new JwtAuthProperties();
  }

  @Bean
  @ConditionalOnMissingBean(GrpcAuthProperties.class)
  @ConfigurationProperties(prefix = "firemud.auth.grpc")
  GrpcAuthProperties grpcAuthProperties() {
    return new GrpcAuthProperties();
  }

  @Bean(name = "jwtUtil")
  @ConditionalOnMissingBean(name = "jwtUtil")
  JwtUtil jwtUtil(JwtAuthProperties props, Environment environment) throws IOException {
    String secret = initialSecret(props, environment);
    ReloadableJwtUtil util = new ReloadableJwtUtil(secret, props.getJwtExpirationMs());
    String path = props.getJwtSecretPath();
    if (path != null && !path.isBlank()) {
      Path p = Path.of(path);
      util.updateSecret(Files.readString(p).trim());
    }
    return util;
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnBean(name = "jwtUtil")
  @ConditionalOnProperty(prefix = "firemud.auth", name = "jwt-secret-path")
  @ConditionalOnMissingBean(JwtSecretWatcher.class)
  JwtSecretWatcher jwtSecretWatcher(@Qualifier("jwtUtil") JwtUtil jwtUtil, JwtAuthProperties props)
      throws IOException {
    if (!(jwtUtil instanceof ReloadableJwtUtil reloadableJwtUtil)) {
      throw new IllegalStateException("firemud.auth.jwt-secret-path requires ReloadableJwtUtil");
    }
    Path path = Path.of(props.getJwtSecretPath());
    Runnable reload =
        () -> {
          try {
            reloadableJwtUtil.updateSecret(Files.readString(path).trim());
          } catch (IOException e) {
            logger.error("Failed to reload JWT secret", e);
          }
        };
    reload.run();
    return JwtSecretWatcher.createAndStart(path, reload);
  }

  @Bean
  @ConditionalOnBean(name = "jwtUtil")
  @ConditionalOnMissingBean(BlockingGrpcStubCustomizer.class)
  BlockingGrpcStubCustomizer blockingGrpcStubCustomizer(@Qualifier("jwtUtil") JwtUtil jwtUtil) {
    return new BlockingGrpcStubCustomizer() {
      @Override
      public <T extends AbstractStub<T>> T customize(T stub) {
        return GrpcClientAuth.attach(stub, jwtUtil);
      }
    };
  }

  @Bean
  @GlobalServerInterceptor
  @ConditionalOnBean(name = "jwtUtil")
  @ConditionalOnProperty(
      prefix = "firemud.auth.grpc",
      name = "interceptor-enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(AuthTokenInterceptor.class)
  AuthTokenInterceptor authTokenInterceptor(
      @Qualifier("jwtUtil") JwtUtil jwtUtil, GrpcAuthProperties props) {
    return new AuthTokenInterceptor(jwtUtil, java.util.Set.copyOf(props.getPublicMethods()));
  }

  private String initialSecret(JwtAuthProperties props, Environment environment) {
    String secret = props.getJwtSecret();
    if (secret != null && !secret.isBlank()) {
      return secret;
    }
    boolean devLike =
        Arrays.stream(environment.getActiveProfiles())
            .map(String::toLowerCase)
            .anyMatch(profile -> profile.equals("dev") || profile.equals("test"));
    if (!devLike) {
      throw new IllegalStateException("firemud.auth.jwt-secret must be set");
    }
    String generated =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    logger.info("Generated random JWT secret for development profile");
    props.setJwtSecret(generated);
    return generated;
  }
}
