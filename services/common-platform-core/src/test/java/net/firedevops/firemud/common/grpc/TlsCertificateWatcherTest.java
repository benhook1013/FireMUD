package net.firedevops.firemud.common.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.CommonCoreAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

class TlsCertificateWatcherTest {
  @Test
  void aggregateHealthMatchesCurrentRegistryCounts() {
    assertAggregateHealth(TlsCertificateWatcher.health());
  }

  @Test
  void registrationRetryBackoffGrowsAndCaps() {
    assertEquals(100, TlsCertificateWatcher.registrationRetryDelay(1).toMillis());
    assertEquals(200, TlsCertificateWatcher.registrationRetryDelay(2).toMillis());
    assertEquals(400, TlsCertificateWatcher.registrationRetryDelay(3).toMillis());
    assertEquals(800, TlsCertificateWatcher.registrationRetryDelay(4).toMillis());
    assertEquals(1_600, TlsCertificateWatcher.registrationRetryDelay(5).toMillis());
    assertEquals(25_600, TlsCertificateWatcher.registrationRetryDelay(9).toMillis());
    assertEquals(30_000, TlsCertificateWatcher.registrationRetryDelay(10).toMillis());
    assertEquals(30_000, TlsCertificateWatcher.registrationRetryDelay(20).toMillis());
  }

  @Test
  void autoConfiguredHealthIndicatorReportsAggregateHealth() {
    HealthIndicator indicator =
        new CommonCoreAutoConfiguration().tlsCertificateReloadHealthIndicator();

    assertAggregateHealth(indicator.health());
  }

