package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
