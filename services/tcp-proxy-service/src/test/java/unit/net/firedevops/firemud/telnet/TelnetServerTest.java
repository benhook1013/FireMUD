package net.firedevops.firemud.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import net.firedevops.firemud.service.TcpProxyEventService;

class TelnetServerTest {
  private TelnetServer server;

  @AfterEach
  void cleanup() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void serverStartsAndStops() throws Exception {
    server =
        new TelnetServer(
            0,
            "ws://localhost/ws",
            false,
            false,
            "",
            "",
            false,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Mockito.mock(TcpProxyEventService.class));
    server.start();
    server.stop();
    assertTrue(true); // no exception means success
  }

  @Test
  void tlsMisconfigurationFailsFastAndIncrementsMetric(@TempDir Path tempDir) {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    String cert = tempDir.resolve("missing-cert.pem").toString();
    String key = tempDir.resolve("missing-key.pem").toString();

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                new TelnetServer(
                    0,
                    "ws://localhost/ws",
                    true,
                    false,
                    cert,
                    key,
                    false,
                    registry,
                    Mockito.mock(TcpProxyEventService.class)));

    assertTrue(ex.getMessage().contains("TLS"));
    assertEquals(1.0, registry.counter("tcpproxy.tls.misconfig").count());
  }
}
