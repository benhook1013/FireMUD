package net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;

/** Canonical key construction for one issuer layout partition. */
final class CanonicalGameplayIssuerRedisKeys {
  private static final String KEY_PREFIX = "session:game:index:issuer:";
  private static final String TAG_DOMAIN = "issuer-layout-tag/v1";

  private CanonicalGameplayIssuerRedisKeys() {}

  static String forMember(CanonicalGameplayIssuerMemberV2Value value) {
    Objects.requireNonNull(value, "value");
    return forPartition(value.layoutDescriptor(), value.reservation().partitionId());
  }

  /** Builds the canonical physical key for an explicit pinned layout partition. */
  static String forPartition(
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layout,
      BigInteger partitionId) {
    validateLayout(layout);
    Objects.requireNonNull(partitionId, "partitionId");
    if (partitionId.signum() < 0 || partitionId.compareTo(layout.partitionCount()) >= 0) {
      throw new IllegalArgumentException("partitionId must be within the pinned issuer layout");
    }
    String canonicalPartitionId = partitionId.toString();
    String tag =
        "il-v1-"
            + HexFormat.of()
                .formatHex(
                    sha256(
                        framed(
                            TAG_DOMAIN,
                            layout.layoutVersion().toString(),
                            layout.issuerId(),
                            canonicalPartitionId)));
    return KEY_PREFIX + "{" + tag + "}:" + layout.issuerId() + ":" + canonicalPartitionId;
  }

  /** Verifies the existing canonical bindingRef mapping before constructing its partition key. */
  static String forBindingReference(
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layout,
      CanonicalGameplayBindingRef bindingRef,
      BigInteger partitionId) {
    validateLayout(layout);
    Objects.requireNonNull(bindingRef, "bindingRef");
    Objects.requireNonNull(partitionId, "partitionId");
    if (!bindingRef.partitionId(layout.partitionCount()).equals(partitionId)) {
      throw new IllegalArgumentException(
          "partitionId does not match the canonical bindingRef mapping");
    }
    return forPartition(layout, partitionId);
  }

  static String layoutTag(
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layout,
      BigInteger partitionId) {
    String key = forPartition(layout, partitionId);
    int tagStart = KEY_PREFIX.length() + 1;
    int tagEnd = key.indexOf('}', tagStart);
    return key.substring(tagStart, tagEnd);
  }

  private static void validateLayout(
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layout) {
    Objects.requireNonNull(layout, "layout");
    if (!CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY.equals(
        layout.mappingIdentity())) {
      throw new IllegalArgumentException("Unsupported canonical issuer mapping identity");
    }
    if (!GameSessionAccountDelegationProfile.ISSUER.equals(layout.issuerId())) {
      throw new IllegalArgumentException("Unsupported Account issuer identity");
    }
    if (layout.layoutVersion().signum() <= 0
        || layout.partitionCount().signum() <= 0
        || layout.partitionCapacity().signum() <= 0) {
      throw new IllegalArgumentException("Pinned issuer layout counters must be positive");
    }
    if (!CanonicalGameplayIssuerMemberV2Codec.sha256(layout.exactDescriptorBytes())
        .equals(layout.descriptorDigest())) {
      throw new IllegalArgumentException("Pinned issuer layout descriptor digest does not match");
    }
  }

  private static byte[] framed(String... values) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (String value : values) {
      byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
      output.writeBytes(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
      output.write(':');
      output.writeBytes(encoded);
    }
    return output.toByteArray();
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }
}
