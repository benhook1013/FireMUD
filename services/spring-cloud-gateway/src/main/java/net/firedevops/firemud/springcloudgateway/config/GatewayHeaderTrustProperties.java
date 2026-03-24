package net.firedevops.firemud.springcloudgateway.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "firemud.gateway.header-trust")
public class GatewayHeaderTrustProperties {
  private final ForwardedClientIp forwardedClientIp = new ForwardedClientIp();
  private final TcpProxy tcpProxy = new TcpProxy();

  public ForwardedClientIp getForwardedClientIp() {
    return forwardedClientIp;
  }

  public TcpProxy getTcpProxy() {
    return tcpProxy;
  }

  public static final class ForwardedClientIp {
    private List<String> trustedProxyCidrs = new ArrayList<>();

    public List<String> getTrustedProxyCidrs() {
      return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
      this.trustedProxyCidrs = trustedProxyCidrs == null ? new ArrayList<>() : trustedProxyCidrs;
    }
  }

  public static final class TcpProxy {
    private List<String> trustedClientCertFingerprintsSha256 = new ArrayList<>();
    private List<String> trustedClientCertDnsSans = new ArrayList<>();
    private List<String> trustedClientCertUriSans = new ArrayList<>();
    private boolean allowInsecureHeadersFromTrustedCidrs;
    private List<String> insecureTrustedCidrs = new ArrayList<>();

    public List<String> getTrustedClientCertFingerprintsSha256() {
      return trustedClientCertFingerprintsSha256;
    }

    public void setTrustedClientCertFingerprintsSha256(
        List<String> trustedClientCertFingerprintsSha256) {
      this.trustedClientCertFingerprintsSha256 =
          trustedClientCertFingerprintsSha256 == null
              ? new ArrayList<>()
              : trustedClientCertFingerprintsSha256;
    }

    public List<String> getTrustedClientCertDnsSans() {
      return trustedClientCertDnsSans;
    }

    public void setTrustedClientCertDnsSans(List<String> trustedClientCertDnsSans) {
      this.trustedClientCertDnsSans =
          trustedClientCertDnsSans == null ? new ArrayList<>() : trustedClientCertDnsSans;
    }

    public List<String> getTrustedClientCertUriSans() {
      return trustedClientCertUriSans;
    }

    public void setTrustedClientCertUriSans(List<String> trustedClientCertUriSans) {
      this.trustedClientCertUriSans =
          trustedClientCertUriSans == null ? new ArrayList<>() : trustedClientCertUriSans;
    }

    public boolean isAllowInsecureHeadersFromTrustedCidrs() {
      return allowInsecureHeadersFromTrustedCidrs;
    }

    public void setAllowInsecureHeadersFromTrustedCidrs(
        boolean allowInsecureHeadersFromTrustedCidrs) {
      this.allowInsecureHeadersFromTrustedCidrs = allowInsecureHeadersFromTrustedCidrs;
    }

    public List<String> getInsecureTrustedCidrs() {
      return insecureTrustedCidrs;
    }

    public void setInsecureTrustedCidrs(List<String> insecureTrustedCidrs) {
      this.insecureTrustedCidrs =
          insecureTrustedCidrs == null ? new ArrayList<>() : insecureTrustedCidrs;
    }
  }
}
