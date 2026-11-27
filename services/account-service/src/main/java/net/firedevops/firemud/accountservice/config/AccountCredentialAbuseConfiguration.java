package net.firedevops.firemud.accountservice.config;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Clock;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbusePolicySource;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseRedisStore;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService;

/**
 * Explicit controlled assembly, intentionally not a scanned Spring configuration. The receiving
 * owner must supply its Account policy and a dedicated Cache/Rate-Limit connection. This does not
 * open a connection, grant an ACL, select production inputs or register an authentication route.
 */
public final class AccountCredentialAbuseConfiguration {
  private AccountCredentialAbuseConfiguration() {}

  public static AccountCredentialAbuseService controlled(
      AccountCredentialAbusePolicySource policies,
      StatefulRedisConnection<byte[], byte[]> dedicatedCacheConnection,
      Clock clock) {
    return new AccountCredentialAbuseService(
        policies,
        dedicatedCacheConnection == null
            ? null
            : new AccountCredentialAbuseRedisStore(dedicatedCacheConnection),
        clock);
  }
}
