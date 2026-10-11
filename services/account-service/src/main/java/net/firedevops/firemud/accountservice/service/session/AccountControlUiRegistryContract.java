package net.firedevops.firemud.accountservice.service.session;

import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.*;

/** Distinct owner descriptor; runtime catalog registration and ACL provisioning remain closed. */
public final class AccountControlUiRegistryContract {
  public static final String SCRIPT_ID = "account-control-ui-registry-cas.v1";
  public static final String RESOURCE = "redis/lua/account-control-ui-registry-cas.lua";

  private AccountControlUiRegistryContract() {}

  public static RedisScriptDescriptor descriptor() {
    return new RedisScriptDescriptor(
        SCRIPT_ID,
        RESOURCE,
        "48c909de4aa5868772662972bf1ce449a2c83fd72cb06e5e2f43fea66e253a1b",
        "account-service",
        "account_coord_app",
        RedisRole.COORDINATION,
        List.of(new KeySpec("tokenRecord", "session:auth:token:", HashTagDeclaration.NOT_REQUIRED)),
        List.of(
            new ArgumentSpec("expectedOriginalRecord"),
            new ArgumentSpec("exactReplacementRecord"),
            new ArgumentSpec("absoluteCleanupDeadlineEpochMillis")),
        ScriptCategory.SESSION_CAS,
        List.of(
            new OutcomeSpec("APPLIED", OutcomeCategory.SUCCESS, MutationEffect.MUTATING),
            new OutcomeSpec("EXACT_REASSERTED", OutcomeCategory.SUCCESS, MutationEffect.MUTATING),
            new OutcomeSpec(
                "INVALID_ARGUMENTS",
                OutcomeCategory.VALIDATION_FAILURE,
                MutationEffect.NON_MUTATING),
            new OutcomeSpec("EXPIRED", OutcomeCategory.STALE, MutationEffect.NON_MUTATING),
            new OutcomeSpec("EXPIRY_MISMATCH", OutcomeCategory.STALE, MutationEffect.NON_MUTATING),
            new OutcomeSpec("CONFLICT", OutcomeCategory.STALE, MutationEffect.NON_MUTATING)),
        ResetSensitivity.CLUSTER,
        LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP,
        "Missing or changed control-ui record denies; only original durable Account issuance may reconcile.",
        CompatibilityLevel.REQUIRES_CLUSTER_RESET,
        List.of(new SupportedCoexistence("v1", "v1")),
        null);
  }
}
