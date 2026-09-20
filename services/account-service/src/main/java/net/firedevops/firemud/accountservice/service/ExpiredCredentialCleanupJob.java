package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Account-owned bounded cleanup for credential material whose expiry has passed. */
@Component
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW"},
    justification =
        "Injected Spring repositories are internal collaborators; invalid cleanup configuration must fail startup.")
public class ExpiredCredentialCleanupJob {
  private static final String PASSWORD_RESET = "password_reset";
  private static final String EMAIL_VERIFICATION = "email_verification";
  private static final String EMAIL_LOGIN_CHALLENGE = "email_login_challenge";

  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final AccountEmailLoginChallengeRepository emailLoginChallengeRepository;
  private final int batchSize;
  private final Counter passwordResetDeleted;
  private final Counter emailVerificationDeleted;
  private final Counter emailLoginChallengeDeleted;
  private final AtomicLong passwordResetLagSeconds = new AtomicLong();
  private final AtomicLong emailVerificationLagSeconds = new AtomicLong();
  private final AtomicLong emailLoginChallengeLagSeconds = new AtomicLong();

  public ExpiredCredentialCleanupJob(
      PasswordResetTokenRepository passwordResetTokenRepository,
      EmailVerificationTokenRepository emailVerificationTokenRepository,
      AccountEmailLoginChallengeRepository emailLoginChallengeRepository,
      MeterRegistry meterRegistry,
      @Value("${firemud.account.credentials.cleanup.batch-size:100}") int batchSize,
      @Value("${firemud.account.credentials.cleanup.interval-ms:60000}") long intervalMs) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException(
          "firemud.account.credentials.cleanup.batch-size must be positive");
    }
    if (intervalMs <= 0) {
      throw new IllegalArgumentException(
          "firemud.account.credentials.cleanup.interval-ms must be positive");
    }
    this.passwordResetTokenRepository = passwordResetTokenRepository;
    this.emailVerificationTokenRepository = emailVerificationTokenRepository;
    this.emailLoginChallengeRepository = emailLoginChallengeRepository;
    this.batchSize = batchSize;
    this.passwordResetDeleted = deletedCounter(meterRegistry, PASSWORD_RESET);
    this.emailVerificationDeleted = deletedCounter(meterRegistry, EMAIL_VERIFICATION);
    this.emailLoginChallengeDeleted = deletedCounter(meterRegistry, EMAIL_LOGIN_CHALLENGE);
    registerLagGauge(meterRegistry, PASSWORD_RESET, passwordResetLagSeconds);
    registerLagGauge(meterRegistry, EMAIL_VERIFICATION, emailVerificationLagSeconds);
    registerLagGauge(meterRegistry, EMAIL_LOGIN_CHALLENGE, emailLoginChallengeLagSeconds);
  }

  @Timed(value = "account.credentials.cleanup")
  @Scheduled(
      fixedDelayString = "${firemud.account.credentials.cleanup.interval-ms:60000}",
      timeUnit = TimeUnit.MILLISECONDS)
  public void cleanupExpiredCredentials() {
    LocalDateTime capturedNow = LocalDateTime.now(ZoneOffset.UTC);
    int passwordResetRows = passwordResetTokenRepository.deleteExpired(capturedNow, batchSize);
    int emailVerificationRows =
        emailVerificationTokenRepository.deleteExpired(capturedNow, batchSize);
    int emailLoginChallengeRows =
        emailLoginChallengeRepository.deleteExpired(capturedNow, batchSize);

    passwordResetDeleted.increment(passwordResetRows);
    emailVerificationDeleted.increment(emailVerificationRows);
    emailLoginChallengeDeleted.increment(emailLoginChallengeRows);
    passwordResetLagSeconds.set(
        lagSeconds(capturedNow, passwordResetTokenRepository.findOldestExpiredAt(capturedNow)));
    emailVerificationLagSeconds.set(
        lagSeconds(capturedNow, emailVerificationTokenRepository.findOldestExpiredAt(capturedNow)));
    emailLoginChallengeLagSeconds.set(
        lagSeconds(capturedNow, emailLoginChallengeRepository.findOldestExpiredAt(capturedNow)));
  }

  private static Counter deletedCounter(MeterRegistry meterRegistry, String family) {
    return Counter.builder("account.credentials.cleanup.deleted")
        .description("Expired Account credential rows deleted")
        .tag("family", family)
        .register(meterRegistry);
  }

  private static void registerLagGauge(
      MeterRegistry meterRegistry, String family, AtomicLong lagSeconds) {
    Gauge.builder("account.credentials.cleanup.lag.seconds", lagSeconds, AtomicLong::doubleValue)
        .description("Age of the oldest expired Account credential row still retained")
        .tag("family", family)
        .register(meterRegistry);
  }

  private static long lagSeconds(
      LocalDateTime capturedNow, Optional<LocalDateTime> oldestExpiredAt) {
    return oldestExpiredAt
        .map(expiredAt -> Math.max(0L, Duration.between(expiredAt, capturedNow).getSeconds()))
        .orElse(0L);
  }
}
