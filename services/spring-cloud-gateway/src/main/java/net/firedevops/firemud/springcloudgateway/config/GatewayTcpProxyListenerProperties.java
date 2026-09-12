package net.firedevops.firemud.springcloudgateway.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for the dedicated TCP Proxy to Gateway TLS WebSocket listener. */
@Component
@ConfigurationProperties(prefix = "firemud.gateway.tcp-proxy-listener")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "Nested mutable beans are intentionally exposed for Spring configuration-property binding.")
public final class GatewayTcpProxyListenerProperties {
  private boolean enabled;
  private String bindAddress = "0.0.0.0";
  private int port = 8443;
  private String certificateChainPath;
  private String privateKeyPath;
  private String trustedClientCaPath;
  private String environment;
  private String trustProfile;
  private final ProductionUri productionUri = new ProductionUri();
  private final MigrationDns migrationDns = new MigrationDns();
  private final BreakglassFingerprint breakglassFingerprint = new BreakglassFingerprint();
  private final DevelopmentCidr developmentCidr = new DevelopmentCidr();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBindAddress() {
    return bindAddress;
  }

  public void setBindAddress(String bindAddress) {
    this.bindAddress = bindAddress;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getCertificateChainPath() {
    return certificateChainPath;
  }

  public void setCertificateChainPath(String certificateChainPath) {
    this.certificateChainPath = certificateChainPath;
  }

  public String getPrivateKeyPath() {
    return privateKeyPath;
  }

  public void setPrivateKeyPath(String privateKeyPath) {
    this.privateKeyPath = privateKeyPath;
  }

  public String getTrustedClientCaPath() {
    return trustedClientCaPath;
  }

  public void setTrustedClientCaPath(String trustedClientCaPath) {
    this.trustedClientCaPath = trustedClientCaPath;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  public String getTrustProfile() {
    return trustProfile;
  }

  public void setTrustProfile(String trustProfile) {
    this.trustProfile = trustProfile;
  }

  public ProductionUri getProductionUri() {
    return productionUri;
  }

  public MigrationDns getMigrationDns() {
    return migrationDns;
  }

  public BreakglassFingerprint getBreakglassFingerprint() {
    return breakglassFingerprint;
  }

  public DevelopmentCidr getDevelopmentCidr() {
    return developmentCidr;
  }

  public static final class ProductionUri {
    private String uriSan;

    public String getUriSan() {
      return uriSan;
    }

    public void setUriSan(String uriSan) {
      this.uriSan = uriSan;
    }
  }

  public static final class MigrationDns {
    private String dnsSan;
    private String owner;
    private String reason;
    private String expiresAt;

    public String getDnsSan() {
      return dnsSan;
    }

    public void setDnsSan(String dnsSan) {
      this.dnsSan = dnsSan;
    }

    public String getOwner() {
      return owner;
    }

    public void setOwner(String owner) {
      this.owner = owner;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }

    public String getExpiresAt() {
      return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
      this.expiresAt = expiresAt;
    }
  }

  public static final class BreakglassFingerprint {
    private String sha256;
    private String incidentReference;
    private String expiresAt;

    public String getSha256() {
      return sha256;
    }

    public void setSha256(String sha256) {
      this.sha256 = sha256;
    }

    public String getIncidentReference() {
      return incidentReference;
    }

    public void setIncidentReference(String incidentReference) {
      this.incidentReference = incidentReference;
    }

    public String getExpiresAt() {
      return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
      this.expiresAt = expiresAt;
    }
  }

  public static final class DevelopmentCidr {
    private String trustedCidr;

    public String getTrustedCidr() {
      return trustedCidr;
    }

    public void setTrustedCidr(String trustedCidr) {
      this.trustedCidr = trustedCidr;
    }
  }
}
