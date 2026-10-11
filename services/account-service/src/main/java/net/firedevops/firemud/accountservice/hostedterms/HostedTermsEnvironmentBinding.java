package net.firedevops.firemud.accountservice.hostedterms;

import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;

/**
 * Immutable Account receipt binding one canonical environment boundary to an exact hosted terms
 * source. This is owner evidence only; it does not establish production publisher custody.
 */
public record HostedTermsEnvironmentBinding(
    UUID bindingId,
    UUID publicationRequestId,
    String environmentBoundary,
    UUID hostedScopeId,
    String operatorLegalIdentity,
    long operatorIdentityVersion,
    UUID catalogVersionId,
    long catalogSourceVersion,
    String authenticatedPublisherIdentity,
    String publicationEventIdentity,
    String publicationEvidenceDigest,
    UUID predecessorBindingId,
    Long predecessorSourceVersion,
    long sourceVersion) {

  public HostedTermsEnvironmentBinding {
    HostedTermsEncoding.requireUuid(bindingId, "environment-binding receipt");
    HostedTermsEncoding.requireUuid(
        publicationRequestId, "environment-binding publication request");
    environmentBoundary = requireBoundary(environmentBoundary);
    HostedTermsEncoding.requireUuid(hostedScopeId, "hosted scope");
    operatorLegalIdentity = HostedTermsEncoding.requireText(operatorLegalIdentity, 2048);
    HostedTermsEncoding.requirePositive(operatorIdentityVersion, "operator identity version");
    HostedTermsEncoding.requireUuid(catalogVersionId, "catalog version");
    HostedTermsEncoding.requirePositive(catalogSourceVersion, "catalog source version");
    authenticatedPublisherIdentity =
        HostedTermsEncoding.requireText(authenticatedPublisherIdentity, 512);
    publicationEventIdentity = HostedTermsEncoding.requireText(publicationEventIdentity, 512);
    publicationEvidenceDigest = HostedTermsEncoding.requireDigest(publicationEvidenceDigest);
    if ((predecessorBindingId == null) != (predecessorSourceVersion == null)) {
      throw new IllegalArgumentException(
          "Binding predecessor identity and source version must be present together");
    }
    if (predecessorBindingId == null) {
      if (sourceVersion != 1) {
        throw new IllegalArgumentException(
            "Initial environment binding cannot fabricate a predecessor source");
      }
    } else {
      HostedTermsEncoding.requireUuid(predecessorBindingId, "predecessor binding");
      HostedTermsEncoding.requirePositive(predecessorSourceVersion, "predecessor source version");
      if (sourceVersion != Math.addExact(predecessorSourceVersion, 1)) {
        throw new IllegalArgumentException(
            "Environment binding source must advance from its exact predecessor");
      }
    }
    HostedTermsEncoding.requirePositive(sourceVersion, "environment binding source version");
  }

  /**
   * Exact independent fence key for this environment mapping. Its namespace is disjoint from the
   * UUID hosted-scope key; its sourceVersion is the binding counter, not catalog materiality.
   */
  public String sourceScopeId() {
    return "environment-boundary/" + environmentBoundary;
  }

  public SourceEvidence sourceEvidence() {
    byte[] bytes = HostedTermsEnvironmentBindingEncoding.receipt(this);
    return new SourceEvidence(
        SourceKind.HOSTED_TERMS,
        sourceScopeId(),
        null,
        Long.toString(sourceVersion),
        null,
        null,
        bytes);
  }

  /**
   * Exact result supplied by the trusted environment/secrets owner outside the Account transaction.
   * The Account workflow separately checks it against the current local catalog and binding head.
   */
  public record PublicationEvidence(
      String environmentBoundary,
      UUID hostedScopeId,
      String operatorLegalIdentity,
      long operatorIdentityVersion,
      UUID catalogVersionId,
      long catalogSourceVersion,
      String authenticatedPublisherIdentity,
      String publicationEventIdentity,
      UUID predecessorBindingId,
      Long predecessorSourceVersion) {
    public PublicationEvidence {
      environmentBoundary = requireBoundary(environmentBoundary);
      HostedTermsEncoding.requireUuid(hostedScopeId, "hosted scope");
      operatorLegalIdentity = HostedTermsEncoding.requireText(operatorLegalIdentity, 2048);
      HostedTermsEncoding.requirePositive(operatorIdentityVersion, "operator identity version");
      HostedTermsEncoding.requireUuid(catalogVersionId, "catalog version");
      HostedTermsEncoding.requirePositive(catalogSourceVersion, "catalog source version");
      authenticatedPublisherIdentity =
          HostedTermsEncoding.requireText(authenticatedPublisherIdentity, 512);
      publicationEventIdentity = HostedTermsEncoding.requireText(publicationEventIdentity, 512);
      if ((predecessorBindingId == null) != (predecessorSourceVersion == null)) {
        throw new IllegalArgumentException(
            "Binding predecessor identity and source version must be present together");
      }
      if (predecessorBindingId != null) {
        HostedTermsEncoding.requireUuid(predecessorBindingId, "predecessor binding");
        HostedTermsEncoding.requirePositive(predecessorSourceVersion, "predecessor source version");
      }
    }
  }

  /**
   * Fresh canonical environment-boundary observation supplied by the trusted environment owner;
   * callers do not select a boundary UUID or name.
   */
  public record CurrentEnvironmentBoundary(
      String environmentBoundary,
      String authenticatedOwnerIdentity,
      String observationEventIdentity,
      byte[] exactOwnerEvidence,
      String ownerEvidenceDigest) {
    public CurrentEnvironmentBoundary {
      environmentBoundary = requireBoundary(environmentBoundary);
      authenticatedOwnerIdentity = HostedTermsEncoding.requireText(authenticatedOwnerIdentity, 512);
      observationEventIdentity = HostedTermsEncoding.requireText(observationEventIdentity, 512);
      exactOwnerEvidence = HostedTermsEncoding.requireBytes(exactOwnerEvidence);
      ownerEvidenceDigest = HostedTermsEncoding.requireDigest(ownerEvidenceDigest);
      if (!HostedTermsEncoding.digest(exactOwnerEvidence).equals(ownerEvidenceDigest)) {
        throw new IllegalArgumentException("Environment-owner evidence digest conflicts");
      }
    }

    @Override
    public byte[] exactOwnerEvidence() {
      return exactOwnerEvidence.clone();
    }
  }

  private static String requireBoundary(String value) {
    return HostedTermsEncoding.requireText(value, 512);
  }
}
