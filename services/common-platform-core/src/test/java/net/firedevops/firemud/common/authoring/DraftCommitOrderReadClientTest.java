package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.account.v1.AccountDraftCommitOrderReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DraftCommitOrderReadClientTest {
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";

  @Test
  void rejectsPlaintextWrongNamespaceAndUninitializedCallsBeforeTransport(@TempDir Path directory)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var plaintext = tls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new DraftCommitOrderReadClient(
                    new ServiceEndpointsProperties(), plaintext, factory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    var client =
        new DraftCommitOrderReadClient(
            new ServiceEndpointsProperties(), tls(directory), factory, "test");
    try {
      assertThatThrownBy(() -> client.read(request("other")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      assertThatThrownBy(() -> client.read(request("test")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      verifyNoInteractions(factory);
    } finally {
      client.close();
    }
  }

  @Test
  void refusesOwnerReadInsideTransactionOrSynchronizationBeforeRequestValidation(
      @TempDir Path directory) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("account.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client = new DraftCommitOrderReadClient(endpoints, tls(directory), factory, "test");
    try {
      client.init();
      var stub =
          mock(
              AccountDraftCommitOrderReadServiceGrpc.AccountDraftCommitOrderReadServiceBlockingStub
                  .class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      var request = request("test");
      when(stub.readHeldOriginalCommitOrder(any()))
          .thenReturn(DraftCommitOrderReadGrpcCodec.toHeldResponse(request));
      setStub(client, stub);

      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.read(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside an active transaction");
        assertThatThrownBy(() -> client.read(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside an active transaction");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }

      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThatThrownBy(() -> client.read(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside an active transaction");
        assertThatThrownBy(() -> client.read(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside an active transaction");
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }

      verify(stub, never()).readHeldOriginalCommitOrder(any());
      assertThat(client.read(request).request()).isEqualTo(request);
      verify(stub).readHeldOriginalCommitOrder(any());
    } finally {
      client.close();
    }
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
    DraftCommitOrderReadClient client = null;
    TlsCertificateWatcher originalWatcher = null;
    var channels = new CopyOnWriteArrayList<ManagedChannel>();
    try {
      ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("account.internal:6565");
      CommonGrpcClientProperties tls = tls(directory);
      GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
      when(factory.buildChannel(
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
      client = new DraftCommitOrderReadClient(endpoints, tls, factory, "test");
      client.init();
      DraftCommitOrderReadClient clientUnderTest = client;

      originalWatcher = watcher(client);
      Set<Thread> activeCallbacks = activeCallbacks(originalWatcher);
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
              "commit-order-client-monitor-holder");
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
              "commit-order-client-close");
      closeThread = closeWorker;
      closeWorker.start();
      assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitCondition(() -> closeWorker.getState() == Thread.State.BLOCKED);

      Files.writeString(Path.of(tls.getCertChain()), "certificate-rotated");
      assertThat(awaitBlockedReloadCallback(activeCallbacks)).isNotNull();
      releaseClientMonitor.countDown();
      closeWorker.join(TimeUnit.SECONDS.toMillis(4));
      assertThat(closeWorker.isAlive())
          .as("close must not hold the client monitor while the watcher drains its callback")
          .isFalse();
      assertThat(closeFailure.get()).isNull();
      assertThat(channels).isNotEmpty();
    } finally {
      releaseClientMonitor.countDown();
      if (monitorHolder != null) monitorHolder.join(TimeUnit.SECONDS.toMillis(5));
      if (closeThread != null) closeThread.join(TimeUnit.SECONDS.toMillis(7));
      try {
        if (originalWatcher != null) originalWatcher.close();
      } finally {
        try {
          if (client != null) {
            if (originalWatcher != null) setWatcher(client, originalWatcher);
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
  void configuresExactAccountServerIdentityAndReturnsOnlyExactHeldResponse(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account.internal:6565");
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(
            eq("account.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client = new DraftCommitOrderReadClient(endpoints, tls(directory), factory, "test");
    try {
      client.init();
      AbstractStub<?> initialized = initializedStub(client);
      assertThat(initialized.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(initialized.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/account-service");
      assertThat(initialized.getCallOptions().getMaxInboundMessageSize())
          .isEqualTo(DraftCommitOrderReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES);

      var stub =
          mock(
              AccountDraftCommitOrderReadServiceGrpc.AccountDraftCommitOrderReadServiceBlockingStub
                  .class);
      when(stub.withDeadlineAfter(eq(5L), eq(java.util.concurrent.TimeUnit.SECONDS)))
          .thenReturn(stub);
      setStub(client, stub);
      var request = request("test");
      var held = DraftCommitOrderReadGrpcCodec.toHeldResponse(request);
      when(stub.readHeldOriginalCommitOrder(any())).thenReturn(held);

      assertThat(client.read(request).request()).isEqualTo(request);
      verify(stub).readHeldOriginalCommitOrder(any());

      when(stub.readHeldOriginalCommitOrder(any()))
          .thenReturn(held.toBuilder().setTargetNamespace("other").build());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid COMMIT_ORDER evidence");
      when(stub.readHeldOriginalCommitOrder(any()))
          .thenReturn(ReadHeldOriginalCommitOrderResponse.getDefaultInstance());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid COMMIT_ORDER evidence");
      when(stub.readHeldOriginalCommitOrder(any()))
          .thenThrow(Status.UNAVAILABLE.withDescription("offline").asRuntimeException());
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
          .isEqualTo(Status.Code.UNAVAILABLE);
    } finally {
      client.close();
    }
  }

  private static DraftCommitOrderReadEvidence.Request request(String namespace) {
    return new DraftCommitOrderReadEvidence.Request(
        1,
        namespace,
        java.util.UUID.fromString("33333333-3333-4333-8333-333333333333"),
        DraftCommitOrderReadGrpcCodecTest.accountBinding());
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return tls;
  }

  private static AbstractStub<?> initializedStub(DraftCommitOrderReadClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  private static void setStub(
      DraftCommitOrderReadClient client,
      AccountDraftCommitOrderReadServiceGrpc.AccountDraftCommitOrderReadServiceBlockingStub stub)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
  }

  private static Object readField(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static TlsCertificateWatcher watcher(DraftCommitOrderReadClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    field.setAccessible(true);
    return (TlsCertificateWatcher) field.get(client);
  }

  private static void setWatcher(DraftCommitOrderReadClient client, TlsCertificateWatcher watcher)
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

  private static Thread awaitBlockedReloadCallback(Set<Thread> activeCallbacks)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      for (Thread callback : activeCallbacks) {
        if (callback.getState() == Thread.State.BLOCKED) {
          for (StackTraceElement frame : callback.getStackTrace()) {
            if (frame.getClassName().endsWith("AbstractReloadingBlockingGrpcClient")
                && frame.getMethodName().equals("reloadChannel")) {
              return callback;
            }
          }
        }
      }
      Thread.sleep(10);
    }
    return null;
  }

  private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
