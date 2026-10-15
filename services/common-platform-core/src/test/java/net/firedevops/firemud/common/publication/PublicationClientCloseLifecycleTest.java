package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class PublicationClientCloseLifecycleTest {
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";

  @TestFactory
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  Stream<DynamicTest> closesEveryPublicationClientOutsideItsMonitor(@TempDir Path directory) {
    return clientCases().stream()
        .map(
            testCase ->
                DynamicTest.dynamicTest(
                    testCase.name(), () -> assertCloseLifecycle(testCase, directory)));
  }

  private static List<ClientCase> clientCases() {
    return List.of(
        new ClientCase(
            "AccountPublicationAuthorizationReadClient",
            AccountPublicationAuthorizationReadClient::new),
        new ClientCase(
            "AuthoredDraftPublishSelectionReadClient",
            AuthoredDraftPublishSelectionReadClient::new),
        new ClientCase(
            "WorldPublicationTerminalCompletionClient",
            WorldPublicationTerminalCompletionClient::new),
        new ClientCase(
            "WorldPublicationTerminalReadClient", WorldPublicationTerminalReadClient::new),
        new ClientCase(
            "WorldSelectedDraftPublicationFreezeClient",
            WorldSelectedDraftPublicationFreezeClient::new),
        new ClientCase(
            "WorldSelectedPublicationArtifactInventoryClient",
            WorldSelectedPublicationArtifactInventoryClient::new),
        new ClientCase(
            "WorldPublishedStartLocationClient", WorldPublishedStartLocationClient::new));
  }

  private static void assertCloseLifecycle(ClientCase testCase, Path directory) throws Exception {
    String previousReloadPolicy = System.getProperty(TLS_RELOAD_PROPERTY);
    System.setProperty(TLS_RELOAD_PROPERTY, "false");
    AbstractReloadingBlockingGrpcClient<?> client = null;
    try {
      Path clientDirectory = Files.createTempDirectory(directory, "publication-client-");
      CommonGrpcClientProperties tls = tls(clientDirectory);
      GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
      ManagedChannel channel = mock(ManagedChannel.class);
      when(channelFactory.buildChannel(
              anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
          .thenReturn(channel);

      client =
          testCase.factory().create(new ServiceEndpointsProperties(), tls, channelFactory, "test");
      initializeClient(client);

      AbstractReloadingBlockingGrpcClient<?> clientUnderTest = client;
      TlsCertificateWatcher closeBoundary = mock(TlsCertificateWatcher.class);
      doAnswer(
              invocation -> {
                assertThat(Thread.holdsLock(clientUnderTest))
                    .as("close must release the client monitor before draining the watcher")
                    .isFalse();
                return null;
              })
          .when(closeBoundary)
          .close();
      setWatcher(clientUnderTest, closeBoundary);

      clientUnderTest.close();

      assertThat(readClientField(clientUnderTest, "closed")).isEqualTo(true);
      assertThat(readClientField(clientUnderTest, "initialized")).isEqualTo(false);
      Method requireStub = clientUnderTest.getClass().getDeclaredMethod("requireStub");
      requireStub.setAccessible(true);
      assertThatThrownBy(() -> requireStub.invoke(clientUnderTest))
          .isInstanceOf(InvocationTargetException.class)
          .hasCauseInstanceOf(IllegalStateException.class);
      verify(closeBoundary).close();
      verify(channel).shutdown();
    } finally {
      if (client != null) {
        client.close();
      }
      if (previousReloadPolicy == null) {
        System.clearProperty(TLS_RELOAD_PROPERTY);
      } else {
        System.setProperty(TLS_RELOAD_PROPERTY, previousReloadPolicy);
      }
    }
  }

  private static void setWatcher(
      AbstractReloadingBlockingGrpcClient<?> client, TlsCertificateWatcher watcher)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    field.setAccessible(true);
    field.set(client, watcher);
  }

  private static void initializeClient(AbstractReloadingBlockingGrpcClient<?> client)
      throws ReflectiveOperationException {
    client.getClass().getMethod("init").invoke(client);
  }

  private static Object readClientField(AbstractReloadingBlockingGrpcClient<?> client, String name)
      throws ReflectiveOperationException {
    Field field = client.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(client);
  }

  private static CommonGrpcClientProperties tls(Path directory) throws IOException {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return tls;
  }

  private record ClientCase(String name, ClientFactory factory) {}

  @FunctionalInterface
  private interface ClientFactory {
    AbstractReloadingBlockingGrpcClient<?> create(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tls,
        GrpcChannelFactory channelFactory,
        String workloadNamespace);
  }
}
