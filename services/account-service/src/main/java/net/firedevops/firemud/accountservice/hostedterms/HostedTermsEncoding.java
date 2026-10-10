package net.firedevops.firemud.accountservice.hostedterms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/** Exact closed encodings for Account-owned hosted terms, publication requests and acceptance. */
public final class HostedTermsEncoding {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HostedTermsEncoding() {}

  public static byte[] publication(
      UUID requestId, AccountHostedTermsService.PublicationEvidence publication) {
    requireUuid(requestId, "publication request");
    Objects.requireNonNull(publication);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "account-hosted-terms-publication/v1");
    fields.put("requestId", requestId.toString());
    fields.put("hostedScopeId", publication.hostedScopeId().toString());
    fields.put("operatorLegalIdentity", publication.operatorLegalIdentity());
    fields.put("operatorIdentityVersion", Long.toString(publication.operatorIdentityVersion()));
    fields.put("document", Base64.getEncoder().encodeToString(publication.documentBytes()));
    fields.put("documentDigest", digest(publication.documentBytes()));
    fields.put("materiality", publication.materiality().name());
    fields.put("materialityEvidenceReference", publication.materialityEvidenceReference());
    fields.put(
        "materialityEvidenceVersion",
        publication.materialityEvidenceVersion() == null
            ? null
            : publication.materialityEvidenceVersion().toString());
    fields.put("publicationEvidenceReference", publication.publicationEvidenceReference());
    fields.put(
        "publicationEvidenceVersion", Long.toString(publication.publicationEvidenceVersion()));
    fields.put("noticeEvidenceReference", publication.noticeEvidenceReference());
    fields.put("noticeEvidenceVersion", Long.toString(publication.noticeEvidenceVersion()));
    fields.put("effectiveAt", publication.effectiveAt().toString());
    return canonical(fields);
  }

  public static byte[] catalog(HostedTermsCatalogVersion version) {
    Objects.requireNonNull(version);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "account-hosted-terms-catalog-version/v1");
    fields.put("versionId", version.versionId().toString());
    fields.put("hostedScopeId", version.hostedScopeId().toString());
    fields.put(
        "predecessorVersionId",
        version.predecessorVersionId() == null ? null : version.predecessorVersionId().toString());
    fields.put("operatorLegalIdentity", version.operatorLegalIdentity());
    fields.put("operatorIdentityVersion", Long.toString(version.operatorIdentityVersion()));
    fields.put("document", Base64.getEncoder().encodeToString(version.documentBytes()));
    fields.put("documentDigest", version.documentDigest());
    fields.put("sourceVersion", Long.toString(version.sourceVersion()));
    fields.put("materialGeneration", Long.toString(version.materialGeneration()));
    fields.put("materiality", version.materiality().name());
    fields.put("materialityEvidenceReference", version.materialityEvidenceReference());
    fields.put(
        "materialityEvidenceVersion",
        version.materialityEvidenceVersion() == null
            ? null
            : version.materialityEvidenceVersion().toString());
    fields.put("publicationEvidenceReference", version.publicationEvidenceReference());
    fields.put("publicationEvidenceVersion", Long.toString(version.publicationEvidenceVersion()));
    fields.put("noticeEvidenceReference", version.noticeEvidenceReference());
    fields.put("noticeEvidenceVersion", Long.toString(version.noticeEvidenceVersion()));
    fields.put("effectiveAt", version.effectiveAt().toString());
    return canonical(fields);
  }

  public static byte[] individualParty(IndividualCreatorPartySource source) {
    return CreatorPartyEncoding.party(Objects.requireNonNull(source));
  }

  public static byte[] affirmativeAction(AccountHostedTermsService.AcceptanceAction action) {
    Objects.requireNonNull(action);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "account-hosted-terms-affirmative-action/v1");
    fields.put("actionRequestId", action.actionRequestId().toString());
    fields.put("affirmative", Boolean.toString(action.affirmative()));
    fields.put("accountId", action.accountId().toString());
    fields.put("creatorPartyId", action.creatorPartyId().toString());
    fields.put("hostedScopeId", action.hostedScopeId().toString());
    fields.put("shownVersionId", action.shownVersionId().toString());
    fields.put("shownDocumentDigest", action.shownDocumentDigest());
    fields.put("shownOperatorLegalIdentity", action.shownOperatorLegalIdentity());
    fields.put(
        "shownOperatorIdentityVersion", Long.toString(action.shownOperatorIdentityVersion()));
    fields.put("authenticatedActionReference", action.authenticatedActionReference());
    fields.put("authenticatedActionVersion", Long.toString(action.authenticatedActionVersion()));
    fields.put("createdAt", action.actionCreatedAt().toString());
    return canonical(fields);
  }

  public static byte[] acceptance(IndividualHostedTermsAcceptance acceptance) {
    Objects.requireNonNull(acceptance);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "account-individual-hosted-terms-acceptance/v1");
    fields.put("evidenceId", acceptance.evidenceId().toString());
    fields.put("actionRequestId", acceptance.actionRequestId().toString());
    fields.put("creatorPartyId", acceptance.creatorPartyId().toString());
    fields.put("accountId", acceptance.accountId().toString());
    fields.put("hostedScopeId", acceptance.hostedScopeId().toString());
    fields.put("termsVersionId", acceptance.termsVersionId().toString());
    fields.put("documentDigest", acceptance.documentDigest());
    fields.put("operatorLegalIdentity", acceptance.operatorLegalIdentity());
    fields.put("operatorIdentityVersion", Long.toString(acceptance.operatorIdentityVersion()));
    fields.put("sourceVersion", Long.toString(acceptance.sourceVersion()));
    fields.put("materialGeneration", Long.toString(acceptance.materialGeneration()));
    fields.put(
        "individualPartySource",
        Base64.getEncoder().encodeToString(acceptance.individualPartySource()));
    fields.put("individualPartySourceDigest", acceptance.individualPartySourceDigest());
    fields.put(
        "affirmativeActionEvidence",
        Base64.getEncoder().encodeToString(acceptance.affirmativeActionEvidence()));
    fields.put("affirmativeActionDigest", acceptance.affirmativeActionDigest());
    fields.put("acceptedAt", acceptance.acceptedAt().toString());
    return canonical(fields);
  }

  /** Exact hosted source bytes include the catalog head and the affirmative acceptance receipt. */
  public static byte[] currentnessSource(
      HostedTermsCatalogVersion catalog, IndividualHostedTermsAcceptance acceptance) {
    return currentnessSource(catalog, acceptance, null);
  }

  /**
   * Exact currentness source also retains any disclosed future catalog and its immutable deadline.
   */
  public static byte[] currentnessSource(
      HostedTermsCatalogVersion catalog,
      IndividualHostedTermsAcceptance acceptance,
      HostedTermsCatalogVersion disclosedDeadline) {
    Objects.requireNonNull(catalog);
    Objects.requireNonNull(acceptance);
    if (!catalog.hostedScopeId().equals(acceptance.hostedScopeId())
        || catalog.materialGeneration() != acceptance.materialGeneration()
        || !catalog.operatorLegalIdentity().equals(acceptance.operatorLegalIdentity())
        || catalog.operatorIdentityVersion() != acceptance.operatorIdentityVersion()) {
      throw new IllegalArgumentException(
          "Currentness source requires one exact catalog and affirmative acceptance");
    }
    if (disclosedDeadline != null) {
      long expectedGeneration =
          Math.addExact(
              catalog.materialGeneration(),
              disclosedDeadline.materiality() == HostedTermsCatalogVersion.Materiality.MATERIAL
                  ? 1
                  : 0);
      if (!catalog.hostedScopeId().equals(disclosedDeadline.hostedScopeId())
          || !Objects.equals(disclosedDeadline.predecessorVersionId(), catalog.versionId())
          || disclosedDeadline.sourceVersion() != Math.addExact(catalog.sourceVersion(), 1)
          || disclosedDeadline.materialGeneration() != expectedGeneration
          || (disclosedDeadline.materiality() != HostedTermsCatalogVersion.Materiality.MATERIAL
              && !disclosedDeadline.hasOperatorIdentityOf(catalog))
          || !disclosedDeadline.effectiveAt().isAfter(catalog.effectiveAt())) {
        throw new IllegalArgumentException(
            "Disclosed deadline must be the exact immutable next catalog version");
      }
    }
    byte[] catalogBytes = catalog(catalog);
    byte[] acceptanceBytes = acceptance(acceptance);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "account-hosted-terms-currentness-source/v2");
    fields.put("hostedScopeId", catalog.hostedScopeId().toString());
    fields.put("catalog", Base64.getEncoder().encodeToString(catalogBytes));
    fields.put("catalogDigest", digest(catalogBytes));
    fields.put("acceptance", Base64.getEncoder().encodeToString(acceptanceBytes));
    fields.put("acceptanceDigest", digest(acceptanceBytes));
    byte[] deadlineBytes = disclosedDeadline == null ? null : catalog(disclosedDeadline);
    fields.put(
        "disclosedDeadlineCatalog",
        deadlineBytes == null ? null : Base64.getEncoder().encodeToString(deadlineBytes));
    fields.put(
        "disclosedDeadlineCatalogDigest", deadlineBytes == null ? null : digest(deadlineBytes));
    return canonical(fields);
  }

  public static String digest(byte[] bytes) {
    Objects.requireNonNull(bytes);
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }

  public static UUID requireUuid(UUID value, String description) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical nonnil " + description + " UUID is required");
    }
    return value;
  }

  public static byte[] requireDocument(byte[] value) {
    return requireBytes(value);
  }

  public static byte[] requireBytes(byte[] value) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException("Nonempty exact evidence bytes are required");
    }
    return value.clone();
  }

  public static String requireText(String value, int maxUtf8Bytes) {
    if (value == null
        || value.isBlank()
        || value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes) {
      throw new IllegalArgumentException("Required exact reference or identity text is invalid");
    }
    return value;
  }

  public static String requireDigest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Canonical SHA-256 digest is required");
    }
    return value;
  }

  public static long requirePositive(long value, String description) {
    if (value <= 0) {
      throw new IllegalArgumentException("Positive " + description + " is required");
    }
    return value;
  }

  private static byte[] canonical(Map<String, Object> fields) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fields));
    } catch (IOException failure) {
      throw new IllegalArgumentException("Hosted-terms evidence cannot be encoded", failure);
    }
  }
}
