package net.firedevops.firemud.accountservice.service.auth;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
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

/** Passive catalog declaration only; it neither provisions an ACL nor activates a Redis client. */
public final class AccountCredentialAbuseRedisScriptContribution
    implements RedisScriptContribution {
  public static final String PRINCIPAL = "account_cache_app";
  public static final String BUCKET_ID = "account-credential-abuse-bucket.v1";
  public static final String ADMISSION_ID = "account-credential-abuse-admission.v1";

  @Override
  public String ownerId() {
    return "account-service";
  }

  @Override
  public Collection<RedisScriptDescriptor> descriptors() {
    return List.of(
        descriptor(
            BUCKET_ID,
            "redis/account_credential_abuse_bucket_v1.lua",
            "85af5355059b2d6953af964e2bc1fd8aaaaab1d9b8aa67d9bb5cd5d854ea4d3d",
            "subject"),
        descriptor(
            ADMISSION_ID,
            "redis/account_credential_abuse_admission_v1.lua",
            "172e789c1bba04cba4b1ec3322420a4f4441a2a7377ef7a112dc1ddb1ebb3e73",
            "pressure"));
  }

  private static RedisScriptDescriptor descriptor(
      String id, String resource, String digest, String kind) {
    return new RedisScriptDescriptor(
        id,
        resource,
        digest,
        "account-service",
        PRINCIPAL,
        RedisRole.CACHE_RATE_LIMIT,
        List.of(
            new KeySpec(
                "bucket",
                AccountCredentialAbuseSubjectKeys.PREFIX + kind + ":",
                HashTagDeclaration.NOT_REQUIRED)),
        List.of(
                "now",
                "expiry",
                "signalSkew",
                "keyLimit",
                "memoryLimit",
                "bucketLimit",
                "counterLimit",
                "admissionLimit",
                "operation",
                "witness",
                "windowMillis",
                "ttlMillis")
            .stream()
            .map(ArgumentSpec::new)
            .toList(),
        ScriptCategory.RATE_LIMIT,
        List.of(
            new OutcomeSpec("OBSERVED", OutcomeCategory.SUCCESS, MutationEffect.NON_MUTATING),
            new OutcomeSpec("APPLIED", OutcomeCategory.SUCCESS, MutationEffect.MUTATING),
            new OutcomeSpec("UNAVAILABLE", OutcomeCategory.DEFERRAL, MutationEffect.NON_MUTATING),
            new OutcomeSpec(
                "CLEANED_UNAVAILABLE", OutcomeCategory.SUCCESS, MutationEffect.MUTATING),
            new OutcomeSpec(
                "ACCOUNTED_UNAVAILABLE", OutcomeCategory.SUCCESS, MutationEffect.MUTATING)),
        ResetSensitivity.NONE,
        LossClass.LOSSY_TIMER_OR_ADVISORY_HINT,
        "Eviction/reset can weaken credential heuristics only. Real-key admission survives lost"
            + " counters. All unavailable outcomes deny new primary authentication; existing"
            + " sessions are unchanged. Mutating unavailable results never constitute an"
            + " authentication success.",
        CompatibilityLevel.COMPATIBLE,
        List.of(new SupportedCoexistence("v1", "v1")),
        null);
  }
}
