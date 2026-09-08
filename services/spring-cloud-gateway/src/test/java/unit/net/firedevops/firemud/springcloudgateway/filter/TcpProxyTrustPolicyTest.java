package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import net.firedevops.firemud.springcloudgateway.config.GatewayTcpProxyListenerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.web.server.ServerWebExchange;

class TcpProxyTrustPolicyTest {
  private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String TCP_PROXY_URI = "spiffe://firemud/ns/firemud/sa/tcp-proxy-service";

  @Test
  void productionUriAcceptsOnlyOneExactCanonicalIdentityWithClientAuth() throws Exception {
    TcpProxyTrustPolicy policy = policy(properties("production_uri"), Set.of("prod"));

    assertThat(policy.authenticatePeer(sslInfo(certificate(List.of(san(6, TCP_PROXY_URI)), true))))
        .isTrue();
    assertThat(
            policy.authenticatePeer(
                sslInfo(
                    certificate(
                        List.of(san(6, TCP_PROXY_URI), san(2, "tcp-proxy.internal")), true))))
        .isTrue();
    assertThat(
            policy.authenticatePeer(
                sslInfo(
                    certificate(
                        List.of(san(6, "spiffe://firemud/ns/other/sa/tcp-proxy-service")), true))))
        .isFalse();
    assertThat(
            policy.authenticatePeer(
                sslInfo(certificate(List.of(san(6, TCP_PROXY_URI), san(6, TCP_PROXY_URI)), true))))
        .isFalse();
    assertThat(policy.authenticatePeer(sslInfo(certificate(List.of(san(6, TCP_PROXY_URI)), false))))
        .isFalse();
    assertThat(policy.authenticatePeer(sslInfo(null))).isFalse();
  }

  @Test
  void productionUriRejectsExpiredClientCertificate() throws Exception {
    TcpProxyTrustPolicy policy = policy(properties("production_uri"), Set.of("prod"));
    X509Certificate expired = certificate(List.of(san(6, TCP_PROXY_URI)), true);
    doThrow(new CertificateExpiredException("expired")).when(expired).checkValidity(Date.from(NOW));

    assertThat(policy.authenticatePeer(sslInfo(expired))).isFalse();
  }

