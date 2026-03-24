package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TelnetSessionContextTest {

  private TelnetSessionContext sessionContext;
  private Logger logger;
  private ListAppender<ILoggingEvent> listAppender;

  @BeforeEach
  void setUpLogger() {
    sessionContext = new TelnetSessionContext();
    logger = (Logger) LoggerFactory.getLogger(TelnetSessionContext.class);
    listAppender = new ListAppender<>();
    listAppender.start();
    logger.addAppender(listAppender);
  }

  @AfterEach
  void tearDownLogger() {
    logger.detachAppender(listAppender);
    listAppender.stop();
  }

  @Test
  void captureFromEnvelope_spaceSeparatedSetsIdsAndLogsSuccess() {
    boolean captured = sessionContext.captureFromEnvelope("SESSION sess-1 tenant-alpha");

    assertTrue(captured);
    assertEquals("sess-1", sessionContext.gameInstanceId());
    assertEquals("tenant-alpha", sessionContext.tenantId());
    assertTrue(sessionContext.isReady());

    List<ILoggingEvent> events = listAppender.list;
    assertEquals(1, events.size());
    ILoggingEvent event = events.get(0);
    assertEquals(Level.INFO, event.getLevel());
    assertEquals(
        "Captured Telnet gameInstance sess-1 for tenant tenant-alpha", event.getFormattedMessage());
  }

  @Test
  void captureFromEnvelope_colonSeparatedLogsWarningAndResets() {
    boolean captured = sessionContext.captureFromEnvelope("SESSION colon-sess:tenant-beta");

    assertFalse(captured);
    assertNull(sessionContext.gameInstanceId());
    assertNull(sessionContext.tenantId());
    assertFalse(sessionContext.isReady());

    List<ILoggingEvent> events = listAppender.list;
    assertEquals(1, events.size());
    ILoggingEvent event = events.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertEquals(
        "Ignoring malformed session envelope: SESSION colon-sess:tenant-beta",
        event.getFormattedMessage());
  }

  @Test
  void captureFromEnvelope_malformedSpaceSeparatedLogsWarningAndResets() {
    boolean captured = sessionContext.captureFromEnvelope("SESSION only-session");

    assertFalse(captured);
    assertNull(sessionContext.gameInstanceId());
    assertNull(sessionContext.tenantId());
    assertFalse(sessionContext.isReady());

    List<ILoggingEvent> events = listAppender.list;
    assertEquals(1, events.size());
    ILoggingEvent event = events.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertEquals(
        "Ignoring malformed session envelope: SESSION only-session", event.getFormattedMessage());
  }

  @Test
  void captureFromEnvelope_missingGameInstanceLogsWarningAndResets() {
    boolean captured = sessionContext.captureFromEnvelope("SESSION :tenant");

    assertFalse(captured);
    assertNull(sessionContext.gameInstanceId());
    assertNull(sessionContext.tenantId());
    assertFalse(sessionContext.isReady());

    List<ILoggingEvent> events = listAppender.list;
    assertEquals(1, events.size());
    ILoggingEvent event = events.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertEquals(
        "Ignoring malformed session envelope: SESSION :tenant", event.getFormattedMessage());
  }
}
