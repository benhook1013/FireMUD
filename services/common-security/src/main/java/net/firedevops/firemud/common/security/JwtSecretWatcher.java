package net.firedevops.firemud.common.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;

/** Watches a JWT secret file and triggers a callback when it changes. */
public class JwtSecretWatcher implements AutoCloseable {
  private static final Logger logger = LoggingUtil.getLogger(JwtSecretWatcher.class);

  private final WatchService watchService;
  private final Path file;
  private final Runnable onChange;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread thread;

  public static JwtSecretWatcher createAndStart(Path file, Runnable onChange) throws IOException {
    JwtSecretWatcher watcher = new JwtSecretWatcher(file, onChange);
    watcher.start();
    return watcher;
  }

  @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "Thread started via start()")
  public JwtSecretWatcher(Path file, Runnable onChange) throws IOException {
    this.file = file.toAbsolutePath();
    this.onChange = onChange;
    this.watchService = FileSystems.getDefault().newWatchService();
    Path dir = this.file.getParent();
    if (dir == null) {
      throw new IOException("File path has no parent: " + file);
    }
    dir.register(
        watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
    thread = new Thread(this::processEvents, "jwt-secret-watcher");
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
      } catch (ClosedWatchServiceException e) {
        // Watch service closed while waiting; exit loop
        return;
      }
      boolean changed = false;
      Path dir = (Path) key.watchable();
      for (WatchEvent<?> event : key.pollEvents()) {
        Path changedPath = dir.resolve((Path) event.context()).toAbsolutePath();
        if (changedPath.equals(file)) {
          changed = true;
          break;
        }
      }
      boolean valid = key.reset();
      if (changed) {
        logger.info("JWT secret change detected; reloading");
        onChange.run();
      }
      if (!valid) {
        break;
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
