package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Credential-free immutable identities tying a migration operation to one physical cohort.
 *
 * <p>{@code kubernetesClusterUid} is the trusted factory's kube-system namespace incarnation
 * identity, paired with separately revalidated cluster kind, node, and network evidence; it is not
 * claimed to be a Kubernetes Cluster object UID. The storage UID fields may identify an exact
 * ephemeral volume as {@code namespaceUid/podUid/volumeName}, when backed by the immutable Pod
 * specification and mount readback; they are not claimed to be PVC object UIDs. The producer Pod,
 * container, and node fields identify the actual co-located migration owner process and its PG
 * Pod/container/Node evidence, not the migration-driver container. A trusted factory must retain
 * and recheck the broader evidence behind these compact identities.
 */
public record CanonicalGameplayLegacyMigrationStorageIdentity(
    String kubernetesClusterUid,
    String kubernetesNamespaceUid,
    String producerPodUid,
    String producerContainerId,
    String producerNodeUid,
    String postgresSystemIdentifier,
    long postgresDatabaseOid,
    String postgresStorageUid,
    String redisRunId,
    String redisStorageUid) {
  public CanonicalGameplayLegacyMigrationStorageIdentity {
    requireText(kubernetesClusterUid, "kubernetesClusterUid");
    requireText(kubernetesNamespaceUid, "kubernetesNamespaceUid");
    requireText(producerPodUid, "producerPodUid");
    requireText(producerContainerId, "producerContainerId");
    requireText(producerNodeUid, "producerNodeUid");
    requireText(postgresSystemIdentifier, "postgresSystemIdentifier");
    requireText(postgresStorageUid, "postgresStorageUid");
    requireText(redisRunId, "redisRunId");
    requireText(redisStorageUid, "redisStorageUid");
    if (!postgresSystemIdentifier.matches("[1-9][0-9]{0,19}")
        || new BigInteger(postgresSystemIdentifier).bitLength() > 64) {
      throw new IllegalArgumentException("postgresSystemIdentifier must be an unsigned 64-bit id");
    }
    if (postgresDatabaseOid <= 0L || postgresDatabaseOid > 0xffff_ffffL) {
      throw new IllegalArgumentException(
          "postgresDatabaseOid must be a positive unsigned 32-bit id");
    }
  }

  /** Stable digest of the exact, non-secret physical identity tuple. */
  public String digest() {
    List<String> fields =
        List.of(
            kubernetesClusterUid,
            kubernetesNamespaceUid,
            producerPodUid,
            producerContainerId,
            producerNodeUid,
            postgresSystemIdentifier,
            Long.toUnsignedString(postgresDatabaseOid),
            postgresStorageUid,
            redisRunId,
            redisStorageUid);
    StringBuilder framed = new StringBuilder("canonical-gameplay-storage-identity/v1|");
    fields.forEach(
        field ->
            framed.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field));
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(framed.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 is unavailable for migration storage identity", impossible);
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()
        || !value.equals(value.strip())
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(name + " must be a non-blank canonical identity value");
    }
  }
}
