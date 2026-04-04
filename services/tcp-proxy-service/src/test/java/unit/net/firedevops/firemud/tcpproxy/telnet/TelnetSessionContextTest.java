package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TelnetSessionContextTest {
  private final TelnetSessionContext sessionContext = new TelnetSessionContext();

  @Test
  void bootstrap_setsIdsAndMarksContextReady() {
    sessionContext.bootstrap("sess-1", "tenant-alpha");

    assertEquals("sess-1", sessionContext.gameInstanceId());
    assertEquals("tenant-alpha", sessionContext.tenantId());
    assertTrue(sessionContext.isReady());
  }

  @Test
  void bootstrap_ignoresMissingValues() {
    sessionContext.bootstrap("", "tenant-beta");

    assertFalse(sessionContext.isReady());
    assertEquals(null, sessionContext.gameInstanceId());
    assertEquals(null, sessionContext.tenantId());
  }
}
