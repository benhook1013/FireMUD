package net.firedevops.firemud.common.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsCertificateWatcherTest {
  @Test
  void directFileChangeBurstTriggersOneReload(@TempDir Path directory) throws Exception {
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

      assertTrue(reloaded.await(5, TimeUnit.SECONDS));
      Thread.sleep(300);
      assertEquals(1, reloads.get());
    }
  }

  @Test
  void projectedDataSwapAndOverflowBothRequestReload(@TempDir Path directory) {
    Set<Path> files = Set.of(directory.resolve("tls.crt").toAbsolutePath());

    assertTrue(
        TlsCertificateWatcher.isReloadEvent(
            files, directory, pathEvent(StandardWatchEventKinds.ENTRY_CREATE, Path.of("..data"))));
    assertTrue(TlsCertificateWatcher.isReloadEvent(files, directory, overflowEvent()));
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
