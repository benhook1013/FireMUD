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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private static final Path PROJECTED_DATA_LINK = Path.of("..data");
  private static final Set<TlsCertificateWatcher> ACTIVE_WATCHERS = ConcurrentHashMap.newKeySet();

  private final WatchService watchService;
  private final Map<WatchKey, Path> keys = new HashMap<>();
  private final Set<Path> files;
  private final Runnable onChange;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicBoolean allRequiredRegistrationsValid = new AtomicBoolean(true);
  private final AtomicBoolean started = new AtomicBoolean();
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
    this.onChange = onChange;
    this.watchService = FileSystems.getDefault().newWatchService();
    for (Path file : this.files) {
      Path dir = file.getParent();
      if (dir == null) {
        throw new IOException("File path has no parent: " + file);
      }
      WatchKey key =
          dir.register(
              watchService,
              StandardWatchEventKinds.ENTRY_MODIFY,
              StandardWatchEventKinds.ENTRY_CREATE,
              StandardWatchEventKinds.ENTRY_DELETE);
      keys.put(key, dir);
    }
    thread = new Thread(this::processEvents, "tls-cert-watcher");
    thread.setDaemon(true);
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
      throw e;
    }
  }

  private void processEvents() {
    while (running.get()) {
      if (keys.isEmpty()) {
        running.set(false);
        logger.error(
            "TLS certificate watcher lost all registered directories; stopping credential reloads");
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
        try {
          onChange.run();
        } catch (RuntimeException e) {
          logger.error(
              "TLS certificate reload callback failed; continuing to watch credentials", e);
        }
      }
    }
  }

  private boolean processKey(WatchKey key) {
    Path dir = keys.get(key);
    boolean changed = false;
    if (dir != null) {
      for (WatchEvent<?> event : key.pollEvents()) {
        changed |= isReloadEvent(files, dir, event);
      }
    }
    if (!key.reset()) {
      keys.remove(key);
      allRequiredRegistrationsValid.set(false);
    }
    return changed;
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

  public boolean hasAllRequiredRegistrations() {
    return running.get() && allRequiredRegistrationsValid.get();
  }

  /** Returns the aggregate health of all successfully started certificate watchers. */
  public static Health health() {
    List<TlsCertificateWatcher> activeWatcherSnapshot = List.copyOf(ACTIVE_WATCHERS);
    int activeWatchers = activeWatcherSnapshot.size();
    int healthyWatchers =
        (int)
            activeWatcherSnapshot.stream()
                .filter(TlsCertificateWatcher::hasAllRequiredRegistrations)
                .count();
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

  @Override
  public void close() throws IOException {
    running.set(false);
    try {
      watchService.close();
    } finally {
      ACTIVE_WATCHERS.remove(this);
      if (thread.isAlive()) {
        thread.interrupt();
      }
    }
  }
}
