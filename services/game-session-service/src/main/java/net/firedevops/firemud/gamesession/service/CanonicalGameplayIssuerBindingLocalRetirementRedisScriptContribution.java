package net.firedevops.firemud.gamesession.service;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/** Passive manual-catalog contribution for one original-operation physical retirement. */
public final class CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution
    implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.issuer-binding-local-retirement.v1";
  public static final String RESOURCE_PATH =
      "redis/lua/game-session-issuer-binding-local-retirement.lua";
  private static final String DESCRIPTOR_PATH =
      "/redis/scripts/game-session-issuer-binding-local-retirement.json";

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
            "Issuer binding-local retirement descriptor is unavailable"));
  }
}
