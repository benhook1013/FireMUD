package net.firedevops.firemud.gamesession.service;

import java.util.UUID;

/** Owner-only tenantless context key; each complete key selects its own Redis slot. */
public final class InitialGameplayLoginContextRedisKeys {
  public static final String FAMILY_SCOPE = "session:game:initial-context:v1:";

  private InitialGameplayLoginContextRedisKeys() {}

  public static String contextKey(UUID contextId) {
    if (contextId == null || contextId.version() != 4 || contextId.variant() != 2) {
      throw new IllegalArgumentException("Server context UUIDv4 required");
    }
    return FAMILY_SCOPE + contextId;
  }
}
