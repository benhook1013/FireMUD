package net.firedevops.firemud.gamesession.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pre-v1 gate waiver (ADR 0185): the stable Coordination Redis incarnation, derived only from the
 * live Redis {@code run_id}. It stands in for operational deployment/incarnation custody, which is
 * owed before v1. A Redis restart changes the {@code run_id}, so every value here changes and the
 * canonical owners that compare incarnations fail closed.
 */
final class PreV1CoordinationIncarnation {
  static final String ATTESTOR_BINDING = "pre-v1-local-coordination-attestor";
  private static final Pattern REDIS_RUN_ID = Pattern.compile("[0-9a-f]{40}");

  private final String redisRunId;
  private final CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
      deployment;
  private final String acceptedAttestationDigest;
  private final String coordOpsRoAclDigest;
  private final String coordRedisConfigObservedDigest;

  private PreV1CoordinationIncarnation(String redisRunId) {
    this.redisRunId = redisRunId;
    this.deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "pre-v1-coordination-redis",
            "run-" + redisRunId,
            List.of("pre-v1-coordination-node"),
            "pre-v1-coordination-redis-build",
            "pre-v1-lua-registry-v1",
            sha256("lua-registry", redisRunId),
            "READY",
            "boot-" + redisRunId,
            "startup-" + redisRunId,
            "storage-" + redisRunId,
            "aof-" + redisRunId);
    this.acceptedAttestationDigest = sha256("accepted-attestation", redisRunId);
    this.coordOpsRoAclDigest = sha256("coord-ops-ro-acl", redisRunId);
    this.coordRedisConfigObservedDigest = sha256("coord-redis-config", redisRunId);
  }

  static PreV1CoordinationIncarnation forRedisRunId(String redisRunId) {
    if (redisRunId == null || !REDIS_RUN_ID.matcher(redisRunId).matches()) {
      throw new IllegalStateException("Coordination Redis run_id is unavailable or malformed");
    }
    return new PreV1CoordinationIncarnation(redisRunId);
  }

  String redisRunId() {
    return redisRunId;
  }

  CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence deployment() {
    return deployment;
  }

  /** {@code sha256:}-prefixed digests, as the issuer and protected receiver values require. */
  String acceptedAttestationDigest() {
    return acceptedAttestationDigest;
  }

  String coordOpsRoAclDigest() {
    return coordOpsRoAclDigest;
  }

  String coordRedisConfigObservedDigest() {
    return coordRedisConfigObservedDigest;
  }

  CanonicalGameplayIssuerMemberV2Value.Incarnation issuerIncarnation() {
    return new CanonicalGameplayIssuerMemberV2Value.Incarnation(
        ATTESTOR_BINDING,
        acceptedAttestationDigest,
        deployment,
        coordOpsRoAclDigest,
        coordRedisConfigObservedDigest);
  }

  /** Bare 64-hex form of one of this incarnation's digests, as region evidence requires. */
  static String bareHex(String prefixedDigest) {
    Objects.requireNonNull(prefixedDigest, "prefixedDigest");
    if (!prefixedDigest.startsWith("sha256:")) {
      throw new IllegalArgumentException("Digest is not sha256-prefixed");
    }
    return prefixedDigest.substring("sha256:".length());
  }

  static String sha256(String label, String seed) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  ("firemud.gs.pre-v1-coordination/" + label + "/v1|" + seed)
                      .getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }
}
