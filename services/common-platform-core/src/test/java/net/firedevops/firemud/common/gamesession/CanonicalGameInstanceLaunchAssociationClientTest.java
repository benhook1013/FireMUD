package net.firedevops.firemud.common.gamesession;

import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.awaitBlockedReloadCallback;
import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.awaitCondition;
import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.awaitLatch;
import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.setWatcher;
import static net.firedevops.firemud.common.testing.ReloadingGrpcClientWatcherTestFixture.watcher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class CanonicalGameInstanceLaunchAssociationClientTest {
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
    CanonicalGameInstanceLaunchAssociationClient client = null;
    TlsCertificateWatcher originalWatcher = null;
    List<ManagedChannel> channels = new CopyOnWriteArrayList<>();
    try {
      ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
      endpoints.setGameSessionService("game-session.internal:6565");
      CommonGrpcClientProperties tls = tls(directory);
      GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
      when(channelFactory.buildChannel(
              eq("game-session.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true)))
          .thenAnswer(
              invocation -> {
                ManagedChannel channel = mock(ManagedChannel.class);
                channels.add(channel);
                return channel;
              });
      client =
          new CanonicalGameInstanceLaunchAssociationClient(endpoints, tls, channelFactory, "test");
      client.init();
      CanonicalGameInstanceLaunchAssociationClient clientUnderTest = client;

      originalWatcher = watcher(client);
      TlsCertificateWatcher closeBoundary = mock(TlsCertificateWatcher.class);
      TlsCertificateWatcher delegatedWatcher = originalWatcher;
      org.mockito.Mockito.doAnswer(
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
              "launch-association-client-monitor-holder");
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
              "launch-association-client-close");
      closeThread = closeWorker;
      closeWorker.start();
      assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitCondition(() -> closeWorker.getState() == Thread.State.BLOCKED);

      Files.writeString(Path.of(tls.getCertChain()), "certificate-rotated");
      assertThat(awaitBlockedReloadCallback(originalWatcher)).isNotNull();

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
      assertThatThrownBy(() -> clientUnderTest.read(request()))
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

  private static Request request() {
    return new Request(
        UUID.randomUUID(),
        "test",
        UUID.randomUUID(),
        "test-world",
        UUID.randomUUID(),
        "control-plane-request",
        "launch-descriptor",
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64));
  }

  private static CommonGrpcClientProperties tls(Path directory) throws IOException {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "fixture").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "fixture").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "fixture").toString());
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
