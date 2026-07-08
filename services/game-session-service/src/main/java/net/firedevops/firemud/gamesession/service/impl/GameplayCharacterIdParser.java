package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.gamesession.service.PositiveLongParsing;

final class GameplayCharacterIdParser {
  private GameplayCharacterIdParser() {}

  static Long parseGameplayCharacterId(String targetEntityId) {
    PositiveLongParsing.ParsedPositiveLong parsed =
        PositiveLongParsing.parseOptionalText(targetEntityId, "characterId");
    if (!parsed.valid()) {
      return null;
    }
    return parsed.value();
  }

  static Long parseGameplayCharacterId(Long characterId, String targetEntityId) {
    if (characterId != null && characterId > 0) {
      return characterId;
    }
    return parseGameplayCharacterId(targetEntityId);
  }
}
