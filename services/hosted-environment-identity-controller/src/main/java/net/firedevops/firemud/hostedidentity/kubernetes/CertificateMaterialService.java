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
    return serialize(
        client,
        plan,
        materialize(
            client,
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            certificateFactory.ingress(plan),
            plan.ingressSecretName(),
            plan.hostname(),
            false,
            "kubernetes.io/tls",
            properties.getIngressTrustAnchorSha256()),
        List.of(plan.hostname()),
        false,
        "kubernetes.io/tls",
        properties.getIngressTrustAnchorSha256());
  }

  public RoleMaterial telnet(KubernetesClient client, EnvironmentIdentityPlan plan) {
    return serialize(
        client,
        plan,
        materialize(
            client,
            plan,
            HostedIdentityContract.TELNET_ROLE,
            certificateFactory.telnet(plan),
            plan.telnetSecretName(),
            plan.hostname(),
            false,
            "kubernetes.io/tls",
            properties.getTelnetTrustAnchorSha256()),
        List.of(plan.hostname()),
        false,
        "kubernetes.io/tls",
        properties.getTelnetTrustAnchorSha256());
  }

  public RoleMaterial grpc(
      KubernetesClient client, EnvironmentIdentityPlan plan, Long acceptedGeneration) {
    String activeRotation = selectedRotationRole(client, plan);
    Secret source;
    if (activeRotation != null && !HostedIdentityContract.GRPC_ROLE.equals(activeRotation)) {
      source =
          client
              .secrets()
              .inNamespace(plan.identityNamespace())
              .withName(plan.grpcSecretName())
              .get();
      if (source == null) {
        return RoleMaterial.pending(HostedIdentityContract.GRPC_ROLE, "serialized-behind-public");
      }
    } else {
      source =
          grpcBundleGenerator.ensure(
              client,
              plan,
              acceptedGeneration,
              properties.getGrpcRenewBefore(),
              properties.getGrpcTrustAnchorSha256());
    }
    SecretMaterialValidator.MaterialSummary summary =
        materialValidator.validate(
            source,
            GrpcTransportBundleGenerator.grpcDnsNames(plan),
            "Opaque",
            true,
            properties.getGrpcTrustAnchorSha256());
    long issuanceGeneration = GrpcTransportBundleGenerator.issuanceGeneration(source);
    return serialize(
        client,
        plan,
        new RoleMaterial(
            HostedIdentityContract.GRPC_ROLE,
            source,
            summary,
            issuanceGeneration,
            issuanceGeneration,
            HostedIdentityContract.TRANSPORT_PROVENANCE,
            "source-ready"),
        GrpcTransportBundleGenerator.grpcDnsNames(plan),
        true,
        "Opaque",
        properties.getGrpcTrustAnchorSha256());
  }

  /**
   * Returns either the current source or the last accepted snapshot. Only one changed role is
   * allowed to advance through projection, rollout, and acknowledgement at a time. A pending
   * rotation keeps ownership ahead of a newly observed source change, so restart/retry cannot
   * switch the active role midway through convergence.
   */
  private RoleMaterial serialize(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      RoleMaterial candidate,
      Collection<String> expectedDnsNames,
      boolean requireClientAuth,
      String expectedType,
      String trustAnchor) {
    if (!candidate.ready()) {
      return candidate;
    }
    String selected = selectedRotationRole(client, plan);
    if (selected == null) {
      return candidate;
    }
    if (candidate.role().equals(selected)) {
      return pendingMaterial(
          client, plan, candidate, expectedDnsNames, requireClientAuth, expectedType, trustAnchor);
    }
    if (!sourceDiffersFromAccepted(client, plan, candidate.role())) {
      return candidate;
    }
    return acceptedMaterial(
        client,
        plan,
        candidate.role(),
        expectedDnsNames,
        requireClientAuth,
        expectedType,
        trustAnchor);
  }

  private RoleMaterial pendingMaterial(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      RoleMaterial candidate,
      Collection<String> expectedDnsNames,
      boolean requireClientAuth,
      String expectedType,
      String trustAnchor) {
    Secret projection =
        client
            .secrets()
            .inNamespace(plan.runtimeNamespace())
            .withName(secretName(plan, candidate.role()))
            .get();
    requireOwned(projection, plan, candidate.role(), "runtime projection Secret");
    Map<String, String> annotations = projection.getMetadata().getAnnotations();
    String projectionRevision =
        SecretProjectionService.revisionForRole(candidate.role(), projection.getData());
    String acceptedRevision =
        annotation(annotations, HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    boolean pending =
        !"accepted"
                .equals(
                    annotation(annotations, HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION))
            || !projectionRevision.equals(acceptedRevision);
    String candidateRevision =
        SecretProjectionService.revisionForRole(candidate.role(), candidate.source().getData());
    if (!pendingProjectionOwnsRotation(pending, projectionRevision, candidateRevision)) {
      return candidate;
    }
    return projectionMaterial(
        projection,
        candidate.role(),
        expectedDnsNames,
        requireClientAuth,
        expectedType,
        trustAnchor,
        "serialized-in-flight");
  }

  static boolean pendingProjectionOwnsRotation(
      boolean pending, String projectionRevision, String candidateRevision) {
    return pending && !projectionRevision.equals(candidateRevision);
  }

  private String selectedRotationRole(KubernetesClient client, EnvironmentIdentityPlan plan) {
    List<RotationState> states =
        List.of(
            rotationState(client, plan, HostedIdentityContract.INGRESS_ROLE),
            rotationState(client, plan, HostedIdentityContract.TELNET_ROLE),
            rotationState(client, plan, HostedIdentityContract.GRPC_ROLE));
    return selectSerializedRole(states);
  }

  static String selectSerializedRole(List<RotationState> states) {
    if (states.stream().anyMatch(RotationState::uninitialized)) {
      return null;
    }
    return states.stream()
        .filter(RotationState::pending)
        .map(RotationState::role)
        .findFirst()
        .orElseGet(
            () ->
                states.stream()
                    .filter(RotationState::changed)
                    .map(RotationState::role)
                    .findFirst()
                    .orElse(null));
  }

  private RotationState rotationState(
      KubernetesClient client, EnvironmentIdentityPlan plan, String role) {
    Secret projection =
        client
            .secrets()
            .inNamespace(plan.runtimeNamespace())
            .withName(secretName(plan, role))
            .get();
    if (projection == null) {
      return new RotationState(role, false, false, true);
    }
    requireOwned(projection, plan, role, "runtime projection Secret");
    Map<String, String> annotations = projection.getMetadata().getAnnotations();
    String currentRevision = SecretProjectionService.revisionForRole(role, projection.getData());
    String recordedRevision = annotation(annotations, HostedIdentityContract.REVISION_ANNOTATION);
    if (!currentRevision.equals(recordedRevision)) {
      throw new IllegalStateException("runtime projection revision does not match its material");
    }
    String acceptedRevision =
        annotation(annotations, HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    if (acceptedRevision == null || !acceptedRevision.matches("sha256:[0-9a-f]{64}")) {
      return new RotationState(role, false, false, true);
    }
    boolean pending =
        !"accepted"
                .equals(
                    annotation(annotations, HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION))
            || !currentRevision.equals(acceptedRevision);
    return new RotationState(role, pending, sourceDiffersFromAccepted(client, plan, role), false);
  }

  private static boolean sourceDiffersFromAccepted(
      KubernetesClient client, EnvironmentIdentityPlan plan, String role) {
    Secret projection =
        client
            .secrets()
            .inNamespace(plan.runtimeNamespace())
            .withName(secretName(plan, role))
            .get();
    if (projection == null || projection.getMetadata() == null) {
      return false;
    }
    String acceptedRevision =
        annotation(
            projection.getMetadata().getAnnotations(),
            HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    Secret source =
        client
            .secrets()
            .inNamespace(plan.identityNamespace())
            .withName(secretName(plan, role))
            .get();
    if (source != null) {
      requireOwned(source, plan, role, "identity source Secret");
    }
    return source != null
        && source.getData() != null
        && !SecretProjectionService.revisionForRole(role, source.getData())
            .equals(acceptedRevision);
  }

  private RoleMaterial acceptedMaterial(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String role,
      Collection<String> expectedDnsNames,
      boolean requireClientAuth,
      String expectedType,
      String trustAnchor) {
    String name = secretName(plan, role);
    Secret current = client.secrets().inNamespace(plan.runtimeNamespace()).withName(name).get();
    requireOwned(current, plan, role, "runtime projection Secret");
    String acceptedRevision =
        annotation(
            current.getMetadata().getAnnotations(),
            HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    Secret accepted = current;
    if (!SecretProjectionService.revisionForRole(role, current.getData())
        .equals(acceptedRevision)) {
      accepted =
          client.secrets().inNamespace(plan.identityNamespace()).withName(name + "-previous").get();
      requireOwned(accepted, plan, role, "accepted predecessor Secret");
    }
    if (!SecretProjectionService.revisionForRole(role, accepted.getData())
        .equals(acceptedRevision)) {
      throw new IllegalStateException("accepted predecessor material is unavailable");
    }
    return projectionMaterial(
        accepted,
        role,
        expectedDnsNames,
        requireClientAuth,
        expectedType,
        trustAnchor,
        "serialized-deferred");
  }

  private RoleMaterial projectionMaterial(
      Secret projection,
      String role,
      Collection<String> expectedDnsNames,
      boolean requireClientAuth,
      String expectedType,
      String trustAnchor,
      String state) {
    Map<String, String> annotations = projection.getMetadata().getAnnotations();
    long sourceGeneration =
        positiveAnnotation(annotations, HostedIdentityContract.SOURCE_GENERATION_ANNOTATION);
    long sourceObjectGeneration =
        positiveAnnotation(annotations, HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION);
    String provenance = annotation(annotations, HostedIdentityContract.PROVENANCE_ANNOTATION);
    if (provenance == null || provenance.isBlank()) {
      throw new IllegalStateException("accepted projection provenance is invalid");
    }
    var summary =
        materialValidator.validate(
            projection, expectedDnsNames, expectedType, requireClientAuth, trustAnchor);
    return new RoleMaterial(
        role, projection, summary, sourceGeneration, sourceObjectGeneration, provenance, state);
  }

  private static void requireOwned(
      Secret secret, EnvironmentIdentityPlan plan, String role, String kind) {
    if (!SecretProjectionService.owned(secret, plan.name(), role)) {
      throw new IllegalStateException(kind + " is not controller-owned");
    }
  }

  private static String annotation(Map<String, String> annotations, String key) {
    return annotations == null ? null : annotations.get(key);
  }

  private static long positiveAnnotation(Map<String, String> annotations, String key) {
    try {
      long value = Long.parseLong(annotation(annotations, key));
      if (value < 1) {
        throw new NumberFormatException();
      }
      return value;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("accepted projection generation is invalid", exception);
    }
  }

  private static String secretName(EnvironmentIdentityPlan plan, String role) {
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> plan.ingressSecretName();
      case HostedIdentityContract.TELNET_ROLE -> plan.telnetSecretName();
      case HostedIdentityContract.GRPC_ROLE -> plan.grpcSecretName();
      default -> throw new IllegalArgumentException("unsupported identity role: " + role);
    };
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

  record RotationState(String role, boolean pending, boolean changed, boolean uninitialized) {}

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
