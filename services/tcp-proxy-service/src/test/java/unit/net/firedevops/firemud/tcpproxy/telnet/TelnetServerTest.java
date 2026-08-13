package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.firedevops.firemud.tcpproxy.health.GatewayGameplayReadinessProbe;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

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
            "",
            "",
            false,
            0,
            0,
            4096,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Mockito.mock(TcpProxyEventService.class),
            readyProbe());
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
                    cert,
                    key,
                    false,
                    0,
                    0,
                    4096,
                    registry,
                    Mockito.mock(TcpProxyEventService.class),
                    readyProbe()));

    assertTrue(ex.getMessage().contains("TLS"));
    assertEquals(1.0, registry.counter("tcpproxy.tls.misconfig").count());
  }

  private GatewayGameplayReadinessProbe readyProbe() {
    GatewayGameplayReadinessProbe probe = Mockito.mock(GatewayGameplayReadinessProbe.class);
    Mockito.when(probe.isReady()).thenReturn(true);
    return probe;
  }
}
