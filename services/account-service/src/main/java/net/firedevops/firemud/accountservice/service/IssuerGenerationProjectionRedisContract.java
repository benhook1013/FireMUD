package net.firedevops.firemud.accountservice.service;

import java.util.List;
import java.util.Objects;
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

/** Registered Account-owned single-key compare-and-set for the issuer generation projection. */
public final class IssuerGenerationProjectionRedisContract {
  public static final String SCRIPT_ID = "account.issuer-generation-projection.v1";
  public static final String RESOURCE_PATH = "redis/account_generation_projection_cas.lua";
  public static final String OWNER = "account-service";
  public static final String PRINCIPAL = "account_coord_app";
  public static final RedisRole ROLE = RedisRole.COORDINATION;
  public static final String KEY_PREFIX = IssuerGenerationProjection.KEY_PREFIX;

  private static final String RESOURCE_SHA256 =
      "1c1df7fdf6edc42a9e3ad5dfd5a0db7c33295b41a9e5c28df2a65e98d9069c16";
  private static final RedisScriptDescriptor DESCRIPTOR = createDescriptor();
  private static final RedisContractRegistry REGISTRY =
      new RedisContractRegistry(
          List.of(new PrefixDeclaration(OWNER, ROLE, PRINCIPAL, KEY_PREFIX)), List.of(DESCRIPTOR));

  private IssuerGenerationProjectionRedisContract() {}

  public static RedisScriptDescriptor descriptor() {
    return DESCRIPTOR;
  }

  /** Declarative registry only; executable access stays private to the Account owner store. */
  public static RedisContractRegistry registry() {
    return REGISTRY;
  }

  static RedisInvocationContract prepareInvocation(
      String key, String expectedMode, String expectedBytes, String candidateBytes) {
    Objects.requireNonNull(key, "Account generation key is required");
    requireCanonicalIssuerKey(key);
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

  private static void requireCanonicalIssuerKey(String key) {
    if (!IssuerGenerationProjection.keyForIssuer(
            net.firedevops.firemud.common.security.ControlUiJwtProfileValidator.ISSUER)
        .equals(key)) {
      throw new IllegalArgumentException(
          "Issuer generation key must bind the configured Account issuer");
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
            new KeySpec("issuerGenerationProjection", KEY_PREFIX, HashTagDeclaration.NOT_REQUIRED)),
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
        "On loss, fail closed and rebuild only from Account's exact current durable source snapshot.",
        CompatibilityLevel.COMPATIBLE,
        List.of(new SupportedCoexistence("v1", "v1")),
        null);
  }
}
