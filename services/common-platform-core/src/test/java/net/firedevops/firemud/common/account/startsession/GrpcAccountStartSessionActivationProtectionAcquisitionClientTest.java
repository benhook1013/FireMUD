package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.common.world.GrpcWorldOriginalStartSessionActivationTerminalReadClient;
import net.firedevops.firemud.common.world.GrpcWorldOriginalStartSessionProtectedActivationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Client construction and boundary denials; no remote Account call or TLS identity proof. */
class GrpcAccountStartSessionActivationProtectionAcquisitionClientTest {
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
  void rejectsUninitializedUseAndAnyAmbientTransactionBeforeRequestHandling() throws IOException {
    var client = client(fileBackedProperties());
    assertThatThrownBy(() -> client.acquire(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.acquire(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
    TransactionSynchronizationManager.setActualTransactionActive(false);

    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> client.acquire(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
    TransactionSynchronizationManager.clearSynchronization();
  }

  @ParameterizedTest
  @EnumSource(ClientKind.class)
  void closeDrainsAnActiveReloadCallbackWithoutHoldingTheClientMonitor(ClientKind clientKind)
      throws Exception {
    LifecycleClient lifecycle = lifecycleClient(clientKind, fileBackedProperties());
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch allowReload = new CountDownLatch(1);
    ExecutorService callbackExecutor =
        Executors.newSingleThreadExecutor(daemonThreadFactory("active-tls-reload"));
    ExecutorService closeExecutor =
        Executors.newSingleThreadExecutor(daemonThreadFactory("grpc-client-close"));
    TlsCertificateWatcher initialWatcher = null;
    TlsCertificateWatcher activeWatcher = null;
    Future<Object> callbackResult = null;
    Future<?> closeResult = null;
    boolean initialized = false;
    Throwable primaryFailure = null;

    try {
      lifecycle.initialize().run();
      initialized = true;
      initialWatcher = watcher(lifecycle.target());
      if (initialWatcher != null) {
        initialWatcher.close();
      }

      // Hold a registered callback before its reload enters the client's synchronized path.
      TlsCertificateWatcher controlledWatcher =
          new TlsCertificateWatcher(
              List.of(
                  temporaryDirectory.resolve("client.pem"),
                  temporaryDirectory.resolve("client-key.pem"),
                  temporaryDirectory.resolve("ca.pem")),
              () -> {
                callbackEntered.countDown();
                awaitReloadPermit(allowReload);
                invokeReloadChannel(lifecycle.target());
              });
      activeWatcher = controlledWatcher;
      setWatcher(lifecycle.target(), controlledWatcher);
      controlledWatcher.start();

      callbackResult = callbackExecutor.submit(() -> invokeReloadCallback(controlledWatcher));
      assertThat(callbackEntered.await(5, TimeUnit.SECONDS))
          .as("the watcher callback is active before close starts")
          .isTrue();

      closeResult =
          closeExecutor.submit(
              () -> {
                lifecycle.close().run();
                return null;
              });
      assertThat(awaitCondition(() -> !controlledWatcher.isRunning(), 5, TimeUnit.SECONDS))
          .as("close reaches watcher shutdown while the callback is active")
          .isTrue();

      allowReload.countDown();
      closeResult.get(2, TimeUnit.SECONDS);
      assertThat(callbackResult.get(2, TimeUnit.SECONDS).toString()).isEqualTo("SUCCEEDED");
    } catch (Exception | Error failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      Throwable cleanupFailure =
          cleanupTest(
              lifecycle,
              initialized,
              allowReload,
              closeResult,
              callbackResult,
              activeWatcher,
              initialWatcher,
              callbackExecutor,
              closeExecutor);
      if (cleanupFailure != null) {
        if (primaryFailure != null) {
          addSuppressed(primaryFailure, cleanupFailure);
        } else {
          throwCleanupFailure(cleanupFailure);
        }
      }
    }
  }

  private GrpcAccountStartSessionActivationProtectionAcquisitionClient client(
      CommonGrpcClientProperties tls) {
    return new GrpcAccountStartSessionActivationProtectionAcquisitionClient(
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

  private LifecycleClient lifecycleClient(
      ClientKind clientKind, CommonGrpcClientProperties tlsProperties) {
    var endpoints = new ServiceEndpointsProperties();
    var channelFactory = new TestChannelFactory();
    return switch (clientKind) {
      case ACCOUNT_ACQUISITION -> {
        var target =
            new GrpcAccountStartSessionActivationProtectionAcquisitionClient(
                endpoints, tlsProperties, channelFactory, "world-runtime");
        yield new LifecycleClient(target, target::init, target::close);
      }
      case ACCOUNT_SETTLEMENT -> {
        var target =
            new GrpcAccountStartSessionActivationProtectionSettlementClient(
                endpoints, tlsProperties, channelFactory, "world-runtime");
        yield new LifecycleClient(target, target::init, target::close);
      }
      case WORLD_TERMINAL_READ -> {
        var target =
            new GrpcWorldOriginalStartSessionActivationTerminalReadClient(
                endpoints, tlsProperties, channelFactory, "world-runtime");
        yield new LifecycleClient(target, target::init, target::close);
      }
      case WORLD_PROTECTED_ACTIVATION -> {
        var target =
            new GrpcWorldOriginalStartSessionProtectedActivationClient(
                endpoints, tlsProperties, channelFactory, "world-runtime");
        yield new LifecycleClient(target, target::init, target::close);
      }
    };
  }

  private static TlsCertificateWatcher watcher(Object client) throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    field.setAccessible(true);
    return (TlsCertificateWatcher) field.get(client);
  }

  private static void setWatcher(Object client, TlsCertificateWatcher watcher)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    field.setAccessible(true);
    field.set(client, watcher);
  }

  private static Object invokeReloadCallback(TlsCertificateWatcher watcher) throws Exception {
    Method callback =
        TlsCertificateWatcher.class.getDeclaredMethod("invokeReloadCallback", boolean.class);
    callback.setAccessible(true);
    try {
      return callback.invoke(watcher, false);
    } catch (InvocationTargetException failure) {
      throw rethrow(failure.getCause());
    }
  }

  private static void invokeReloadChannel(Object client) {
    try {
      Method reload = AbstractReloadingBlockingGrpcClient.class.getDeclaredMethod("reloadChannel");
      reload.setAccessible(true);
      reload.invoke(client);
    } catch (InvocationTargetException failure) {
      throw new IllegalStateException("gRPC client reload failed", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Could not invoke gRPC client reload", failure);
    }
  }

  private static Exception rethrow(Throwable failure) {
    if (failure instanceof Exception exception) {
      return exception;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    return new IllegalStateException(failure);
  }

  private static void awaitReloadPermit(CountDownLatch allowReload) {
    try {
      if (!allowReload.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test did not release the active reload callback");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("active reload callback was interrupted", interrupted);
    }
  }

  private static boolean awaitCondition(
      BooleanSupplier condition, long timeout, TimeUnit timeUnit) {
    long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        return false;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      if (Thread.currentThread().isInterrupted()) {
        return false;
      }
    }
    return true;
  }

  private static Throwable cleanupTest(
      LifecycleClient lifecycle,
      boolean initialized,
      CountDownLatch allowReload,
      Future<?> closeResult,
      Future<?> callbackResult,
      TlsCertificateWatcher activeWatcher,
      TlsCertificateWatcher initialWatcher,
      ExecutorService callbackExecutor,
      ExecutorService closeExecutor) {
    allowReload.countDown();
    Throwable failure = null;
    if (closeResult != null) {
      failure = attemptCleanup(failure, () -> closeResult.get(7, TimeUnit.SECONDS));
    } else if (initialized) {
      failure = attemptCleanup(failure, () -> lifecycle.close().run());
    }
    if (callbackResult != null) {
      failure = attemptCleanup(failure, () -> callbackResult.get(7, TimeUnit.SECONDS));
    }
    if (activeWatcher != null) {
      failure = attemptCleanup(failure, activeWatcher::close);
    }
    if (initialWatcher != null) {
      failure = attemptCleanup(failure, initialWatcher::close);
    }
    failure = attemptCleanup(failure, callbackExecutor::shutdownNow);
    failure = attemptCleanup(failure, closeExecutor::shutdownNow);
    failure =
        attemptCleanup(
            failure,
            () -> assertThat(callbackExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue());
    failure =
        attemptCleanup(
            failure,
            () -> assertThat(closeExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue());
    return failure;
  }

  private static Throwable attemptCleanup(Throwable priorFailure, CheckedAction cleanup) {
    try {
      cleanup.run();
    } catch (Throwable cleanupFailure) {
      if (priorFailure == null) {
        return cleanupFailure;
      }
      addSuppressed(priorFailure, cleanupFailure);
    }
    return priorFailure;
  }

  private static void addSuppressed(Throwable primary, Throwable secondary) {
    if (primary != secondary) {
      primary.addSuppressed(secondary);
    }
  }

  private static void throwCleanupFailure(Throwable failure) throws Exception {
    if (failure instanceof Exception exception) {
      throw exception;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    throw new IllegalStateException(failure);
  }

  private static ThreadFactory daemonThreadFactory(String threadName) {
    AtomicInteger threadNumber = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, threadName + "-" + threadNumber.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private enum ClientKind {
    ACCOUNT_ACQUISITION,
    ACCOUNT_SETTLEMENT,
    WORLD_TERMINAL_READ,
    WORLD_PROTECTED_ACTIVATION
  }

  private record LifecycleClient(Object target, CheckedAction initialize, CheckedAction close) {}

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }

  private static final class TestChannelFactory extends GrpcChannelFactory {
    private final ManagedChannel channel = mock(ManagedChannel.class);

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      return channel;
    }
  }
}
