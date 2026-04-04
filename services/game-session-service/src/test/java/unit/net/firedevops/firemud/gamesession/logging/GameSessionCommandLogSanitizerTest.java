package net.firedevops.firemud.gamesession.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GameSessionCommandLogSanitizerTest {
  @Test
  void loginCommandsAreRedacted() {
    assertEquals(
        "LOGIN [redacted]", GameSessionCommandLogSanitizer.sanitize("LOGIN demo swordfish"));
    assertEquals(
        "LOGON [redacted]", GameSessionCommandLogSanitizer.sanitize("LOGON demo swordfish"));
  }

  @Test
  void nonSensitiveCommandsPassThrough() {
    assertEquals("look", GameSessionCommandLogSanitizer.sanitize("look"));
  }
}
