package net.firedevops.firemud.accountservice.creatorparty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapDigest;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import tools.jackson.databind.json.JsonMapper;

/** Closed canonical encodings; every identity, reference and independent version is bound. */
public final class CreatorPartyEncoding {
  private CreatorPartyEncoding() {}

  public static byte[] party(IndividualCreatorPartySource source) {
    Objects.requireNonNull(source);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "account-individual-creator-party-source/v1");
    fields.put("creatorPartyId", source.creatorPartyId().toString());
    fields.put("accountId", source.accountId().toString());
    fields.put("verificationStatus", source.verificationStatus().name());
    fields.put("identityVersion", Long.toString(source.identityVersion()));
    fields.put("policyReference", source.policyReference());
    fields.put("policyVersion", decimal(source.policyVersion()));
    fields.put("verificationEvidenceReference", source.verificationEvidenceReference());
    fields.put("verificationEvidenceVersion", decimal(source.verificationEvidenceVersion()));
    fields.put("sourceVersion", Long.toString(source.sourceVersion()));
    return canonical(fields);
  }

  public static byte[] request(
      UUID requestId, FreshTenantCreatorEvidence creation, IndividualCreatorPartySource party) {
    requireUuid(requestId);
    Objects.requireNonNull(creation);
    Objects.requireNonNull(party);
    if (!creation.initiatingAccountId().equals(party.accountId())) {
      throw new IllegalArgumentException("Creator must bind their own individual party");
    }
    return canonical(
        Map.of(
            "schema",
            "account-fresh-creator-party-association-request/v1",
            "requestId",
            requestId.toString(),
            "creatorEvidence",
            new String(creation(creation), StandardCharsets.UTF_8),
            "individualPartySource",
            new String(party(party), StandardCharsets.UTF_8)));
  }

  public static byte[] result(UUID historyId, byte[] request) {
    requireUuid(historyId);
    Objects.requireNonNull(request);
    if (request.length == 0) {
      throw new IllegalArgumentException("Exact original request is required");
    }
    return canonical(
        Map.of(
            "schema",
            "account-fresh-creator-party-association-result/v1",
            "historyId",
            historyId.toString(),
            "associationSourceVersion",
            "1",
            "request",
            new String(request, StandardCharsets.UTF_8)));
  }

  public static byte[] creation(FreshTenantCreatorEvidence evidence) {
    return AccountTenantCreationBootstrapDigest.creatorEvidencePayload(evidence);
  }

  public static String digest(byte[] bytes) {
    Objects.requireNonNull(bytes);
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  static void requireUuid(UUID uuid) {
    if (uuid == null || uuid.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("Canonical nonnil identity is required");
    }
  }

  private static String decimal(Long value) {
    return value == null ? null : value.toString();
  }

  private static byte[] canonical(Map<String, Object> fields) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(
          JsonMapper.builder().build().writeValueAsString(fields));
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Creator-party evidence cannot be canonically encoded", exception);
    }
  }
}
