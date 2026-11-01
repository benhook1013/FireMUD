package net.firedevops.firemud.accountservice.service.session;

import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.*;

/** Explicit owner-local contribution; runtime registration and live ACL delivery remain closed. */
public final class AccountTenantCreationAdmissionRedisContract {
  public static final String SCRIPT_ID = "account-tenant-creation-admission.v1";
  public static final String RESOURCE = "redis/lua/account-tenant-creation-admission.lua";

  private AccountTenantCreationAdmissionRedisContract() {}

  public static RedisScriptDescriptor descriptor() {
    return new RedisScriptDescriptor(
        SCRIPT_ID,
        RESOURCE,
        "8a714da0c21597a4bb6c70947de87ed3afc8f0ff89bf0f2b33b0491f18a51ffb",
        "account-service",
        "account_coord_app",
        RedisRole.COORDINATION,
        List.of(
            new KeySpec("tokenRecord", "session:auth:token:", HashTagDeclaration.FULL_KEY_ANCHOR),
            new KeySpec(
                "creationReceipt",
                "session:auth:creation-admission:",
                HashTagDeclaration.EXACT_FULL_KEY_ANCHOR_TAG)),
        List.of(
            new ArgumentSpec("mode"),
            new ArgumentSpec("exactActiveRecord"),
            new ArgumentSpec("exactOriginalReceipt"),
            new ArgumentSpec("ownerNowEpochMillis")),
        ScriptCategory.SESSION_CAS,
        List.of(
            new OutcomeSpec("RECEIPT_CREATED", OutcomeCategory.SUCCESS, MutationEffect.MUTATING),
            new OutcomeSpec(
                "EXACT_RECEIPT_REASSERTED", OutcomeCategory.SUCCESS, MutationEffect.MUTATING),
            new OutcomeSpec(
                "INVALID_ARGUMENTS",
                OutcomeCategory.VALIDATION_FAILURE,
                MutationEffect.NON_MUTATING),
            new OutcomeSpec("EXPIRED", OutcomeCategory.STALE, MutationEffect.NON_MUTATING),
            new OutcomeSpec(
                "MISSING_OR_CHANGED_RECEIPT", OutcomeCategory.STALE, MutationEffect.NON_MUTATING),
            new OutcomeSpec("EXPIRY_MISMATCH", OutcomeCategory.STALE, MutationEffect.NON_MUTATING),
            new OutcomeSpec(
                "TOKEN_NOT_ACTIVE_OR_CHANGED", OutcomeCategory.STALE, MutationEffect.NON_MUTATING)),
        ResetSensitivity.CLUSTER,
        LossClass.PREMIUM_FINANCIAL_CROSS_TENANT_OR_UNIQUE_EXTERNAL_EFFECT,
        "Receipt loss or uncertainty denies dispatch and requires original SQL/Game Design reconciliation; never recreate a missing recovery receipt or infer NOT_CREATED. Redis is not durable admission authority.",
        CompatibilityLevel.COMPATIBLE,
        List.of(new SupportedCoexistence("v1", "v1")),
        null);
  }
}