  @Test
  void healthyWatcherReportsWatching(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    WatcherCounts baseline = watcherCounts(TlsCertificateWatcher.health());

    try (TlsCertificateWatcher ignored =
        TlsCertificateWatcher.createAndStart(List.of(certificate), () -> {})) {
      assertHealthDelta(baseline, 1, 1, 0, TlsCertificateWatcher.health());
    }
  }

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
  void changesQueuedAcrossWatchKeysCauseBoundedReloads(@TempDir Path directory) throws Exception {
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
      boolean secondReloadObserved = secondReload.await(300, TimeUnit.MILLISECONDS);
      assertTrue(!secondReloadObserved || reloads.get() >= 2);
      assertTrue(reloads.get() >= 1 && reloads.get() <= 2);
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
  void failedWatchKeyResetReregistersRecreatedDirectory(@TempDir Path directory) throws Exception {
    Path watchedDirectory = Files.createDirectory(directory.resolve("certificate"));
    Path certificate = Files.writeString(watchedDirectory.resolve("tls.crt"), "certificate-1");
    CountDownLatch reloaded = new CountDownLatch(1);

    try (TlsCertificateWatcher watcher =
        new TlsCertificateWatcher(List.of(certificate), reloaded::countDown)) {
      Files.writeString(certificate, "certificate-2");
      Files.delete(certificate);
      Files.delete(watchedDirectory);
      Files.createDirectory(watchedDirectory);
      Files.writeString(certificate, "certificate-3");
      watcher.start();

      assertTrue(reloaded.await(5, TimeUnit.SECONDS));
      assertTrue(watcher.isRunning());
      assertTrue(watcher.isHealthy());
    }
  }

  @Test
  void failedInitialReregistrationRetriesAfterDirectoryIsRecreated(@TempDir Path directory)
      throws Exception {
    Path replacedDirectory = Files.createDirectory(directory.resolve("replaced"));
    Path retainedDirectory = Files.createDirectory(directory.resolve("retained"));
    Path replacedCertificate =
        Files.writeString(replacedDirectory.resolve("tls.crt"), "certificate-1");
    Path retainedCertificate =
        Files.writeString(retainedDirectory.resolve("tls.crt"), "certificate-1");

    Logger logger = (Logger) LoggerFactory.getLogger(TlsCertificateWatcher.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    logger.addAppender(appender);
    try {
      try (TlsCertificateWatcher watcher =
          TlsCertificateWatcher.createAndStart(
              List.of(replacedCertificate, retainedCertificate), () -> {})) {
        Files.delete(replacedCertificate);
        Files.delete(replacedDirectory);
        awaitUnhealthy(watcher);
        awaitLogCount(
            appender, Level.ERROR, "TLS certificate watcher failed its re-registration retry", 3);
        assertFalse(watcher.isHealthy());

        Files.createDirectory(replacedDirectory);
        Files.writeString(replacedCertificate, "certificate-2");

        awaitHealthy(watcher);
        assertTrue(watcher.isRunning());
      }
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void failedInitialReregistrationRemainsFailClosedAndCloseCancelsRetry(@TempDir Path directory)
      throws Exception {
    Path missingDirectory = Files.createDirectory(directory.resolve("missing"));
    Path retainedDirectory = Files.createDirectory(directory.resolve("retained"));
    Path missingCertificate =
        Files.writeString(missingDirectory.resolve("tls.crt"), "certificate-1");
    Path retainedCertificate =
        Files.writeString(retainedDirectory.resolve("tls.crt"), "certificate-1");
    WatcherCounts baseline = watcherCounts(TlsCertificateWatcher.health());

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(missingCertificate, retainedCertificate), () -> {});
    try {
      Files.delete(missingCertificate);
      Files.delete(missingDirectory);
      awaitUnhealthy(watcher);
      Thread.sleep(700);
      assertFalse(watcher.isHealthy());
      assertHealthDelta(baseline, 1, 0, 1, TlsCertificateWatcher.health());
    } finally {
      watcher.close();
    }

    Files.createDirectory(missingDirectory);
    assertFalse(watcher.isRunning());
    assertHealthDelta(baseline, 0, 0, 0, TlsCertificateWatcher.health());
  }

  @Test
  void failedWatchKeyResetWhenDirectoryIsGoneRemainsFailClosed(@TempDir Path directory)
      throws Exception {
    Path watchedDirectory = Files.createDirectory(directory.resolve("certificate"));
    Path certificate = Files.writeString(watchedDirectory.resolve("tls.crt"), "certificate-1");
    WatcherCounts baseline = watcherCounts(TlsCertificateWatcher.health());

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(List.of(certificate), () -> {});
    try {
      Files.delete(certificate);
      Files.delete(watchedDirectory);
      awaitStopped(watcher);
      assertFalse(watcher.isHealthy());
      assertHealthDelta(baseline, 1, 0, 1, TlsCertificateWatcher.health());
    } finally {
      watcher.close();
    }
    assertHealthDelta(baseline, 0, 0, 0, TlsCertificateWatcher.health());
  }

  @Test
  void callbackFailureDoesNotStopWatching(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    Path privateKey = Files.writeString(directory.resolve("tls.key"), "key-1");
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch firstAttempt = new CountDownLatch(1);
    CountDownLatch successfulRetry = new CountDownLatch(1);
    WatcherCounts baseline = watcherCounts(TlsCertificateWatcher.health());

    try (TlsCertificateWatcher watcher =
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
      awaitUnhealthy(watcher);
      assertHealthDelta(baseline, 1, 0, 1, TlsCertificateWatcher.health());

      assertTrue(successfulRetry.await(5, TimeUnit.SECONDS));
      assertTrue(attempts.get() >= 2);
      awaitHealthy(watcher);
      assertHealthDelta(baseline, 1, 1, 0, TlsCertificateWatcher.health());
    }
  }

  @Test
  void closeWaitsForActiveReloadCallbackBeforeReturning(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    AtomicBoolean callbackExited = new AtomicBoolean();
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate),
            () -> {
              callbackEntered.countDown();
              try {
                releaseCallback.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                callbackExited.set(true);
              }
            });
    try {
      Files.writeString(certificate, "certificate-2");
      assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));

      CountDownLatch closeFinished = new CountDownLatch(1);
      Thread closeThread =
          new Thread(
              () -> {
                try {
                  watcher.close();
                } catch (Throwable e) {
                  closeFailure.set(e);
                } finally {
                  closeFinished.countDown();
                }
              });
      closeThread.start();

      assertFalse(closeFinished.await(200, TimeUnit.MILLISECONDS));
      assertFalse(callbackExited.get());
      releaseCallback.countDown();
      assertTrue(closeFinished.await(5, TimeUnit.SECONDS));
      closeThread.join(5_000);
      assertTrue(callbackExited.get());
      assertFalse(watcher.isRunning());
      assertNull(closeFailure.get());
    } finally {
      releaseCallback.countDown();
      watcher.close();
    }
  }

  @Test
  void failedCallbackRetryIsCancelledWhenWatcherCloses(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch firstAttempt = new CountDownLatch(1);

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate),
            () -> {
              attempts.incrementAndGet();
              firstAttempt.countDown();
              throw new IllegalStateException("simulated reload failure");
            });
    try {
      Files.writeString(certificate, "certificate-2");
      assertTrue(firstAttempt.await(5, TimeUnit.SECONDS));
    } finally {
      watcher.close();
    }

    Thread.sleep(300);
    assertEquals(1, attempts.get());
  }

  @Test
  void failedCallbackRetryIsBoundedUntilAnotherCertificateEvent(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch retryAttempt = new CountDownLatch(1);

    try (TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate),
            () -> {
              if (attempts.incrementAndGet() == 2) {
                retryAttempt.countDown();
              }
              throw new IllegalStateException("simulated reload failure");
            })) {
      Files.writeString(certificate, "certificate-2");
      assertTrue(retryAttempt.await(5, TimeUnit.SECONDS));
      Thread.sleep(300);
      assertEquals(2, attempts.get());
      assertFalse(watcher.isHealthy());
    }
  }

  @Test
  void unexpectedWorkerFailureReportsStoppedUntilCloseRemovesWatcher(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    CountDownLatch callbackInvoked = new CountDownLatch(1);
    WatcherCounts baseline = watcherCounts(TlsCertificateWatcher.health());

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate),
            () -> {
              callbackInvoked.countDown();
              throw new AssertionError("simulated unexpected worker failure");
            });
    try {
      Files.writeString(certificate, "certificate-2");
      assertTrue(callbackInvoked.await(5, TimeUnit.SECONDS));
      awaitStopped(watcher);
      assertFalse(watcher.isHealthy());
      assertHealthDelta(baseline, 1, 0, 1, TlsCertificateWatcher.health());
    } finally {
      watcher.close();
    }
    assertHealthDelta(baseline, 0, 0, 0, TlsCertificateWatcher.health());
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
    WatcherCounts baseline = watcherCounts(TlsCertificateWatcher.health());

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
      assertTrue(watcher.isHealthy());
      Files.delete(retiredCertificate);
      Files.delete(retiredDirectory);
      watcher.start();
      assertTrue(retiredReload.await(5, TimeUnit.SECONDS));
      assertTrue(watcher.isRunning());
      assertFalse(watcher.isHealthy());
      assertHealthDelta(baseline, 1, 0, 1, TlsCertificateWatcher.health());

      synchronized (reloadPhase) {
        Files.writeString(retainedCertificate, "certificate-2");
        retainedWriteComplete.set(true);
      }
      assertTrue(retainedReload.await(5, TimeUnit.SECONDS));
      assertTrue(watcher.isRunning());
      assertFalse(watcher.isHealthy());
      assertHealthDelta(baseline, 1, 0, 1, TlsCertificateWatcher.health());
    }
  }

  @Test
  void losingFinalWatchKeyReportsStoppedUntilCloseRemovesWatcher(@TempDir Path directory)
      throws Exception {
    Path watchedDirectory = Files.createDirectory(directory.resolve("certificate"));
    Path certificate = Files.writeString(watchedDirectory.resolve("tls.crt"), "certificate-1");
    WatcherCounts baseline = watcherCounts(TlsCertificateWatcher.health());

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(List.of(certificate), () -> {});
    try {
      Files.delete(certificate);
      Files.delete(watchedDirectory);
      awaitStopped(watcher);
      assertHealthDelta(baseline, 1, 0, 1, TlsCertificateWatcher.health());
    } finally {
      watcher.close();
    }
    assertHealthDelta(baseline, 0, 0, 0, TlsCertificateWatcher.health());
  }

  @Test
  void closingHealthyWatcherRestoresAggregateHealthBaseline(@TempDir Path directory)
      throws Exception {
    Path unrelatedCertificate =
        Files.writeString(directory.resolve("unrelated.crt"), "certificate-1");
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");

    try (TlsCertificateWatcher unrelated =
        TlsCertificateWatcher.createAndStart(List.of(unrelatedCertificate), () -> {})) {
      WatcherCounts baseline = watcherCounts(TlsCertificateWatcher.health());
      TlsCertificateWatcher watcher =
          TlsCertificateWatcher.createAndStart(List.of(certificate), () -> {});
      try {
        assertHealthDelta(baseline, 1, 1, 0, TlsCertificateWatcher.health());
      } finally {
        watcher.close();
      }
      assertHealthDelta(baseline, 0, 0, 0, TlsCertificateWatcher.health());
    }
  }

  private static void assertHealthDelta(
      WatcherCounts baseline,
      int activeDelta,
      int healthyDelta,
      int unhealthyDelta,
      Health health) {
    WatcherCounts actual = watcherCounts(health);
    assertEquals(baseline.active() + activeDelta, actual.active());
    assertEquals(baseline.healthy() + healthyDelta, actual.healthy());
    assertEquals(baseline.unhealthy() + unhealthyDelta, actual.unhealthy());
    assertAggregateHealth(health);
  }

  private static void assertAggregateHealth(Health health) {
    WatcherCounts counts = watcherCounts(health);
    assertEquals(counts.active(), counts.healthy() + counts.unhealthy());
    if (counts.active() == 0) {
      assertEquals("UP", health.getStatus().getCode());
      assertEquals("disabled_or_not_configured", health.getDetails().get("tlsReload"));
    } else if (counts.unhealthy() == 0) {
      assertEquals("UP", health.getStatus().getCode());
      assertEquals("watching", health.getDetails().get("tlsReload"));
    } else {
      assertEquals("OUT_OF_SERVICE", health.getStatus().getCode());
      assertEquals("watcher_unhealthy", health.getDetails().get("tlsReload"));
    }
  }

  private static WatcherCounts watcherCounts(Health health) {
    return new WatcherCounts(
        ((Number) health.getDetails().get("activeWatchers")).intValue(),
        ((Number) health.getDetails().get("healthyWatchers")).intValue(),
        ((Number) health.getDetails().get("unhealthyWatchers")).intValue());
  }

  private static void awaitStopped(TlsCertificateWatcher watcher) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (watcher.isRunning() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(watcher.isRunning());
  }

  private static void awaitUnhealthy(TlsCertificateWatcher watcher) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (watcher.isHealthy() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(watcher.isHealthy());
  }

  private static void awaitHealthy(TlsCertificateWatcher watcher) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!watcher.isHealthy() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(watcher.isHealthy());
  }

  private static void awaitLogCount(
      ListAppender<ILoggingEvent> appender, Level level, String message, int expectedCount)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      long count =
          appender.list.stream()
              .filter(event -> event.getLevel() == level)
              .filter(event -> event.getFormattedMessage().contains(message))
              .count();
      if (count >= expectedCount) {
        return;
      }
      Thread.sleep(10);
    }
    long count =
        appender.list.stream()
            .filter(event -> event.getLevel() == level)
            .filter(event -> event.getFormattedMessage().contains(message))
            .count();
    assertTrue(count >= expectedCount, "Expected at least " + expectedCount + " matching logs");
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

  private record WatcherCounts(int active, int healthy, int unhealthy) {}
}
