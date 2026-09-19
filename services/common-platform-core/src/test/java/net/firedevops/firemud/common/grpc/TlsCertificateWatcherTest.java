package net.firedevops.firemud.common.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.CommonCoreAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
  void successfulInlineReregistrationRequestsReloadWithoutFileEvents(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    TlsCertificateWatcher watcher = new TlsCertificateWatcher(List.of(certificate), () -> {});
    Field keysField = TlsCertificateWatcher.class.getDeclaredField("keys");
    Method processKeyMethod =
        TlsCertificateWatcher.class.getDeclaredMethod("processKey", WatchKey.class);
    keysField.setAccessible(true);
    processKeyMethod.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<WatchKey, Path> keys = (Map<WatchKey, Path>) keysField.get(watcher);
    WatchKey originalKey = keys.keySet().iterator().next();

    try {
      originalKey.cancel();
      assertTrue((Boolean) processKeyMethod.invoke(watcher, originalKey));
      assertEquals(1, keys.size());
      assertTrue(keys.containsValue(directory.toAbsolutePath().normalize()));
    } finally {
      watcher.close();
    }
  }

  @Test
  void successfulInlineReregistrationCancelsObsoleteRegistrationRetry(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    TlsCertificateWatcher watcher = new TlsCertificateWatcher(List.of(certificate), () -> {});
    Field keysField = TlsCertificateWatcher.class.getDeclaredField("keys");
    Field registrationRetryTaskField =
        TlsCertificateWatcher.class.getDeclaredField("registrationRetryTask");
    Field registrationRetryScheduledField =
        TlsCertificateWatcher.class.getDeclaredField("registrationRetryScheduled");
    Field registrationRetryAttemptsField =
        TlsCertificateWatcher.class.getDeclaredField("registrationRetryAttempts");
    Method processKeyMethod =
        TlsCertificateWatcher.class.getDeclaredMethod("processKey", WatchKey.class);
    keysField.setAccessible(true);
    registrationRetryTaskField.setAccessible(true);
    registrationRetryScheduledField.setAccessible(true);
    registrationRetryAttemptsField.setAccessible(true);
    processKeyMethod.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<WatchKey, Path> keys = (Map<WatchKey, Path>) keysField.get(watcher);
    WatchKey originalKey = keys.keySet().iterator().next();
    ScheduledFuture<?> staleRetry = retryExecutor(watcher).schedule(() -> {}, 1, TimeUnit.DAYS);
    registrationRetryTaskField.set(watcher, staleRetry);
    registrationRetryScheduledField.setBoolean(watcher, true);
    registrationRetryAttemptsField.setInt(watcher, 3);

    try {
      originalKey.cancel();
      assertTrue((Boolean) processKeyMethod.invoke(watcher, originalKey));
      assertEquals(1, keys.size());
      assertTrue(keys.containsValue(directory.toAbsolutePath().normalize()));
      assertTrue(staleRetry.isCancelled());
      assertNull(registrationRetryTaskField.get(watcher));
      assertFalse(registrationRetryScheduledField.getBoolean(watcher));
      assertEquals(0, registrationRetryAttemptsField.getInt(watcher));
    } finally {
      watcher.close();
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
    AtomicReference<CountDownLatch> recoveredCallback = new AtomicReference<>();

    Logger logger = (Logger) LoggerFactory.getLogger(TlsCertificateWatcher.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    logger.addAppender(appender);
    try {
      try (TlsCertificateWatcher watcher =
          TlsCertificateWatcher.createAndStart(
              List.of(replacedCertificate, retainedCertificate),
              () -> {
                CountDownLatch callback = recoveredCallback.get();
                if (callback != null) {
                  callback.countDown();
                }
              })) {
        Files.delete(replacedCertificate);
        Files.delete(replacedDirectory);
        awaitUnhealthy(watcher);
        awaitLogCount(
            appender, Level.ERROR, "TLS certificate watcher failed its re-registration retry", 3);
        assertFalse(watcher.isHealthy());

        CountDownLatch postRecoveryCallback = new CountDownLatch(1);
        recoveredCallback.set(postRecoveryCallback);
        Files.createDirectory(replacedDirectory);
        Files.writeString(replacedCertificate, "certificate-2");

        assertTrue(postRecoveryCallback.await(5, TimeUnit.SECONDS));
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

    Logger logger = (Logger) LoggerFactory.getLogger(TlsCertificateWatcher.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    logger.addAppender(appender);

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(missingCertificate, retainedCertificate), () -> {});
    try {
      Files.delete(missingCertificate);
      Files.delete(missingDirectory);
      awaitUnhealthy(watcher);
      awaitLogCount(
          appender, Level.ERROR, "TLS certificate watcher failed its re-registration retry", 3);
      assertFalse(watcher.isHealthy());
      assertHealthDelta(baseline, 1, 0, 1, TlsCertificateWatcher.health());
    } finally {
      watcher.close();
      logger.detachAppender(appender);
      appender.stop();
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
  void closeFromReloadCallbackDoesNotInterruptWorker(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    CountDownLatch callbackClosed = new CountDownLatch(1);
    AtomicBoolean callbackWasInterrupted = new AtomicBoolean();
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    AtomicReference<TlsCertificateWatcher> watcherReference = new AtomicReference<>();

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate),
            () -> {
              try {
                watcherReference.get().close();
                callbackWasInterrupted.set(Thread.currentThread().isInterrupted());
              } catch (Throwable failure) {
                closeFailure.set(failure);
              } finally {
                callbackClosed.countDown();
              }
            });
    watcherReference.set(watcher);
    try {
      Files.writeString(certificate, "certificate-2");
      assertTrue(callbackClosed.await(5, TimeUnit.SECONDS));
      assertFalse(watcher.isRunning());
      assertFalse(callbackWasInterrupted.get());
      assertNull(closeFailure.get());
    } finally {
      watcher.close();
    }
  }

  @Test
  void closeFromReloadRetryDoesNotAwaitOrInterruptRetryThread(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    CountDownLatch firstAttempt = new CountDownLatch(1);
    CountDownLatch retryClosed = new CountDownLatch(1);
    AtomicBoolean retryWasInterrupted = new AtomicBoolean();
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    AtomicReference<TlsCertificateWatcher> watcherReference = new AtomicReference<>();

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate),
            () -> {
              if (firstAttempt.getCount() > 0) {
                firstAttempt.countDown();
                throw new IllegalStateException("simulated reload failure");
              }
              try {
                watcherReference.get().close();
                retryWasInterrupted.set(Thread.currentThread().isInterrupted());
              } catch (Throwable failure) {
                closeFailure.set(failure);
              } finally {
                retryClosed.countDown();
              }
            });
    watcherReference.set(watcher);
    try {
      Files.writeString(certificate, "certificate-2");
      assertTrue(firstAttempt.await(5, TimeUnit.SECONDS));
      assertTrue(retryClosed.await(5, TimeUnit.SECONDS));
      assertFalse(retryWasInterrupted.get());
      assertNull(closeFailure.get());
      assertFalse(watcher.isRunning());
    } finally {
      watcher.close();
    }
  }

  @Test
  void failedCallbackRetryIsCancelledWhenWatcherCloses(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch firstAttempt = new CountDownLatch(1);
    CountDownLatch releaseFailedCallback = new CountDownLatch(1);

    TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate),
            () -> {
              attempts.incrementAndGet();
              firstAttempt.countDown();
              try {
                releaseFailedCallback.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              throw new IllegalStateException("simulated reload failure");
            });
    try {
      Files.writeString(certificate, "certificate-2");
      assertTrue(firstAttempt.await(5, TimeUnit.SECONDS));
      releaseFailedCallback.countDown();
      ScheduledFuture<?> retryTask = awaitScheduledCallbackRetry(watcher);
      ScheduledExecutorService retryExecutor = retryExecutor(watcher);
      watcher.close();
      assertTrue(retryTask.isCancelled());
      assertTrue(retryExecutor.isTerminated());
      assertEquals(1, attempts.get());
    } finally {
      releaseFailedCallback.countDown();
      watcher.close();
    }
  }

  @Test
  @Timeout(15)
  void failedCallbackRetriesContinueAfterTheAttemptCap(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    AtomicInteger attempts = new AtomicInteger();
    Logger logger = (Logger) LoggerFactory.getLogger(TlsCertificateWatcher.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    logger.addAppender(appender);

    try {
      try (TlsCertificateWatcher watcher =
          TlsCertificateWatcher.createAndStart(
              List.of(certificate),
              () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("simulated reload failure");
              })) {
        invokeReloadCallback(watcher, false);
        scheduleCallbackRetry(watcher);
        for (int retry = 0; retry < 12; retry++) {
          runScheduledCallbackRetry(watcher);
        }
        assertEquals(13, attempts.get());
        assertFalse(watcher.isHealthy());
        assertEquals(
            1,
            appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("callback retries exhausted"))
                .count());
        assertTrue(scheduledCallbackRetry(watcher) != null);
        retryExecutor(watcher).shutdownNow();
      }
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void callbackRetryDoesNotBlockBehindActiveReloadCallback(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch failedCallback = new CountDownLatch(1);
    CountDownLatch releaseFailedCallback = new CountDownLatch(1);
    CountDownLatch blockingCallbackEntered = new CountDownLatch(1);
    CountDownLatch releaseBlockingCallback = new CountDownLatch(1);

    try (TlsCertificateWatcher watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(certificate),
            () -> {
              if (attempts.incrementAndGet() == 1) {
                failedCallback.countDown();
                try {
                  releaseFailedCallback.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("simulated reload failure");
              }
              blockingCallbackEntered.countDown();
              try {
                releaseBlockingCallback.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            })) {
      Files.writeString(certificate, "certificate-2");
      assertTrue(failedCallback.await(5, TimeUnit.SECONDS));
      releaseFailedCallback.countDown();
      ScheduledFuture<?> retryTask = awaitScheduledCallbackRetry(watcher);
      assertTrue(retryTask.cancel(false));
      Files.writeString(certificate, "certificate-3");
      assertTrue(blockingCallbackEntered.await(5, TimeUnit.SECONDS));

      Future<?> retry = submitRetryCallback(watcher);
      try {
        retry.get(500, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        throw new AssertionError("callback retry blocked behind an active reload callback", e);
      } finally {
        releaseBlockingCallback.countDown();
      }
      retry.get(5, TimeUnit.SECONDS);
      assertEquals(2, attempts.get());
    } finally {
      releaseFailedCallback.countDown();
    }
  }

  @Test
  void registrationRecoveryWaitsForActiveReloadCallback(@TempDir Path directory) throws Exception {
    Path watchedDirectory = Files.createDirectory(directory.resolve("watched"));
    Path certificate = Files.writeString(watchedDirectory.resolve("tls.crt"), "certificate-1");
    CountDownLatch callbackInvoked = new CountDownLatch(1);

    TlsCertificateWatcher watcher =
        new TlsCertificateWatcher(List.of(certificate), callbackInvoked::countDown);
    Field keysField = TlsCertificateWatcher.class.getDeclaredField("keys");
    Field callbackMonitorField = TlsCertificateWatcher.class.getDeclaredField("callbackMonitor");
    Method retryMethod = TlsCertificateWatcher.class.getDeclaredMethod("retryMissingRegistrations");
    keysField.setAccessible(true);
    callbackMonitorField.setAccessible(true);
    retryMethod.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<Object, Path> keys = (Map<Object, Path>) keysField.get(watcher);
    Object watchKey = keys.entrySet().iterator().next().getKey();
    keys.remove(watchKey);

    java.util.concurrent.locks.ReentrantLock callbackMonitor =
        (java.util.concurrent.locks.ReentrantLock) callbackMonitorField.get(watcher);
    CountDownLatch lockAcquired = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    CountDownLatch recoveryStarted = new CountDownLatch(1);
    Thread lockHolder =
        new Thread(
            () -> {
              callbackMonitor.lock();
              try {
                lockAcquired.countDown();
                try {
                  releaseLock.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              } finally {
                callbackMonitor.unlock();
              }
            });
    lockHolder.start();
    try {
      assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));
      AtomicBoolean recoveryFinished = new AtomicBoolean();
      Thread recovery =
          new Thread(
              () -> {
                try {
                  recoveryStarted.countDown();
                  retryMethod.invoke(watcher);
                  recoveryFinished.set(true);
                } catch (ReflectiveOperationException e) {
                  throw new AssertionError("failed to invoke registration recovery", e);
                }
              });
      recovery.start();
      assertTrue(recoveryStarted.await(5, TimeUnit.SECONDS));
      assertFalse(callbackInvoked.await(100, TimeUnit.MILLISECONDS));
      assertFalse(recoveryFinished.get());
      releaseLock.countDown();
      assertTrue(callbackInvoked.await(5, TimeUnit.SECONDS));
      recovery.join(5_000);
      assertTrue(recoveryFinished.get());
    } finally {
      releaseLock.countDown();
      lockHolder.join(5_000);
      watcher.close();
    }
  }

  @Test
  @Timeout(15)
  void lockWaiterIsNotTrackedAndCannotReloadAfterClose(@TempDir Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    AtomicBoolean callbackInvoked = new AtomicBoolean();
    TlsCertificateWatcher watcher =
        new TlsCertificateWatcher(List.of(certificate), () -> callbackInvoked.set(true));
    Field callbackMonitorField = TlsCertificateWatcher.class.getDeclaredField("callbackMonitor");
    Field activeCallbacksField = TlsCertificateWatcher.class.getDeclaredField("activeCallbacks");
    callbackMonitorField.setAccessible(true);
    activeCallbacksField.setAccessible(true);
    java.util.concurrent.locks.ReentrantLock callbackMonitor =
        (java.util.concurrent.locks.ReentrantLock) callbackMonitorField.get(watcher);
    @SuppressWarnings("unchecked")
    Set<Thread> activeCallbacks = (Set<Thread>) activeCallbacksField.get(watcher);
    AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
    callbackMonitor.lock();
    Thread waiter =
        new Thread(
            () -> {
              try {
                invokeReloadCallback(watcher, false);
              } catch (Throwable failure) {
                waiterFailure.set(failure);
              }
            });
    waiter.start();
    try {
      awaitLockWaiter(waiter);
      assertTrue(activeCallbacks.isEmpty());
      watcher.close();
    } finally {
      callbackMonitor.unlock();
      waiter.join(5_000);
      watcher.close();
    }
    assertFalse(waiter.isAlive());
    assertNull(waiterFailure.get());
    assertFalse(callbackInvoked.get());
  }

  @Test
  @Timeout(15)
  void closePreventsCallbackRegistrationWaitingOnCallbackState(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    AtomicBoolean callbackInvoked = new AtomicBoolean();
    AtomicBoolean callbackStartedAfterClose = new AtomicBoolean();
    AtomicBoolean closeStarted = new AtomicBoolean();
    AtomicReference<Throwable> invocationFailure = new AtomicReference<>();
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    TlsCertificateWatcher watcher =
        new TlsCertificateWatcher(
            List.of(certificate),
            () -> {
              callbackInvoked.set(true);
              if (closeStarted.get()) {
                callbackStartedAfterClose.set(true);
              }
            });
    Field callbackStateMonitorField =
        TlsCertificateWatcher.class.getDeclaredField("callbackStateMonitor");
    callbackStateMonitorField.setAccessible(true);
    Object callbackStateMonitor = callbackStateMonitorField.get(watcher);
    Thread invocation =
        new Thread(
            () -> {
              try {
                invokeReloadCallback(watcher, false);
              } catch (Throwable failure) {
                invocationFailure.set(failure);
              }
            });
    CountDownLatch closeFinished = new CountDownLatch(1);
    Thread closeThread =
        new Thread(
            () -> {
              closeStarted.set(true);
              try {
                watcher.close();
              } catch (Throwable failure) {
                closeFailure.set(failure);
              } finally {
                closeFinished.countDown();
              }
            });

    synchronized (callbackStateMonitor) {
      invocation.start();
      awaitLockWaiter(invocation);
      closeThread.start();
      awaitStopped(watcher);
    }
    try {
      assertTrue(closeStarted.get());
      assertTrue(closeFinished.await(5, TimeUnit.SECONDS));
      invocation.join(5_000);
      assertFalse(invocation.isAlive());
      assertFalse(callbackInvoked.get());
      assertFalse(callbackStartedAfterClose.get());
      assertNull(invocationFailure.get());
      assertNull(closeFailure.get());
    } finally {
      watcher.close();
      closeThread.join(5_000);
      invocation.join(5_000);
    }
  }

  private static Future<?> submitRetryCallback(TlsCertificateWatcher watcher) throws Exception {
    ScheduledExecutorService retryExecutor = retryExecutor(watcher);
    Method retryMethod = TlsCertificateWatcher.class.getDeclaredMethod("retryReloadCallback");
    retryMethod.setAccessible(true);
    return retryExecutor.submit(
        () -> {
          try {
            retryMethod.invoke(watcher);
          } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to invoke callback retry", e);
          }
        });
  }

  private static ScheduledExecutorService retryExecutor(TlsCertificateWatcher watcher)
      throws Exception {
    Field executorField = TlsCertificateWatcher.class.getDeclaredField("retryExecutor");
    executorField.setAccessible(true);
    return (ScheduledExecutorService) executorField.get(watcher);
  }

  private static ScheduledFuture<?> scheduledCallbackRetry(TlsCertificateWatcher watcher)
      throws Exception {
    Field retryTaskField = TlsCertificateWatcher.class.getDeclaredField("retryTask");
    retryTaskField.setAccessible(true);
    return (ScheduledFuture<?>) retryTaskField.get(watcher);
  }

  private static ScheduledFuture<?> awaitScheduledCallbackRetry(TlsCertificateWatcher watcher)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    ScheduledFuture<?> retryTask;
    do {
      retryTask = scheduledCallbackRetry(watcher);
      if (retryTask != null) {
        assertTrue(!retryTask.isDone());
        return retryTask;
      }
      Thread.onSpinWait();
    } while (System.nanoTime() < deadline);
    throw new AssertionError("callback retry was not scheduled");
  }

  private static void invokeReloadCallback(
      TlsCertificateWatcher watcher, boolean retryOnlyWhenUnhealthy) throws Exception {
    Method callbackMethod =
        TlsCertificateWatcher.class.getDeclaredMethod("invokeReloadCallback", boolean.class);
    callbackMethod.setAccessible(true);
    callbackMethod.invoke(watcher, retryOnlyWhenUnhealthy);
  }

  private static void scheduleCallbackRetry(TlsCertificateWatcher watcher) throws Exception {
    Method scheduleMethod = TlsCertificateWatcher.class.getDeclaredMethod("scheduleCallbackRetry");
    scheduleMethod.setAccessible(true);
    scheduleMethod.invoke(watcher);
  }

  private static void runScheduledCallbackRetry(TlsCertificateWatcher watcher) throws Exception {
    ScheduledFuture<?> retryTask = awaitScheduledCallbackRetry(watcher);
    assertTrue(retryTask.cancel(false));
    Method retryMethod = TlsCertificateWatcher.class.getDeclaredMethod("retryReloadCallback");
    retryMethod.setAccessible(true);
    retryMethod.invoke(watcher);
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

  private static void awaitLockWaiter(Thread waiter) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (waiter.getState() == Thread.State.NEW || waiter.getState() == Thread.State.RUNNABLE) {
      if (System.nanoTime() >= deadline) {
        break;
      }
      Thread.onSpinWait();
    }
    assertTrue(
        waiter.getState() == Thread.State.WAITING || waiter.getState() == Thread.State.BLOCKED,
        () -> "expected lock waiter, but was " + waiter.getState());
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
