package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Composed negative boundary, NOT successful issuer/end-to-end proof. Actual primary auth,
 * canonical source composition/currentness, issuance service/repository and PostgreSQL transactions
 * execute together. External creation/bootstrap, party verification, legal action/publication and
 * environment observations are explicitly stipulated test-only fixtures. Real signer owner is
 * deliberately unavailable: no fabricated committed signer, private Capture, registry receipt or
 * successful issuance is manufactured. Missing custody/signing/Redis never become authority.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountControlUiOwnerWorkflowPostgresIntegrationTest {
  static final String CALLER =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/logging-admin-service";
  static final String OTP = "test-only-original-creator-otp";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @TempDir Path temporary;

  @Test
  void realSourceAndPrimaryCompositionRollsBackOtpWhenGenuineSignerIsUnavailable() {
    var f =
        new AccountControlUiOwnerSourcesFixture(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), temporary, true);
    var environment = f.terms.captureCurrentEnvironmentBoundary();
    var source = f.tx(() -> f.authority.captureInitial(f.tenant, environment));
    assertThat(source.actor()).isEqualTo(f.account.getAccountUuid());
    assertThat(source.tenant()).isEqualTo(f.tenant);
    assertThat(source.sources()).hasSize(8);
    var originalOtp = f.challenges.findByAccountId(f.account.getId()).orElseThrow();
    try (var ignored = withPeer(CALLER)) {
      assertThatThrownBy(() -> f.service.issue(f.request(OTP), environment))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Existing committed control-ui signer unavailable");
      assertThat(f.challenges.findByAccountId(f.account.getId())).contains(originalOtp);
      // The same exact request can retry its original OTP after owner rollback, but cannot mint.
      assertThatThrownBy(() -> f.service.issue(f.request(OTP), environment))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Existing committed control-ui signer unavailable");
      assertThatThrownBy(() -> f.service.issue(f.request("changed-secret"), environment))
          .isInstanceOf(AuthenticationException.class);
    }
    var afterDenial = f.challenges.findByAccountId(f.account.getId()).orElseThrow();
    assertThat(afterDenial.getId()).isEqualTo(originalOtp.getId());
    assertThat(afterDenial.getCodeHash()).isEqualTo(originalOtp.getCodeHash());
    assertThat(afterDenial.getInvalidAttemptCount()).isEqualTo(1);
    f.assertNoIssuance();
  }

  @Test
  void invalidOtpBudgetPersistsThroughProxiedPrimaryDenialAndExhaustsExistingFiveAttemptLimit() {
    var f =
        new AccountControlUiOwnerSourcesFixture(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), temporary, true);
    var environment = f.terms.captureCurrentEnvironmentBoundary();
    var original = f.challenges.findByAccountId(f.account.getId()).orElseThrow();
    try (var ignored = withPeer(CALLER)) {
      for (int attempt = 1; attempt <= 5; attempt++) {
        assertThatThrownBy(() -> f.service.issue(f.request("wrong-otp"), environment))
            .isInstanceOf(AuthenticationException.class);
        if (attempt < 5) {
          var retained = f.challenges.findByAccountId(f.account.getId()).orElseThrow();
          assertThat(retained.getId()).isEqualTo(original.getId());
          assertThat(retained.getCodeHash()).isEqualTo(original.getCodeHash());
          assertThat(retained.getInvalidAttemptCount()).isEqualTo(attempt);
        } else {
          assertThat(f.challenges.findByAccountId(f.account.getId())).isEmpty();
        }
      }
      assertThatThrownBy(() -> f.service.issue(f.request(OTP), environment))
          .isInstanceOf(AuthenticationException.class);
    }
    f.assertNoIssuance();
  }

  @Test
  void concurrentInvalidAttemptsBothCommitTheirOwnAccountingWithoutMinting() throws Exception {
    var f =
        new AccountControlUiOwnerSourcesFixture(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), temporary, true);
    var environment = f.terms.captureCurrentEnvironmentBoundary();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<AuthenticationException> attempt =
          () -> {
            try (var ignored = withPeer(CALLER)) {
              ready.countDown();
              awaitLatch(start);
              try {
                f.service.issue(f.request("concurrent-wrong-otp"), environment);
                throw new AssertionError("Invalid credential must not authenticate");
              } catch (AuthenticationException expected) {
                return expected;
              }
            }
          };
      var first = executor.submit(attempt);
      var second = executor.submit(attempt);
      awaitLatch(ready);
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
      assertThat(second.get(10, TimeUnit.SECONDS)).isNotNull();
      assertThat(
              f.challenges
                  .findByAccountId(f.account.getId())
                  .orElseThrow()
                  .getInvalidAttemptCount())
          .isEqualTo(2);
      f.assertNoIssuance();
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Expected concurrent credential attempt boundary");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted concurrent credential attempt boundary");
    }
  }

  @Test
  void changedProtectedCallerAndUnboundCurrentEnvironmentDenyWithoutConsumingOtp() {
    var f =
        new AccountControlUiOwnerSourcesFixture(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), temporary, true);
    var environment = f.terms.captureCurrentEnvironmentBoundary();
    var originalOtp = f.challenges.findByAccountId(f.account.getId()).orElseThrow();
    try (var ignored =
        withPeer("spiffe://firemud/ns/control-ui-owner-proof/sa/game-design-service")) {
      assertThatThrownBy(() -> f.service.issue(f.request(OTP), environment))
          .isInstanceOf(IllegalStateException.class);
    }
    // Explicit test-only protected environment observation now identifies a different scope.
    // The actual current owner binding repository has no matching publication; no fallback exists.
    f.boundary.set(testBoundary("test-withdrawn"));
    CapturedEnvironmentBoundary changed = f.terms.captureCurrentEnvironmentBoundary();
    try (var ignored = withPeer(CALLER)) {
      assertThatThrownBy(() -> f.service.issue(f.request(OTP), changed))
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(f.challenges.findByAccountId(f.account.getId())).contains(originalOtp);
    f.assertNoIssuance();
  }

  private static HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary testBoundary(
      String name) {
    return AccountControlUiOwnerSourcesFixture.testBoundary(name);
  }

  static AccountControlUiOwnerSourcesFixture.PeerScope withPeer(String uri) {
    return AccountControlUiOwnerSourcesFixture.withPeer(uri);
  }
}
