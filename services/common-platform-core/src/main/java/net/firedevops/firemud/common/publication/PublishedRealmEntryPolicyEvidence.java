package net.firedevops.firemud.common.publication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.ObjectMapper;

/** One authored policy and its original immutable source identity within an owner-sealed set. */
public record PublishedRealmEntryPolicyEvidence(
    UUID policyId,
    UUID sourceCommitId,
    UUID sourceRevisionId,
    String logicalRevisionId,
    RealmEntryPolicy policy,
    String policyDigest) {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public PublishedRealmEntryPolicyEvidence {
    requireNonNil(policyId, "policyId");
    requireNonNil(sourceCommitId, "sourceCommitId");
    requireNonNil(sourceRevisionId, "sourceRevisionId");
    if (logicalRevisionId == null
        || logicalRevisionId.isBlank()
        || logicalRevisionId.length() > 128
        || !StandardCharsets.UTF_8.newEncoder().canEncode(logicalRevisionId)) {
      throw new IllegalArgumentException("Exact authored logical revision identity required");
    }
    policy = requireCanonicalPolicy(policy);
    requireDigest(policyDigest, "policyDigest");
  }

  /** Computes the exact Game Design owner policy digest over the actual sealed release identity. */
  public static String calculateDigest(
      UUID policyId,
      TargetProof target,
      int versionNumber,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest,
      String publishWorkflowId,
      String manifestHash,
      UUID sourceCommitId,
      UUID sourceRevisionId,
      String logicalRevisionId,
      RealmEntryPolicy policy) {
    requireNonNil(policyId, "policyId");
    Objects.requireNonNull(target, "target");
    if (versionNumber <= 0) {
      throw new IllegalArgumentException("Positive published version number required");
    }
    requireText(publishedReleaseBundleRef, "publishedReleaseBundleRef");
    requireDigest(publishedReleaseBundleDigest, "publishedReleaseBundleDigest");
    requireText(publishWorkflowId, "publishWorkflowId");
    requireDigest(manifestHash, "manifestHash");
    requireNonNil(sourceCommitId, "sourceCommitId");
    requireNonNil(sourceRevisionId, "sourceRevisionId");
    if (logicalRevisionId == null
        || logicalRevisionId.isBlank()
        || logicalRevisionId.length() > 128
        || !StandardCharsets.UTF_8.newEncoder().canEncode(logicalRevisionId)) {
      throw new IllegalArgumentException("Exact authored logical revision identity required");
    }
    RealmEntryPolicy exactPolicy = requireCanonicalPolicy(policy);

    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("schema", PublishedRealmEntryPolicySetEvidence.POLICY_DIGEST_SCHEMA);
    preimage.put("policyId", policyId.toString());
    preimage.put("target", PublishedRealmEntryPolicySetEvidence.targetProofObject(target));
    preimage.put("versionNumber", versionNumber);
    preimage.put("sourceCommitId", sourceCommitId.toString());
    preimage.put("sourceRevisionId", sourceRevisionId.toString());
    preimage.put("logicalRevisionId", logicalRevisionId);
    preimage.put("publishedReleaseBundleRef", publishedReleaseBundleRef);
    preimage.put("publishedReleaseBundleDigest", publishedReleaseBundleDigest);
    preimage.put("publishWorkflowId", publishWorkflowId);
    preimage.put("manifestHash", manifestHash);
    try {
      preimage.put("policy", JSON.readTree(exactPolicy.canonicalJson()));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Canonical realm-entry policy JSON is invalid", invalid);
    }
    return sha256(canonicalize(preimage));
  }

  /** Verifies this row against the shared sealed publication context. */
  public boolean hasValidDigest(PublishedRealmEntryPolicySetEvidence set) {
    if (set == null) return false;
    try {
      return policyDigest.equals(
          calculateDigest(
              policyId,
              set.target(),
              set.versionNumber(),
              set.publishedReleaseBundleRef(),
              set.publishedReleaseBundleDigest(),
              set.publishWorkflowId(),
              set.manifestHash(),
              sourceCommitId,
              sourceRevisionId,
              logicalRevisionId,
              policy));
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  /** Requires exact digest agreement with the shared sealed publication context. */
  public PublishedRealmEntryPolicyEvidence requireValidDigest(
      PublishedRealmEntryPolicySetEvidence set) {
    if (!hasValidDigest(set)) {
      throw new IllegalArgumentException(
          "Published realm-entry policy digest differs from evidence");
    }
    return this;
  }

  static RealmEntryPolicy requireCanonicalPolicy(RealmEntryPolicy policy) {
    Objects.requireNonNull(policy, "policy");
    RealmEntryPolicy parsed = RealmEntryPolicy.parseCanonical(policy.canonicalJson(), JSON);
    if (!parsed.equals(policy)) {
      throw new IllegalArgumentException("Policy fields contradict canonical policy JSON");
    }
    return parsed;
  }

  static byte[] canonicalize(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Policy evidence could not be canonicalized", invalid);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  static void requireText(String value, String name) {
    if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Canonical nonblank " + name + " required");
    }
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
      throw new IllegalArgumentException("Valid UTF-8 " + name + " required");
    }
  }

  static void requireDigest(String value, String name) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Canonical SHA-256 " + name + " required");
    }
  }

  static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
    return value;
  }
}
