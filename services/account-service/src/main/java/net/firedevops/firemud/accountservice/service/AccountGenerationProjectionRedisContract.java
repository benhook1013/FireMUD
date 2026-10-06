package net.firedevops.firemud.accountservice.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/** Registered Account-owned single-key compare-and-set for the account generation projection. */
public final class AccountGenerationProjectionRedisContract {
  public static final String SCRIPT_ID = "account.account-generation-projection.v1";
  public static final String RESOURCE_PATH = "redis/account_generation_projection_cas.lua";
  public static final String DESCRIPTOR_RESOURCE =
      "/redis/scripts/account-generation-projection-cas.json";
  public static final String OWNER = "account-service";
  public static final String PRINCIPAL = "account_coord_app";
  public static final RedisScriptDescriptor.RedisRole ROLE =
      RedisScriptDescriptor.RedisRole.COORDINATION;
  public static final String CATEGORY = "account_generation_projection_cas";
  public static final String KEY_PREFIX = AccountGenerationProjection.KEY_PREFIX;
  public static final String RESOURCE_SHA256 =
      "b8cf7abd202786a0b45b59444d1396a6c821e65284092e21a46a995e19e72507";

  private static final List<RedisScriptDescriptor.KeyDeclaration> KEYS =
      List.of(
          new RedisScriptDescriptor.KeyDeclaration("accountGenerationProjection", KEY_PREFIX, ""));
  private static final List<String> ARGUMENTS =
      List.of("expectedMode", "expectedBytes", "candidateBytes");
  private static final String TAIL_LOSS_BEHAVIOR =
      "On loss, fail closed and rebuild only from Account's exact current durable source snapshot.";
  private static final Map<Integer, String> OUTCOMES =
      Map.of(1, "applied", 0, "replay", -1, "stale", -2, "invalid", -3, "ttl_present");
  private static final RedisScriptCatalog CATALOG =
      RedisScriptCatalog.loadInstalled(
          AccountGenerationProjectionRedisContract.class.getClassLoader());

  private AccountGenerationProjectionRedisContract() {}

  /** Resolves the descriptor only from an installed owner contribution. */
  public static RedisScriptDescriptor descriptor() {
    RedisScriptDescriptor descriptor = CATALOG.require(SCRIPT_ID);
    requireSupportedDescriptor(descriptor);
    return descriptor;
  }

  /** Validates the exact Account-local invocation shape before private Redis execution. */
  static void validateInvocation(
      String key, String expectedMode, String expectedBytes, String candidateBytes) {
    Objects.requireNonNull(key, "Account generation key is required");
    Objects.requireNonNull(expectedMode, "Expected generation mode is required");
    Objects.requireNonNull(expectedBytes, "Expected generation bytes are required");
    Objects.requireNonNull(candidateBytes, "Candidate generation bytes are required");
    requireCanonicalAccountKey(key);
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
    if (descriptor != CATALOG.require(SCRIPT_ID)
        || !SCRIPT_ID.equals(descriptor.id())
        || !OWNER.equals(descriptor.owner())
        || !RESOURCE_PATH.equals(descriptor.scriptResource())
        || !RESOURCE_SHA256.equals(descriptor.sourceSha256())
        || descriptor.redisRole() != ROLE
        || !CATEGORY.equals(descriptor.category())
        || !KEYS.equals(descriptor.keys())
        || !ARGUMENTS.equals(descriptor.arguments())
        || !"requires_cluster_reset".equals(descriptor.resetSensitivity())
        || !"session".equals(descriptor.lossOutcomeClass())
        || !TAIL_LOSS_BEHAVIOR.equals(descriptor.tailLossBehavior())
        || !OUTCOMES.equals(descriptor.outcomes())) {
      throw new IllegalStateException(
          "Account current-generation projection descriptor differs from its owner contract");
    }
  }

  private static void requireCanonicalAccountKey(String key) {
    if (!key.startsWith(KEY_PREFIX)) {
      throw new IllegalArgumentException("Account generation key is outside its owner prefix");
    }
    String accountId = key.substring(KEY_PREFIX.length());
    try {
      UUID parsed = UUID.fromString(accountId);
      if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(accountId)) {
        throw new IllegalArgumentException(
            "Account generation key must use a canonical non-nil UUID");
      }
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Account generation key must use a canonical non-nil UUID", malformed);
    }
  }
}
