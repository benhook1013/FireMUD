package net.firedevops.firemud.common.account.sourceintake;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.entity.sourceintake.GrpcEntitySelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.gamedesign.GrpcSelectedOwnerIntakeSourceClient;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderClient;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationSettlementClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadClient;
import net.firedevops.firemud.common.publication.WorldSelectedOwnerInventoryGrpcClient;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Lifecycle proof with real file watchers and a mocked channel factory, not a TLS handshake. */
class SelectedOwnerIntakeClientLifecycleTest {
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";

  @ParameterizedTest
  @EnumSource(ClientKind.class)
  @Timeout(15)
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void closeReleasesClientMonitorBeforeDrainingCertificateCallback(
      ClientKind kind, @TempDir Path directory) throws Exception {
    String previousReloadPolicy = System.getProperty(TLS_RELOAD_PROPERTY);
    System.setProperty(TLS_RELOAD_PROPERTY, "true");
    CountDownLatch releaseClientMonitor = new CountDownLatch(1);
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    Thread monitorHolder = null;
    Thread closeThread = null;
    AbstractReloadingBlockingGrpcClient<?> client = null;
    TlsCertificateWatcher originalWatcher = null;
    List<ManagedChannel> channels = new CopyOnWriteArrayList<>();
    try {
      ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
      endpoints.setWorldManagementService("world.internal:6565");
      CommonGrpcClientProperties tls = tls(directory);
      GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
      when(channelFactory.buildChannel(
              any(String.class), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
          .thenAnswer(
              invocation -> {
                ManagedChannel channel = mock(ManagedChannel.class);
                channels.add(channel);
                return channel;
              });
      ClientHandle handle = kind.create(endpoints, tls, channelFactory);
      client = handle.client();
      handle.initialize().run();
      AbstractReloadingBlockingGrpcClient<?> clientUnderTest = client;

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
              "selected-owner-intake-client-monitor-holder");
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
              "selected-owner-intake-client-close");
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
      assertThatThrownBy(() -> handle.initialize().run())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");
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

  @FunctionalInterface
  private interface Initialize {
    void run() throws Exception;
  }

  private record ClientHandle(
      AbstractReloadingBlockingGrpcClient<?> client, Initialize initialize) {}

  private enum ClientKind {
    AUTHORIZATION,
    WORLD_CLOSURE_AUTHORIZATION,
    SOURCE_READ,
    ENTITY_TERMINAL,
    AUTOMATION_TERMINAL,
    GAME_DESIGN_SOURCE,
    WORLD_INVENTORY,
    GAME_DESIGN_PUBLICATION_TERMINAL,
    ACCOUNT_PUBLICATION_SETTLEMENT,
    ACCOUNT_PUBLICATION_HELD_READ,
    ACCOUNT_PUBLICATION_ORDER,
    GAME_DESIGN_PUBLICATION_SELECTION,
    WORLD_PUBLICATION_TERMINAL;

    ClientHandle create(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tls,
        GrpcChannelFactory factory) {
      return switch (this) {
        case AUTHORIZATION -> {
          var client =
              new AccountSelectedOwnerIntakeAuthorizationGrpcClient(
                  endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case WORLD_CLOSURE_AUTHORIZATION -> {
          var client =
              new AccountSelectedOwnerIntakeWorldClosureAuthorizationGrpcClient(
                  endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case SOURCE_READ -> {
          var client = new GrpcSelectedOwnerIntakeSourceReadClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case ENTITY_TERMINAL -> {
          var client =
              new GrpcEntitySelectedSourceIntakeTerminalReadClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case AUTOMATION_TERMINAL -> {
          var client =
              new AutomationSelectedSourceIntakeTerminalReadClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case GAME_DESIGN_SOURCE -> {
          var client = new GrpcSelectedOwnerIntakeSourceClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case WORLD_INVENTORY -> {
          var client = new WorldSelectedOwnerInventoryGrpcClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case GAME_DESIGN_PUBLICATION_TERMINAL -> {
          var client = new GameDesignPublicationTerminalClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case ACCOUNT_PUBLICATION_SETTLEMENT -> {
          var client =
              new AccountSelectedPublicationSettlementClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case ACCOUNT_PUBLICATION_HELD_READ -> {
          var client =
              new AccountPublicationAuthorizationReadClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case ACCOUNT_PUBLICATION_ORDER -> {
          var client = new AccountSelectedPublicationOrderClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case GAME_DESIGN_PUBLICATION_SELECTION -> {
          var client = new AuthoredDraftPublishSelectionReadClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case WORLD_PUBLICATION_TERMINAL -> {
          var client = new WorldPublicationTerminalReadClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
      };
    }
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
