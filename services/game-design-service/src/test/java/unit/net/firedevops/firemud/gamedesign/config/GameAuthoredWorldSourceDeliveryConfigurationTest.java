package net.firedevops.firemud.gamedesign.config;

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
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.service.impl.GameAuthoredWorldSourceDeliveryService;
import net.firedevops.firemud.gamedesign.service.impl.GameAuthoredWorldSourceDeliveryWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

class GameAuthoredWorldSourceDeliveryConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void missingFeatureFlagDoesNotRegisterAClientOrWorker() {
    runner(null, "authored-world-config-test", null)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(WorldAuthoredSourceIntakeClient.class);
              assertThat(context).doesNotHaveBean(GameAuthoredWorldSourceDeliveryWorker.class);
            });
    runner(null, "authored-world-config-test", false)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(GameAuthoredWorldSourceDeliveryWorker.class);
            });
  }

  @Test
  void explicitEnableRegistersMtlSClientServiceAndBoundedWorker() throws IOException {
    Path certificate = certificateFile("client.crt");
    Path privateKey = certificateFile("client.key");
    Path caCertificate = certificateFile("ca.crt");
    runner(
            new ClientMaterial(certificate, privateKey, caCertificate, false),
            "authored-world-config-test",
            true)
        .withPropertyValues("firemud.authored-world-source.delivery.batch-size=20")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(WorldAuthoredSourceIntakeClient.class);
              assertThat(context).hasSingleBean(GameAuthoredWorldSourceDeliveryService.class);
              assertThat(context).hasSingleBean(GameAuthoredWorldSourceDeliveryWorker.class);
            });
  }

  @Test
  void explicitEnableFailsClosedWithoutNamespaceOrFileBackedMtls() throws IOException {
    Path certificate = certificateFile("client.crt");
    Path privateKey = certificateFile("client.key");
    Path caCertificate = certificateFile("ca.crt");
    runner(new ClientMaterial(certificate, privateKey, caCertificate, false), "", true)
        .run(context -> assertThat(context).hasFailed());
    runner(
            new ClientMaterial(Path.of("classpath:client.crt"), privateKey, caCertificate, false),
            "authored-world-config-test",
            true)
        .run(context -> assertThat(context).hasFailed());
    runner(
            new ClientMaterial(certificate, privateKey, caCertificate, true),
            "authored-world-config-test",
            true)
        .run(context -> assertThat(context).hasFailed());
  }

  private ApplicationContextRunner runner(
      ClientMaterial material, String workloadNamespace, Boolean enabled) {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withUserConfiguration(
                GameAuthoredWorldSourceDeliveryConfiguration.class,
                DeliveryPropertiesTestConfiguration.class)
            .withPropertyValues(
                "firemud.grpc.workload-namespace=" + workloadNamespace,
                "firemud.authored-world-source.delivery.poll-interval-ms=3600000");
    if (enabled != null) {
      runner = runner.withPropertyValues("firemud.authored-world-source.enabled=" + enabled);
    }
    if (material == null) {
      return runner;
    }
    return runner
        .withBean(
            GameAuthoredWorldSourceDeliveryRepository.class,
            () -> mock(GameAuthoredWorldSourceDeliveryRepository.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(ServiceEndpointsProperties.class, ServiceEndpointsProperties::new)
        .withBean(CommonGrpcClientProperties.class, material::properties)
        .withBean(
            GrpcChannelFactory.class,
            GameAuthoredWorldSourceDeliveryConfigurationTest::channelFactory);
  }

  private Path certificateFile(String name) throws IOException {
    Path file = temporaryDirectory.resolve(name);
    return Files.writeString(file, "test tls material");
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

  private record ClientMaterial(
      Path certificate, Path privateKey, Path caCertificate, boolean plaintext) {
    CommonGrpcClientProperties properties() {
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
      properties.setCaCert(caCertificate.toString());
      properties.setPlaintext(plaintext);
      return properties;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(GameAuthoredWorldSourceDeliveryProperties.class)
  static class DeliveryPropertiesTestConfiguration {}
}
