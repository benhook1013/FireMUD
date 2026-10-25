package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import org.junit.jupiter.api.Test;

class CanonicalGameplayIssuerRedisKeysTest {
  @Test
  void bindingRefVectorsUseThePublishedUnsignedSha256Mapping() {
    var countOne = layout(BigInteger.ONE, BigInteger.ONE);
    var countSixteen = layout(BigInteger.ONE, BigInteger.valueOf(16));
    CanonicalGameplayBindingRef ascii =
        CanonicalGameplayBindingRef.of("tenant-01", "instance-01", "session-01");
    CanonicalGameplayBindingRef delimiter =
        CanonicalGameplayBindingRef.of("tenant/01", "game:01", "session|01");

    assertThat(new String(ascii.bytes(), StandardCharsets.UTF_8))
        .isEqualTo("13:bindingRef/v19:tenant-0111:instance-0110:session-01");
    assertThat(sha256(ascii.bytes()))
        .isEqualTo("43f08f503d85155c8bbc4501f14f496f7b35fa334b875382da86831251722565");
    assertThat(ascii.partitionId(BigInteger.ONE)).isEqualTo(BigInteger.ZERO);
    assertThat(ascii.partitionId(BigInteger.valueOf(16))).isEqualTo(BigInteger.valueOf(5));
    assertThat(sha256(delimiter.bytes()))
        .isEqualTo("89e8ba42d8f2fc0525c5b7ac2363528c1cbe7d00fc968b7ff95118da3443413a");
    assertThat(delimiter.partitionId(BigInteger.valueOf(16))).isEqualTo(BigInteger.TEN);
    assertThat(
            CanonicalGameplayIssuerRedisKeys.forBindingReference(countOne, ascii, BigInteger.ZERO))
        .endsWith(":" + GameSessionAccountDelegationProfile.ISSUER + ":0");
    assertThat(
            CanonicalGameplayIssuerRedisKeys.forBindingReference(
                countSixteen, ascii, BigInteger.valueOf(5)))
        .endsWith(":" + GameSessionAccountDelegationProfile.ISSUER + ":5");
    assertThat(
            CanonicalGameplayIssuerRedisKeys.forBindingReference(
                countSixteen, delimiter, BigInteger.TEN))
        .endsWith(":" + GameSessionAccountDelegationProfile.ISSUER + ":10");
  }

  @Test
  void keyTagPinsLayoutVersionIssuerAndPartitionWithoutTenantMixing() {
    var versionOne = layout(BigInteger.ONE, BigInteger.valueOf(16));
    var versionTwo = layout(BigInteger.TWO, BigInteger.valueOf(16));
    String firstPartition =
        CanonicalGameplayIssuerRedisKeys.forPartition(versionOne, BigInteger.ZERO);
    String otherPartition =
        CanonicalGameplayIssuerRedisKeys.forPartition(versionOne, BigInteger.ONE);
    String otherVersion =
        CanonicalGameplayIssuerRedisKeys.forPartition(versionTwo, BigInteger.ZERO);

    assertThat(firstPartition)
        .startsWith("session:game:index:issuer:{il-v1-")
        .endsWith(":" + GameSessionAccountDelegationProfile.ISSUER + ":0");
    assertThat(firstPartition).doesNotContain("tenant");
    assertThat(CanonicalGameplayIssuerRedisKeys.layoutTag(versionOne, BigInteger.ZERO))
        .matches("il-v1-[0-9a-f]{64}")
        .isNotEqualTo(CanonicalGameplayIssuerRedisKeys.layoutTag(versionOne, BigInteger.ONE))
        .isNotEqualTo(CanonicalGameplayIssuerRedisKeys.layoutTag(versionTwo, BigInteger.ZERO));
    assertThat(otherPartition).isNotEqualTo(firstPartition);
    assertThat(otherVersion).isNotEqualTo(firstPartition);
  }

  @Test
  void memberKeyUsesItsPinnedLayoutAndReservationPartition() {
    CanonicalGameplayIssuerMemberV2Value member =
        CanonicalGameplayIssuerMemberV2ValueCodecTest.fixture();

    assertThat(CanonicalGameplayIssuerRedisKeys.forMember(member))
        .isEqualTo(
            CanonicalGameplayIssuerRedisKeys.forPartition(
                member.layoutDescriptor(), member.reservation().partitionId()));
    assertThat(CanonicalGameplayIssuerRedisKeys.forMember(member))
        .doesNotContain(member.owner().tenantId().toString());
  }

  @Test
  void rejectsMismatchedOrOutOfLayoutPartitions() {
    var layout = layout(BigInteger.ONE, BigInteger.valueOf(16));
    CanonicalGameplayBindingRef bindingRef =
        CanonicalGameplayBindingRef.of("tenant-01", "instance-01", "session-01");

    assertThatThrownBy(
            () ->
                CanonicalGameplayIssuerRedisKeys.forBindingReference(
                    layout, bindingRef, BigInteger.valueOf(6)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> CanonicalGameplayIssuerRedisKeys.forPartition(layout, BigInteger.valueOf(16)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> CanonicalGameplayIssuerRedisKeys.forPartition(layout, BigInteger.valueOf(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layout(
      BigInteger version, BigInteger count) {
    byte[] bytes = ("layout-v" + version).getBytes(StandardCharsets.UTF_8);
    return new CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor(
        GameSessionAccountDelegationProfile.ISSUER,
        version,
        count,
        BigInteger.valueOf(32),
        CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY,
        bytes,
        CanonicalGameplayIssuerMemberV2Codec.sha256(bytes));
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
