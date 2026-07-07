package net.firedevops.firemud.gamesession.service.impl;

final class GameplayCharacterIdParser {
  private GameplayCharacterIdParser() {}

  static Long parseGameplayCharacterId(String targetEntityId) {
    if (targetEntityId == null || targetEntityId.isBlank()) {
      return null;
    }
    try {
      long parsed = Long.parseLong(targetEntityId);
      return parsed > 0 ? parsed : null;
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  static Long parseGameplayCharacterId(Long characterId, String targetEntityId) {
    if (characterId != null && characterId > 0) {
      return characterId;
    }
    return parseGameplayCharacterId(targetEntityId);
  }
}
