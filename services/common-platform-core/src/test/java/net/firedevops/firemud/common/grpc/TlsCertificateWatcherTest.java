package net.firedevops.firemud.common.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsCertificateWatcherTest {
  @Test
  void rapidDirectFileChangesTriggerReload(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    Path privateKey = Files.writeString(directory.resolve("tls.key"), "key-1");
    Path caCertificate = Files.writeString(directory.resolve("ca.crt"), "ca-1");
    AtomicInteger reloads = new AtomicInteger();
    CountDownLatch reloaded = new CountDownLatch(1);

    try (TlsCertificateWatcher ignored =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate, privateKey, caCertificate),
            () -> {
              reloads.incrementAndGet();
              reloaded.countDown();
            })) {
      Files.writeString(certificate, "certificate-2");
      Files.writeString(privateKey, "key-2");
      Files.writeString(caCertificate, "ca-2");
      Files.writeString(certificate, "certificate-3");
      Files.writeString(privateKey, "key-3");
      Files.writeString(caCertificate, "ca-3");

      assertTrue(reloaded.await(5, TimeUnit.SECONDS));
      assertTrue(reloads.get() >= 1);
    }
  }

  @Test
  void changesQueuedAcrossWatchKeysInOneBurstTriggerExactlyOneReload(@TempDir Path directory)
      throws Exception {
    Path certificateDirectory = Files.createDirectory(directory.resolve("certificate"));
    Path privateKeyDirectory = Files.createDirectory(directory.resolve("private-key"));
    Path certificate = Files.writeString(certificateDirectory.resolve("tls.crt"), "certificate-1");
    Path privateKey = Files.writeString(privateKeyDirectory.resolve("tls.key"), "key-1");
    AtomicInteger reloads = new AtomicInteger();
    CountDownLatch firstReload = new CountDownLatch(1);
    CountDownLatch secondReload = new CountDownLatch(1);

    try (TlsCertificateWatcher watcher =
        new TlsCertificateWatcher(
            List.of(certificate, privateKey),
            () -> {
              if (reloads.incrementAndGet() == 1) {
                firstReload.countDown();
              } else {
                secondReload.countDown();
              }
            })) {
      Files.writeString(certificate, "certificate-2");
      Files.writeString(privateKey, "key-2");
      watcher.start();

      assertTrue(firstReload.await(5, TimeUnit.SECONDS));
      assertFalse(secondReload.await(300, TimeUnit.MILLISECONDS));
      assertEquals(1, reloads.get());
    }
  }

  @Test
  void projectedDataSwapAndOverflowBothRequestReload(@TempDir Path directory) {
    Set<Path> files = Set.of(directory.resolve("tls.crt").toAbsolutePath());

    assertTrue(
        TlsCertificateWatcher.isReloadEvent(
            files, directory, pathEvent(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("tls.crt"))));
    assertTrue(
        TlsCertificateWatcher.isReloadEvent(
            files, directory, pathEvent(StandardWatchEventKinds.ENTRY_DELETE, Path.of("tls.crt"))));
    assertFalse(
        TlsCertificateWatcher.isReloadEvent(
            files,
            directory,
            pathEvent(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("unrelated.txt"))));
    assertTrue(
        TlsCertificateWatcher.isReloadEvent(
            files, directory, pathEvent(StandardWatchEventKinds.ENTRY_CREATE, Path.of("..data"))));
    assertTrue(TlsCertificateWatcher.isReloadEvent(files, directory, overflowEvent()));
  }

  @Test
  void callbackFailureDoesNotStopWatching(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    Path privateKey = Files.writeString(directory.resolve("tls.key"), "key-1");
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch firstAttempt = new CountDownLatch(1);
    CountDownLatch successfulRetry = new CountDownLatch(1);

    try (TlsCertificateWatcher ignored =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate, privateKey),
            () -> {
              int attempt = attempts.incrementAndGet();
              if (attempt == 1) {
                firstAttempt.countDown();
                throw new IllegalStateException("simulated reload failure");
              }
              successfulRetry.countDown();
            })) {
      Files.writeString(certificate, "certificate-2");
      assertTrue(firstAttempt.await(5, TimeUnit.SECONDS));

      Files.writeString(privateKey, "key-2");
      assertTrue(successfulRetry.await(5, TimeUnit.SECONDS));
      assertTrue(attempts.get() >= 2);
    }
  }

  @Test
  void losingFinalWatchKeyStopsWatcher(@TempDir Path directory) throws Exception {
    Path watchedDirectory = Files.createDirectory(directory.resolve("certificate"));
    Path certificate = Files.writeString(watchedDirectory.resolve("tls.crt"), "certificate-1");

    try (TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(List.of(certificate), () -> {})) {
      Files.delete(certificate);
      Files.delete(watchedDirectory);

      awaitStopped(watcher);
    }
  }

  @Test
  void losingOneWatchKeyKeepsWatchingRemainingDirectories(@TempDir Path directory)
      throws Exception {
    Path retiredDirectory = Files.createDirectory(directory.resolve("retired"));
    Path retainedDirectory = Files.createDirectory(directory.resolve("retained"));
    Path retiredCertificate =
        Files.writeString(retiredDirectory.resolve("tls.crt"), "certificate-1");
    Path retainedCertificate =
        Files.writeString(retainedDirectory.resolve("tls.crt"), "certificate-1");
    CountDownLatch retiredReload = new CountDownLatch(1);
    CountDownLatch retainedReload = new CountDownLatch(1);
    AtomicBoolean retainedWriteComplete = new AtomicBoolean();
    Object reloadPhase = new Object();

    try (TlsCertificateWatcher watcher =
        new TlsCertificateWatcher(
            List.of(retiredCertificate, retainedCertificate),
            () -> {
              synchronized (reloadPhase) {
                if (retainedWriteComplete.get()) {
                  retainedReload.countDown();
                } else {
                  retiredReload.countDown();
                }
              }
            })) {
      assertTrue(watcher.hasAllRequiredRegistrations());
      Files.delete(retiredCertificate);
      Files.delete(retiredDirectory);
      watcher.start();
      assertTrue(retiredReload.await(5, TimeUnit.SECONDS));
      assertTrue(watcher.isRunning());
      assertFalse(watcher.hasAllRequiredRegistrations());

      synchronized (reloadPhase) {
        Files.writeString(retainedCertificate, "certificate-2");
        retainedWriteComplete.set(true);
      }
      assertTrue(retainedReload.await(5, TimeUnit.SECONDS));
      assertTrue(watcher.isRunning());
      assertFalse(watcher.hasAllRequiredRegistrations());
    }
  }

  private static void awaitStopped(TlsCertificateWatcher watcher) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (watcher.isRunning() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(watcher.isRunning());
  }

  private static WatchEvent<Path> pathEvent(WatchEvent.Kind<Path> kind, Path context) {
    return new WatchEvent<>() {
      @Override
      public Kind<Path> kind() {
        return kind;
      }

      @Override
      public int count() {
        return 1;
      }

      @Override
      public Path context() {
        return context;
      }
    };
  }

  private static WatchEvent<Object> overflowEvent() {
    return new WatchEvent<>() {
      @Override
      public Kind<Object> kind() {
        return StandardWatchEventKinds.OVERFLOW;
      }

      @Override
      public int count() {
        return 1;
      }

      @Override
      public Object context() {
        return null;
      }
    };
  }
}
