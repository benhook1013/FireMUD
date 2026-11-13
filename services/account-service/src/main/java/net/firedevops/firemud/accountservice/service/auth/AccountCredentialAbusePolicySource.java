package net.firedevops.firemud.accountservice.service.auth;

import java.time.Instant;
import java.util.Objects;

/** Account-owned selection boundary; no implementation is selected by ambient configuration. */
@FunctionalInterface
public interface AccountCredentialAbusePolicySource {
  /** Missing, invalid or unavailable selection must return no snapshot or throw, never defaults. */
  Snapshot resolve(Instant now);

  /**
   * Explicit controlled composition only. This supplies values, not proof of production settings,
   * protected key delivery or Redis endpoint custody. The caller retains the key's lifecycle.
   */
  static AccountCredentialAbusePolicySource controlled(
      AccountCredentialAbusePolicy policy, AccountCredentialAbuseSubjectKeys.DigestKey key) {
    Snapshot snapshot = new Snapshot(policy, key);
    return now -> policy.isEffectiveAt(now) ? snapshot : null;
  }

  record Snapshot(
      AccountCredentialAbusePolicy policy, AccountCredentialAbuseSubjectKeys.DigestKey key) {
    public Snapshot {
      Objects.requireNonNull(policy, "Explicit credential-abuse policy is required");
      Objects.requireNonNull(key, "Explicit credential-abuse key is required");
    }

    @Override
    public String toString() {
      return "AccountCredentialAbusePolicySnapshot[redacted,non-authorizing]";
    }
  }
}
