package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt-in composition of World's durable authenticated authored-source owner boundary. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.authored-world-source",
    name = "enabled",
    havingValue = "true")
public class WorldAuthoredSourceIntakeConfiguration {
  private static final String SERVER_CERTIFICATE =
      "spring.ssl.bundle.pem.firemud-grpc.keystore.certificate";
  private static final String SERVER_PRIVATE_KEY =
      "spring.ssl.bundle.pem.firemud-grpc.keystore.private-key";
  private static final String SERVER_CA_CERTIFICATE =
      "spring.ssl.bundle.pem.firemud-grpc.truststore.certificate";

  @Bean(initMethod = "init", destroyMethod = "close")
  public AuthoredWorldSourceClient authoredWorldSourceClient(
      Environment environment,
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    requireSecureServer(environment);
    return new AuthoredWorldSourceClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  @Bean
  public WorldAuthoredSourceIntakeService worldAuthoredSourceIntakeService(
      AuthoredWorldSourceClient sourceClient,
      WorldAuthoredSourceIntakeRepository repository,
      PlatformTransactionManager transactionManager,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new WorldAuthoredSourceIntakeService(
        sourceClient, repository, transactionManager, workloadNamespace);
  }

  static void requireSecureServer(Environment environment) {
    if (!environment.getProperty("spring.grpc.server.enabled", Boolean.class, true)
        || !environment.getProperty("spring.grpc.server.ssl.enabled", Boolean.class, true)
        || !"REQUIRE"
            .equalsIgnoreCase(
                environment.getProperty("spring.grpc.server.ssl.client-auth", "REQUIRE"))) {
      throw new IllegalStateException(
          "World authored-source intake requires an enabled gRPC mTLS server "
              + "with client-auth=REQUIRE");
    }
    requireReadableFile(environment.getProperty(SERVER_CERTIFICATE), "server certificate");
    requireReadableFile(environment.getProperty(SERVER_PRIVATE_KEY), "server private key");
    requireReadableFile(environment.getProperty(SERVER_CA_CERTIFICATE), "server CA certificate");
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null
        || configuredPath.isBlank()
        || configuredPath.startsWith("classpath:")) {
      throw new IllegalStateException(
          "World authored-source intake requires file-backed server mTLS " + label);
    }
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "World authored-source intake requires file-backed server mTLS " + label, exception);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalStateException(
          "World authored-source intake requires a readable file-backed server mTLS " + label);
    }
  }
}
