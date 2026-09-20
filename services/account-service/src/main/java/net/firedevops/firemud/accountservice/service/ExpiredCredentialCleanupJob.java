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
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
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
  private static final Logger logger = LoggingUtil.getLogger(ExpiredCredentialCleanupJob.class);
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
  private final Counter passwordResetFailures;
  private final Counter emailVerificationFailures;
  private final Counter emailLoginChallengeFailures;
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
    this.passwordResetFailures = failureCounter(meterRegistry, PASSWORD_RESET);
    this.emailVerificationFailures = failureCounter(meterRegistry, EMAIL_VERIFICATION);
    this.emailLoginChallengeFailures = failureCounter(meterRegistry, EMAIL_LOGIN_CHALLENGE);
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
    cleanupPasswordReset(capturedNow);
    cleanupEmailVerification(capturedNow);
    cleanupEmailLoginChallenge(capturedNow);
  }

  private void cleanupPasswordReset(LocalDateTime capturedNow) {
    try {
      passwordResetDeleted.increment(
          passwordResetTokenRepository.deleteExpired(capturedNow, batchSize));
      passwordResetLagSeconds.set(
          lagSeconds(capturedNow, passwordResetTokenRepository.findOldestExpiredAt(capturedNow)));
    } catch (RuntimeException ex) {
      passwordResetFailures.increment();
      logger.warn(
          "Expired credential cleanup step failed for family {} ({})",
          PASSWORD_RESET,
          ex.getClass().getSimpleName());
    }
  }

  private void cleanupEmailVerification(LocalDateTime capturedNow) {
    try {
      emailVerificationDeleted.increment(
          emailVerificationTokenRepository.deleteExpired(capturedNow, batchSize));
      emailVerificationLagSeconds.set(
          lagSeconds(
              capturedNow, emailVerificationTokenRepository.findOldestExpiredAt(capturedNow)));
    } catch (RuntimeException ex) {
      emailVerificationFailures.increment();
      logger.warn(
          "Expired credential cleanup step failed for family {} ({})",
          EMAIL_VERIFICATION,
          ex.getClass().getSimpleName());
    }
  }

  private void cleanupEmailLoginChallenge(LocalDateTime capturedNow) {
    try {
      emailLoginChallengeDeleted.increment(
          emailLoginChallengeRepository.deleteExpired(capturedNow, batchSize));
      emailLoginChallengeLagSeconds.set(
          lagSeconds(capturedNow, emailLoginChallengeRepository.findOldestExpiredAt(capturedNow)));
    } catch (RuntimeException ex) {
      emailLoginChallengeFailures.increment();
      logger.warn(
          "Expired credential cleanup step failed for family {} ({})",
          EMAIL_LOGIN_CHALLENGE,
          ex.getClass().getSimpleName());
    }
  }

  private static Counter deletedCounter(MeterRegistry meterRegistry, String family) {
    return Counter.builder("account.credentials.cleanup.deleted")
        .description("Expired Account credential rows deleted")
        .tag("family", family)
        .register(meterRegistry);
  }

  private static Counter failureCounter(MeterRegistry meterRegistry, String family) {
    return Counter.builder("account.credentials.cleanup.failure")
        .description("Expired Account credential cleanup family failures")
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
