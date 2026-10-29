package net.firedevops.firemud.gamesession.service;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/** Passive manual descriptor contribution for one-key issuer tenant barrier scripts. */
public final class CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution
    implements RedisScriptContribution {
  public static final String OPEN_SCRIPT_ID = "game-session.issuer-admission-barrier-open.v1";
  public static final String CLOSE_SCRIPT_ID = "game-session.issuer-admission-barrier-close.v1";
  public static final String OPEN_RESOURCE_PATH =
      "redis/lua/game-session-issuer-admission-barrier-open.lua";
  public static final String CLOSE_RESOURCE_PATH =
      "redis/lua/game-session-issuer-admission-barrier-close.lua";
  private static final String OPEN_DESCRIPTOR_PATH =
      "/redis/scripts/game-session-issuer-admission-barrier-open.json";
  private static final String CLOSE_DESCRIPTOR_PATH =
      "/redis/scripts/game-session-issuer-admission-barrier-close.json";

  @Override
  public String ownerId() {
    return "game-session-service";
  }

  @Override
  public Collection<RedisScriptDescriptor> descriptors() {
    return List.of(
        CanonicalGameplayAdmissionBarrierRedisScriptDescriptorLoader.read(
            OPEN_DESCRIPTOR_PATH,
            OPEN_SCRIPT_ID,
            "Issuer admission barrier descriptors are unavailable"),
        CanonicalGameplayAdmissionBarrierRedisScriptDescriptorLoader.read(
            CLOSE_DESCRIPTOR_PATH,
            CLOSE_SCRIPT_ID,
            "Issuer admission barrier descriptors are unavailable"));
  }
}
