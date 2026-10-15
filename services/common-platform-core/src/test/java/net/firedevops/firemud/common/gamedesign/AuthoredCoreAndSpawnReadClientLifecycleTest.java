package net.firedevops.firemud.common.gamedesign;

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
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsClient;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Real file-watcher lifecycle proof with mocked channels, not a TLS handshake. The controlled
 * self-signed Entity certificate satisfies only the spawn client's local constructor URI check; key
 * and CA files are readable placeholders, never production credentials.
 */
class AuthoredCoreAndSpawnReadClientLifecycleTest {
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";

  @ParameterizedTest
  @EnumSource(ClientKind.class)
  @Timeout(30)
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
              "authored-core-spawn-client-monitor-holder");
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
              "authored-core-spawn-client-close");
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
    AUTHORED_CORE,
    SPAWN_REQUIREMENTS;

    ClientHandle create(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tls,
        GrpcChannelFactory factory) {
      return switch (this) {
        case AUTHORED_CORE -> {
          var client = new GrpcAuthoredInitialActorCoreReadClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
        case SPAWN_REQUIREMENTS -> {
          var client = new WorldPublishedSpawnRequirementsClient(endpoints, tls, factory, "test");
          yield new ClientHandle(client, client::init);
        }
      };
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

  private static TlsCertificateWatcher watcher(AbstractReloadingBlockingGrpcClient<?> client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    field.setAccessible(true);
    return (TlsCertificateWatcher) field.get(client);
  }

  private static void setWatcher(
      AbstractReloadingBlockingGrpcClient<?> client, TlsCertificateWatcher watcher)
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

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(entityCertificate(directory).toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
    return tls;
  }

  /** Only the constructor's local Entity URI check uses this controlled certificate. */
  private static Path entityCertificate(Path directory) throws Exception {
    Path keytool =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                ? "keytool.exe"
                : "keytool");
    Path store = directory.resolve("entity.p12");
    Path output = directory.resolve("keytool.log");
    // Matches the existing spawn mTLS fixture's JDK keytool certificate-generation mechanism.
    Process process =
        new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias",
                "entity",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=Controlled lifecycle Entity",
                "-validity",
                "1",
                "-ext",
                "SAN=URI:spiffe://firemud/ns/test/sa/entity-management-service",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "controlled-lifecycle",
                "-keypass",
                "controlled-lifecycle")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Controlled lifecycle certificate generation timed out");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException(
          "Controlled lifecycle certificate generation failed: " + Files.readString(output));
    }
    var keys = java.security.KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(store)) {
      keys.load(input, "controlled-lifecycle".toCharArray());
    }
    return Files.write(directory.resolve("client.crt"), keys.getCertificate("entity").getEncoded());
  }

  private static void restoreReloadPolicy(String previousValue) {
    if (previousValue == null) {
      System.clearProperty(TLS_RELOAD_PROPERTY);
    } else {
      System.setProperty(TLS_RELOAD_PROPERTY, previousValue);
    }
  }
}
