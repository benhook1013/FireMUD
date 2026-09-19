package net.firedevops.firemud.common.grpc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.boot.health.contributor.Health;

/**
 * Watches a set of certificate files for modifications and invokes a callback when any of them
 * changes. This is used to hot reload gRPC TLS credentials when cert-manager rotates Kubernetes
 * secrets.
 */
public class TlsCertificateWatcher implements AutoCloseable {
  private static final Logger logger = LoggingUtil.getLogger(TlsCertificateWatcher.class);
  private static final Duration RELOAD_DEBOUNCE = Duration.ofMillis(100);
  private static final Duration MAX_RELOAD_DELAY = Duration.ofSeconds(1);
  private static final Duration INITIAL_REGISTRATION_RETRY_DELAY = Duration.ofMillis(100);
  private static final Duration MAX_REGISTRATION_RETRY_DELAY = Duration.ofSeconds(30);
  private static final int MAX_RETRY_ATTEMPTS = 10;
  private static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(5);
  private static final Duration SHUTDOWN_FORCE_PERIOD = Duration.ofMillis(100);
  private static final Path PROJECTED_DATA_LINK = Path.of("..data");
  private static final Set<TlsCertificateWatcher> ACTIVE_WATCHERS = ConcurrentHashMap.newKeySet();

  private final WatchService watchService;
  private final Map<WatchKey, Path> keys = new HashMap<>();
  private final Set<Path> requiredDirectories;
  private final Set<Path> files;
  private final Runnable onChange;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicBoolean allRequiredRegistrationsValid = new AtomicBoolean(true);
  private final AtomicBoolean reloadCallbackHealthy = new AtomicBoolean(true);
  private final AtomicBoolean started = new AtomicBoolean();
  private final ScheduledExecutorService retryExecutor;
  private final AtomicReference<Thread> retryExecutorThread = new AtomicReference<>();
  private final ReentrantLock callbackMonitor = new ReentrantLock();
  private final Object callbackStateMonitor = new Object();
  private final Object retryMonitor = new Object();
  private final Object registrationMonitor = new Object();
  private final Set<Thread> activeCallbacks = ConcurrentHashMap.newKeySet();
  private ScheduledFuture<?> retryTask;
  private boolean retryScheduled;
  private int callbackRetryAttempts;
  private boolean callbackRetryExhaustionLogged;
  private ScheduledFuture<?> registrationRetryTask;
  private boolean registrationRetryScheduled;
  private int registrationRetryAttempts;
  private final Thread thread;

