package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionIdParsingTest {

  @Test
  void parseAcceptsPositiveSessionId() {
    SessionIdParsing.ParsedSessionId parsed = SessionIdParsing.parse("41");

    assertTrue(parsed.valid());
    assertEquals(41L, parsed.value());
    assertEquals(null, parsed.errorMessage());
  }

  @Test
  void parseRejectsMalformedSessionIdAsNumericError() {
    SessionIdParsing.ParsedSessionId parsed = SessionIdParsing.parse("session-1");

    assertFalse(parsed.valid());
    assertEquals("sessionId must be numeric", parsed.errorMessage());
  }

  @Test
  void parseRejectsNonPositiveSessionIdAsPositiveError() {
    SessionIdParsing.ParsedSessionId zero = SessionIdParsing.parse("0");
    SessionIdParsing.ParsedSessionId negative = SessionIdParsing.parse("-1");

    assertFalse(zero.valid());
    assertEquals("sessionId must be positive", zero.errorMessage());
    assertFalse(negative.valid());
    assertEquals("sessionId must be positive", negative.errorMessage());
  }

  @Test
  void requireRejectsMalformedAndNonPositiveSessionIdsWithCanonicalMessages() {
    IllegalArgumentException malformed =
        assertThrows(IllegalArgumentException.class, () -> SessionIdParsing.require("session-1"));
    assertEquals("sessionId must be numeric", malformed.getMessage());

    IllegalArgumentException zero =
        assertThrows(IllegalArgumentException.class, () -> SessionIdParsing.require("0"));
    assertEquals("sessionId must be positive", zero.getMessage());
  }
}
