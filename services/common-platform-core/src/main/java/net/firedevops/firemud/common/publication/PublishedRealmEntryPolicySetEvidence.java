package net.firedevops.firemud.common.publication;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Complete, bounded and owner-bound published realm policy set for one tenant version. */
public record PublishedRealmEntryPolicySetEvidence(
    UUID canonicalTenantId,
    long versionId,
    int versionNumber,
    String releaseBundleIdentity,
    String publishWorkflowId,
    String manifestHash,
    List<PublishedRealmEntryPolicyEvidence> policies,
    String policySetDigest) {
  public static final int MAX_POLICIES = 128;

  public PublishedRealmEntryPolicySetEvidence {
    if (canonicalTenantId == null || new UUID(0L, 0L).equals(canonicalTenantId)) {
      throw new IllegalArgumentException("canonicalTenantId must be a non-nil UUID");
    }
    if (versionId <= 0 || versionNumber <= 0) {
      throw new IllegalArgumentException("Published policy set version must be positive");
    }
    if (releaseBundleIdentity == null
        || !releaseBundleIdentity.matches("sha256:[0-9a-f]{64}")
        || publishWorkflowId == null
        || publishWorkflowId.isBlank()
        || manifestHash == null
        || manifestHash.isBlank()) {
      throw new IllegalArgumentException("Published policy set owner identity is incomplete");
    }
    policies = List.copyOf(Objects.requireNonNull(policies, "policies"));
    if (policies.isEmpty() || policies.size() > MAX_POLICIES) {
      throw new IllegalArgumentException("Published realm-entry policy set is empty or oversized");
    }
    String sourceProvenance = null;
    Long sourceGameRowId = null;
    String sourceGameTenantKey = null;
    String previousWorld = null;
    String previousRealm = null;
    int visiblePublicProductionCount = 0;
    Set<String> realmSlugs = new HashSet<>();
    for (PublishedRealmEntryPolicyEvidence policy : policies) {
      if (policy == null
          || !canonicalTenantId.equals(policy.canonicalTenantId())
          || versionId != policy.versionId()
          || versionNumber != policy.versionNumber()
          || !releaseBundleIdentity.equals(policy.releaseBundleIdentity())
          || !publishWorkflowId.equals(policy.publishWorkflowId())
          || !manifestHash.equals(policy.manifestHash())) {
        throw new IllegalArgumentException(
            "Published policy set contains contradictory owner evidence");
      }
      if (sourceProvenance == null) {
        sourceProvenance = policy.tenantIdentityProvenanceKind();
        sourceGameRowId = policy.sourceGameRowId();
        sourceGameTenantKey = policy.sourceGameTenantKey();
      } else if (!sourceProvenance.equals(policy.tenantIdentityProvenanceKind())
          || !sourceGameRowId.equals(policy.sourceGameRowId())
          || !sourceGameTenantKey.equals(policy.sourceGameTenantKey())) {
        throw new IllegalArgumentException(
            "Published policy set contains contradictory tenant provenance");
      }
      String world = policy.policy().worldSlug();
      String realm = policy.policy().realmSlug();
      if (!realmSlugs.add(realm)) {
        throw new IllegalArgumentException(
            "Published policy set contains a realm slug more than once within its tenant");
      }
      if (previousWorld != null
          && (previousWorld.compareTo(world) > 0
              || (previousWorld.equals(world) && previousRealm.compareTo(realm) >= 0))) {
        throw new IllegalArgumentException("Published policy set selector order is not canonical");
      }
      previousWorld = world;
      previousRealm = realm;
      if (policy.policy().visible() && policy.policy().publicProduction()) {
        visiblePublicProductionCount++;
      }
    }
    if (visiblePublicProductionCount != 1) {
      throw new IllegalArgumentException(
          "Published policy set must contain exactly one visible publicProduction realm");
    }
    if (policySetDigest == null || !policySetDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Published realm-entry policy set digest is malformed");
    }
  }

  public static PublishedRealmEntryPolicySetEvidence create(
      UUID canonicalTenantId,
      long versionId,
      int versionNumber,
      String releaseBundleIdentity,
      String publishWorkflowId,
      String manifestHash,
      List<PublishedRealmEntryPolicyEvidence> policies,
      ObjectMapper objectMapper) {
    Objects.requireNonNull(policies, "policies");
    if (policies.isEmpty() || policies.size() > MAX_POLICIES) {
      throw new IllegalArgumentException("Published realm-entry policy set is empty or oversized");
    }
    List<PublishedRealmEntryPolicyEvidence> ordered =
        policies.stream()
            .sorted(
                (left, right) -> {
                  int worldOrder = left.policy().worldSlug().compareTo(right.policy().worldSlug());
                  return worldOrder != 0
                      ? worldOrder
                      : left.policy().realmSlug().compareTo(right.policy().realmSlug());
                })
            .toList();
    String digest =
        calculateDigest(
            canonicalTenantId,
            versionId,
            versionNumber,
            releaseBundleIdentity,
            publishWorkflowId,
            manifestHash,
            ordered,
            objectMapper);
    return new PublishedRealmEntryPolicySetEvidence(
        canonicalTenantId,
        versionId,
        versionNumber,
        releaseBundleIdentity,
        publishWorkflowId,
        manifestHash,
        ordered,
        digest);
  }

  /** Verifies the complete-set digest and every child policy digest. */
  public boolean hasValidDigest(ObjectMapper objectMapper) {
    return policies.stream().allMatch(policy -> policy.hasValidDigest(objectMapper))
        && policySetDigest.equals(
            calculateDigest(
                canonicalTenantId,
                versionId,
                versionNumber,
                releaseBundleIdentity,
                publishWorkflowId,
                manifestHash,
                policies,
                objectMapper));
  }

  public PublishedRealmEntryPolicySetEvidence requireValidDigest(ObjectMapper objectMapper) {
    if (!hasValidDigest(objectMapper)) {
      throw new IllegalArgumentException(
          "Published realm-entry policy set digest does not match evidence");
    }
    return this;
  }

  private static String calculateDigest(
      UUID canonicalTenantId,
      long versionId,
      int versionNumber,
      String releaseBundleIdentity,
      String publishWorkflowId,
      String manifestHash,
      List<PublishedRealmEntryPolicyEvidence> policies,
      ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    ObjectNode preimage = objectMapper.createObjectNode();
    preimage.put("schemaVersion", RealmEntryPolicy.SCHEMA_VERSION);
    preimage.put("canonicalTenantId", canonicalTenantId.toString());
    preimage.put(
        "tenantIdentityProvenanceKind", policies.getFirst().tenantIdentityProvenanceKind());
    preimage.put("sourceGameRowId", policies.getFirst().sourceGameRowId());
    preimage.put("sourceGameTenantKey", policies.getFirst().sourceGameTenantKey());
    preimage.put("versionId", versionId);
    preimage.put("versionNumber", versionNumber);
    preimage.put("releaseBundleIdentity", releaseBundleIdentity);
    preimage.put("publishWorkflowId", publishWorkflowId);
    preimage.put("manifestHash", manifestHash);
    preimage.put("policyCount", policies.size());
    ArrayNode selectors = objectMapper.createArrayNode();
    for (PublishedRealmEntryPolicyEvidence policy : policies) {
      ObjectNode selector = objectMapper.createObjectNode();
      selector.put("policyId", policy.policyId().toString());
      selector.put("sourceRevisionId", policy.sourceRevisionId());
      selector.put("worldSlug", policy.policy().worldSlug());
      selector.put("realmSlug", policy.policy().realmSlug());
      selector.put("policyDigest", policy.policyDigest());
      selectors.add(selector);
    }
    preimage.set("policies", selectors);
    try {
      return sha256(Rfc8785CanonicalJson.canonicalizeUtf8(preimage.toString()));
    } catch (IOException exception) {
      throw new IllegalStateException("RFC 8785 canonicalization failed", exception);
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
}
