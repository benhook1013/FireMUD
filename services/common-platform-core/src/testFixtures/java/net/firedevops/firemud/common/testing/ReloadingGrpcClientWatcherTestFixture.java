package net.firedevops.firemud.common.testing;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;

/** Shared reflection and synchronization helpers for reloading gRPC client shutdown tests. */
public final class ReloadingGrpcClientWatcherTestFixture {
  private ReloadingGrpcClientWatcherTestFixture() {}

  public static Thread awaitBlockedReloadCallback(TlsCertificateWatcher watcher)
      throws ReflectiveOperationException, InterruptedException {
    Set<Thread> activeCallbacks = activeCallbacks(watcher);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      for (Thread callback : activeCallbacks) {
        if (callback.getState() == Thread.State.BLOCKED && hasReloadChannelFrame(callback)) {
          return callback;
        }
      }
      Thread.sleep(10);
    }
    return null;
  }

  public static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    if (!condition.getAsBoolean()) {
      throw new AssertionError("Condition was not met within 5 seconds");
    }
  }

  public static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  public static TlsCertificateWatcher watcher(AbstractReloadingBlockingGrpcClient<?> client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    field.setAccessible(true);
    return (TlsCertificateWatcher) field.get(client);
  }

  public static void setWatcher(
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

  private static boolean hasReloadChannelFrame(Thread thread) {
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (frame.getClassName().endsWith("AbstractReloadingBlockingGrpcClient")
          && frame.getMethodName().equals("reloadChannel")) {
        return true;
      }
    }
    return false;
  }
}
