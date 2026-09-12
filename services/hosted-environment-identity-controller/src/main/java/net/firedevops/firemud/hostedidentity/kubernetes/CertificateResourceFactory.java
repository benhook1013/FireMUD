package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/** Builds cert-manager Certificates without depending on a generated cert-manager Java model. */
@Component
public class CertificateResourceFactory {
  private static final Duration PUBLIC_CERTIFICATE_DURATION = Duration.ofDays(90);

  public GenericKubernetesResource ingress(EnvironmentIdentityPlan plan) {
    return certificate(
        plan,
        HostedIdentityContract.INGRESS_ROLE,
        plan.ingressCertificateName(),
        plan.ingressSecretName(),
        plan.ingressIssuer(),
        List.of(plan.hostname()),
        List.of(),
        List.of("digital signature", "key encipherment", "server auth"),
        PUBLIC_CERTIFICATE_DURATION,
        null);
  }

  public GenericKubernetesResource telnet(EnvironmentIdentityPlan plan) {
    return certificate(
        plan,
        HostedIdentityContract.TELNET_ROLE,
        plan.telnetCertificateName(),
        plan.telnetSecretName(),
        plan.telnetIssuer(),
        List.of(plan.hostname()),
        List.of(),
        List.of("digital signature", "key encipherment", "server auth"),
        PUBLIC_CERTIFICATE_DURATION,
        null);
  }

  public GenericKubernetesResource gatewayInternalWs(
      EnvironmentIdentityPlan plan, Duration renewBefore) {
    HostedIdentityProperties.requireValidGrpcRenewBefore(renewBefore);
    return certificate(
        plan,
        HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
        plan.gatewayInternalWsCertificateName(),
        plan.gatewayInternalWsSecretName(),
        plan.grpcIssuer(),
        List.of(plan.gatewayInternalWsDnsName()),
        List.of(),
        List.of("digital signature", "key encipherment", "server auth"),
        HostedIdentityProperties.INTERNAL_CERTIFICATE_DURATION,
        renewBefore);
  }

  public GenericKubernetesResource tcpProxyBridge(
      EnvironmentIdentityPlan plan, Duration renewBefore) {
    HostedIdentityProperties.requireValidGrpcRenewBefore(renewBefore);
    return certificate(
        plan,
        HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
        plan.tcpProxyBridgeCertificateName(),
        plan.tcpProxyBridgeSecretName(),
        plan.grpcIssuer(),
        List.of(),
        List.of(plan.tcpProxyBridgeUriSan()),
        List.of("digital signature", "key encipherment", "client auth"),
        HostedIdentityProperties.INTERNAL_CERTIFICATE_DURATION,
        renewBefore);
  }

  private GenericKubernetesResource certificate(
      EnvironmentIdentityPlan plan,
      String role,
      String certificateName,
      String secretName,
      String issuer,
      List<String> dnsNames,
      List<String> uriSans,
      List<String> usages,
      Duration duration,
      Duration renewBefore) {
    GenericKubernetesResource resource = new GenericKubernetesResource();
    resource.setApiVersion("cert-manager.io/v1");
    resource.setKind("Certificate");
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName(certificateName)
            .withNamespace(plan.identityNamespace())
            .withLabels(HostedIdentityContract.managedLabels(plan.name(), role))
            .build());

    Map<String, Object> privateKey = new LinkedHashMap<>();
    privateKey.put("algorithm", "RSA");
    privateKey.put("size", HostedIdentityProperties.CERTIFICATE_RSA_KEY_SIZE_BITS);
    privateKey.put("encoding", "PKCS8");
    privateKey.put("rotationPolicy", "Always");

    Map<String, Object> issuerRef = new LinkedHashMap<>();
    issuerRef.put("name", issuer);
    issuerRef.put("kind", "ClusterIssuer");
    issuerRef.put("group", "cert-manager.io");

    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("secretName", secretName);
    spec.put("isCA", false);
    Map<String, Object> secretTemplateMetadata = new LinkedHashMap<>();
    secretTemplateMetadata.put("labels", HostedIdentityContract.managedLabels(plan.name(), role));
    secretTemplateMetadata.put(
        "annotations",
        Map.of(
            HostedIdentityContract.PROVENANCE_ANNOTATION,
            "cert-manager",
            HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION,
            "source-materialized"));
    spec.put("secretTemplate", secretTemplateMetadata);
    spec.put("privateKey", privateKey);
    if (!dnsNames.isEmpty()) {
      spec.put("dnsNames", new ArrayList<>(dnsNames));
    }
    if (!uriSans.isEmpty()) {
      spec.put("uris", new ArrayList<>(uriSans));
    }
    spec.put("usages", new ArrayList<>(usages));
    spec.put("encodeUsagesInRequest", true);
    spec.put("issuerRef", issuerRef);
    if (duration != null) {
      spec.put("duration", certManagerDuration(duration));
    }
    if (renewBefore != null) {
      HostedIdentityProperties.requireValidGrpcRenewBefore(renewBefore);
      spec.put("renewBefore", certManagerDuration(renewBefore));
    }
    resource.setAdditionalProperties(Map.of("spec", spec));
    return resource;
  }

  private static String certManagerDuration(Duration duration) {
    long nanoseconds = duration.toNanos();
    long nanosecondsPerHour = Duration.ofHours(1).toNanos();
    if (nanoseconds % nanosecondsPerHour == 0) {
      return nanoseconds / nanosecondsPerHour + "h";
    }
    long nanosecondsPerSecond = Duration.ofSeconds(1).toNanos();
    if (nanoseconds % nanosecondsPerSecond == 0) {
      return nanoseconds / nanosecondsPerSecond + "s";
    }
    return nanoseconds + "ns";
  }
}
