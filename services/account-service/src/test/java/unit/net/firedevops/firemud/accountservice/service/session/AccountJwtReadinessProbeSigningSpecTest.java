package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ReadinessProbeSigningSpec;
import org.junit.jupiter.api.Test;

class AccountJwtReadinessProbeSigningSpecTest {
  @Test
  void acceptsAccountCanaryAndEveryProtectedProductionProfile() {
    assertValid(
        AccountMountedJwtSignerBundle.READINESS_VALIDATOR_ID,
        ProbeKind.CANARY,
        AccountMountedJwtSignerBundle.READINESS_CANARY_PROFILE,
        AccountMountedJwtSignerBundle.READINESS_CANARY_AUDIENCE);
    assertValid(
        AccountMountedJwtSignerBundle.READINESS_VALIDATOR_ID,
        ProbeKind.REPRESENTATIVE,
        "control-ui",
        "control-ui");
    assertValid(
        AccountMountedJwtSignerBundle.READINESS_VALIDATOR_ID,
        ProbeKind.REPRESENTATIVE,
        "player-bootstrap",
        "player-bootstrap");
    assertValid(
        AccountMountedJwtSignerBundle.READINESS_VALIDATOR_ID,
        ProbeKind.REPRESENTATIVE,
        AccountMountedJwtSignerBundle.REPRESENTATIVE_PROFILE,
        AccountMountedJwtSignerBundle.REPRESENTATIVE_AUDIENCE);
  }

  @Test
  void permitsCanonicalProfilesForGameSessionInapplicableRejectionProbes() {
    assertValid(
        AccountMountedJwtSignerBundle.GAME_SESSION_VALIDATOR_ID,
        ProbeKind.CANARY,
        AccountMountedJwtSignerBundle.READINESS_CANARY_PROFILE,
        AccountMountedJwtSignerBundle.READINESS_CANARY_AUDIENCE);
    assertValid(
        AccountMountedJwtSignerBundle.GAME_SESSION_VALIDATOR_ID,
        ProbeKind.REPRESENTATIVE,
        "control-ui",
        "control-ui");
    assertValid(
        AccountMountedJwtSignerBundle.GAME_SESSION_VALIDATOR_ID,
        ProbeKind.REPRESENTATIVE,
        "player-bootstrap",
        "player-bootstrap");
    assertValid(
        AccountMountedJwtSignerBundle.GAME_SESSION_VALIDATOR_ID,
        ProbeKind.REPRESENTATIVE,
        AccountMountedJwtSignerBundle.REPRESENTATIVE_PROFILE,
        AccountMountedJwtSignerBundle.REPRESENTATIVE_AUDIENCE);
  }

  @Test
  void rejectsUnknownValidatorAndMismatchedProfileAudienceCombinations() {
    assertInvalid(
        "unknown-service",
        ProbeKind.CANARY,
        AccountMountedJwtSignerBundle.READINESS_CANARY_PROFILE,
        AccountMountedJwtSignerBundle.READINESS_CANARY_AUDIENCE);
    assertInvalid(
        AccountMountedJwtSignerBundle.READINESS_VALIDATOR_ID,
        ProbeKind.REPRESENTATIVE,
        AccountMountedJwtSignerBundle.REPRESENTATIVE_PROFILE,
        "control-ui");
    assertInvalid(
        AccountMountedJwtSignerBundle.GAME_SESSION_VALIDATOR_ID,
        ProbeKind.REPRESENTATIVE,
        "player-bootstrap",
        "control-ui");
    assertInvalid(
        AccountMountedJwtSignerBundle.GAME_SESSION_VALIDATOR_ID,
        ProbeKind.CANARY,
        AccountMountedJwtSignerBundle.READINESS_CANARY_PROFILE,
        "account-service");
  }

  private static void assertValid(
      String validatorId, ProbeKind kind, String profile, String audience) {
    assertThatCode(() -> signingSpec(validatorId, kind, profile, audience))
        .doesNotThrowAnyException();
  }

  private static void assertInvalid(
      String validatorId, ProbeKind kind, String profile, String audience) {
    assertThatThrownBy(() -> signingSpec(validatorId, kind, profile, audience))
        .isInstanceOf(AccountMountedJwtSignerBundle.InvalidMountedSignerBundleException.class);
  }

  private static ReadinessProbeSigningSpec signingSpec(
      String validatorId, ProbeKind kind, String profile, String audience) {
    return new ReadinessProbeSigningSpec(
        validatorId, kind, profile, audience, UUID.randomUUID(), "1", "key-1", 20_300, 20_480);
  }
}
