package net.firedevops.firemud.common.grpc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;

/**
 * Watches a set of certificate files for modifications and invokes a callback when any of them
 * changes. This is used to hot reload gRPC TLS credentials when cert-manager rotates Kubernetes
 * secrets.
 */
public class TlsCertificateWatcher implements AutoCloseable {
  private static final Logger logger = LoggingUtil.getLogger(TlsCertificateWatcher.class);

  private final WatchService watchService;
  private final Map<WatchKey, Path> keys = new HashMap<>();
  private final Set<Path> files;
  private final Runnable onChange;
  private final AtomicBoolean running = new AtomicBoolean(true);
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
    this.files = Set.copyOf(files.stream().map(Path::toAbsolutePath).toList());
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
              StandardWatchEventKinds.ENTRY_CREATE);
      keys.put(key, dir);
    }
    thread = new Thread(this::processEvents, "tls-cert-watcher");
    thread.setDaemon(true);
  }

  public void start() {
    thread.start();
  }

  private void processEvents() {
    while (running.get()) {
      WatchKey key;
      try {
        key = watchService.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      Path dir = keys.get(key);
      boolean changed = false;
      if (dir != null) {
        for (WatchEvent<?> event : key.pollEvents()) {
          Path changedPath = dir.resolve((Path) event.context()).toAbsolutePath();
          if (files.contains(changedPath)) {
            changed = true;
            break;
          }
        }
      }
      boolean valid = key.reset();
      if (!valid) {
        keys.remove(key);
      }
      if (changed) {
        logger.info("TLS certificate change detected; reloading channel");
        onChange.run();
      }
    }
  }

  @Override
  public void close() throws IOException {
    running.set(false);
    try {
      watchService.close();
    } finally {
      if (thread.isAlive()) {
        thread.interrupt();
      }
    }
  }
}
