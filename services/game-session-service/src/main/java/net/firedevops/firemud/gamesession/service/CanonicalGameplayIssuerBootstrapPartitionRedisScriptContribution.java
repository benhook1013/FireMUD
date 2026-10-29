package net.firedevops.firemud.gamesession.service;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/** Passive manual-catalog contribution for the local issuer-bootstrap partition builder. */
public final class CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution
    implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.issuer-bootstrap-partition-build.v1";
  public static final String RESOURCE_PATH =
      "redis/lua/game-session-issuer-bootstrap-partition-build.lua";
  private static final String DESCRIPTOR_PATH =
      "/redis/scripts/game-session-issuer-bootstrap-partition-build.json";

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
            "Issuer bootstrap partition Redis descriptor is unavailable"));
  }
}
