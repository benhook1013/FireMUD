package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.GrpcTransportBundleGenerator;
import net.firedevops.firemud.hostedidentity.security.SecretMaterialValidator;
import org.springframework.stereotype.Component;

/** Owns cert-manager ordering and public validation of materialized Secrets. */
@Component
public class CertificateMaterialService {
  private final CertificateResourceFactory certificateFactory;
  private final SecretMaterialValidator materialValidator;
  private final GrpcTransportBundleGenerator grpcBundleGenerator;
  private final HostedIdentityProperties properties;

  public CertificateMaterialService(
      CertificateResourceFactory certificateFactory,
      SecretMaterialValidator materialValidator,
      GrpcTransportBundleGenerator grpcBundleGenerator,
      HostedIdentityProperties properties) {
    this.certificateFactory = certificateFactory;
    this.materialValidator = materialValidator;
    this.grpcBundleGenerator = grpcBundleGenerator;
    this.properties = properties;
  }

  public RoleMaterial ingress(KubernetesClient client, EnvironmentIdentityPlan plan) {
    return materialize(
        client,
        plan,
        HostedIdentityContract.INGRESS_ROLE,
        certificateFactory.ingress(plan),
        plan.ingressSecretName(),
        plan.hostname(),
        false,
        "kubernetes.io/tls",
        properties.getIngressTrustAnchorSha256());
  }

  public RoleMaterial telnet(KubernetesClient client, EnvironmentIdentityPlan plan) {
    return materialize(
        client,
        plan,
        HostedIdentityContract.TELNET_ROLE,
        certificateFactory.telnet(plan),
        plan.telnetSecretName(),
        plan.hostname(),
        false,
        "kubernetes.io/tls",
        properties.getTelnetTrustAnchorSha256());
  }

  public RoleMaterial grpc(
      KubernetesClient client, EnvironmentIdentityPlan plan, Long acceptedGeneration) {
    Secret source =
        grpcBundleGenerator.ensure(
            client,
            plan,
            acceptedGeneration,
            properties.getGrpcRenewBefore(),
            properties.getGrpcTrustAnchorSha256());
    SecretMaterialValidator.MaterialSummary summary =
        materialValidator.validate(
            source,
            GrpcTransportBundleGenerator.grpcDnsNames(plan),
            "Opaque",
            true,
            properties.getGrpcTrustAnchorSha256());
    long issuanceGeneration = GrpcTransportBundleGenerator.issuanceGeneration(source);
    return new RoleMaterial(
        HostedIdentityContract.GRPC_ROLE,
        source,
        summary,
        issuanceGeneration,
        issuanceGeneration,
        HostedIdentityContract.TRANSPORT_PROVENANCE,
        "source-ready");
  }

  private RoleMaterial materialize(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String role,
      GenericKubernetesResource certificate,
      String secretName,
      String hostname,
      boolean requireClientAuth,
      String expectedType,
      String trustAnchor) {
    applyCertificate(client, plan.identityNamespace(), certificate);
    GenericKubernetesResource currentCertificate =
        client
            .genericKubernetesResources(ResourceContexts.CERTIFICATES)
            .inNamespace(plan.identityNamespace())
            .withName(certificate.getMetadata().getName())
            .get();
    CertificateRevision certificateRevision = readyRevision(currentCertificate);
    if (certificateRevision == null) {
      return RoleMaterial.pending(role, "certificate-pending");
    }
    Secret source =
        client.secrets().inNamespace(plan.identityNamespace()).withName(secretName).get();
    if (source == null) {
      return RoleMaterial.pending(role, "materialization-pending");
    }
    SecretMaterialValidator.MaterialSummary summary =
        materialValidator.validate(source, hostname, expectedType, requireClientAuth, trustAnchor);
    return new RoleMaterial(
        role,
        source,
        summary,
        certificateRevision.revision(),
        certificateRevision.objectGeneration(),
        "cert-manager",
        "source-ready");
  }

