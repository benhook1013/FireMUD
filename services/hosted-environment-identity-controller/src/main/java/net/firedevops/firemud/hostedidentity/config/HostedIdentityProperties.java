package net.firedevops.firemud.hostedidentity.config;

import java.time.Duration;
import java.util.regex.Pattern;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firemud.hosted-identity")
public class HostedIdentityProperties implements InitializingBean {
  private static final Logger LOGGER = LoggerFactory.getLogger(HostedIdentityProperties.class);
  private static final int MAX_HOSTNAME_LENGTH = 253;
  private static final Pattern HOSTNAME_PATTERN =
      Pattern.compile(
          "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$");
  public static final Duration INTERNAL_CERTIFICATE_DURATION = Duration.ofDays(30);
  public static final Duration INTERNAL_CERTIFICATE_RENEWAL_SLACK = Duration.ofMinutes(5);
  public static final Duration MINIMUM_GRPC_RENEW_BEFORE = Duration.ofMinutes(5);
  private static final int CANONICAL_PREVIEW_TELNET_PORT_BASE = 32000;
  private static final int CANONICAL_DEV_DEMO_TELNET_PORT = 32016;

  public enum ActivationMode {
    PAUSED,
    OBSERVE,
    ACTIVE
  }

  private String controlNamespace = "firemud-system";
  private String activationMode = "paused";
  private ActivationMode resolvedActivationMode = ActivationMode.PAUSED;
  private String previewDomain = "preview.firedevops.net";
  private String devDemoHostname = "dev.preview.firedevops.net";
  private String ingressIssuer = "letsencrypt-prod";
  private String telnetIssuer = "letsencrypt-prod";
  private String grpcIssuer = "firemud-ca-issuer";
  private String caSecretName = HostedIdentityContract.GRPC_CA_SECRET_NAME;
  private String ingressTrustAnchorSha256 = "";
  private String telnetTrustAnchorSha256 = "";
  private String grpcTrustAnchorSha256 = "";
  private String ingressLeafSha256 = "";
  private String telnetLeafSha256 = "";
  private String previewRequestedHeadAnnotation = "firemud.dev/requested-preview-head-sha";
  private String previewDeployedHeadAnnotation = "firemud.dev/last-preview-head-sha";
  private String devDemoRequestedHeadAnnotation = "firemud.dev/requested-dev-demo-head-sha";
  private String devDemoHeadAnnotation = "firemud.dev/last-dev-demo-head-sha";
  private String previewTelnetPortAnnotation = "firemud.dev/last-preview-telnet-port";
  private String devDemoTelnetPortAnnotation = "firemud.dev/last-dev-demo-telnet-port";
  private int previewTelnetPortBase = CANONICAL_PREVIEW_TELNET_PORT_BASE;
  private int devDemoTelnetPort = CANONICAL_DEV_DEMO_TELNET_PORT;
  private Duration reconcileInterval = Duration.ofSeconds(30);
  private Duration grpcRenewBefore = Duration.ofDays(7);

  @Override
  public void afterPropertiesSet() {
    ActivationMode resolvedMode = resolveActivationMode(true);
    if (!HostedIdentityContract.CONTROL_NAMESPACE.equals(controlNamespace)) {
      throw new IllegalStateException(
          "hosted identity control namespace must be " + HostedIdentityContract.CONTROL_NAMESPACE);
    }
    if (!HostedIdentityContract.GRPC_CA_SECRET_NAME.equals(caSecretName)) {
      throw new IllegalStateException(
          "gRPC CA secret name must be " + HostedIdentityContract.GRPC_CA_SECRET_NAME);
    }
    requireValidHostname("preview domain", previewDomain);
    requireValidHostname("dev-demo hostname", devDemoHostname);
    requireCanonicalTelnetPort(
        "preview Telnet port base", previewTelnetPortBase, CANONICAL_PREVIEW_TELNET_PORT_BASE);
    requireCanonicalTelnetPort(
        "dev-demo Telnet port", devDemoTelnetPort, CANONICAL_DEV_DEMO_TELNET_PORT);
    requireValidGrpcRenewBefore(grpcRenewBefore);
    requireValidOptionalSha256Pin("ingress trust-anchor SHA-256 pin", ingressTrustAnchorSha256);
    requireValidOptionalSha256Pin("telnet trust-anchor SHA-256 pin", telnetTrustAnchorSha256);
    requireValidOptionalSha256Pin("gRPC trust-anchor SHA-256 pin", grpcTrustAnchorSha256);
    if (resolvedMode == ActivationMode.ACTIVE) {
      requireConfiguredSha256Pin("gRPC trust-anchor SHA-256 pin", grpcTrustAnchorSha256);
    }
    requireValidOptionalSha256Pin("ingress leaf SHA-256 pin", ingressLeafSha256);
    requireValidOptionalSha256Pin("telnet leaf SHA-256 pin", telnetLeafSha256);
    if (reconcileInterval == null || reconcileInterval.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new IllegalStateException("reconcile interval must be at least 1 second");
    }
    resolvedActivationMode = resolvedMode;
  }

