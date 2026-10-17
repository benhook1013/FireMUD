package net.firedevops.firemud.common.account.startsession;

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
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Construction and local boundary denials; no authenticated Account transport proof. */
class GrpcAccountStartSessionWorldParticipationSettlementClientTest {
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";

  @TempDir Path temporaryDirectory;

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsPlaintextClasspathAndMissingMaterial() {
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> client(plaintext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var classpath = new CommonGrpcClientProperties();
    classpath.setCertChain("classpath:client.pem");
    classpath.setPrivateKey("/tmp/key.pem");
    classpath.setCaCert("/tmp/ca.pem");
    assertThatThrownBy(() -> client(classpath))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    assertThatThrownBy(() -> client(new CommonGrpcClientProperties()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");
  }

  @Test
  void rejectsUninitializedUseAmbientTransactionsAndChangedNamespace() throws IOException {
    var client = client(fileBackedProperties());
    assertThatThrownBy(() -> client.settle(input("world-runtime")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThatThrownBy(() -> client.settle(input("another-runtime")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.settle(input("world-runtime")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient World transaction");
    TransactionSynchronizationManager.setActualTransactionActive(false);

    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> client.settle(input("world-runtime")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient World transaction");
    TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void closePreventsExplicitInitialization() throws IOException {
    var client = client(fileBackedProperties());
    client.close();
    assertThatThrownBy(client::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

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
    GrpcAccountStartSessionWorldParticipationSettlementClient client = null;
    TlsCertificateWatcher originalWatcher = null;
    List<ManagedChannel> channels = new CopyOnWriteArrayList<>();
    try {
      ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("account.internal:6565");
      CommonGrpcClientProperties tls = tls(directory);
      GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
      when(channelFactory.buildChannel(
              eq("account.internal:6565"),
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
          new GrpcAccountStartSessionWorldParticipationSettlementClient(
              endpoints, tls, channelFactory, "world-runtime");
      client.init();
      GrpcAccountStartSessionWorldParticipationSettlementClient clientUnderTest = client;

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
              "settlement-client-monitor-holder");
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
              "settlement-client-close");
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

      assertThatThrownBy(clientUnderTest::init)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account World participation settlement client is closed");
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

  private static CommonGrpcClientProperties tls(Path directory) throws IOException {
    var properties = new CommonGrpcClientProperties();
    properties.setCertChain(
        Files.writeString(directory.resolve("client.pem"), "certificate").toString());
    properties.setPrivateKey(
        Files.writeString(directory.resolve("client-key.pem"), "private key").toString());
    properties.setCaCert(
        Files.writeString(directory.resolve("ca.pem"), "CA certificate").toString());
    return properties;
  }

  private static void restoreReloadPolicy(String previousReloadPolicy) {
    if (previousReloadPolicy == null) {
      System.clearProperty(TLS_RELOAD_PROPERTY);
    } else {
      System.setProperty(TLS_RELOAD_PROPERTY, previousReloadPolicy);
    }
  }

  private GrpcAccountStartSessionWorldParticipationSettlementClient client(
      CommonGrpcClientProperties tls) {
    return new GrpcAccountStartSessionWorldParticipationSettlementClient(
        new ServiceEndpointsProperties(), tls, mock(GrpcChannelFactory.class), "world-runtime");
  }

  private CommonGrpcClientProperties fileBackedProperties() throws IOException {
    Path certificate = Files.createFile(temporaryDirectory.resolve("client.pem"));
    Path privateKey = Files.createFile(temporaryDirectory.resolve("client-key.pem"));
    Path ca = Files.createFile(temporaryDirectory.resolve("ca.pem"));
    var properties = new CommonGrpcClientProperties();
    properties.setCertChain(certificate.toString());
    properties.setPrivateKey(privateKey.toString());
    properties.setCaCert(ca.toString());
    return properties;
  }

  private static AccountStartSessionWorldParticipationSettlementInput input(String namespace) {
    return new AccountStartSessionWorldParticipationSettlementInput(
        namespace,
        UUID.fromString("d1f41bfb-265a-4f3b-8b2a-124cba20ce43"),
        UUID.fromString("a8c1e8c8-f237-41b7-918d-ec2281bcac10"),
        31L);
  }
}
