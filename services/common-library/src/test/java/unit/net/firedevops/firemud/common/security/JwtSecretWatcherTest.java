package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JwtSecretWatcherTest {
  @Test
  void triggersCallbackOnFileChange() throws Exception {
    Path temp = Files.createTempFile("jwt-secret", ".txt");
    Files.writeString(temp, "one");
    CountDownLatch latch = new CountDownLatch(1);
    JwtSecretWatcher watcher = JwtSecretWatcher.createAndStart(temp, latch::countDown);
    try {
      Files.writeString(temp, "two");
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } finally {
      watcher.close();
      Files.deleteIfExists(temp);
    }
  }
}
