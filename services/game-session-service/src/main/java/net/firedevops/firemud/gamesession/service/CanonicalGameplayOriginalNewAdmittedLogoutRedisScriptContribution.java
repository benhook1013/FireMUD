package net.firedevops.firemud.gamesession.service;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/** Explicit descriptor contribution for the manual admitted logout readback. */
public final class CanonicalGameplayOriginalNewAdmittedLogoutRedisScriptContribution
    implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.original-new-admitted-logout.v1";
  public static final String RESOURCE_PATH =
      "redis/lua/game-session-original-new-admitted-logout.lua";
  private static final String DESCRIPTOR_PATH =
      "/redis/scripts/game-session-original-new-admitted-logout.json";

  @Override
  public String ownerId() {
    return "game-session-service";
  }

  @Override
  public Collection<RedisScriptDescriptor> descriptors() {
    return List.of(
        GameSessionRedisScriptDescriptors.load(
            getClass(),
            DESCRIPTOR_PATH,
            ownerId(),
            SCRIPT_ID,
            "Original NEW admitted logout Redis descriptor is unavailable"));
  }
}
