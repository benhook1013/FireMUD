package net.firedevops.firemud.common.publication;

import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.awaitBlockedReloadCallback;
import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.awaitCondition;
import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.awaitLatch;
import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.setWatcher;
import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.watcher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class WorldPublicationTerminalCompletionClientTest {
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";

  @Test
  @Timeout(15)
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void closeReleasesClientMonitorBeforeDrainingCertificateCallback(@TempDir Path directory)
      throws Exception {
    String previousReloadPolicy = System.getProperty(TLS_RELOAD_PROPERTY);
    System.setProperty(TLS_RELOAD_PROPERTY, "true");
    CountDownLatch releaseClientMonitor = new CountDownLatch(1);
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    Thread monitorHolder = null;
    Thread closeThread = null;
    WorldPublicationTerminalCompletionClient client = null;
    TlsCertificateWatcher originalWatcher = null;
    List<ManagedChannel> channels = new CopyOnWriteArrayList<>();
    try {
      ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
      endpoints.setWorldManagementService("world.internal:6565");
      CommonGrpcClientProperties tls = tls(directory);
      GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
      when(channelFactory.buildChannel(
              eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
          .thenAnswer(
              invocation -> {
                ManagedChannel channel = mock(ManagedChannel.class);
                channels.add(channel);
                return channel;
              });
      client = new WorldPublicationTerminalCompletionClient(endpoints, tls, channelFactory, "test");
      client.init();
      WorldPublicationTerminalCompletionClient clientUnderTest = client;

      AbstractStub<?> initializedStub = initializedStub(client);
      assertThat(initializedStub.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(initializedStub.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/world-management-service");

      originalWatcher = watcher(client);
      TlsCertificateWatcher closeBoundary = mock(TlsCertificateWatcher.class);
      TlsCertificateWatcher delegatedWatcher = originalWatcher;
      doAnswer(
              invocation -> {
                assertThat(Thread.holdsLock(clientUnderTest))
                    .as("the client monitor must be released before watcher shutdown")
                    .isFalse();
                delegatedWatcher.close();
                return null;
              })
          .when(closeBoundary)
          .close();
      setWatcher(clientUnderTest, closeBoundary);
      CountDownLatch clientMonitorHeld = new CountDownLatch(1);
      monitorHolder =
          new Thread(
              () -> {
                synchronized (clientUnderTest) {
                  clientMonitorHeld.countDown();
                  awaitLatch(releaseClientMonitor);
                }
              },
              "publication-client-monitor-holder");
      monitorHolder.start();
      assertThat(clientMonitorHeld.await(5, TimeUnit.SECONDS)).isTrue();

      CountDownLatch closeStarted = new CountDownLatch(1);
      Thread closeWorker =
          new Thread(
              () -> {
                closeStarted.countDown();
                try {
                  clientUnderTest.close();
                } catch (Throwable failure) {
                  closeFailure.set(failure);
                }
              },
              "publication-client-close");
      closeThread = closeWorker;
      closeWorker.start();
      assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitCondition(() -> closeWorker.getState() == Thread.State.BLOCKED);

      Files.writeString(Path.of(tls.getCertChain()), "certificate-rotated");
      Thread callback = awaitBlockedReloadCallback(originalWatcher);
      assertThat(callback).isNotNull();

      releaseClientMonitor.countDown();
      closeWorker.join(TimeUnit.SECONDS.toMillis(4));
      assertThat(closeWorker.isAlive())
          .as("close must not hold the client monitor while the watcher drains its callback")
          .isFalse();
      assertThat(closeFailure.get()).isNull();

      clientUnderTest.close();
      assertThatThrownBy(clientUnderTest::init)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("client is closed");
      var operation = WorldPublicationTerminalTestFixtures.operation();
      var terminal =
          WorldPublicationTerminalTestFixtures.terminal(operation, Outcome.NO_PUBLICATION);
      var request =
          WorldPublicationTerminalCompletionGrpcCodec.Request.create(
              "test", operation.canonicalBytes(), terminal.canonicalBytes());
      assertThatThrownBy(() -> clientUnderTest.complete(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      assertThat(channels).isNotEmpty();
      for (ManagedChannel channel : channels) {
        verify(channel, times(1)).shutdown();
      }
    } finally {
      releaseClientMonitor.countDown();
      if (monitorHolder != null) {
        monitorHolder.join(TimeUnit.SECONDS.toMillis(5));
      }
      if (closeThread != null) {
        closeThread.join(TimeUnit.SECONDS.toMillis(7));
      }
      try {
        if (originalWatcher != null) {
          originalWatcher.close();
        }
      } finally {
        try {
          if (client != null) {
            if (originalWatcher != null) {
              setWatcher(client, originalWatcher);
            }
            client.close();
          }
        } finally {
          restoreReloadPolicy(previousReloadPolicy);
        }
      }
    }
  }

  private static AbstractStub<?> initializedStub(WorldPublicationTerminalCompletionClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  private static Object readField(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return tls;
  }

  private static void restoreReloadPolicy(String previousValue) {
    if (previousValue == null) {
      System.clearProperty(TLS_RELOAD_PROPERTY);
    } else {
      System.setProperty(TLS_RELOAD_PROPERTY, previousValue);
    }
  }
}
