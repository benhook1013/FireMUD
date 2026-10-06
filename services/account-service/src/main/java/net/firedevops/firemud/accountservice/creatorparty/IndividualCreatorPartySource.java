package net.firedevops.firemud.accountservice.creatorparty;

import java.util.UUID;

/** Opaque owner evidence, not legal identity facts or a caller-supplied verification decision. */
public record IndividualCreatorPartySource(
    UUID creatorPartyId,
    UUID accountId,
    VerificationStatus verificationStatus,
    long identityVersion,
    String policyReference,
    Long policyVersion,
    String verificationEvidenceReference,
    Long verificationEvidenceVersion,
    long sourceVersion) {
  public enum VerificationStatus {
    UNVERIFIED,
    VERIFIED,
    UNSUPPORTED
  }

  public IndividualCreatorPartySource {
    CreatorPartyEncoding.requireUuid(creatorPartyId);
    CreatorPartyEncoding.requireUuid(accountId);
    java.util.Objects.requireNonNull(verificationStatus);
    if (identityVersion <= 0 || sourceVersion != 1) {
      throw new IllegalArgumentException(
          "Only a positive identity and initial source are supported");
    }
    requireReference(policyReference, policyVersion);
    requireReference(verificationEvidenceReference, verificationEvidenceVersion);
    if (verificationStatus == VerificationStatus.VERIFIED
        && (policyReference == null || verificationEvidenceReference == null)) {
      throw new IllegalArgumentException("Verified individual requires exact policy and evidence");
    }
  }

  public boolean locallyVerified() {
    return verificationStatus == VerificationStatus.VERIFIED;
  }

  private static void requireReference(String reference, Long version) {
    if ((reference == null) != (version == null)
        || (reference != null
            && (reference.isBlank() || reference.length() > 512 || version <= 0))) {
      throw new IllegalArgumentException("Opaque reference requires its exact positive version");
    }
    if (reference != null) {
      net.firedevops.firemud.common.tenant.GameTenantCreationDigest.utf8ByteLength(reference);
    }
  }
}
