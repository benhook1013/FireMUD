package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/** Builds cert-manager Certificates without depending on a generated cert-manager Java model. */
@Component
public class CertificateResourceFactory {
  public GenericKubernetesResource ingress(EnvironmentIdentityPlan plan) {
    return certificate(
        plan,
        HostedIdentityContract.INGRESS_ROLE,
        plan.ingressCertificateName(),
        plan.ingressSecretName(),
        plan.ingressIssuer(),
        List.of(plan.hostname()),
        List.of("digital signature", "key encipherment", "server auth"));
  }

  public GenericKubernetesResource telnet(EnvironmentIdentityPlan plan) {
    return certificate(
        plan,
        HostedIdentityContract.TELNET_ROLE,
        plan.telnetCertificateName(),
        plan.telnetSecretName(),
        plan.telnetIssuer(),
        List.of(plan.hostname()),
        List.of("digital signature", "key encipherment", "server auth"));
  }

  private GenericKubernetesResource certificate(
      EnvironmentIdentityPlan plan,
      String role,
      String certificateName,
      String secretName,
      String issuer,
      List<String> dnsNames,
      List<String> usages) {
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
    privateKey.put("encoding", "PKCS8");
    privateKey.put("rotationPolicy", "Always");

    Map<String, Object> issuerRef = new LinkedHashMap<>();
    issuerRef.put("name", issuer);
    issuerRef.put("kind", "ClusterIssuer");
    issuerRef.put("group", "cert-manager.io");

    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("secretName", secretName);
    Map<String, Object> secretTemplateMetadata = new LinkedHashMap<>();
    secretTemplateMetadata.put("labels", HostedIdentityContract.managedLabels(plan.name(), role));
    secretTemplateMetadata.put(
        "annotations",
        Map.of(
            HostedIdentityContract.PROVENANCE_ANNOTATION,
            "cert-manager",
            HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION,
            "source-materialized"));
    spec.put("secretTemplate", Map.of("metadata", secretTemplateMetadata));
    spec.put("privateKey", privateKey);
    spec.put("dnsNames", new ArrayList<>(dnsNames));
    spec.put("usages", new ArrayList<>(usages));
    spec.put("issuerRef", issuerRef);
    resource.setAdditionalProperties(Map.of("spec", spec));
    return resource;
  }
}
