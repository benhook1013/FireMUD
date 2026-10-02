package net.firedevops.firemud.gamesession.service;

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

/** Registered single-key coordination contract for Game Session's issuer projection. */
public final class IssuerAuthorityProjectionRedisContract {
  public static final String SCRIPT_ID = "game-session.issuer-authority-projection.v1";
  public static final String RESOURCE_PATH = "redis/issuer_authority_projection_cas.lua";
  public static final String OWNER = "game-session-service";
  public static final String PRINCIPAL = "gamesession_coord_app";
  public static final RedisRole ROLE = RedisRole.COORDINATION;
  public static final String PROJECTION_SCHEMA_VERSION =
      IssuerAuthorityProjectionTransitions.SCHEMA_VERSION;
  public static final String KEY_PREFIX = IssuerAuthorityProjectionTransitions.KEY_PREFIX;

  private static final String RESOURCE_SHA256 =
      "1c1df7fdf6edc42a9e3ad5dfd5a0db7c33295b41a9e5c28df2a65e98d9069c16";
  private static final RedisScriptDescriptor DESCRIPTOR = createDescriptor();
  private static final RedisContractRegistry REGISTRY =
      new RedisContractRegistry(
          List.of(new PrefixDeclaration(OWNER, ROLE, PRINCIPAL, KEY_PREFIX)), List.of(DESCRIPTOR));

  private IssuerAuthorityProjectionRedisContract() {}

  public static RedisScriptDescriptor descriptor() {
    return DESCRIPTOR;
  }

  /** Declarative registry only; executable access remains private to the typed owner store. */
  public static RedisContractRegistry registry() {
    return REGISTRY;
  }

  public static String keyForIssuer(String issuerId) {
    Objects.requireNonNull(issuerId, "exact issuer ID is required");
    if (issuerId.isBlank() || issuerId.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "exact issuer ID must be nonblank and contain no controls");
    }
    return KEY_PREFIX + issuerId;
  }

  static RedisInvocationContract prepareInvocation(
      String key, String expectedMode, String expectedBytes, String candidateBytes) {
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

  private static RedisScriptDescriptor createDescriptor() {
    return new RedisScriptDescriptor(
        SCRIPT_ID,
        RESOURCE_PATH,
        RESOURCE_SHA256,
        OWNER,
        PRINCIPAL,
        ROLE,
        List.of(new KeySpec("issuerProjection", KEY_PREFIX, HashTagDeclaration.NOT_REQUIRED)),
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
        LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP,
        "On loss, quarantine this issuer scope until an authenticated exact Account checkpoint is reconciled; never admit from this projection.",
        CompatibilityLevel.COMPATIBLE,
        List.of(new SupportedCoexistence("v1", "v1")),
        null);
  }
}
