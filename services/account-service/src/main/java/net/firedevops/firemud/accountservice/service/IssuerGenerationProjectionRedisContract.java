package net.firedevops.firemud.accountservice.service;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
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

/** Account-owned passive single-key compare-and-set contract for its issuer projection. */
public final class IssuerGenerationProjectionRedisContract {
  public static final String SCRIPT_ID = "account.issuer-generation-projection.v1";
  public static final String RESOURCE_PATH = "redis/account_generation_projection_cas.lua";
  public static final String DESCRIPTOR_RESOURCE =
      "/redis/scripts/issuer-generation-projection-cas.json";
  public static final String OWNER = "account-service";
  public static final String PRINCIPAL = "account_coord_app";
  public static final RedisRole ROLE = RedisRole.COORDINATION;
  public static final ScriptCategory CATEGORY = ScriptCategory.SESSION_CAS;
  public static final String KEY_PREFIX = IssuerGenerationProjection.KEY_PREFIX;
  public static final String RESOURCE_SHA256 =
      "b8cf7abd202786a0b45b59444d1396a6c821e65284092e21a46a995e19e72507";

  private static final List<KeySpec> KEYS =
      List.of(
          new KeySpec("issuerGenerationProjection", KEY_PREFIX, HashTagDeclaration.NOT_REQUIRED));
  private static final List<ArgumentSpec> ARGUMENTS =
      List.of(
          new ArgumentSpec("expectedMode"),
          new ArgumentSpec("expectedBytes"),
          new ArgumentSpec("candidateBytes"));
  private static final List<OutcomeSpec> OUTCOMES =
      List.of(
          outcome("APPLIED", OutcomeCategory.SUCCESS, MutationEffect.MUTATING),
          outcome("REPLAY", OutcomeCategory.REPLAY, MutationEffect.NON_MUTATING),
          outcome("STALE", OutcomeCategory.STALE, MutationEffect.NON_MUTATING),
          outcome("INVALID", OutcomeCategory.VALIDATION_FAILURE, MutationEffect.NON_MUTATING),
          outcome("TTL_PRESENT", OutcomeCategory.VALIDATION_FAILURE, MutationEffect.NON_MUTATING));
  private static final ResetSensitivity RESET_SENSITIVITY = ResetSensitivity.CLUSTER;
  private static final LossClass LOSS_CLASS = LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP;
  private static final String TAIL_LOSS_BEHAVIOR =
      "On loss, fail closed and rebuild only from Account's exact current durable source snapshot.";
  private static final CompatibilityLevel COMPATIBILITY_LEVEL =
      CompatibilityLevel.REQUIRES_CLUSTER_RESET;
  private static final List<SupportedCoexistence> SUPPORTED_COEXISTENCE =
      List.of(new SupportedCoexistence("v1", "v1"));
  private static final RedisScriptCatalog CATALOG =
      RedisScriptCatalog.loadInstalled(
          IssuerGenerationProjectionRedisContract.class.getClassLoader());

  private IssuerGenerationProjectionRedisContract() {}

  /** Resolves and checks the catalogued descriptor before any private Redis execution. */
  public static RedisScriptDescriptor descriptor() {
    RedisScriptDescriptor descriptor = CATALOG.require(SCRIPT_ID);
    requireSupportedDescriptor(descriptor);
    return descriptor;
  }

  /** Validates the Account-local invocation shape before the private single-key CAS call. */
  static void validateInvocation(
      String key, String expectedMode, String expectedBytes, String candidateBytes) {
    Objects.requireNonNull(key, "Account generation key is required");
    Objects.requireNonNull(expectedMode, "Expected generation mode is required");
    Objects.requireNonNull(expectedBytes, "Expected generation bytes are required");
    Objects.requireNonNull(candidateBytes, "Candidate generation bytes are required");
    requireCanonicalIssuerKey(key);
    switch (expectedMode) {
      case "ABSENT" -> {
        if (!expectedBytes.isEmpty() || candidateBytes.isEmpty()) {
          throw new IllegalArgumentException("ABSENT projection invocation is malformed");
        }
      }
      case "PRESENT" -> {
        if (expectedBytes.isEmpty() || candidateBytes.isEmpty()) {
          throw new IllegalArgumentException("PRESENT projection invocation is malformed");
        }
      }
      case "VERIFY" -> {
        if (expectedBytes.isEmpty() || !candidateBytes.isEmpty()) {
          throw new IllegalArgumentException("VERIFY projection invocation is malformed");
        }
      }
      default -> throw new IllegalArgumentException("Account projection mode is unsupported");
    }
  }

  static void requireSupportedDescriptor(RedisScriptDescriptor descriptor) {
    if (descriptor == null
        || !descriptor.equals(CATALOG.require(SCRIPT_ID))
        || !SCRIPT_ID.equals(descriptor.scriptId())
        || !OWNER.equals(descriptor.owner())
        || !RESOURCE_PATH.equals(descriptor.resourcePath())
        || !RESOURCE_SHA256.equals(descriptor.sha256())
        || !PRINCIPAL.equals(descriptor.principal())
        || descriptor.role() != ROLE
        || descriptor.category() != CATEGORY
        || !KEYS.equals(descriptor.keys())
        || !ARGUMENTS.equals(descriptor.arguments())
        || descriptor.resetSensitivity() != RESET_SENSITIVITY
        || descriptor.lossClass() != LOSS_CLASS
        || !TAIL_LOSS_BEHAVIOR.equals(descriptor.tailLossBehavior())
        || descriptor.compatibilityLevel() != COMPATIBILITY_LEVEL
        || !SUPPORTED_COEXISTENCE.equals(descriptor.supportedCoexistence())
        || descriptor.legacyPayloadShape() != null
        || !OUTCOMES.equals(descriptor.outcomes())) {
      throw new IllegalStateException(
          "Account current-generation projection descriptor differs from its owner contract");
    }
  }

  private static void requireCanonicalIssuerKey(String key) {
    if (!IssuerGenerationProjection.keyForIssuer(
            net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.ISSUER)
        .equals(key)) {
      throw new IllegalArgumentException(
          "Issuer generation key must bind the canonical Account issuer");
    }
  }

  private static OutcomeSpec outcome(String code, OutcomeCategory category, MutationEffect effect) {
    return new OutcomeSpec(code, category, effect);
  }
}
