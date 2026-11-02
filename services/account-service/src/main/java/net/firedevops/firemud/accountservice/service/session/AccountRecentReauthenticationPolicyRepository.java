package net.firedevops.firemud.accountservice.service.session;

import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads the exact current, versioned platform and deployment policy source rows. */
public final class AccountRecentReauthenticationPolicyRepository {
  private final DSLContext dsl;

  public AccountRecentReauthenticationPolicyRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "Account policy SQL source is required");
  }

  /**
   * Locks both current policy pointers through the caller's owner transaction. Missing or malformed
   * current policy is unavailable; there is no caller-side merge, default, or fallback.
   */
  public AccountRecentReauthenticationPolicy.Effective resolveCurrentForGameCreation() {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT platform.policy_version AS platform_version, "
                + "platform.recent_age_seconds AS platform_age, "
                + "platform.max_clock_skew_seconds AS platform_skew, "
                + "deployment.policy_version AS deployment_version, "
                + "deployment.recent_age_seconds AS deployment_age, "
                + "deployment.max_clock_skew_seconds AS deployment_skew "
                + "FROM account_recent_reauthentication_policy_current AS platform_current "
                + "JOIN account_recent_reauthentication_policy_values AS platform "
                + "ON platform.policy_scope = platform_current.policy_scope "
                + "AND platform.policy_version = platform_current.policy_version "
                + "CROSS JOIN account_recent_reauthentication_policy_current AS deployment_current "
                + "JOIN account_recent_reauthentication_policy_values AS deployment "
                + "ON deployment.policy_scope = deployment_current.policy_scope "
                + "AND deployment.policy_version = deployment_current.policy_version "
                + "WHERE platform_current.policy_scope = 'PLATFORM' "
                + "AND deployment_current.policy_scope = 'DEPLOYMENT' "
                + "FOR SHARE OF platform_current, deployment_current");
    if (row == null) {
      throw unavailable();
    }
    try {
      return AccountRecentReauthenticationPolicy.resolve(
          new AccountRecentReauthenticationPolicy.PlatformValue(
              requiredPositive(row, "platform_version"),
              requiredPositive(row, "platform_age"),
              requiredNonnegative(row, "platform_skew")),
          new AccountRecentReauthenticationPolicy.DeploymentValue(
              requiredPositive(row, "deployment_version"),
              requiredPositive(row, "deployment_age"),
              requiredNonnegative(row, "deployment_skew")));
    } catch (IllegalArgumentException | IllegalStateException malformed) {
      throw unavailable();
    }
  }

  private static long requiredPositive(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value <= 0L) {
      throw unavailable();
    }
    return value;
  }

  private static long requiredNonnegative(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value < 0L) {
      throw unavailable();
    }
    return value;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Current Account policy requires an owner transaction");
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Current Account reauthentication policy is unavailable");
  }
}
