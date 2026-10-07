package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class WorldAuthoredSourceIntakeConfigurationTest {
  private static final String NAMESPACE = "authored-source-world-test";
  private static final String SERVER_CERTIFICATE =
      "spring.ssl.bundle.pem.firemud-grpc.keystore.certificate";
  private static final String SERVER_PRIVATE_KEY =
      "spring.ssl.bundle.pem.firemud-grpc.keystore.private-key";
  private static final String SERVER_CA_CERTIFICATE =
      "spring.ssl.bundle.pem.firemud-grpc.truststore.certificate";

  @TempDir Path temporaryDirectory;

  @Test
  void missingFeatureFlagDoesNotRegisterClientOwnerOrGrpcReceiver() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            WorldAuthoredSourceIntakeConfiguration.class,
            WorldAuthoredSourceIntakeGrpcService.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(AuthoredWorldSourceClient.class);
              assertThat(context).doesNotHaveBean(WorldAuthoredSourceIntakeService.class);
              assertThat(context).doesNotHaveBean(WorldAuthoredSourceIntakeGrpcService.class);
            });
    new ApplicationContextRunner()
        .withUserConfiguration(
            WorldAuthoredSourceIntakeConfiguration.class,
            WorldAuthoredSourceIntakeGrpcService.class)
        .withPropertyValues("firemud.authored-world-source.enabled=false")
        .run(
            context ->
                assertThat(context).doesNotHaveBean(WorldAuthoredSourceIntakeGrpcService.class));
  }

  @Test
  void explicitEnableRegistersOnlyWithFileBackedMutualTlsAndTrustedNamespace() throws IOException {
    Material material = material();
    secureRunner(material, NAMESPACE)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AuthoredWorldSourceClient.class);
              assertThat(context).hasSingleBean(WorldAuthoredSourceIntakeService.class);
              assertThat(context).hasSingleBean(WorldAuthoredSourceIntakeGrpcService.class);
            });
  }

  @Test
  void explicitEnableFailsClosedForMissingIdentityOrInsecureServerOrClient() throws IOException {
    Material material = material();
    secureRunner(material, "").run(context -> assertThat(context).hasFailed());
    secureRunner(material, NAMESPACE)
        .withPropertyValues("spring.grpc.server.ssl.enabled=false")
        .run(context -> assertThat(context).hasFailed());
    secureRunner(material, NAMESPACE)
        .withPropertyValues("spring.grpc.server.ssl.client-auth=NONE")
        .run(context -> assertThat(context).hasFailed());
    secureRunner(material, NAMESPACE, true).run(context -> assertThat(context).hasFailed());
    secureRunner(material, NAMESPACE)
        .withPropertyValues(SERVER_CERTIFICATE + "=classpath:server.crt")
        .run(context -> assertThat(context).hasFailed());
  }

  private ApplicationContextRunner secureRunner(Material material, String workloadNamespace) {
    return secureRunner(material, workloadNamespace, false);
  }

  private ApplicationContextRunner secureRunner(
      Material material, String workloadNamespace, boolean plaintext) {
    return new ApplicationContextRunner()
        .withUserConfiguration(
            WorldAuthoredSourceIntakeConfiguration.class,
            WorldAuthoredSourceIntakeGrpcService.class)
        .withBean(
            WorldAuthoredSourceIntakeRepository.class,
            () -> mock(WorldAuthoredSourceIntakeRepository.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(ServiceEndpointsProperties.class, ServiceEndpointsProperties::new)
        .withBean(CommonGrpcClientProperties.class, () -> material.clientProperties(plaintext))
        .withBean(
            GrpcChannelFactory.class, WorldAuthoredSourceIntakeConfigurationTest::channelFactory)
        .withPropertyValues(
            "firemud.authored-world-source.enabled=true",
            "firemud.grpc.workload-namespace=" + workloadNamespace,
            "spring.grpc.server.enabled=true",
            "spring.grpc.server.ssl.enabled=true",
            "spring.grpc.server.ssl.client-auth=REQUIRE",
            SERVER_CERTIFICATE + "=" + material.serverCertificate(),
            SERVER_PRIVATE_KEY + "=" + material.serverPrivateKey(),
            SERVER_CA_CERTIFICATE + "=" + material.serverCaCertificate());
  }

  private Material material() throws IOException {
    return new Material(
        file("client.crt"),
        file("client.key"),
        file("client-ca.crt"),
        file("server.crt"),
        file("server.key"),
        file("server-ca.crt"));
  }

  private Path file(String name) throws IOException {
    return Files.writeString(temporaryDirectory.resolve(name), "test tls material");
  }

  private static GrpcChannelFactory channelFactory() {
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    try {
      when(factory.buildChannel(
              anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
          .thenReturn(mock(ManagedChannel.class));
    } catch (SSLException exception) {
      throw new IllegalStateException("Unable to configure test gRPC channel", exception);
    }
    return factory;
  }

  private record Material(
      Path clientCertificate,
      Path clientPrivateKey,
      Path clientCaCertificate,
      Path serverCertificate,
      Path serverPrivateKey,
      Path serverCaCertificate) {
    CommonGrpcClientProperties clientProperties(boolean plaintext) {
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(clientCertificate.toString());
      properties.setPrivateKey(clientPrivateKey.toString());
      properties.setCaCert(clientCaCertificate.toString());
      properties.setPlaintext(plaintext);
      return properties;
    }
  }
}
