package net.firedevops.firemud.accountservice.hostedterms;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable Account-owned document and source version; publication is not proof of legal review.
 */
public record HostedTermsCatalogVersion(
    UUID versionId,
    UUID hostedScopeId,
    UUID predecessorVersionId,
    String operatorLegalIdentity,
    long operatorIdentityVersion,
    byte[] documentBytes,
    String documentDigest,
    long sourceVersion,
    long materialGeneration,
    Materiality materiality,
    String materialityEvidenceReference,
    Long materialityEvidenceVersion,
    String publicationEvidenceReference,
    long publicationEvidenceVersion,
    String noticeEvidenceReference,
    long noticeEvidenceVersion,
    Instant effectiveAt) {

  public enum Materiality {
    INITIAL,
    MATERIAL,
    NONMATERIAL
  }

  public HostedTermsCatalogVersion {
    HostedTermsEncoding.requireUuid(versionId, "terms version");
    HostedTermsEncoding.requireUuid(hostedScopeId, "hosted scope");
    if (predecessorVersionId != null) {
      HostedTermsEncoding.requireUuid(predecessorVersionId, "predecessor version");
    }
    operatorLegalIdentity = HostedTermsEncoding.requireText(operatorLegalIdentity, 2048);
    if (operatorIdentityVersion <= 0 || sourceVersion <= 0 || materialGeneration <= 0) {
      throw new IllegalArgumentException(
          "Positive independent source and identity versions required");
    }
    documentBytes = HostedTermsEncoding.requireDocument(documentBytes);
    documentDigest = HostedTermsEncoding.requireDigest(documentDigest);
    if (!HostedTermsEncoding.digest(documentBytes).equals(documentDigest)) {
      throw new IllegalArgumentException(
          "Document digest must bind the exact stored document bytes");
    }
    Objects.requireNonNull(materiality, "materiality classification is required");
    Objects.requireNonNull(effectiveAt, "disclosed effective instant is required");
    if (effectiveAt.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException(
          "Effective instant must preserve PostgreSQL microsecond precision");
    }
    publicationEvidenceReference =
        HostedTermsEncoding.requireText(publicationEvidenceReference, 512);
    if (publicationEvidenceVersion <= 0) {
      throw new IllegalArgumentException("Positive publication evidence version required");
    }
    noticeEvidenceReference = HostedTermsEncoding.requireText(noticeEvidenceReference, 512);
    if (noticeEvidenceVersion <= 0) {
      throw new IllegalArgumentException("Positive disclosed-notice evidence version required");
    }
    if (predecessorVersionId == null) {
      if (materiality != Materiality.INITIAL
          || sourceVersion != 1
          || materialGeneration != 1
          || materialityEvidenceReference != null
          || materialityEvidenceVersion != null) {
        throw new IllegalArgumentException(
            "Initial catalog birth cannot fabricate predecessor state");
      }
    } else {
      materialityEvidenceReference =
          HostedTermsEncoding.requireText(materialityEvidenceReference, 512);
      if (materialityEvidenceVersion == null
          || materialityEvidenceVersion <= 0
          || materiality == Materiality.INITIAL) {
        throw new IllegalArgumentException("Audited materiality classification is required");
      }
    }
  }

  @Override
  public byte[] documentBytes() {
    return documentBytes.clone();
  }

  public boolean hasOperatorIdentityOf(HostedTermsCatalogVersion other) {
    return other != null
        && operatorIdentityVersion == other.operatorIdentityVersion
        && operatorLegalIdentity.equals(other.operatorLegalIdentity);
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof HostedTermsCatalogVersion that)) {
      return false;
    }
    return versionId.equals(that.versionId)
        && hostedScopeId.equals(that.hostedScopeId)
        && Objects.equals(predecessorVersionId, that.predecessorVersionId)
        && operatorLegalIdentity.equals(that.operatorLegalIdentity)
        && operatorIdentityVersion == that.operatorIdentityVersion
        && Arrays.equals(documentBytes, that.documentBytes)
        && documentDigest.equals(that.documentDigest)
        && sourceVersion == that.sourceVersion
        && materialGeneration == that.materialGeneration
        && materiality == that.materiality
        && Objects.equals(materialityEvidenceReference, that.materialityEvidenceReference)
        && Objects.equals(materialityEvidenceVersion, that.materialityEvidenceVersion)
        && publicationEvidenceReference.equals(that.publicationEvidenceReference)
        && publicationEvidenceVersion == that.publicationEvidenceVersion
        && noticeEvidenceReference.equals(that.noticeEvidenceReference)
        && noticeEvidenceVersion == that.noticeEvidenceVersion
        && effectiveAt.equals(that.effectiveAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        versionId,
        hostedScopeId,
        predecessorVersionId,
        operatorLegalIdentity,
        operatorIdentityVersion,
        Arrays.hashCode(documentBytes),
        documentDigest,
        sourceVersion,
        materialGeneration,
        materiality,
        materialityEvidenceReference,
        materialityEvidenceVersion,
        publicationEvidenceReference,
        publicationEvidenceVersion,
        noticeEvidenceReference,
        noticeEvidenceVersion,
        effectiveAt);
  }
}
