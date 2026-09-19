package net.firedevops.firemud.common.grpc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

class GrpcServerTlsReloaderTest {
  @Test
  void restartFailureAfterStopFailsReadinessAndRetries(@TempDir Path directory) throws Exception {
    GrpcServerLifecycle serverLifecycle = mock(GrpcServerLifecycle.class);
    AtomicInteger startAttempts = new AtomicInteger();
    CountDownLatch firstStartFailed = new CountDownLatch(1);
    CountDownLatch retryStarted = new CountDownLatch(1);
    doAnswer(
            ignored -> {
              if (startAttempts.incrementAndGet() == 1) {
                firstStartFailed.countDown();
                throw new IllegalStateException("simulated TLS restart failure");
              }
              retryStarted.countDown();
              return null;
            })
        .when(serverLifecycle)
        .start();

    GrpcServerTlsReloader reloader = new GrpcServerTlsReloader(serverLifecycle);
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");

    try (TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(List.of(certificate), () -> invokeReload(reloader))) {
      Files.writeString(certificate, "certificate-2");

      assertTrue(firstStartFailed.await(5, TimeUnit.SECONDS));
      awaitCondition(() -> !watcher.isHealthy());
      assertTrue(retryStarted.await(5, TimeUnit.SECONDS));
      awaitCondition(watcher::isHealthy);

      assertTrue(startAttempts.get() >= 2);
      verify(serverLifecycle, atLeast(2)).stop();
      verify(serverLifecycle, atLeast(2)).start();
      InOrder restartOrder = inOrder(serverLifecycle);
      restartOrder.verify(serverLifecycle).stop();
      restartOrder.verify(serverLifecycle).start();
      restartOrder.verify(serverLifecycle).stop();
      restartOrder.verify(serverLifecycle).start();
    }
  }

  private static void invokeReload(GrpcServerTlsReloader reloader) {
    try {
      Method reload = GrpcServerTlsReloader.class.getDeclaredMethod("reload");
      reload.setAccessible(true);
      reload.invoke(reloader);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException failure) {
        throw failure;
      }
      throw new AssertionError("gRPC TLS reload failed with a non-runtime exception", e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Could not invoke gRPC TLS reload callback", e);
    }
  }

  private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean());
  }
}
