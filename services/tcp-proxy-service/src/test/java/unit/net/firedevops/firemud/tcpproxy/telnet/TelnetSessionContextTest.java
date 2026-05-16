package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TelnetSessionContextTest {
  private final TelnetSessionContext sessionContext = new TelnetSessionContext();

  @Test
  void bootstrap_setsIdsAndMarksContextReady() {
    sessionContext.bootstrap("sess-1", "tenant-alpha", "demo", "production", "17");

    assertEquals("sess-1", sessionContext.gameInstanceId());
    assertEquals("tenant-alpha", sessionContext.tenantId());
    assertEquals("demo", sessionContext.worldSlug());
    assertEquals("production", sessionContext.realmSlug());
    assertEquals("17", sessionContext.pointerVersion());
    assertTrue(sessionContext.isReady());
  }

  @Test
  void bootstrap_ignoresMissingValues() {
    sessionContext.bootstrap("", "tenant-beta", "demo", "production", "17");

    assertFalse(sessionContext.isReady());
    assertEquals(null, sessionContext.gameInstanceId());
    assertEquals(null, sessionContext.tenantId());
    assertEquals(null, sessionContext.worldSlug());
    assertEquals(null, sessionContext.realmSlug());
    assertEquals(null, sessionContext.pointerVersion());
  }

  @Test
  void bootstrap_dropsPartialRoutingBundle() {
    sessionContext.bootstrap("sess-2", "tenant-beta", "demo", "production", "");

    assertTrue(sessionContext.isReady());
    assertEquals("sess-2", sessionContext.gameInstanceId());
    assertEquals("tenant-beta", sessionContext.tenantId());
    assertEquals(null, sessionContext.worldSlug());
    assertEquals(null, sessionContext.realmSlug());
    assertEquals(null, sessionContext.pointerVersion());
  }
}
