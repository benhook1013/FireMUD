package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadClient;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyClient;
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
  Stream<DynamicTest> sharesInitAndCloseLifecycleAcrossExplicitClients(@TempDir Path directory) {
    return clientCases().stream()
        .map(
            testCase ->
                DynamicTest.dynamicTest(
                    testCase.name(), () -> assertLifecycle(testCase, directory)));
  }

  private static List<ClientCase> clientCases() {
    return List.of(
        new ClientCase(
            "DraftCommitOrderReadClient",
            "Account COMMIT_ORDER read client is closed",
            DraftCommitOrderReadClient::new),
        new ClientCase(
            "WorldOriginalDraftGraphApplyClient",
            "World original graph apply client is closed",
            WorldOriginalDraftGraphApplyClient::new),
        new ClientCase(
            "AccountPublicationAuthorizationReadClient",
            "Account publication authorization read client is closed",
            AccountPublicationAuthorizationReadClient::new),
        new ClientCase(
            "AuthoredDraftPublishSelectionReadClient",
            "Game Design selected Draft publication read client is closed",
            AuthoredDraftPublishSelectionReadClient::new),
        new ClientCase(
            "WorldPublicationTerminalCompletionClient",
            "World publication completion client is closed",
            WorldPublicationTerminalCompletionClient::new),
        new ClientCase(
            "WorldPublicationTerminalReadClient",
            "World publication terminal read client is closed",
            WorldPublicationTerminalReadClient::new),
        new ClientCase(
            "WorldSelectedDraftPublicationFreezeClient",
            "World selected-publication freeze client is closed",
            WorldSelectedDraftPublicationFreezeClient::new),
        new ClientCase(
            "WorldSelectedPublicationArtifactInventoryClient",
            "World selected-publication inventory client is closed",
            WorldSelectedPublicationArtifactInventoryClient::new),
        new ClientCase(
            "WorldPublishedStartLocationClient",
            "World selector read client is closed",
            WorldPublishedStartLocationClient::new));
  }

  private static void assertLifecycle(ClientCase testCase, Path directory) throws Exception {
    String previousReloadPolicy = System.getProperty(TLS_RELOAD_PROPERTY);
    System.setProperty(TLS_RELOAD_PROPERTY, "false");
    AbstractReloadingBlockingGrpcClient<?> client = null;
    try {
      Path clientDirectory = Files.createTempDirectory(directory, "explicit-client-");
      CommonGrpcClientProperties tls = tls(clientDirectory);
      GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
      ManagedChannel channel = mock(ManagedChannel.class);
      when(channelFactory.buildChannel(
              anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
          .thenReturn(channel);

      client =
          testCase.factory().create(new ServiceEndpointsProperties(), tls, channelFactory, "test");
      initializeClient(client);
      initializeClient(client);
      verify(channelFactory, times(1))
          .buildChannel(anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean());

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

      assertThatThrownBy(() -> initializeClient(clientUnderTest))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(testCase.closedMessage());
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
      throws Exception {
    try {
      client.getClass().getMethod("init").invoke(client);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw failure;
    }
  }

  private static CommonGrpcClientProperties tls(Path directory) throws IOException {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return tls;
  }

  private record ClientCase(String name, String closedMessage, ClientFactory factory) {}

  @FunctionalInterface
  private interface ClientFactory {
    AbstractReloadingBlockingGrpcClient<?> create(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tls,
        GrpcChannelFactory channelFactory,
        String workloadNamespace);
  }
}
