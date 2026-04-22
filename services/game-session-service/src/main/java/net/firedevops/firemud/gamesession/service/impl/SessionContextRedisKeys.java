package net.firedevops.firemud.gamesession.service.impl;

import java.util.Locale;

final class SessionContextRedisKeys {
  private static final String CONTEXT_KEY_TEMPLATE = "sessionctx:%d:%d:context";
  private static final String SESSION_KEY_TEMPLATE = "sessionctx:session:%d:context";
  private static final String IDENTITY_KEY_TEMPLATE = "sessionctx:%d:identity:%d:%d:context";
  private static final String NAME_KEY_TEMPLATE = "sessionctx:%d:identity:%d:name:%s:context";
  private static final String MOVEMENT_EFFECT_KEY_TEMPLATE = "sessionctx:%d:%d:movement-effect:%s";
  private static final String DURABLE_EFFECT_KEY_TEMPLATE = "sessionctx:%d:%d:durable-effect:%s";

  private SessionContextRedisKeys() {}

  static String contextKey(long tenantId, long sessionId) {
    return String.format(CONTEXT_KEY_TEMPLATE, tenantId, sessionId);
  }

  static String sessionKey(long sessionId) {
    return String.format(SESSION_KEY_TEMPLATE, sessionId);
  }

  static String identityKey(long tenantId, long gameInstanceId, long characterId) {
    return String.format(IDENTITY_KEY_TEMPLATE, tenantId, gameInstanceId, characterId);
  }

  static String nameKey(long tenantId, long gameInstanceId, String characterName) {
    return String.format(NAME_KEY_TEMPLATE, tenantId, gameInstanceId, normalizeName(characterName));
  }

  static String movementEffectKey(long tenantId, long sessionId, String effectId) {
    return String.format(MOVEMENT_EFFECT_KEY_TEMPLATE, tenantId, sessionId, effectId);
  }

  static String durableEffectKey(long tenantId, long sessionId, String effectId) {
    return String.format(DURABLE_EFFECT_KEY_TEMPLATE, tenantId, sessionId, effectId);
  }

  static String normalizeName(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
