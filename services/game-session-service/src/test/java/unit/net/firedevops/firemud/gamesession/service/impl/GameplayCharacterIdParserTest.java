package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GameplayCharacterIdParserTest {

  @Test
  void parseGameplayCharacterIdReturnsNullForBlankMalformedAndNonPositiveText() {
    assertNull(GameplayCharacterIdParser.parseGameplayCharacterId("   "));
    assertNull(GameplayCharacterIdParser.parseGameplayCharacterId("npc-alpha"));
    assertNull(GameplayCharacterIdParser.parseGameplayCharacterId("0"));
  }

  @Test
  void parseGameplayCharacterIdReturnsPositiveCharacterIdFromText() {
    assertEquals(91L, GameplayCharacterIdParser.parseGameplayCharacterId("91"));
  }

  @Test
  void parseGameplayCharacterIdPrefersExplicitPositiveCharacterId() {
    assertEquals(17L, GameplayCharacterIdParser.parseGameplayCharacterId(17L, "npc-alpha"));
  }

  @Test
  void parseGameplayCharacterIdFallsBackToTextWhenExplicitCharacterIdIsNonPositive() {
    assertEquals(91L, GameplayCharacterIdParser.parseGameplayCharacterId(0L, "91"));
  }
}
