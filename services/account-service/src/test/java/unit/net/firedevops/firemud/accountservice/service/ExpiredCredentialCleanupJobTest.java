package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExpiredCredentialCleanupJobTest {
  @Test
  void cleanupUsesOneCapturedCutoffAndConfiguredBoundForEveryCredentialFamily() {
    PasswordResetTokenRepository passwordReset = mock(PasswordResetTokenRepository.class);
    EmailVerificationTokenRepository emailVerification =
        mock(EmailVerificationTokenRepository.class);
    AccountEmailLoginChallengeRepository emailLoginChallenge =
        mock(AccountEmailLoginChallengeRepository.class);
    when(passwordReset.deleteExpired(any(), eq(2))).thenReturn(2);
    when(emailVerification.deleteExpired(any(), eq(2))).thenReturn(1);
    when(emailLoginChallenge.deleteExpired(any(), eq(2))).thenReturn(0);
    when(passwordReset.findOldestExpiredAt(any())).thenReturn(Optional.empty());
    when(emailVerification.findOldestExpiredAt(any())).thenReturn(Optional.empty());
    when(emailLoginChallenge.findOldestExpiredAt(any())).thenReturn(Optional.empty());
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    ExpiredCredentialCleanupJob job =
        new ExpiredCredentialCleanupJob(
            passwordReset, emailVerification, emailLoginChallenge, meterRegistry, 2, 60_000);

    job.cleanupExpiredCredentials();

    ArgumentCaptor<LocalDateTime> capturedNow = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(passwordReset).deleteExpired(capturedNow.capture(), eq(2));
    verify(emailVerification).deleteExpired(capturedNow.capture(), eq(2));
    verify(emailLoginChallenge).deleteExpired(capturedNow.capture(), eq(2));
    assertThat(capturedNow.getAllValues()).containsOnly(capturedNow.getValue());
    assertThat(
            meterRegistry
                .get("account.credentials.cleanup.deleted")
                .tag("family", "password_reset")
                .counter()
                .count())
        .isEqualTo(2);
    assertThat(
            meterRegistry
                .get("account.credentials.cleanup.deleted")
                .tag("family", "email_verification")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void cleanupPublishesOldestExpiredRowLagWithoutCredentialMaterial() {
    PasswordResetTokenRepository passwordReset = mock(PasswordResetTokenRepository.class);
    EmailVerificationTokenRepository emailVerification =
        mock(EmailVerificationTokenRepository.class);
    AccountEmailLoginChallengeRepository emailLoginChallenge =
        mock(AccountEmailLoginChallengeRepository.class);
    when(passwordReset.deleteExpired(any(), eq(5))).thenReturn(0);
    when(emailVerification.deleteExpired(any(), eq(5))).thenReturn(0);
    when(emailLoginChallenge.deleteExpired(any(), eq(5))).thenReturn(0);
    when(passwordReset.findOldestExpiredAt(any()))
        .thenAnswer(
            invocation ->
                Optional.of(invocation.getArgument(0, LocalDateTime.class).minusSeconds(7)));
    when(emailVerification.findOldestExpiredAt(any())).thenReturn(Optional.empty());
    when(emailLoginChallenge.findOldestExpiredAt(any())).thenReturn(Optional.empty());
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ExpiredCredentialCleanupJob job =
        new ExpiredCredentialCleanupJob(
            passwordReset, emailVerification, emailLoginChallenge, meterRegistry, 5, 60_000);

    job.cleanupExpiredCredentials();

    assertThat(
            meterRegistry
                .get("account.credentials.cleanup.lag.seconds")
                .tag("family", "password_reset")
                .gauge()
                .value())
        .isEqualTo(7);
  }

  @Test
  void cleanupConfigurationMustBePositive() {
    PasswordResetTokenRepository passwordReset = mock(PasswordResetTokenRepository.class);
    EmailVerificationTokenRepository emailVerification =
        mock(EmailVerificationTokenRepository.class);
    AccountEmailLoginChallengeRepository emailLoginChallenge =
        mock(AccountEmailLoginChallengeRepository.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    assertThatThrownBy(
            () ->
                new ExpiredCredentialCleanupJob(
                    passwordReset,
                    emailVerification,
                    emailLoginChallenge,
                    meterRegistry,
                    0,
                    60_000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batch-size");
    assertThatThrownBy(
            () ->
                new ExpiredCredentialCleanupJob(
                    passwordReset, emailVerification, emailLoginChallenge, meterRegistry, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("interval-ms");
  }
}