  private static void applyCertificate(
      KubernetesClient client, String namespace, GenericKubernetesResource desired) {
    var operation =
        client
            .genericKubernetesResources(ResourceContexts.CERTIFICATES)
            .inNamespace(namespace)
            .withName(desired.getMetadata().getName());
    GenericKubernetesResource existing = operation.get();
    if (existing == null) {
      client
          .genericKubernetesResources(ResourceContexts.CERTIFICATES)
          .inNamespace(namespace)
          .resource(desired)
          .create();
    } else {
      if (!"cert-manager.io/v1".equals(existing.getApiVersion())
          || !"Certificate".equals(existing.getKind())
          || existing.getMetadata() == null
          || !containsDesiredLabels(
              existing.getMetadata().getLabels(), desired.getMetadata().getLabels())) {
        throw new IllegalStateException("owned Certificate identity metadata drifted");
      }
      Object desiredSpec = desired.getAdditionalProperties().get("spec");
      Object existingSpec =
          existing.getAdditionalProperties() == null
              ? null
              : existing.getAdditionalProperties().get("spec");
      if (!desiredSubsetEquivalent(desiredSpec, existingSpec)) {
        throw new IllegalStateException("owned Certificate spec drifted; refusing replacement");
      }
    }
  }

  static boolean containsDesiredLabels(Map<String, String> existing, Map<String, String> desired) {
    return existing != null
        && desired != null
        && desired.entrySet().stream()
            .allMatch(entry -> Objects.equals(entry.getValue(), existing.get(entry.getKey())));
  }

  static boolean desiredSubsetEquivalent(Object desired, Object existing) {
    if (desired instanceof Map<?, ?> desiredMap) {
      if (!(existing instanceof Map<?, ?> existingMap)) {
        return false;
      }
      return desiredMap.entrySet().stream()
          .allMatch(
              entry ->
                  existingMap.containsKey(entry.getKey())
                      && desiredSubsetEquivalent(
                          entry.getValue(), existingMap.get(entry.getKey())));
    }
    if (desired instanceof Collection<?> desiredCollection) {
      if (!(existing instanceof Collection<?> existingCollection)
          || desiredCollection.size() != existingCollection.size()) {
        return false;
      }
      var left = desiredCollection.iterator();
      var right = existingCollection.iterator();
      while (left.hasNext()) {
        if (!desiredSubsetEquivalent(left.next(), right.next())) {
          return false;
        }
      }
      return true;
    }
    if (desired instanceof Number desiredNumber && existing instanceof Number existingNumber) {
      return new BigDecimal(desiredNumber.toString())
              .compareTo(new BigDecimal(existingNumber.toString()))
          == 0;
    }
    return Objects.equals(desired, existing);
  }

  static CertificateRevision readyRevision(GenericKubernetesResource resource) {
    if (resource == null
        || resource.getMetadata() == null
        || resource.getMetadata().getGeneration() == null
        || resource.getAdditionalProperties() == null) {
      return null;
    }
    Object status = resource.getAdditionalProperties().get("status");
    if (!(status instanceof Map<?, ?> statusMap)) {
      return null;
    }
    long generation = resource.getMetadata().getGeneration();
    Long revision = positiveLong(statusMap.get("revision"));
    if (revision == null) {
      return null;
    }
    Object conditions = statusMap.get("conditions");
    if (!(conditions instanceof List<?> conditionList)) {
      return null;
    }
    boolean ready =
        conditionList.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .anyMatch(
                condition ->
                    "Ready".equals(condition.get("type"))
                        && "True".equals(condition.get("status"))
                        && Long.valueOf(generation)
                            .equals(positiveLong(condition.get("observedGeneration"))));
    return ready ? new CertificateRevision(revision, generation) : null;
  }

  private static Long positiveLong(Object value) {
    try {
      long result =
          value instanceof Number number
              ? number.longValue()
              : Long.parseLong(String.valueOf(value));
      return result > 0 ? result : null;
    } catch (RuntimeException exception) {
      return null;
    }
  }

  record CertificateRevision(long revision, long objectGeneration) {}

  public record RoleMaterial(
      String role,
      Secret source,
      SecretMaterialValidator.MaterialSummary summary,
      long sourceGeneration,
      long sourceObjectGeneration,
      String provenance,
      String state) {
    static RoleMaterial pending(String role, String state) {
      return new RoleMaterial(role, null, null, 0, 0, "", state);
    }

    public boolean ready() {
      return source != null && summary != null;
    }

    public String revision() {
      return summary == null ? null : summary.certificateFingerprint();
    }
  }
}
