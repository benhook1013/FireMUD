package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PositiveLongParsingTest {

  @Test
  void parseOptionalTextTreatsBlankValueAsAbsent() {
    PositiveLongParsing.ParsedPositiveLong parsed =
        PositiveLongParsing.parseOptionalText("   ", "recipientId");

    assertFalse(parsed.present());
    assertFalse(parsed.valid());
    assertFalse(parsed.invalid());
  }

  @Test
  void parseOptionalTextAcceptsPositiveNumber() {
    PositiveLongParsing.ParsedPositiveLong parsed =
        PositiveLongParsing.parseOptionalText("41", "recipientId");

    assertTrue(parsed.present());
    assertTrue(parsed.valid());
    assertEquals(41L, parsed.value());
  }

  @Test
  void parseOptionalTextMarksMalformedAndNonPositiveValuesInvalid() {
    PositiveLongParsing.ParsedPositiveLong malformed =
        PositiveLongParsing.parseOptionalText("nope", "recipientId");
    PositiveLongParsing.ParsedPositiveLong zero =
        PositiveLongParsing.parseOptionalText("0", "recipientId");

    assertTrue(malformed.invalid());
    assertTrue(zero.invalid());
  }

  @Test
  void requireOptionalTextRejectsMalformedAndNonPositiveValuesWithCanonicalMessages() {
    IllegalArgumentException malformed =
        assertThrows(
            IllegalArgumentException.class,
            () -> PositiveLongParsing.requireOptionalText("nope", "tenantId"));
    IllegalArgumentException zero =
        assertThrows(
            IllegalArgumentException.class,
            () -> PositiveLongParsing.requireOptionalText("0", "tenantId"));

    assertEquals("tenantId must be numeric", malformed.getMessage());
    assertEquals("tenantId must be positive", zero.getMessage());
  }
}
