package net.firedevops.firemud.springcloudgateway.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "firemud.gateway.header-trust")
public class GatewayHeaderTrustProperties {
  private final ForwardedClientIp forwardedClientIp = new ForwardedClientIp();
  private final TcpProxy tcpProxy = new TcpProxy();

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Nested configuration objects are Spring-bound mutable property holders.")
  public ForwardedClientIp getForwardedClientIp() {
    return forwardedClientIp;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Nested configuration objects are Spring-bound mutable property holders.")
  public TcpProxy getTcpProxy() {
    return tcpProxy;
  }

  public static final class ForwardedClientIp {
    private List<String> trustedProxyCidrs = new ArrayList<>();

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Spring configuration binder populates nested list properties through the live holder.")
    public List<String> getTrustedProxyCidrs() {
      return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
      this.trustedProxyCidrs =
          trustedProxyCidrs == null ? new ArrayList<>() : new ArrayList<>(trustedProxyCidrs);
    }
  }

  public static final class TcpProxy {
    private List<String> trustedClientCertFingerprintsSha256 = new ArrayList<>();
    private List<String> trustedClientCertDnsSans = new ArrayList<>();
    private List<String> trustedClientCertUriSans = new ArrayList<>();
    private boolean allowInsecureHeadersFromTrustedCidrs;
    private List<String> insecureTrustedCidrs = new ArrayList<>();

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Spring configuration binder populates nested list properties through the live holder.")
    public List<String> getTrustedClientCertFingerprintsSha256() {
      return trustedClientCertFingerprintsSha256;
    }

    public void setTrustedClientCertFingerprintsSha256(
        List<String> trustedClientCertFingerprintsSha256) {
      this.trustedClientCertFingerprintsSha256 =
          trustedClientCertFingerprintsSha256 == null
              ? new ArrayList<>()
              : new ArrayList<>(trustedClientCertFingerprintsSha256);
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Spring configuration binder populates nested list properties through the live holder.")
    public List<String> getTrustedClientCertDnsSans() {
      return trustedClientCertDnsSans;
    }

    public void setTrustedClientCertDnsSans(List<String> trustedClientCertDnsSans) {
      this.trustedClientCertDnsSans =
          trustedClientCertDnsSans == null
              ? new ArrayList<>()
              : new ArrayList<>(trustedClientCertDnsSans);
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Spring configuration binder populates nested list properties through the live holder.")
    public List<String> getTrustedClientCertUriSans() {
      return trustedClientCertUriSans;
    }

    public void setTrustedClientCertUriSans(List<String> trustedClientCertUriSans) {
      this.trustedClientCertUriSans =
          trustedClientCertUriSans == null
              ? new ArrayList<>()
              : new ArrayList<>(trustedClientCertUriSans);
    }

    public boolean isAllowInsecureHeadersFromTrustedCidrs() {
      return allowInsecureHeadersFromTrustedCidrs;
    }

    public void setAllowInsecureHeadersFromTrustedCidrs(
        boolean allowInsecureHeadersFromTrustedCidrs) {
      this.allowInsecureHeadersFromTrustedCidrs = allowInsecureHeadersFromTrustedCidrs;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Spring configuration binder populates nested list properties through the live holder.")
    public List<String> getInsecureTrustedCidrs() {
      return insecureTrustedCidrs;
    }

    public void setInsecureTrustedCidrs(List<String> insecureTrustedCidrs) {
      this.insecureTrustedCidrs =
          insecureTrustedCidrs == null ? new ArrayList<>() : new ArrayList<>(insecureTrustedCidrs);
    }
  }
}
