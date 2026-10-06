package net.firedevops.firemud.common.publication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Immutable typed owner evidence shared by Game Design producers and runtime receivers. */
public record PublishedRealmEntryPolicyEvidence(
    UUID policyId,
    UUID canonicalTenantId,
    String tenantIdentityProvenanceKind,
    long sourceGameRowId,
    String sourceGameTenantKey,
    long versionId,
    int versionNumber,
    long sourceRevisionId,
    String releaseBundleIdentity,
    String publishWorkflowId,
    String manifestHash,
    RealmEntryPolicy policy,
    String policyDigest) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final int MAX_TENANT_KEY_CODE_POINTS = 36;
  private static final int MAX_TENANT_KEY_UTF16_LENGTH = 72;

  public PublishedRealmEntryPolicyEvidence {
    requireNonNil(policyId, "policyId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    if (!"NEW_GAME_ROW".equals(tenantIdentityProvenanceKind)
        && !"RETAINED_GAME_V29".equals(tenantIdentityProvenanceKind)) {
      throw new IllegalArgumentException("Tenant identity provenance kind is not recognized");
    }
    if (sourceGameRowId <= 0
        || sourceGameTenantKey == null
        || sourceGameTenantKey.isBlank()
        || sourceGameTenantKey.length() > MAX_TENANT_KEY_UTF16_LENGTH
        || sourceGameTenantKey.codePointCount(0, sourceGameTenantKey.length())
            > MAX_TENANT_KEY_CODE_POINTS
        || !StandardCharsets.UTF_8.newEncoder().canEncode(sourceGameTenantKey)
        || versionId <= 0
        || versionNumber <= 0
        || sourceRevisionId <= 0
        || releaseBundleIdentity == null
        || !releaseBundleIdentity.matches("sha256:[0-9a-f]{64}")
        || publishWorkflowId == null
        || publishWorkflowId.isBlank()
        || manifestHash == null
        || manifestHash.isBlank()) {
      throw new IllegalArgumentException("Published realm-entry policy evidence is incomplete");
    }
    Objects.requireNonNull(policy, "policy");
    if (policyDigest == null || !policyDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Published realm-entry policy digest is malformed");
    }
  }

  public static PublishedRealmEntryPolicyEvidence create(
      UUID policyId,
      UUID canonicalTenantId,
      String tenantIdentityProvenanceKind,
      long sourceGameRowId,
      String sourceGameTenantKey,
      long versionId,
      int versionNumber,
      long sourceRevisionId,
      String releaseBundleIdentity,
      String publishWorkflowId,
      String manifestHash,
      RealmEntryPolicy policy,
      ObjectMapper objectMapper) {
    requireCanonicalPolicy(policy, objectMapper);
    String digest =
        calculateDigest(
            policyId,
            canonicalTenantId,
            tenantIdentityProvenanceKind,
            sourceGameRowId,
            sourceGameTenantKey,
            versionId,
            versionNumber,
            sourceRevisionId,
            releaseBundleIdentity,
            publishWorkflowId,
            manifestHash,
            policy,
            objectMapper);
    return new PublishedRealmEntryPolicyEvidence(
        policyId,
        canonicalTenantId,
        tenantIdentityProvenanceKind,
        sourceGameRowId,
        sourceGameTenantKey,
        versionId,
        versionNumber,
        sourceRevisionId,
        releaseBundleIdentity,
        publishWorkflowId,
        manifestHash,
        policy,
        digest);
  }

  /** Verifies the evidence digest against every immutable owner and policy field. */
  public boolean hasValidDigest(ObjectMapper objectMapper) {
    try {
      requireCanonicalPolicy(policy, objectMapper);
      return policyDigest.equals(
          calculateDigest(
              policyId,
              canonicalTenantId,
              tenantIdentityProvenanceKind,
              sourceGameRowId,
              sourceGameTenantKey,
              versionId,
              versionNumber,
              sourceRevisionId,
              releaseBundleIdentity,
              publishWorkflowId,
              manifestHash,
              policy,
              objectMapper));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  /** Requires exact digest agreement and returns this immutable evidence. */
  public PublishedRealmEntryPolicyEvidence requireValidDigest(ObjectMapper objectMapper) {
    if (!hasValidDigest(objectMapper)) {
      throw new IllegalArgumentException(
          "Published realm-entry policy digest does not match evidence");
    }
    return this;
  }

  /** Deterministic identity for a full-version release bundle, excluding local database IDs. */
  public static String releaseBundleIdentity(
      UUID canonicalTenantId,
      long versionId,
      String publishWorkflowId,
      String manifestHash,
      ObjectMapper objectMapper) {
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    if (versionId <= 0 || publishWorkflowId == null || publishWorkflowId.isBlank()) {
      throw new IllegalArgumentException("Published release bundle identity is incomplete");
    }
    ObjectNode identity = objectMapper.createObjectNode();
    identity.put("canonicalTenantId", canonicalTenantId.toString());
    identity.put("versionId", versionId);
    identity.put("publishWorkflowId", publishWorkflowId);
    identity.put("manifestHash", Objects.requireNonNull(manifestHash, "manifestHash"));
    return sha256(canonicalize(identity.toString()));
  }

  private static String calculateDigest(
      UUID policyId,
      UUID canonicalTenantId,
      String tenantIdentityProvenanceKind,
      long sourceGameRowId,
      String sourceGameTenantKey,
      long versionId,
      int versionNumber,
      long sourceRevisionId,
      String releaseBundleIdentity,
      String publishWorkflowId,
      String manifestHash,
      RealmEntryPolicy policy,
      ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    ObjectNode preimage = objectMapper.createObjectNode();
    preimage.put("schemaVersion", RealmEntryPolicy.SCHEMA_VERSION);
    preimage.put("policyId", requireNonNil(policyId, "policyId").toString());
    preimage.put(
        "canonicalTenantId", requireNonNil(canonicalTenantId, "canonicalTenantId").toString());
    preimage.put("tenantIdentityProvenanceKind", tenantIdentityProvenanceKind);
    preimage.put("sourceGameRowId", sourceGameRowId);
    preimage.put("sourceGameTenantKey", sourceGameTenantKey);
    preimage.put("versionId", versionId);
    preimage.put("versionNumber", versionNumber);
    preimage.put("sourceRevisionId", sourceRevisionId);
    preimage.put("releaseBundleIdentity", releaseBundleIdentity);
    preimage.put("publishWorkflowId", publishWorkflowId);
    preimage.put("manifestHash", manifestHash);
    try {
      preimage.set("policy", objectMapper.readTree(policy.canonicalJson()));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Published realm-entry policy JSON is invalid", exception);
    }
    return sha256(canonicalize(preimage.toString()));
  }

  private static byte[] canonicalize(String json) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (IOException exception) {
      throw new IllegalStateException("RFC 8785 canonicalization failed", exception);
    }
  }

  private static void requireCanonicalPolicy(RealmEntryPolicy policy, ObjectMapper objectMapper) {
    Objects.requireNonNull(policy, "policy");
    RealmEntryPolicy parsed = RealmEntryPolicy.parseCanonical(policy.canonicalJson(), objectMapper);
    if (!parsed.equals(policy)) {
      throw new IllegalArgumentException(
          "Published realm-entry policy fields contradict canonical JSON");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static UUID requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
    return value;
  }
}
