package unit.net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import net.firedevops.firemud.accountservice.hostedterms.AccountDeploymentEnvironmentBoundaryProvider;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding;
import org.junit.jupiter.api.Test;

class AccountDeploymentEnvironmentBoundaryProviderTest {
  private static final String ENVIRONMENT = "production-eu";
  private static final String OWNER = "deployment-owner/cluster-eu";
  private static final String EVENT = "environment-observation/84";
  private static final byte[] OWNER_EVIDENCE = "owner-evidence-v1".getBytes(StandardCharsets.UTF_8);
  private static final String OWNER_EVIDENCE_DIGEST = HostedTermsEncoding.digest(OWNER_EVIDENCE);

  @Test
  void returnsOnlyTheExactFixedDeploymentBoundaryAndHasNoRequestSelector() throws Exception {
    var provider = provider(evidence(OWNER_EVIDENCE));

    var captured = provider.currentBoundary();

    assertThat(captured.environmentBoundary()).isEqualTo(ENVIRONMENT);
    assertThat(captured.authenticatedOwnerIdentity()).isEqualTo(OWNER);
    assertThat(captured.observationEventIdentity()).isEqualTo(EVENT);
    assertThat(captured.ownerEvidenceDigest()).isEqualTo(OWNER_EVIDENCE_DIGEST);
    assertThat(captured.exactOwnerEvidence()).isEqualTo(OWNER_EVIDENCE);
    assertThat(
            AccountHostedTermsService.CurrentEnvironmentBoundaryAuthority.class
                .getMethod("currentBoundary")
                .getParameterCount())
        .isZero();
  }

  @Test
  void rejectsEveryExpectedFieldMismatch() {
    var evidence = evidence(OWNER_EVIDENCE);
    String otherDigest =
        HostedTermsEncoding.digest("different-evidence".getBytes(StandardCharsets.UTF_8));

    assertMismatch("another-environment", OWNER, EVENT, OWNER_EVIDENCE_DIGEST, evidence);
    assertMismatch(ENVIRONMENT, "another-owner", EVENT, OWNER_EVIDENCE_DIGEST, evidence);
    assertMismatch(ENVIRONMENT, OWNER, "another-event", OWNER_EVIDENCE_DIGEST, evidence);
    assertMismatch(ENVIRONMENT, OWNER, EVENT, otherDigest, evidence);
  }

  @Test
  void rejectsAbsentExpectedValuesAndEvidence() {
    assertThatThrownBy(
            () ->
                new AccountDeploymentEnvironmentBoundaryProvider(
                    null, OWNER, EVENT, OWNER_EVIDENCE_DIGEST, evidence(OWNER_EVIDENCE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountDeploymentEnvironmentBoundaryProvider(
                    ENVIRONMENT, null, EVENT, OWNER_EVIDENCE_DIGEST, evidence(OWNER_EVIDENCE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountDeploymentEnvironmentBoundaryProvider(
                    ENVIRONMENT, OWNER, null, OWNER_EVIDENCE_DIGEST, evidence(OWNER_EVIDENCE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountDeploymentEnvironmentBoundaryProvider(
                    ENVIRONMENT, OWNER, EVENT, null, evidence(OWNER_EVIDENCE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountDeploymentEnvironmentBoundaryProvider(
                    ENVIRONMENT, OWNER, EVENT, OWNER_EVIDENCE_DIGEST, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void keepsAnIndependentImmutableCopyOfTheSuppliedEvidenceBytes() {
    byte[] callerBytes = OWNER_EVIDENCE.clone();
    var suppliedEvidence = evidence(callerBytes);
    var provider = provider(suppliedEvidence);
    callerBytes[0] ^= 0x7f;

    byte[] returnedBytes = provider.currentBoundary().exactOwnerEvidence();
    returnedBytes[0] ^= 0x7f;

    assertThat(provider.currentBoundary().exactOwnerEvidence()).isEqualTo(OWNER_EVIDENCE);
  }

  @Test
  void existingBoundaryRecordRejectsBytesWithAConflictingDigest() {
    assertThatThrownBy(
            () ->
                new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
                    ENVIRONMENT,
                    OWNER,
                    EVENT,
                    "different-evidence".getBytes(StandardCharsets.UTF_8),
                    OWNER_EVIDENCE_DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Environment-owner evidence digest conflicts");
  }

  private static AccountDeploymentEnvironmentBoundaryProvider provider(
      HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary evidence) {
    return new AccountDeploymentEnvironmentBoundaryProvider(
        ENVIRONMENT, OWNER, EVENT, OWNER_EVIDENCE_DIGEST, evidence);
  }

  private static HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary evidence(byte[] bytes) {
    return new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
        ENVIRONMENT, OWNER, EVENT, bytes, HostedTermsEncoding.digest(bytes));
  }

  private static void assertMismatch(
      String environment,
      String owner,
      String event,
      String digest,
      HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary evidence) {
    assertThatThrownBy(
            () ->
                new AccountDeploymentEnvironmentBoundaryProvider(
                    environment, owner, event, digest, evidence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Deployment environment evidence differs from the fixed server boundary");
  }
}
