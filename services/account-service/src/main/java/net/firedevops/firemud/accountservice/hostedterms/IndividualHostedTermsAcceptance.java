package net.firedevops.firemud.accountservice.hostedterms;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only party-wide affirmative acceptance with the exact local individual source captured.
 */
public record IndividualHostedTermsAcceptance(
    UUID evidenceId,
    UUID actionRequestId,
    UUID creatorPartyId,
    UUID accountId,
    UUID hostedScopeId,
    UUID termsVersionId,
    String documentDigest,
    String operatorLegalIdentity,
    long operatorIdentityVersion,
    long sourceVersion,
    long materialGeneration,
    byte[] individualPartySource,
    String individualPartySourceDigest,
    byte[] affirmativeActionEvidence,
    String affirmativeActionDigest,
    OffsetDateTime acceptedAt) {

  public IndividualHostedTermsAcceptance {
    HostedTermsEncoding.requireUuid(evidenceId, "acceptance evidence");
    HostedTermsEncoding.requireUuid(actionRequestId, "acceptance action request");
    HostedTermsEncoding.requireUuid(creatorPartyId, "creator party");
    HostedTermsEncoding.requireUuid(accountId, "accepting Account");
    HostedTermsEncoding.requireUuid(hostedScopeId, "hosted scope");
    HostedTermsEncoding.requireUuid(termsVersionId, "accepted terms version");
    documentDigest = HostedTermsEncoding.requireDigest(documentDigest);
    operatorLegalIdentity = HostedTermsEncoding.requireText(operatorLegalIdentity, 2048);
    if (operatorIdentityVersion <= 0 || sourceVersion <= 0 || materialGeneration <= 0) {
      throw new IllegalArgumentException("Positive operator and currentness versions required");
    }
    individualPartySource = HostedTermsEncoding.requireBytes(individualPartySource);
    individualPartySourceDigest = HostedTermsEncoding.requireDigest(individualPartySourceDigest);
    affirmativeActionEvidence = HostedTermsEncoding.requireBytes(affirmativeActionEvidence);
    affirmativeActionDigest = HostedTermsEncoding.requireDigest(affirmativeActionDigest);
    Objects.requireNonNull(acceptedAt, "database acceptance time is required");
    if (!HostedTermsEncoding.digest(individualPartySource).equals(individualPartySourceDigest)
        || !HostedTermsEncoding.digest(affirmativeActionEvidence).equals(affirmativeActionDigest)) {
      throw new IllegalArgumentException("Acceptance evidence digest does not match exact bytes");
    }
  }

  @Override
  public byte[] individualPartySource() {
    return individualPartySource.clone();
  }

  @Override
  public byte[] affirmativeActionEvidence() {
    return affirmativeActionEvidence.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof IndividualHostedTermsAcceptance that)) {
      return false;
    }
    return evidenceId.equals(that.evidenceId)
        && actionRequestId.equals(that.actionRequestId)
        && creatorPartyId.equals(that.creatorPartyId)
        && accountId.equals(that.accountId)
        && hostedScopeId.equals(that.hostedScopeId)
        && termsVersionId.equals(that.termsVersionId)
        && documentDigest.equals(that.documentDigest)
        && operatorLegalIdentity.equals(that.operatorLegalIdentity)
        && operatorIdentityVersion == that.operatorIdentityVersion
        && sourceVersion == that.sourceVersion
        && materialGeneration == that.materialGeneration
        && Arrays.equals(individualPartySource, that.individualPartySource)
        && individualPartySourceDigest.equals(that.individualPartySourceDigest)
        && Arrays.equals(affirmativeActionEvidence, that.affirmativeActionEvidence)
        && affirmativeActionDigest.equals(that.affirmativeActionDigest)
        && acceptedAt.equals(that.acceptedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        evidenceId,
        actionRequestId,
        creatorPartyId,
        accountId,
        hostedScopeId,
        termsVersionId,
        documentDigest,
        operatorLegalIdentity,
        operatorIdentityVersion,
        sourceVersion,
        materialGeneration,
        Arrays.hashCode(individualPartySource),
        individualPartySourceDigest,
        Arrays.hashCode(affirmativeActionEvidence),
        affirmativeActionDigest,
        acceptedAt);
  }
}
