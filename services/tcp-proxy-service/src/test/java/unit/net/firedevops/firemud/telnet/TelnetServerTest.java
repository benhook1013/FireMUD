package net.firedevops.firemud.telnet;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    server = new TelnetServer(0, "ws://localhost/ws");
    server.start();
    server.stop();
    assertTrue(true); // no exception means success
  }
}
