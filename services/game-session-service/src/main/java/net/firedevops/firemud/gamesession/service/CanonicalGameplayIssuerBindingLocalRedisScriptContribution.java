package net.firedevops.firemud.gamesession.service;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/** Passive manual-catalog contribution for ordinary binding-local issuer projection. */
public final class CanonicalGameplayIssuerBindingLocalRedisScriptContribution
    implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.issuer-binding-local-add.v1";
  public static final String RESOURCE_PATH = "redis/lua/game-session-issuer-binding-local-add.lua";
  private static final String DESCRIPTOR_PATH =
      "/redis/scripts/game-session-issuer-binding-local-add.json";

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
            "Issuer binding-local Redis descriptor is unavailable"));
  }
}
