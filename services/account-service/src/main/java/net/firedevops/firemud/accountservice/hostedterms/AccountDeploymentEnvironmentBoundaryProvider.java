package net.firedevops.firemud.accountservice.hostedterms;

import java.util.Objects;

/**
 * Adapts one fixed deployment boundary and its supplied owner evidence to Account's
 * current-boundary interface.
 *
 * <p>This adapter does not authenticate, obtain, or refresh deployment evidence and does not
 * observe an external environment. Its inputs must come from trusted deployment-evidence delivery
 * and must already have passed that owner's machine validation. This class is intentionally
 * unregistered; runtime composition remains disabled until that trusted input source is supplied.
 * Account still independently locks and checks its current environment binding, catalog, operator,
 * and acceptance sources when authorizing a mutation.
 */
public final class AccountDeploymentEnvironmentBoundaryProvider
    implements AccountHostedTermsService.CurrentEnvironmentBoundaryAuthority {
  private final HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary capturedBoundary;

  /**
   * Captures a server-selected deployment boundary and its exact supplied owner evidence.
   *
   * @param environmentBoundary fixed environment boundary selected by server deployment policy
   * @param authenticatedOwnerIdentity expected identity of the deployment evidence owner
   * @param observationEventIdentity expected identity of the deployment observation event
   * @param ownerEvidenceDigest expected digest of the exact owner evidence
   * @param validatedDeploymentEvidence already machine-validated immutable evidence delivered by a
   *     trusted deployment source; this adapter only checks its exact binding and defensive-copies
   *     it
   * @throws IllegalArgumentException if any required expected value is absent or the supplied
   *     evidence does not exactly match the fixed deployment values
   * @throws NullPointerException if the supplied evidence record is absent
   */
  public AccountDeploymentEnvironmentBoundaryProvider(
      String environmentBoundary,
      String authenticatedOwnerIdentity,
      String observationEventIdentity,
      String ownerEvidenceDigest,
      HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary validatedDeploymentEvidence) {
    String expectedBoundary = HostedTermsEncoding.requireText(environmentBoundary, 512);
    String expectedOwner = HostedTermsEncoding.requireText(authenticatedOwnerIdentity, 512);
    String expectedEvent = HostedTermsEncoding.requireText(observationEventIdentity, 512);
    String expectedDigest = HostedTermsEncoding.requireDigest(ownerEvidenceDigest);
    Objects.requireNonNull(
        validatedDeploymentEvidence, "validated deployment evidence is required");

    byte[] exactOwnerEvidence = validatedDeploymentEvidence.exactOwnerEvidence();
    if (!expectedBoundary.equals(validatedDeploymentEvidence.environmentBoundary())
        || !expectedOwner.equals(validatedDeploymentEvidence.authenticatedOwnerIdentity())
        || !expectedEvent.equals(validatedDeploymentEvidence.observationEventIdentity())
        || !expectedDigest.equals(validatedDeploymentEvidence.ownerEvidenceDigest())
        || !expectedDigest.equals(HostedTermsEncoding.digest(exactOwnerEvidence))) {
      throw new IllegalArgumentException(
          "Deployment environment evidence differs from the fixed server boundary");
    }

    // Reconstruct through the existing immutable evidence record so its exact-byte and digest
    // validation remains the canonical shape check, and the provider owns an independent snapshot.
    this.capturedBoundary =
        new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
            expectedBoundary, expectedOwner, expectedEvent, exactOwnerEvidence, expectedDigest);
  }

  /** Returns the fixed captured boundary; request data cannot select or change its scope. */
  @Override
  public HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary currentBoundary() {
    return new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
        capturedBoundary.environmentBoundary(),
        capturedBoundary.authenticatedOwnerIdentity(),
        capturedBoundary.observationEventIdentity(),
        capturedBoundary.exactOwnerEvidence(),
        capturedBoundary.ownerEvidenceDigest());
  }
}
