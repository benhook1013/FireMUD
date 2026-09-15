package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
