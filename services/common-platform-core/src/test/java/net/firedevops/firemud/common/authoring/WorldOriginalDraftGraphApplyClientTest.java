package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.AbstractStub;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.worldmanagement.v1.WorldOriginalDraftGraphApplyServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldOriginalDraftGraphApplyClientTest {
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
    WorldOriginalDraftGraphApplyClient client = null;
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
      client = new WorldOriginalDraftGraphApplyClient(endpoints, tls, channelFactory, "test");
      client.init();
      WorldOriginalDraftGraphApplyClient clientUnderTest = client;

      AbstractStub<?> initializedStub = initializedStub(client);
      assertThat(initializedStub.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(initializedStub.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/world-management-service");

      originalWatcher = watcher(client);
      Set<Thread> activeCallbacks = activeCallbacks(originalWatcher);
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
              "original-graph-client-monitor-holder");
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
              "original-graph-client-close");
      closeThread = closeWorker;
      closeWorker.start();
      assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitCondition(() -> closeWorker.getState() == Thread.State.BLOCKED);

      Files.writeString(Path.of(tls.getCertChain()), "certificate-rotated");
      Thread callback = awaitBlockedReloadCallback(activeCallbacks);
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
      var request = WorldOriginalDraftGraphApplyGrpcCodecTest.request("test");
      assertThatThrownBy(() -> clientUnderTest.apply(request))
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

  private static Thread awaitBlockedReloadCallback(Set<Thread> activeCallbacks)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      for (Thread callback : activeCallbacks) {
        if (callback.getState() == Thread.State.BLOCKED
            && hasFrame(callback, "AbstractReloadingBlockingGrpcClient", "reloadChannel")) {
          return callback;
        }
      }
      Thread.sleep(10);
    }
    return null;
  }

  private static boolean hasFrame(Thread thread, String className, String methodName) {
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (frame.getClassName().endsWith(className) && frame.getMethodName().equals(methodName)) {
        return true;
      }
    }
    return false;
  }

  private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static TlsCertificateWatcher watcher(WorldOriginalDraftGraphApplyClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    field.setAccessible(true);
    return (TlsCertificateWatcher) field.get(client);
  }

  private static void setWatcher(
      WorldOriginalDraftGraphApplyClient client, TlsCertificateWatcher watcher)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    field.setAccessible(true);
    field.set(client, watcher);
  }

  @SuppressWarnings("unchecked")
  private static Set<Thread> activeCallbacks(TlsCertificateWatcher watcher)
      throws ReflectiveOperationException {
    Field field = TlsCertificateWatcher.class.getDeclaredField("activeCallbacks");
    field.setAccessible(true);
    return (Set<Thread>) field.get(watcher);
  }

  private static AbstractStub<?> initializedStub(WorldOriginalDraftGraphApplyClient client)
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

  private static void restoreReloadPolicy(String previousValue) {
    if (previousValue == null) {
      System.clearProperty(TLS_RELOAD_PROPERTY);
    } else {
      System.setProperty(TLS_RELOAD_PROPERTY, previousValue);
    }
  }

  @Test
  void refusesPlaintextMissingMaterialAndClasspathBeforeTransport(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var tls = tls(directory);
    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldOriginalDraftGraphApplyClient(
                    new ServiceEndpointsProperties(), tls, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    tls.setPlaintext(false);
    tls.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new WorldOriginalDraftGraphApplyClient(
                    new ServiceEndpointsProperties(), tls, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    tls.setCaCert(directory.resolve("missing.crt").toString());
    assertThatThrownBy(
            () ->
                new WorldOriginalDraftGraphApplyClient(
                    new ServiceEndpointsProperties(), tls, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(factory);
  }

  @Test
  void refusesWrongNamespaceUninitializedAndClosedUse(@TempDir Path directory) throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var client =
        new WorldOriginalDraftGraphApplyClient(
            new ServiceEndpointsProperties(), tls(directory), factory, "test");
    try {
      assertThatThrownBy(
              () -> client.apply(WorldOriginalDraftGraphApplyGrpcCodecTest.request("other")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> client.apply(WorldOriginalDraftGraphApplyGrpcCodecTest.request("test")))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(factory);
    } finally {
      client.close();
    }
    assertThatThrownBy(client::init).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> client.apply(WorldOriginalDraftGraphApplyGrpcCodecTest.request("test")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesOwnerApplyInsideTransactionOrSynchronizationBeforeRequestValidation(
      @TempDir Path directory) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("world.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client = new WorldOriginalDraftGraphApplyClient(endpoints, tls(directory), factory, "test");
    try {
      client.init();
      var stub =
          mock(
              WorldOriginalDraftGraphApplyServiceGrpc
                  .WorldOriginalDraftGraphApplyServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      var request = WorldOriginalDraftGraphApplyGrpcCodecTest.request("test");
      when(stub.applyOriginalDraftGraph(any()))
          .thenReturn(
              WorldOriginalDraftGraphApplyGrpcCodec.toResponse(
                  request, WorldOriginalDraftGraphApplyGrpcCodecTest.committed(request)));
      stubField().set(client, stub);

      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.apply(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside an active transaction");
        assertThatThrownBy(() -> client.apply(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside an active transaction");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }

      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThatThrownBy(() -> client.apply(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside an active transaction");
        assertThatThrownBy(() -> client.apply(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside an active transaction");
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }

      verify(stub, never()).applyOriginalDraftGraph(any());
      assertThat(client.apply(request).request()).isEqualTo(request);
      verify(stub).applyOriginalDraftGraph(any());
    } finally {
      client.close();
    }
  }

  @Test
  void configuresWorldEndpointAndExactPeerAndDecodesOnlyClosedResults(@TempDir Path directory)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("world.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    when(factory.buildChannel(
            eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(mock(ManagedChannel.class));
    var client = new WorldOriginalDraftGraphApplyClient(endpoints, tls(directory), factory, "test");
    try {
      client.init();
      var field = stubField();
      var actual =
          (WorldOriginalDraftGraphApplyServiceGrpc.WorldOriginalDraftGraphApplyServiceBlockingStub)
              field.get(client);
      assertThat(actual.getCallOptions().getMaxInboundMessageSize())
          .isEqualTo(WorldOriginalDraftGraphApplyGrpcCodec.MAX_RESPONSE_WIRE_BYTES);
      assertThat(client.workloadNamespace()).isEqualTo("test");
      assertThat(actual.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      var peerField =
          GrpcServerPeerIdentityCallCredentials.class.getDeclaredField("expectedPeerUri");
      peerField.setAccessible(true);
      assertThat(peerField.get(actual.getCallOptions().getCredentials()))
          .isEqualTo("spiffe://firemud/ns/test/sa/world-management-service");
      verify(factory)
          .buildChannel(
              eq("world.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
      var stub =
          mock(
              WorldOriginalDraftGraphApplyServiceGrpc
                  .WorldOriginalDraftGraphApplyServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      field.set(client, stub);
      var request = WorldOriginalDraftGraphApplyGrpcCodecTest.request("test");
      var response =
          WorldOriginalDraftGraphApplyGrpcCodec.toResponse(
              request, WorldOriginalDraftGraphApplyGrpcCodecTest.committed(request));
      when(stub.applyOriginalDraftGraph(any())).thenReturn(response);
      assertThat(client.apply(request).request()).isEqualTo(request);
      verify(stub)
          .applyOriginalDraftGraph(WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request));
      when(stub.applyOriginalDraftGraph(any()))
          .thenReturn(response.toBuilder().clearOwnerReadbackBytes().build());
      assertThatThrownBy(() -> client.apply(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid original graph apply evidence");
      var unavailable = Status.UNAVAILABLE.withDescription("lost response").asRuntimeException();
      when(stub.applyOriginalDraftGraph(any())).thenThrow(unavailable);
      assertThatThrownBy(() -> client.apply(request)).isSameAs(unavailable);
      var lostAck =
          Status.DEADLINE_EXCEEDED.withDescription("owner may have committed").asRuntimeException();
      doThrow(lostAck).when(stub).applyOriginalDraftGraph(any());
      assertThatThrownBy(() -> client.apply(request)).isSameAs(lostAck);
    } finally {
      client.close();
    }
  }

  private static Field stubField() throws ReflectiveOperationException {
    var field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return field;
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "fixture").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "fixture").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "fixture").toString());
    return tls;
  }
}
