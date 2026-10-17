package net.firedevops.firemud.common.world;

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
import io.grpc.ManagedChannelBuilder;
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
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadClient.InvalidTerminalEvidenceException;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldStartSessionExecutionTerminalResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldStartSessionExecutionTerminalReadServiceGrpc.WorldStartSessionExecutionTerminalReadServiceBlockingStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldStartSessionExecutionTerminalReadClientTest {
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";

  /**
   * Controlled file watcher and mocked channels prove local lifecycle only, not mTLS or World
   * authority.
   */
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
    WorldStartSessionExecutionTerminalReadClient client = null;
    TlsCertificateWatcher originalWatcher = null;
    List<ManagedChannel> channels = new CopyOnWriteArrayList<>();
    try {
      ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
      endpoints.setWorldManagementService("world.internal:6565");
      CommonGrpcClientProperties tls = fileBacked(directory);
      GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
      when(channelFactory.buildChannel(
              eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
          .thenAnswer(
              invocation -> {
                ManagedChannel channel = mock(ManagedChannel.class);
                channels.add(channel);
                return channel;
              });
      client =
          new WorldStartSessionExecutionTerminalReadClient(
              endpoints, tls, channelFactory, "world-runtime");
      client.init();
      WorldStartSessionExecutionTerminalReadClient clientUnderTest = client;

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
              "world-start-session-terminal-client-monitor-holder");
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
              "world-start-session-terminal-client-close");
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
          .hasMessage("World StartSession terminal read client is closed");
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
          if (previousReloadPolicy == null) {
            System.clearProperty(TLS_RELOAD_PROPERTY);
          } else {
            System.setProperty(TLS_RELOAD_PROPERTY, previousReloadPolicy);
          }
        }
      }
    }
  }

  @Test
  void malformedOrMismatchedOwnerResponseHasADistinctEvidenceFailure(@TempDir Path directory)
      throws Exception {
    var client =
        new WorldStartSessionExecutionTerminalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    // Controlled stub responses prove decoding classification, not authenticated transport.
    var stub = mock(WorldStartSessionExecutionTerminalReadServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    ReflectionTestUtils.setField(client, "stub", stub);
    ReflectionTestUtils.setField(client, "initialized", true);
    var request = WorldStartSessionExecutionTerminalReadGrpcCodecTest.request();
    try {
      for (var response :
          List.of(
              ReadWorldStartSessionExecutionTerminalResponse.getDefaultInstance(),
              ReadWorldStartSessionExecutionTerminalResponse.newBuilder()
                  .setReadRequestId(UUID.randomUUID().toString())
                  .build(),
              ReadWorldStartSessionExecutionTerminalResponse.newBuilder()
                  .setReadRequestId(request.readRequestId().toString())
                  .build())) {
        when(stub.readWorldStartSessionExecutionTerminal(any())).thenReturn(response);
        assertThatThrownBy(() -> client.read(request))
            .isInstanceOf(InvalidTerminalEvidenceException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);
      }
    } finally {
      client.close();
    }
  }

  @Test
  void clientReceiveLimitIncludesTheCompleteCanonicalTerminalEnvelope(@TempDir Path directory)
      throws Exception {
    var client =
        new WorldStartSessionExecutionTerminalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    // No connection is made: this verifies stub configuration, not authenticated socket proof.
    var channel = ManagedChannelBuilder.forTarget("localhost:1").usePlaintext().build();
    try {
      assertThat(client.buildStub(channel).getCallOptions().getMaxInboundMessageSize())
          .isEqualTo(WorldStartSessionExecutionTerminalReadGrpcCodec.MAX_RESPONSE_BYTES)
          .isGreaterThan(WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES);
    } finally {
      channel.shutdownNow();
      client.close();
    }
  }

  @Test
  void requiresFileBackedMtlsInitializationAndAnIndependentCallerTransaction(
      @TempDir Path directory) throws Exception {
    var plaintext = fileBacked(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldStartSessionExecutionTerminalReadClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    new GrpcChannelFactory(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var classpath = fileBacked(directory);
    classpath.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new WorldStartSessionExecutionTerminalReadClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    new GrpcChannelFactory(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    var client =
        new WorldStartSessionExecutionTerminalReadClient(
            new ServiceEndpointsProperties(),
            fileBacked(directory),
            new GrpcChannelFactory(),
            "world-runtime");
    try {
      assertThatThrownBy(() -> client.read(null)).isInstanceOf(NullPointerException.class);
      var request = WorldStartSessionExecutionTerminalReadGrpcCodecTest.request();
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized");

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient transaction");
    } finally {
      TransactionSynchronizationManager.clear();
      client.close();
    }
  }

  private static CommonGrpcClientProperties fileBacked(Path directory) throws Exception {
    var properties = new CommonGrpcClientProperties();
    properties.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    properties.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "private key").toString());
    properties.setCaCert(
        Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return properties;
  }
}
