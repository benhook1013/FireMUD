package net.firedevops.firemud.accountservice.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisContractRegistry;
import net.firedevops.firemud.common.redis.contracts.RedisContractRegistry.InvocationRequest;
import net.firedevops.firemud.common.redis.contracts.RedisContractRegistry.PrefixDeclaration;
import net.firedevops.firemud.common.redis.contracts.RedisInvocationContract;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.ArgumentSpec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.CompatibilityLevel;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.HashTagDeclaration;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.KeySpec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.LossClass;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.MutationEffect;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.OutcomeCategory;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.OutcomeSpec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.RedisRole;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.ResetSensitivity;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.ScriptCategory;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.SupportedCoexistence;

/** Registered Account-owned single-key CAS for one exact membership-generation pair. */
public final class MembershipGenerationProjectionRedisContract {
  public static final String SCRIPT_ID = "account.membership-generation-projection.v1";
  public static final String RESOURCE_PATH = "redis/account_generation_projection_cas.lua";
  public static final String OWNER = "account-service";
  public static final String PRINCIPAL = "account_coord_app";
  public static final RedisRole ROLE = RedisRole.COORDINATION;
  public static final String KEY_PREFIX = MembershipGenerationProjection.KEY_PREFIX;

  private static final String RESOURCE_SHA256 =
      "1c1df7fdf6edc42a9e3ad5dfd5a0db7c33295b41a9e5c28df2a65e98d9069c16";
  private static final RedisScriptDescriptor DESCRIPTOR = createDescriptor();
  private static final RedisContractRegistry REGISTRY =
      new RedisContractRegistry(
          List.of(new PrefixDeclaration(OWNER, ROLE, PRINCIPAL, KEY_PREFIX)), List.of(DESCRIPTOR));

  private MembershipGenerationProjectionRedisContract() {}

  public static RedisScriptDescriptor descriptor() {
    return DESCRIPTOR;
  }

  /** Declarative registry only; executable access stays private to the owner store. */
  public static RedisContractRegistry registry() {
    return REGISTRY;
  }

  static RedisInvocationContract prepareInvocation(
      String key, String expectedMode, String expectedBytes, String candidateBytes) {
    Objects.requireNonNull(key, "Membership generation key is required");
    requireCanonicalMembershipKey(key);
    return REGISTRY.prepareInvocation(
        new InvocationRequest(
            SCRIPT_ID,
            RESOURCE_PATH,
            RESOURCE_SHA256,
            OWNER,
            PRINCIPAL,
            ROLE,
            List.of(key),
            List.of(expectedMode, expectedBytes, candidateBytes)));
  }

  private static void requireCanonicalMembershipKey(String key) {
    if (!key.startsWith(KEY_PREFIX)) {
      throw new IllegalArgumentException("Membership generation key is outside its owner prefix");
    }
    String pair = key.substring(KEY_PREFIX.length());
    int separator = pair.indexOf(':');
    if (separator <= 0 || separator != pair.lastIndexOf(':') || separator == pair.length() - 1) {
      throw new IllegalArgumentException(
          "Membership generation key must contain one canonical Account/tenant UUID pair");
    }
    requireCanonicalUuid(pair.substring(0, separator));
    requireCanonicalUuid(pair.substring(separator + 1));
  }

  private static void requireCanonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException(
            "Membership generation key must use canonical non-nil UUIDs");
      }
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Membership generation key must use canonical non-nil UUIDs", malformed);
    }
  }

  private static RedisScriptDescriptor createDescriptor() {
    return new RedisScriptDescriptor(
        SCRIPT_ID,
        RESOURCE_PATH,
        RESOURCE_SHA256,
        OWNER,
        PRINCIPAL,
        ROLE,
        List.of(
            new KeySpec(
                "membershipGenerationProjection", KEY_PREFIX, HashTagDeclaration.NOT_REQUIRED)),
        List.of(
            new ArgumentSpec("expectedMode"),
            new ArgumentSpec("expectedBytes"),
            new ArgumentSpec("candidateBytes")),
        ScriptCategory.SESSION_CAS,
        List.of(
            new OutcomeSpec("APPLIED", OutcomeCategory.SUCCESS, MutationEffect.MUTATING),
            new OutcomeSpec("REPLAY", OutcomeCategory.REPLAY, MutationEffect.NON_MUTATING),
            new OutcomeSpec("STALE", OutcomeCategory.STALE, MutationEffect.NON_MUTATING),
            new OutcomeSpec(
                "INVALID", OutcomeCategory.VALIDATION_FAILURE, MutationEffect.NON_MUTATING),
            new OutcomeSpec(
                "TTL_PRESENT", OutcomeCategory.VALIDATION_FAILURE, MutationEffect.NON_MUTATING)),
        ResetSensitivity.CLUSTER,
        LossClass.EMPTY_REDIS_COLD_START_OR_RESET,
        "On loss, fail closed and rebuild only from Account's exact existing membership source.",
        CompatibilityLevel.COMPATIBLE,
        List.of(new SupportedCoexistence("v1", "v1")),
        null);
  }
}
