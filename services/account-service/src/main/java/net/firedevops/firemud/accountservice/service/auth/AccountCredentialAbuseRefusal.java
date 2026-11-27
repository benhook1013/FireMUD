package net.firedevops.firemud.accountservice.service.auth;

/** Closed, non-enumerating refusal for receiving adapters. No subject or secret is retained. */
public final class AccountCredentialAbuseRefusal extends RuntimeException {
  private final boolean retryLater;
  private final Long retryAfterMillis;

  private AccountCredentialAbuseRefusal(boolean retryLater, Long retryAfterMillis) {
    super(retryLater ? "AUTH_RETRY_LATER" : "AUTH_ABUSE_CONTROL_UNAVAILABLE");
    this.retryLater = retryLater;
    this.retryAfterMillis = retryAfterMillis;
  }

  public static AccountCredentialAbuseRefusal unavailable() {
    return new AccountCredentialAbuseRefusal(false, null);
  }

  public static AccountCredentialAbuseRefusal from(
      AccountCredentialAbuseService.Decision decision) {
    if (decision == null) return unavailable();
    boolean retry = decision.outcome() == AccountCredentialAbuseService.Outcome.RETRY_LATER;
    if (!retry && decision.outcome() != AccountCredentialAbuseService.Outcome.UNAVAILABLE)
      return unavailable();
    Long millis = null;
    if (decision.retryAfter() != null) {
      try {
        long value = decision.retryAfter().toMillis();
        if (value < 1
            || value > AccountCredentialAbusePolicy.MAX_EXACT_REDIS_INTEGER
            || !java.time.Duration.ofMillis(value).equals(decision.retryAfter()))
          return unavailable();
        millis = value;
      } catch (ArithmeticException invalid) {
        return unavailable();
      }
    }
    if (retry && millis == null) return unavailable();
    return new AccountCredentialAbuseRefusal(retry, millis);
  }

  public boolean retryLater() {
    return retryLater;
  }

  public Long retryAfterMillis() {
    return retryAfterMillis;
  }
}