  public static TlsCertificateWatcher createAndStart(List<Path> files, Runnable onChange)
      throws IOException {
    TlsCertificateWatcher watcher = new TlsCertificateWatcher(files, onChange);
    watcher.start();
    return watcher;
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Thread started only via start() after constructor")
  public TlsCertificateWatcher(List<Path> files, Runnable onChange) throws IOException {
    this.files = Set.copyOf(files.stream().map(path -> path.toAbsolutePath().normalize()).toList());
    this.requiredDirectories = requiredDirectories(this.files);
    this.onChange = onChange;
    this.watchService = FileSystems.getDefault().newWatchService();
    try {
      for (Path directory : requiredDirectories) {
        keys.put(registerDirectory(directory), directory);
      }
    } catch (IOException | RuntimeException e) {
      try {
        watchService.close();
      } catch (IOException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
    allRequiredRegistrationsValid.set(allRequiredDirectoriesRegistered());
    retryExecutor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread retryThread =
                  Thread.ofPlatform()
                      .daemon(true)
                      .name("tls-cert-reload-retry")
                      .unstarted(runnable);
              retryExecutorThread.set(retryThread);
              return retryThread;
            });
    thread = new Thread(this::processEvents, "tls-cert-watcher");
    thread.setDaemon(true);
  }

  private static Set<Path> requiredDirectories(Set<Path> files) throws IOException {
    Set<Path> directories = new HashSet<>();
    for (Path file : files) {
      Path directory = file.getParent();
      if (directory == null) {
        throw new IOException("File path has no parent: " + file);
      }
      directories.add(directory);
    }
    return Set.copyOf(directories);
  }

  private WatchKey registerDirectory(Path directory) throws IOException {
    return directory.register(
        watchService,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE);
  }

  private boolean allRequiredDirectoriesRegistered() {
    return keys.values().containsAll(requiredDirectories);
  }

  public void start() {
    if (!started.compareAndSet(false, true)) {
      throw new IllegalThreadStateException("TLS certificate watcher has already been started");
    }
    ACTIVE_WATCHERS.add(this);
    try {
      thread.start();
    } catch (RuntimeException e) {
      ACTIVE_WATCHERS.remove(this);
      retryExecutor.shutdownNow();
      running.set(false);
      try {
        watchService.close();
      } catch (IOException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }

  private void processEvents() {
    try {
      while (running.get()) {
        boolean noRegisteredDirectories;
        synchronized (registrationMonitor) {
          noRegisteredDirectories = keys.isEmpty();
        }
        if (noRegisteredDirectories) {
          logger.error(
              "TLS certificate watcher lost all registered directories; stopping credential reloads");
          running.set(false);
          return;
        }

        WatchKey key;
        try {
          key = watchService.take();
        } catch (ClosedWatchServiceException e) {
          return;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        if (processKey(key)) {
          drainReloadBurst();
          if (!running.get()) {
            return;
          }
          logger.info("TLS certificate projection or file change detected; reloading credentials");
          if (invokeReloadCallback(false) == CallbackInvocationResult.FAILED) {
            scheduleCallbackRetry();
          }
        }
      }
    } finally {
      if (running.get()) {
        logger.error("TLS certificate watcher worker exited unexpectedly");
      }
      running.set(false);
    }
  }

  private CallbackInvocationResult invokeReloadCallback(boolean retryOnlyWhenUnhealthy) {
    if (!running.get()) {
      return CallbackInvocationResult.SKIPPED;
    }
    Thread callbackThread = Thread.currentThread();
    if (retryOnlyWhenUnhealthy) {
      if (!callbackMonitor.tryLock()) {
        return CallbackInvocationResult.SKIPPED;
      }
    } else {
      callbackMonitor.lock();
    }
    try {
      if (!running.get()) {
        return CallbackInvocationResult.SKIPPED;
      }
      if (retryOnlyWhenUnhealthy && reloadCallbackHealthy.get()) {
        return CallbackInvocationResult.SKIPPED;
      }
      synchronized (callbackStateMonitor) {
        if (!running.get()) {
          return CallbackInvocationResult.SKIPPED;
        }
        activeCallbacks.add(callbackThread);
      }
      RuntimeException callbackFailure = null;
      try {
        onChange.run();
      } catch (RuntimeException e) {
        callbackFailure = e;
      } finally {
        synchronized (callbackStateMonitor) {
          activeCallbacks.remove(callbackThread);
          callbackStateMonitor.notifyAll();
        }
      }
      if (callbackFailure != null) {
        reloadCallbackHealthy.set(false);
        logger.error(
            "TLS certificate reload callback failed; continuing to watch credentials",
            callbackFailure);
        return CallbackInvocationResult.FAILED;
      }
      reloadCallbackHealthy.set(true);
      cancelScheduledRetry();
      return CallbackInvocationResult.SUCCEEDED;
    } finally {
      callbackMonitor.unlock();
    }
  }

  private void scheduleCallbackRetry() {
    synchronized (retryMonitor) {
      if (!running.get() || retryScheduled) {
        return;
      }
      int retryAttempt = Math.min(MAX_RETRY_ATTEMPTS, callbackRetryAttempts + 1);
      if (retryAttempt >= MAX_RETRY_ATTEMPTS && !callbackRetryExhaustionLogged) {
        callbackRetryExhaustionLogged = true;
        logger.error(
            "TLS certificate reload callback retries exhausted after {} attempts",
            MAX_RETRY_ATTEMPTS);
      }
      Duration retryDelay = registrationRetryDelay(retryAttempt);
      callbackRetryAttempts = retryAttempt;
      retryScheduled = true;
      try {
        retryTask =
            retryExecutor.schedule(
                this::retryReloadCallback, retryDelay.toNanos(), TimeUnit.NANOSECONDS);
      } catch (RuntimeException e) {
        retryScheduled = false;
        logger.error("TLS certificate watcher could not schedule a bounded reload retry", e);
      }
    }
  }

  private void cancelScheduledRetry() {
    synchronized (retryMonitor) {
      retryScheduled = false;
      if (retryTask != null) {
        retryTask.cancel(false);
        retryTask = null;
      }
      callbackRetryAttempts = 0;
      callbackRetryExhaustionLogged = false;
    }
  }

  private void retryReloadCallback() {
    synchronized (retryMonitor) {
      retryTask = null;
      retryScheduled = false;
    }
    if (running.get() && invokeReloadCallback(true) == CallbackInvocationResult.FAILED) {
      scheduleCallbackRetry();
    }
  }

  private boolean processKey(WatchKey key) {
    boolean changed;
    boolean missingRegistration;
    synchronized (registrationMonitor) {
      Path dir = keys.get(key);
      changed = false;
      if (dir != null) {
        for (WatchEvent<?> event : key.pollEvents()) {
          changed |= isReloadEvent(files, dir, event);
        }
      }
      if (!key.reset()) {
        keys.remove(key);
        if (dir != null) {
          try {
            keys.put(registerDirectory(dir), dir);
            // A successful inline re-registration after directory replacement is itself a
            // credential projection change, even when the reset produced no file event.
            changed = true;
          } catch (IOException | RuntimeException e) {
            if (running.get()) {
              logger.error("TLS certificate watcher failed to re-register directory {}", dir, e);
            } else {
              logger.debug(
                  "TLS certificate watcher did not re-register directory {}; shutdown has begun",
                  dir,
                  e);
            }
          }
        }
      }
      allRequiredRegistrationsValid.set(allRequiredDirectoriesRegistered());
      // A delayed retry can keep a watcher alive only when another watch key remains active.
      // With the final key gone, processEvents must stop fail closed instead of reviving it.
      missingRegistration = !allRequiredRegistrationsValid.get() && !keys.isEmpty();
    }
    if (missingRegistration) {
      scheduleRegistrationRetry();
    } else {
      synchronized (retryMonitor) {
        registrationRetryAttempts = 0;
        registrationRetryScheduled = false;
        if (registrationRetryTask != null) {
          registrationRetryTask.cancel(false);
          registrationRetryTask = null;
        }
      }
    }
    return changed;
  }

  private void scheduleRegistrationRetry() {
    synchronized (retryMonitor) {
      if (!running.get() || registrationRetryScheduled) {
        return;
      }
      int retryAttempt = Math.min(MAX_RETRY_ATTEMPTS, registrationRetryAttempts + 1);
      Duration retryDelay = registrationRetryDelay(retryAttempt);
      registrationRetryAttempts = retryAttempt;
      registrationRetryScheduled = true;
      try {
        registrationRetryTask =
            retryExecutor.schedule(
                this::retryMissingRegistrations, retryDelay.toNanos(), TimeUnit.NANOSECONDS);
      } catch (RuntimeException e) {
        registrationRetryScheduled = false;
        logger.error("TLS certificate watcher could not schedule a registration retry", e);
      }
    }
  }

  static Duration registrationRetryDelay(int attempt) {
    long delayNanos = INITIAL_REGISTRATION_RETRY_DELAY.toNanos();
    long maximumNanos = MAX_REGISTRATION_RETRY_DELAY.toNanos();
    for (int i = 1; i < attempt && delayNanos < maximumNanos; i++) {
      delayNanos =
          Math.min(maximumNanos, delayNanos > maximumNanos / 2 ? maximumNanos : delayNanos * 2);
    }
    return Duration.ofNanos(delayNanos);
  }

  private void retryMissingRegistrations() {
    synchronized (retryMonitor) {
      registrationRetryTask = null;
      registrationRetryScheduled = false;
    }
    if (!running.get()) {
      return;
    }
    boolean recovered;
    synchronized (registrationMonitor) {
      if (!running.get()) {
        return;
      }
      for (Path directory : requiredDirectories) {
        if (keys.containsValue(directory)) {
          continue;
        }
        try {
          keys.put(registerDirectory(directory), directory);
        } catch (IOException | RuntimeException e) {
          logger.error(
              "TLS certificate watcher failed its re-registration retry for {}", directory, e);
        }
      }
      allRequiredRegistrationsValid.set(allRequiredDirectoriesRegistered());
      recovered = allRequiredRegistrationsValid.get();
      if (recovered) {
        // Re-registering a lost directory restores observation, but the callback must
        // successfully rebuild credentials before watcher health is restored.
        reloadCallbackHealthy.set(false);
      }
    }
    if (recovered) {
      synchronized (retryMonitor) {
        registrationRetryAttempts = 0;
      }
      // Registration recovery is itself a health transition. Wait for any in-flight callback so
      // this recovery reload cannot be skipped and then masked by a different callback restoring
      // health before the recovered credentials have been rebuilt.
      if (invokeReloadCallback(false) != CallbackInvocationResult.SUCCEEDED) {
        scheduleCallbackRetry();
      }
    } else {
      scheduleRegistrationRetry();
    }
  }

  private enum CallbackInvocationResult {
    SUCCEEDED,
    SKIPPED,
    FAILED
  }

  private void drainReloadBurst() {
    long maximumDeadline = System.nanoTime() + MAX_RELOAD_DELAY.toNanos();
    long quietDeadline = System.nanoTime() + RELOAD_DEBOUNCE.toNanos();
    while (running.get()) {
      long remainingNanos = Math.min(maximumDeadline, quietDeadline) - System.nanoTime();
      if (remainingNanos <= 0) {
        return;
      }
      WatchKey key;
      try {
        key = watchService.poll(remainingNanos, TimeUnit.NANOSECONDS);
      } catch (ClosedWatchServiceException e) {
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (key == null) {
        return;
      }
      if (processKey(key)) {
        quietDeadline = System.nanoTime() + RELOAD_DEBOUNCE.toNanos();
      }
    }
  }

  static boolean isReloadEvent(Set<Path> files, Path dir, WatchEvent<?> event) {
    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
      return true;
    }
    if (!(event.context() instanceof Path relativePath)) {
      return false;
    }
    if (PROJECTED_DATA_LINK.equals(relativePath)) {
      return true;
    }
    return files.contains(dir.resolve(relativePath).toAbsolutePath().normalize());
  }

  public boolean isRunning() {
    return running.get();
  }

  public boolean isHealthy() {
    return running.get() && allRequiredRegistrationsValid.get() && reloadCallbackHealthy.get();
  }

  /** Returns the aggregate health of all successfully started certificate watchers. */
  public static Health health() {
    List<TlsCertificateWatcher> activeWatcherSnapshot = List.copyOf(ACTIVE_WATCHERS);
    int activeWatchers = activeWatcherSnapshot.size();
    int healthyWatchers =
        (int) activeWatcherSnapshot.stream().filter(TlsCertificateWatcher::isHealthy).count();
    int unhealthyWatchers = activeWatchers - healthyWatchers;
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("activeWatchers", activeWatchers);
    details.put("healthyWatchers", healthyWatchers);
    details.put("unhealthyWatchers", unhealthyWatchers);

    if (activeWatchers == 0) {
      return Health.up()
          .withDetail("tlsReload", "disabled_or_not_configured")
          .withDetails(details)
          .build();
    }
    if (unhealthyWatchers == 0) {
      return Health.up().withDetail("tlsReload", "watching").withDetails(details).build();
    }
    return Health.outOfService()
        .withDetail("tlsReload", "watcher_unhealthy")
        .withDetails(details)
        .build();
  }

  private boolean awaitActiveCallbacks(Duration timeout) {
    Thread caller = Thread.currentThread();
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (callbackStateMonitor) {
      while (activeCallbacks.stream().anyMatch(thread -> thread != caller)) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
          return false;
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(callbackStateMonitor, remainingNanos);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return true;
    }
  }

  private void interruptActiveCallbacks() {
    Thread caller = Thread.currentThread();
    activeCallbacks.stream().filter(thread -> thread != caller).forEach(Thread::interrupt);
  }

  private static boolean awaitThreadTermination(Thread thread, Duration timeout) {
    if (thread == Thread.currentThread()) {
      return !thread.isAlive();
    }
    try {
      TimeUnit.NANOSECONDS.timedJoin(thread, timeout.toNanos());
      return !thread.isAlive();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private boolean awaitRetryExecutorTermination(Duration timeout) {
    try {
      return retryExecutor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() throws IOException {
    running.set(false);
    synchronized (retryMonitor) {
      retryScheduled = false;
      registrationRetryScheduled = false;
      registrationRetryAttempts = 0;
      callbackRetryAttempts = 0;
      callbackRetryExhaustionLogged = false;
      if (retryTask != null) {
        retryTask.cancel(false);
        retryTask = null;
      }
      if (registrationRetryTask != null) {
        registrationRetryTask.cancel(false);
        registrationRetryTask = null;
      }
    }
    IOException closeFailure = null;
    try {
      watchService.close();
    } catch (IOException e) {
      closeFailure = e;
    } finally {
      ACTIVE_WATCHERS.remove(this);
    }

    boolean callbacksStopped = awaitActiveCallbacks(SHUTDOWN_GRACE_PERIOD);
    if (!callbacksStopped) {
      interruptActiveCallbacks();
      awaitActiveCallbacks(SHUTDOWN_FORCE_PERIOD);
    }

    boolean threadStopped = awaitThreadTermination(thread, SHUTDOWN_GRACE_PERIOD);
    if (!threadStopped && thread != Thread.currentThread()) {
      thread.interrupt();
      awaitThreadTermination(thread, SHUTDOWN_FORCE_PERIOD);
    }

    retryExecutor.shutdown();
    if (retryExecutorThread.get() != Thread.currentThread()) {
      if (!awaitRetryExecutorTermination(SHUTDOWN_GRACE_PERIOD)) {
        retryExecutor.shutdownNow();
        awaitRetryExecutorTermination(SHUTDOWN_FORCE_PERIOD);
      }
    }

    if (closeFailure != null) {
      throw closeFailure;
    }
  }
}
