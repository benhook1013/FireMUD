package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CidrBlockTest {
  @Test
  void normalizesLiteralsWithoutDnsResolution() {
    assertThat(CidrBlock.normalizeIpLiteral("192.0.2.1")).isEqualTo("192.0.2.1");
    assertThat(CidrBlock.normalizeIpLiteral("[2001:DB8::1]")).isEqualTo("2001:db8:0:0:0:0:0:1");
  }

  @Test
  void rejectsHostnamesMalformedIpv4AndZones() {
    assertThat(CidrBlock.normalizeIpLiteral("example.com")).isNull();
    assertThat(CidrBlock.normalizeIpLiteral("127.1")).isNull();
    assertThat(CidrBlock.normalizeIpLiteral("2130706433")).isNull();
    assertThat(CidrBlock.normalizeIpLiteral("fe80::1%eth0")).isNull();
    assertThat(CidrBlock.parse("192.0.2.0/24")).isNotNull();
    assertThat(CidrBlock.parse("192.0.2.0/33")).isNull();
  }

  @Test
  void warnsWithRejectedValueAndRetainsValidBlocks(CapturedOutput output) throws Exception {
    CidrSet ranges = new CidrSet(java.util.List.of("192.0.2.0/24", "example.com/32"));

    assertThat(ranges.contains(InetAddress.getByName("192.0.2.42"))).isTrue();
    assertThat(ranges.contains(InetAddress.getByName("203.0.113.42"))).isFalse();
    assertThat(output)
        .contains("Ignoring invalid configured CIDR entry")
        .contains("value=example.com/32");
  }

  @Test
  void matchesIpv4MappedAddressesAgainstIpv4Blocks() throws Exception {
    CidrBlock block = CidrBlock.parse("192.0.2.0/24");

    assertThat(block.contains(mappedAddress(192, 0, 2, 42))).isTrue();
    assertThat(block.contains(mappedAddress(198, 51, 100, 42))).isFalse();
  }

  @Test
  void mapsRepresentableIpv4MappedCidrsAndRejectsWiderRanges() throws Exception {
    CidrBlock block = CidrBlock.parse("::ffff:192.0.2.0/120");

    assertThat(block).isNotNull();
    assertThat(block.contains(mappedAddress(192, 0, 2, 42))).isTrue();
    assertThat(block.contains(mappedAddress(192, 0, 3, 42))).isFalse();
    assertThat(CidrBlock.parse("::ffff:192.0.2.0/95")).isNull();
  }

  @Test
  void preservesOrdinaryIpv6Matching() throws Exception {
    CidrBlock block = CidrBlock.parse("2001:db8::/32");

    assertThat(block.contains(InetAddress.getByName("2001:db8::42"))).isTrue();
    assertThat(block.contains(InetAddress.getByName("2001:db9::42"))).isFalse();
    assertThat(block.contains(InetAddress.getByName("192.0.2.42"))).isFalse();
  }

  private static InetAddress mappedAddress(int first, int second, int third, int fourth)
      throws Exception {
    return Inet6Address.getByAddress(
        null,
        new byte[] {
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          (byte) 0xff,
          (byte) 0xff,
          (byte) first,
          (byte) second,
          (byte) third,
          (byte) fourth
        },
        -1);
  }
}
