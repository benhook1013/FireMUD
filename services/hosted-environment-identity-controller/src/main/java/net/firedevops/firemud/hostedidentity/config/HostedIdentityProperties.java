package net.firedevops.firemud.hostedidentity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firemud.hosted-identity")
public class HostedIdentityProperties {
  public enum ActivationMode {
    PAUSED,
    OBSERVE,
    ACTIVE
  }

  private String controlNamespace = "firemud-system";
  private String activationMode = "paused";
  private String previewDomain = "preview.firedevops.net";
  private String devDemoHostname = "dev.preview.firedevops.net";
  private String ingressIssuer = "letsencrypt-prod";
  private String telnetIssuer = "letsencrypt-prod";
  private String grpcIssuer = "firemud-ca-issuer";
  private String caSecretName = "firemud-grpc-ca";
  private String ingressTrustAnchorSha256 = "";
  private String telnetTrustAnchorSha256 = "";
  private String grpcTrustAnchorSha256 = "";
  private String ingressLeafSha256 = "";
  private String telnetLeafSha256 = "";
  private String previewHeadAnnotation = "firemud.dev/last-preview-head-sha";
  private String devDemoHeadAnnotation = "firemud.dev/last-dev-demo-head-sha";
  private String previewTelnetPortAnnotation = "firemud.dev/last-preview-telnet-port";
  private String devDemoTelnetPortAnnotation = "firemud.dev/last-dev-demo-telnet-port";
  private int previewTelnetPortBase = 32000;
  private int devDemoTelnetPort = 32016;
  private Duration reconcileInterval = Duration.ofSeconds(30);
  private Duration grpcRenewBefore = Duration.ofDays(7);

  public String getControlNamespace() {
    return controlNamespace;
  }

  public void setControlNamespace(String controlNamespace) {
    this.controlNamespace = controlNamespace;
  }

  public String getActivationMode() {
    return activationMode;
  }

  public void setActivationMode(String activationMode) {
    this.activationMode = activationMode;
  }

  /** Invalid or missing activation is deliberately treated as paused. */
  public ActivationMode activationMode() {
    if (activationMode == null) {
      return ActivationMode.PAUSED;
    }
    try {
      return ActivationMode.valueOf(activationMode.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return ActivationMode.PAUSED;
    }
  }

  public String getPreviewDomain() {
    return previewDomain;
  }

  public void setPreviewDomain(String previewDomain) {
    this.previewDomain = previewDomain;
  }

  public String getDevDemoHostname() {
    return devDemoHostname;
  }

  public void setDevDemoHostname(String devDemoHostname) {
    this.devDemoHostname = devDemoHostname;
  }

  public String getIngressIssuer() {
    return ingressIssuer;
  }

  public void setIngressIssuer(String ingressIssuer) {
    this.ingressIssuer = ingressIssuer;
  }

  public String getTelnetIssuer() {
    return telnetIssuer;
  }

  public void setTelnetIssuer(String telnetIssuer) {
    this.telnetIssuer = telnetIssuer;
  }

  public String getGrpcIssuer() {
    return grpcIssuer;
  }

  public void setGrpcIssuer(String grpcIssuer) {
    this.grpcIssuer = grpcIssuer;
  }

  public String getCaSecretName() {
    return caSecretName;
  }

  public void setCaSecretName(String caSecretName) {
    this.caSecretName = caSecretName;
  }

  public String getIngressTrustAnchorSha256() {
    return ingressTrustAnchorSha256;
  }

  public void setIngressTrustAnchorSha256(String ingressTrustAnchorSha256) {
    this.ingressTrustAnchorSha256 = ingressTrustAnchorSha256;
  }

  public String getTelnetTrustAnchorSha256() {
    return telnetTrustAnchorSha256;
  }

  public void setTelnetTrustAnchorSha256(String telnetTrustAnchorSha256) {
    this.telnetTrustAnchorSha256 = telnetTrustAnchorSha256;
  }

  public String getGrpcTrustAnchorSha256() {
    return grpcTrustAnchorSha256;
  }

  public void setGrpcTrustAnchorSha256(String grpcTrustAnchorSha256) {
    this.grpcTrustAnchorSha256 = grpcTrustAnchorSha256;
  }

  public String getIngressLeafSha256() {
    return ingressLeafSha256;
  }

  public void setIngressLeafSha256(String ingressLeafSha256) {
    this.ingressLeafSha256 = ingressLeafSha256;
  }

  public String getTelnetLeafSha256() {
    return telnetLeafSha256;
  }

  public void setTelnetLeafSha256(String telnetLeafSha256) {
    this.telnetLeafSha256 = telnetLeafSha256;
  }

  public String getPreviewHeadAnnotation() {
    return previewHeadAnnotation;
  }

  public void setPreviewHeadAnnotation(String previewHeadAnnotation) {
    this.previewHeadAnnotation = previewHeadAnnotation;
  }

  public String getDevDemoHeadAnnotation() {
    return devDemoHeadAnnotation;
  }

  public void setDevDemoHeadAnnotation(String devDemoHeadAnnotation) {
    this.devDemoHeadAnnotation = devDemoHeadAnnotation;
  }

  public String getPreviewTelnetPortAnnotation() {
    return previewTelnetPortAnnotation;
  }

  public void setPreviewTelnetPortAnnotation(String previewTelnetPortAnnotation) {
    this.previewTelnetPortAnnotation = previewTelnetPortAnnotation;
  }

  public String getDevDemoTelnetPortAnnotation() {
    return devDemoTelnetPortAnnotation;
  }

  public void setDevDemoTelnetPortAnnotation(String devDemoTelnetPortAnnotation) {
    this.devDemoTelnetPortAnnotation = devDemoTelnetPortAnnotation;
  }

  public int getPreviewTelnetPortBase() {
    return previewTelnetPortBase;
  }

  public void setPreviewTelnetPortBase(int previewTelnetPortBase) {
    this.previewTelnetPortBase = previewTelnetPortBase;
  }

  public int getDevDemoTelnetPort() {
    return devDemoTelnetPort;
  }

  public void setDevDemoTelnetPort(int devDemoTelnetPort) {
    this.devDemoTelnetPort = devDemoTelnetPort;
  }

  public Duration getReconcileInterval() {
    return reconcileInterval;
  }

  public void setReconcileInterval(Duration reconcileInterval) {
    this.reconcileInterval = reconcileInterval;
  }

  public Duration getGrpcRenewBefore() {
    return grpcRenewBefore;
  }

  public void setGrpcRenewBefore(Duration grpcRenewBefore) {
    this.grpcRenewBefore = grpcRenewBefore;
  }
}