  @Test
  void canonicalWorkloadIdentityUsesAdr0038Grammar() {
    assertThat(
            TcpProxyTrustPolicy.normalizeTcpProxyWorkloadIdentity(
                "spiffe://FIREMUD/ns/firemud/sa/tcp-proxy-service"))
        .isEqualTo(TCP_PROXY_URI);
    assertThat(
            TcpProxyTrustPolicy.normalizeTcpProxyWorkloadIdentity(
                "spiffe://firemud/ns/fire%6dud/sa/tcp-proxy-service"))
        .isEqualTo(TCP_PROXY_URI);

    for (String rejected :
        List.of(
            "SPIFFE://firemud/ns/firemud/sa/tcp-proxy-service",
            "spiffe://other/ns/firemud/sa/tcp-proxy-service",
            "spiffe://firemud/ns/firemud/sa/other-service",
            "spiffe://firemud/ns/firemud/sa/tcp-proxy-service/extra",
            "spiffe://firemud/ns/../sa/tcp-proxy-service",
            "spiffe://firemud/ns/firemud/sa/tcp-proxy-service?query=1",
            "spiffe://firemud/ns/firemud/sa/tcp-proxy%2Fservice")) {
      assertThatThrownBy(() -> TcpProxyTrustPolicy.normalizeTcpProxyWorkloadIdentity(rejected))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void configurationRequiresExactlyOneCompleteProfile() {
    GatewayTcpProxyListenerProperties missing = baseProperties();
    assertThatThrownBy(() -> policy(missing, Set.of("prod")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("trust-profile is required");

    GatewayTcpProxyListenerProperties conflicting = properties("production_uri");
    conflicting.getMigrationDns().setDnsSan("tcp-proxy.internal");
    assertThatThrownBy(() -> policy(conflicting, Set.of("prod")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inactive trust profiles");

    GatewayTcpProxyListenerProperties expired = properties("migration_dns");
    expired.getMigrationDns().setDnsSan("tcp-proxy.internal");
    expired.getMigrationDns().setOwner("platform");
    expired.getMigrationDns().setReason("issuer migration");
    expired.getMigrationDns().setExpiresAt("2026-09-07T09:59:59Z");
    assertThatThrownBy(() -> policy(expired, Set.of("prod")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be in the future");
  }

  @Test
  void migrationDnsStopsAuthenticatingWhenItsApprovalExpires() throws Exception {
    GatewayTcpProxyListenerProperties properties = properties("migration_dns");
    properties.getMigrationDns().setDnsSan("tcp-proxy.internal");
    properties.getMigrationDns().setOwner("platform");
    properties.getMigrationDns().setReason("issuer migration");
    properties.getMigrationDns().setExpiresAt("2026-09-07T10:01:00Z");
    MutableClock clock = new MutableClock(NOW);
    TcpProxyTrustPolicy policy =
        new TcpProxyTrustPolicy(
            properties, new GatewayHeaderTrustProperties(), 8080, clock, Set.of("prod"));
    SslInfo peer = sslInfo(certificate(List.of(san(2, "tcp-proxy.internal")), true));

    assertThat(policy.authenticatePeer(peer)).isTrue();
    clock.set(Instant.parse("2026-09-07T10:01:00Z"));
    assertThat(policy.authenticatePeer(peer)).isFalse();
  }

  @Test
  void migrationDnsAcceptsOnlyOneExactDnsIdentity() throws Exception {
    GatewayTcpProxyListenerProperties properties = properties("migration_dns");
    properties.getMigrationDns().setDnsSan("tcp-proxy.internal");
    properties.getMigrationDns().setOwner("platform");
    properties.getMigrationDns().setReason("issuer migration");
    properties.getMigrationDns().setExpiresAt("2026-09-08T10:00:00Z");
    TcpProxyTrustPolicy policy = policy(properties, Set.of("prod"));

    assertThat(
            policy.authenticatePeer(
                sslInfo(certificate(List.of(san(2, "tcp-proxy.internal")), true))))
        .isTrue();
    assertThat(
            policy.authenticatePeer(sslInfo(certificate(List.of(san(2, "other.internal")), true))))
        .isFalse();
    assertThat(
            policy.authenticatePeer(
                sslInfo(
                    certificate(
                        List.of(san(2, "tcp-proxy.internal"), san(2, "tcp-proxy.internal")),
                        true))))
        .isFalse();
  }

  @Test
  void breakglassAcceptsOnlyExactLeafFingerprint() throws Exception {
    byte[] allowedEncoding = new byte[] {1, 2, 3, 4};
    GatewayTcpProxyListenerProperties properties = properties("breakglass_fingerprint");
    properties
        .getBreakglassFingerprint()
        .setSha256(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(allowedEncoding)));
    TcpProxyTrustPolicy policy = policy(properties, Set.of("prod"));

    assertThat(policy.authenticatePeer(sslInfo(certificate(List.of(), true, allowedEncoding))))
        .isTrue();
    assertThat(
            policy.authenticatePeer(sslInfo(certificate(List.of(), true, new byte[] {4, 3, 2, 1}))))
        .isFalse();
  }

  @Test
  void developmentCidrRequiresTlsExactPortAndAllowedSource() throws Exception {
    GatewayTcpProxyListenerProperties properties = properties("development_cidr");
    properties.setTrustedClientCaPath(null);
    properties.getDevelopmentCidr().setTrustedCidr("10.20.0.0/16");
    TcpProxyTrustPolicy policy = policy(properties, Set.of("test"));

    assertThat(
            policy.isTrusted(
                exchange(8443, mock(SslInfo.class)), InetAddress.getByName("10.20.1.4")))
        .isTrue();
    assertThat(
            policy.isTrusted(
                exchange(8443, mock(SslInfo.class)), InetAddress.getByName("10.21.1.4")))
        .isFalse();
    assertThat(
            policy.isTrusted(
                exchange(8080, mock(SslInfo.class)), InetAddress.getByName("10.20.1.4")))
        .isFalse();
    assertThat(policy.isTrusted(exchange(8443, null), InetAddress.getByName("10.20.1.4")))
        .isFalse();
  }

  @Test
  void developmentCidrIsRejectedForPlayerFacingOrProdProfiles() {
    GatewayTcpProxyListenerProperties properties = properties("development_cidr");
    properties.setTrustedClientCaPath(null);
    properties.setEnvironment("production");
    properties.getDevelopmentCidr().setTrustedCidr("127.0.0.1/32");

    assertThatThrownBy(() -> policy(properties, Set.of("prod")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("restricted to local-dev or isolated-test");
  }

  @Test
  void developmentCidrRejectsDefaultRoutesForIpv4AndIpv6() {
    for (String cidr : List.of("0.0.0.0/0", "::/0")) {
      GatewayTcpProxyListenerProperties properties = properties("development_cidr");
      properties.setTrustedClientCaPath(null);
      properties.getDevelopmentCidr().setTrustedCidr(cidr);

      assertThatThrownBy(() -> policy(properties, Set.of("test")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("development-cidr.trusted-cidr is invalid");
    }
  }

  @Test
  void legacyPlaintextTrustIsRejectedOutsideExplicitLocalOrTestProfile() {
    GatewayHeaderTrustProperties legacy = new GatewayHeaderTrustProperties();
    legacy.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    legacy.getTcpProxy().setInsecureTrustedCidrs(List.of("127.0.0.1/32"));

    assertThatThrownBy(
            () ->
                new TcpProxyTrustPolicy(
                    new GatewayTcpProxyListenerProperties(), legacy, 8080, CLOCK, Set.of("prod")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("restricted to explicit test/dev/local profiles");
  }

  @Test
  void legacyPlaintextTrustAllowsOnlyConfiguredSourcesInExplicitTestProfile() throws Exception {
    GatewayHeaderTrustProperties legacy = new GatewayHeaderTrustProperties();
    legacy.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    legacy.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    TcpProxyTrustPolicy policy =
        new TcpProxyTrustPolicy(
            new GatewayTcpProxyListenerProperties(), legacy, 8080, CLOCK, Set.of("test"));

    assertThat(policy.isTrusted(mock(ServerWebExchange.class), InetAddress.getByName("10.1.2.3")))
        .isTrue();
    assertThat(policy.isTrusted(mock(ServerWebExchange.class), InetAddress.getByName("192.0.2.1")))
        .isFalse();
  }

  @Test
  void legacyPlaintextTrustRejectsDefaultRoutesForIpv4AndIpv6() {
    for (String cidr : List.of("0.0.0.0/0", "::/0")) {
      GatewayHeaderTrustProperties legacy = new GatewayHeaderTrustProperties();
      legacy.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
      legacy.getTcpProxy().setInsecureTrustedCidrs(List.of(cidr));

      assertThatThrownBy(
              () ->
                  new TcpProxyTrustPolicy(
                      new GatewayTcpProxyListenerProperties(), legacy, 8080, CLOCK, Set.of("test")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("contains an invalid source CIDR");
    }
  }

  private static TcpProxyTrustPolicy policy(
      GatewayTcpProxyListenerProperties properties, Set<String> activeProfiles) {
    return new TcpProxyTrustPolicy(
        properties, new GatewayHeaderTrustProperties(), 8080, CLOCK, activeProfiles);
  }

  private static GatewayTcpProxyListenerProperties properties(String profile) {
    GatewayTcpProxyListenerProperties properties = baseProperties();
    properties.setTrustProfile(profile);
    switch (profile) {
      case "production_uri" -> properties.getProductionUri().setUriSan(TCP_PROXY_URI);
      case "migration_dns" -> {
        // Completed by the test so missing-field behavior can also be exercised.
      }
      case "breakglass_fingerprint" -> {
        properties
            .getBreakglassFingerprint()
            .setSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        properties.getBreakglassFingerprint().setIncidentReference("INC-123");
        properties.getBreakglassFingerprint().setExpiresAt("2026-09-08T10:00:00Z");
      }
      case "development_cidr" -> {
        properties.setEnvironment("isolated-test");
        properties.getDevelopmentCidr().setTrustedCidr("127.0.0.1/32");
      }
      default -> throw new IllegalArgumentException(profile);
    }
    return properties;
  }

  private static GatewayTcpProxyListenerProperties baseProperties() {
    GatewayTcpProxyListenerProperties properties = new GatewayTcpProxyListenerProperties();
    properties.setEnabled(true);
    properties.setPort(8443);
    properties.setCertificateChainPath("server.crt");
    properties.setPrivateKeyPath("server.key");
    properties.setTrustedClientCaPath("client-ca.crt");
    properties.setEnvironment("production");
    return properties;
  }

  private static ServerWebExchange exchange(int localPort, SslInfo sslInfo) {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getLocalAddress()).thenReturn(new InetSocketAddress("127.0.0.1", localPort));
    when(request.getSslInfo()).thenReturn(sslInfo);
    return exchange;
  }

  private static SslInfo sslInfo(X509Certificate certificate) {
    SslInfo sslInfo = mock(SslInfo.class);
    when(sslInfo.getPeerCertificates())
        .thenReturn(
            certificate == null ? new X509Certificate[0] : new X509Certificate[] {certificate});
    return sslInfo;
  }

  private static X509Certificate certificate(List<List<?>> sans, boolean clientAuth)
      throws Exception {
    return certificate(sans, clientAuth, new byte[] {1, 2, 3, 4});
  }

  private static X509Certificate certificate(List<List<?>> sans, boolean clientAuth, byte[] encoded)
      throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    Collection<List<?>> subjectAlternativeNames = new ArrayList<>(sans);
    when(certificate.getSubjectAlternativeNames()).thenReturn(subjectAlternativeNames);
    when(certificate.getExtendedKeyUsage())
        .thenReturn(clientAuth ? List.of(TcpProxyTrustPolicy.CLIENT_AUTH_EKU) : List.of());
    when(certificate.getEncoded()).thenReturn(encoded);
    return certificate;
  }

  private static List<?> san(int type, String value) {
    return List.of(type, value);
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;

    private MutableClock(Instant instant) {
      this.instant = new AtomicReference<>(instant);
    }

    private void set(Instant value) {
      instant.set(value);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