  private static void requireValidOptionalSha256Pin(String propertyName, String value) {
    if (value != null && !value.isEmpty() && !value.matches("[0-9a-f]{64}")) {
      throw new IllegalStateException(
          propertyName + " must be empty or 64 lowercase hexadecimal characters");
    }
  }

  private static void requireConfiguredSha256Pin(String propertyName, String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalStateException(
          propertyName
              + " must be a nonempty 64 lowercase hexadecimal value when activation is active");
    }
  }

  public static void requireValidHostname(String propertyName, String value) {
    if (value == null
        || value.length() > MAX_HOSTNAME_LENGTH
        || !HOSTNAME_PATTERN.matcher(value).matches()) {
      throw new IllegalStateException(
          propertyName
              + " must match the lowercase hostname contract and contain at most "
              + MAX_HOSTNAME_LENGTH
              + " characters");
    }
  }

  private static void requireCanonicalTelnetPort(
      String propertyName, int actualPort, int canonicalPort) {
    if (actualPort != canonicalPort) {
      throw new IllegalStateException(propertyName + " must be " + canonicalPort);
    }
  }

  public static void requireValidGrpcRenewBefore(Duration renewBefore) {
    if (renewBefore == null
        || renewBefore.compareTo(MINIMUM_GRPC_RENEW_BEFORE) < 0
        || renewBefore.compareTo(
                INTERNAL_CERTIFICATE_DURATION.minus(INTERNAL_CERTIFICATE_RENEWAL_SLACK))
            > 0) {
      throw new IllegalStateException(
          "gRPC renewal window must be at least 5 minutes and leave at least 5 minutes before the 30-day certificate expiry");
    }
  }

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
    this.resolvedActivationMode = resolveActivationMode(false);
  }

  /** Invalid or missing activation is deliberately treated as paused. */
  public ActivationMode activationMode() {
    return resolvedActivationMode;
  }

  private ActivationMode resolveActivationMode(boolean warnIfInvalid) {
    if (activationMode == null) {
      return ActivationMode.PAUSED;
    }
    try {
      return ActivationMode.valueOf(activationMode.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      if (warnIfInvalid) {
        LOGGER.warn(
            "Rejected hosted identity activation mode '{}'; defaulting to paused", activationMode);
      }
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

  public String getPreviewRequestedHeadAnnotation() {
    return previewRequestedHeadAnnotation;
  }

  public void setPreviewRequestedHeadAnnotation(String previewRequestedHeadAnnotation) {
    this.previewRequestedHeadAnnotation = previewRequestedHeadAnnotation;
  }

  public String getPreviewDeployedHeadAnnotation() {
    return previewDeployedHeadAnnotation;
  }

  public void setPreviewDeployedHeadAnnotation(String previewDeployedHeadAnnotation) {
    this.previewDeployedHeadAnnotation = previewDeployedHeadAnnotation;
  }

  public String getDevDemoHeadAnnotation() {
    return devDemoHeadAnnotation;
  }

  public String getDevDemoRequestedHeadAnnotation() {
    return devDemoRequestedHeadAnnotation;
  }

  public void setDevDemoRequestedHeadAnnotation(String devDemoRequestedHeadAnnotation) {
    this.devDemoRequestedHeadAnnotation = devDemoRequestedHeadAnnotation;
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
