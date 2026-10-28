package net.firedevops.firemud.gamesession.service;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/** Passive descriptor for the one-key create-only INITIAL OPEN barrier script. */
public final class CanonicalGameplayAccountInitialBarrierRedisScriptContribution
    implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.account-admission-barrier-initial-open.v1";
  public static final String RESOURCE_PATH =
      "redis/lua/game-session-account-admission-barrier-initial-open.lua";
  private static final String DESCRIPTOR_PATH =
      "/redis/scripts/game-session-account-admission-barrier-initial-open.json";

  @Override
  public String ownerId() {
    return "game-session-service";
  }

  @Override
  public Collection<RedisScriptDescriptor> descriptors() {
    return List.of(
        CanonicalGameplayAdmissionBarrierRedisScriptDescriptorLoader.read(
            DESCRIPTOR_PATH, SCRIPT_ID, "INITIAL account barrier descriptor is unavailable"));
  }
}
