package net.firedevops.firemud.hostedidentity.kubernetes;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
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

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected configuration is application-scoped and is never exposed.")
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

  /**
   * Creates one ordered reconciliation batch whose first ready role lazily captures the shared
   * rotation-selection snapshot.
   */
  public MaterializationBatch beginMaterialization(
      KubernetesClient client, EnvironmentIdentityPlan plan) {
    return new MaterializationBatch(client, plan);
  }

  private RoleMaterial ingress(
      KubernetesClient client, EnvironmentIdentityPlan plan, MaterializationBatch batch) {
    RoleExpectation expectation =
        new RoleExpectation(
            List.of(plan.hostname()),
            List.of(),
            true,
            false,
            "kubernetes.io/tls",
            properties.getIngressTrustAnchorSha256());
    return materializeSerialized(
        client,
        plan,
        HostedIdentityContract.INGRESS_ROLE,
        certificateFactory.ingress(plan),
        plan.ingressSecretName(),
        expectation,
        batch);
  }

  private RoleMaterial telnet(
      KubernetesClient client, EnvironmentIdentityPlan plan, MaterializationBatch batch) {
    RoleExpectation expectation =
        new RoleExpectation(
            List.of(plan.hostname()),
            List.of(),
            true,
            false,
            "kubernetes.io/tls",
            properties.getTelnetTrustAnchorSha256());
    return materializeSerialized(
        client,
        plan,
        HostedIdentityContract.TELNET_ROLE,
        certificateFactory.telnet(plan),
        plan.telnetSecretName(),
        expectation,
        batch);
  }

  private RoleMaterial gatewayInternalWs(
      KubernetesClient client, EnvironmentIdentityPlan plan, MaterializationBatch batch) {
    RoleExpectation expectation =
        new RoleExpectation(
            List.of(plan.gatewayInternalWsDnsName()),
            List.of(),
            true,
            false,
            "kubernetes.io/tls",
            properties.getGrpcTrustAnchorSha256());
    return materializeSerialized(
        client,
        plan,
        HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
        certificateFactory.gatewayInternalWs(plan, properties.getGrpcRenewBefore()),
        plan.gatewayInternalWsSecretName(),
        expectation,
        batch);
  }

  private RoleMaterial tcpProxyBridge(
      KubernetesClient client, EnvironmentIdentityPlan plan, MaterializationBatch batch) {
    RoleExpectation expectation =
        new RoleExpectation(
            List.of(),
            List.of(plan.tcpProxyBridgeUriSan()),
            false,
            true,
            "kubernetes.io/tls",
            properties.getGrpcTrustAnchorSha256());
    return materializeSerialized(
        client,
        plan,
        HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
        certificateFactory.tcpProxyBridge(plan, properties.getGrpcRenewBefore()),
        plan.tcpProxyBridgeSecretName(),
        expectation,
        batch);
  }

  private RoleMaterial grpc(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      Long acceptedGeneration,
      MaterializationBatch batch) {
    RoleExpectation expectation =
        new RoleExpectation(
            GrpcTransportBundleGenerator.grpcDnsNames(plan),
            List.of(),
            true,
            true,
            "Opaque",
            properties.getGrpcTrustAnchorSha256());
    RoleMaterial pinned =
        batch.pinnedUnacceptedMaterial(HostedIdentityContract.GRPC_ROLE, expectation);
    if (pinned != null) {
      return pinned;
    }
    if (batch.deferBehindSelectedRotation(HostedIdentityContract.GRPC_ROLE)) {
      return acceptedMaterial(client, plan, HostedIdentityContract.GRPC_ROLE, expectation);
    }
    if (batch.deferBehindPostSnapshotRotation(HostedIdentityContract.GRPC_ROLE)) {
      return acceptedMaterial(client, plan, HostedIdentityContract.GRPC_ROLE, expectation);
    }
    Secret source =
        grpcBundleGenerator.ensure(
            client,
            plan,
            acceptedGeneration,
            properties.getGrpcRenewBefore(),
            properties.getGrpcTrustAnchorSha256());
    requireOwned(source, plan, HostedIdentityContract.GRPC_ROLE, "identity source Secret");
    SecretMaterialValidator.MaterialSummary summary =
        materialValidator.validateIdentity(
            source,
            expectation.expectedDnsNames(),
            expectation.expectedUriSans(),
            expectation.expectedType(),
            expectation.requireServerAuth(),
            expectation.requireClientAuth(),
            expectation.trustAnchor());
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
        expectation,
        batch);
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
      RoleExpectation expectation,
      MaterializationBatch batch) {
    if (!candidate.ready()) {
      return candidate;
    }
    String selectedRotationRole = batch.selectedRotationRole();
    if (selectedRotationRole == null) {
      return batch.claimPostSnapshotRotation(candidate)
          ? candidate
          : acceptedMaterial(client, plan, candidate.role(), expectation);
    }
    if (candidate.role().equals(selectedRotationRole)) {
      return pendingMaterial(client, plan, candidate, expectation);
    }
    if (batch.initializing(candidate.role())) {
      return candidate;
    }
    return acceptedMaterial(client, plan, candidate.role(), expectation);
  }

  private RoleMaterial materializeSerialized(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String role,
      GenericKubernetesResource certificate,
      String secretName,
      RoleExpectation expectation,
      MaterializationBatch batch) {
    if (batch.rotationSelectionResolved && batch.deferBehindSelectedRotation(role)) {
      return acceptedMaterial(client, plan, role, expectation);
    }
    ReadyCertificate readyCertificate =
        readyCertificateRevision(client, plan.identityNamespace(), certificate);
    if (readyCertificate == null) {
      return RoleMaterial.pending(role, "certificate-pending");
    }
    batch.selectedRotationRole();
    RoleMaterial pinned = batch.pinnedUnacceptedMaterial(role, expectation);
    if (pinned != null) {
      return pinned;
    }
    if (batch.deferBehindSelectedRotation(role)) {
      return acceptedMaterial(client, plan, role, expectation);
    }
    return serialize(
        client,
        plan,
        materializeSource(client, plan, role, secretName, expectation, readyCertificate),
        expectation,
        batch);
  }

  private RoleMaterial pendingMaterial(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      RoleMaterial candidate,
      RoleExpectation expectation) {
    Secret projection =
        client
            .secrets()
            .inNamespace(plan.runtimeNamespace())
            .withName(secretName(plan, candidate.role()))
            .get();
    requireOwned(projection, plan, candidate.role(), "runtime projection Secret");
    Map<String, String> annotations = projection.getMetadata().getAnnotations();
    String projectionRevision = runtimeProjectionRevision(candidate.role(), projection);
    String recordedRevision = annotation(annotations, HostedIdentityContract.REVISION_ANNOTATION);
    String candidateRevision =
        SecretProjectionService.revisionForRole(candidate.role(), candidate.source().getData());
    if (projectionRevision == null || !projectionRevision.equals(recordedRevision)) {
      if (!candidateRevision.equals(recordedRevision)) {
        throw new IllegalStateException(
            "runtime projection material drifted while its identity source advanced");
      }
      return candidate;
    }
    String acceptedRevision =
        annotation(annotations, HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    boolean pending =
        !"accepted"
                .equals(
                    annotation(annotations, HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION))
            || !projectionRevision.equals(acceptedRevision);
    if (!pendingProjectionOwnsRotation(pending, projectionRevision, candidateRevision)) {
      return candidate;
    }
    return projectionMaterial(projection, candidate.role(), expectation, "serialized-in-flight");
  }

  static boolean pendingProjectionOwnsRotation(
      boolean pending, String projectionRevision, String candidateRevision) {
    return pending && !projectionRevision.equals(candidateRevision);
  }

  private RotationSnapshot rotationSnapshot(KubernetesClient client, EnvironmentIdentityPlan plan) {
    Map<String, RotationObservation> observations = new LinkedHashMap<>();
    for (String role :
        List.of(
            HostedIdentityContract.INGRESS_ROLE,
            HostedIdentityContract.TELNET_ROLE,
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
            HostedIdentityContract.GRPC_ROLE)) {
      observations.put(role, rotationObservation(client, plan, role));
    }
    String selectedRole =
        selectSerializedRole(
            observations.values().stream().map(RotationObservation::state).toList());
    return new RotationSnapshot(selectedRole, Map.copyOf(observations));
  }

  static String selectSerializedRole(List<RotationState> states) {
    String driftedRole =
        states.stream()
            .filter(RotationState::drifted)
            .map(RotationState::role)
            .findFirst()
            .orElse(null);
    if (driftedRole != null) {
      return driftedRole;
    }
    String pendingRole =
        states.stream()
            .filter(RotationState::pending)
            .map(RotationState::role)
            .findFirst()
            .orElse(null);
    if (pendingRole != null) {
      return pendingRole;
    }
    String changedRole =
        states.stream()
            .filter(RotationState::changed)
            .map(RotationState::role)
            .findFirst()
            .orElse(null);
    if (changedRole != null) {
      return changedRole;
    }
    return null;
  }

  private RotationObservation rotationObservation(
      KubernetesClient client, EnvironmentIdentityPlan plan, String role) {
    Secret projection =
        client
            .secrets()
            .inNamespace(plan.runtimeNamespace())
            .withName(secretName(plan, role))
            .get();
    if (projection == null) {
      return new RotationObservation(
          new RotationState(role, false, false, true, false), null, null, false);
    }
    requireOwned(projection, plan, role, "runtime projection Secret");
    Map<String, String> annotations = projection.getMetadata().getAnnotations();
    String currentRevision = runtimeProjectionRevision(role, projection);
    String recordedRevision = annotation(annotations, HostedIdentityContract.REVISION_ANNOTATION);
    if (currentRevision == null || !currentRevision.equals(recordedRevision)) {
      return new RotationObservation(
          new RotationState(role, true, false, false, true), projection, null, false);
    }
    String acceptedRevision =
        annotation(annotations, HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    if (acceptedRevision == null || !acceptedRevision.matches("sha256:[0-9a-f]{64}")) {
      return new RotationObservation(
          new RotationState(role, false, false, true, false), projection, null, true);
    }
    boolean pending =
        !"accepted"
                .equals(
                    annotation(annotations, HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION))
            || !currentRevision.equals(acceptedRevision);
    String sourceRevision = sourceRevision(client, plan, role);
    return new RotationObservation(
        new RotationState(
            role,
            pending,
            sourceRevision != null && !sourceRevision.equals(acceptedRevision),
            false,
            false),
        projection,
        sourceRevision,
        false);
  }

  private static String sourceRevision(
      KubernetesClient client, EnvironmentIdentityPlan plan, String role) {
    Secret source =
        client
            .secrets()
            .inNamespace(plan.identityNamespace())
            .withName(secretName(plan, role))
            .get();
    if (source != null) {
      requireIdentitySourceBinding(source, plan, role);
    }
    return source == null || source.getData() == null
        ? null
        : SecretProjectionService.revisionForRole(role, source.getData());
  }

  private static String runtimeProjectionRevision(String role, Secret projection) {
    try {
      return SecretProjectionService.revisionForRole(role, projection.getData());
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private RoleMaterial acceptedMaterial(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String role,
      RoleExpectation expectation) {
    String name = secretName(plan, role);
    Secret current = client.secrets().inNamespace(plan.runtimeNamespace()).withName(name).get();
    requireOwned(current, plan, role, "runtime projection Secret");
    Map<String, String> annotations = current.getMetadata().getAnnotations();
    String acceptedRevision =
        annotation(annotations, HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    if (acceptedRevision == null || !acceptedRevision.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalStateException("accepted projection snapshot is unavailable");
    }
    long acceptedGeneration =
        positiveAnnotation(
            annotations, HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION);
    long acceptedObjectGeneration =
        positiveAnnotation(
            annotations, HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION);
    String acceptedSpki =
        annotation(annotations, HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION);
    if (acceptedSpki == null || !acceptedSpki.matches("[0-9a-f]{64}")) {
      throw new IllegalStateException("accepted projection SPKI fingerprint is invalid");
    }
    String recordedRevision = annotation(annotations, HostedIdentityContract.REVISION_ANNOTATION);
    String runtimeRevision = runtimeProjectionRevision(role, current);
    boolean drifted = runtimeRevision == null || !runtimeRevision.equals(recordedRevision);
    Secret accepted = current;
    if (!acceptedRevision.equals(runtimeRevision)) {
      accepted =
          client.secrets().inNamespace(plan.identityNamespace()).withName(name + "-previous").get();
      if (accepted != null) {
        requireOwned(accepted, plan, role, "accepted predecessor Secret");
      }
    }
    if (accepted == null || !acceptedRevision.equals(runtimeProjectionRevision(role, accepted))) {
      Secret retainedSource =
          client.secrets().inNamespace(plan.identityNamespace()).withName(name).get();
      requireIdentitySourceBinding(retainedSource, plan, role);
      if (!acceptedRevision.equals(runtimeProjectionRevision(role, retainedSource))) {
        throw new IllegalStateException("accepted predecessor material is unavailable");
      }
      accepted = retainedSource;
    }
    SecretMaterialValidator.MaterialSummary summary =
        validateAcceptedMaterial(accepted, role, expectation);
    if (!acceptedSpki.equals(summary.spkiSha256())) {
      throw new IllegalStateException("accepted projection SPKI fingerprint changed");
    }
    String provenance =
        HostedIdentityContract.GRPC_ROLE.equals(role)
            ? HostedIdentityContract.TRANSPORT_PROVENANCE
            : "cert-manager";
    return new RoleMaterial(
        role,
        accepted,
        summary,
        acceptedGeneration,
        acceptedObjectGeneration,
        provenance,
        drifted ? "serialized-deferred-drift" : "serialized-deferred");
  }

  private SecretMaterialValidator.MaterialSummary validateAcceptedMaterial(
      Secret accepted, String role, RoleExpectation current) {
    boolean sharedGrpcTrust =
        HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE.equals(role)
            || HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE.equals(role)
            || HostedIdentityContract.GRPC_ROLE.equals(role);
    Secret validationSecret = accepted;
    String expectedType = current.expectedType();
    String expectedTrustAnchor = current.trustAnchor();
    if (sharedGrpcTrust) {
      expectedTrustAnchor = "";
      if (HostedIdentityContract.GRPC_ROLE.equals(role)) {
        if (!"Opaque".equals(accepted.getType())) {
          throw new IllegalStateException("accepted gRPC projection has an unexpected type");
        }
        validationSecret =
            new io.fabric8.kubernetes.api.model.SecretBuilder(accepted)
                .withType("kubernetes.io/tls")
                .build();
        expectedType = "kubernetes.io/tls";
      }
    }
    return materialValidator.validateIdentity(
        validationSecret,
        current.expectedDnsNames(),
        current.expectedUriSans(),
        expectedType,
        current.requireServerAuth(),
        current.requireClientAuth(),
        expectedTrustAnchor);
  }

  private RoleMaterial projectionMaterial(
      Secret projection, String role, RoleExpectation expectation, String state) {
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
        materialValidator.validateIdentity(
            projection,
            expectation.expectedDnsNames(),
            expectation.expectedUriSans(),
            expectation.expectedType(),
            expectation.requireServerAuth(),
            expectation.requireClientAuth(),
            expectation.trustAnchor());
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
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE -> plan.gatewayInternalWsSecretName();
      case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> plan.tcpProxyBridgeSecretName();
      case HostedIdentityContract.GRPC_ROLE -> plan.grpcSecretName();
      default -> throw new IllegalArgumentException("unsupported identity role: " + role);
    };
  }

  private static String certificateName(EnvironmentIdentityPlan plan, String role) {
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> plan.ingressCertificateName();
      case HostedIdentityContract.TELNET_ROLE -> plan.telnetCertificateName();
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE ->
          plan.gatewayInternalWsCertificateName();
      case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> plan.tcpProxyBridgeCertificateName();
      default ->
          throw new IllegalArgumentException("unsupported cert-manager identity role: " + role);
    };
  }

  private static String issuerName(EnvironmentIdentityPlan plan, String role) {
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> plan.ingressIssuer();
      case HostedIdentityContract.TELNET_ROLE -> plan.telnetIssuer();
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
          HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE ->
          plan.grpcIssuer();
      default ->
          throw new IllegalArgumentException("unsupported cert-manager identity role: " + role);
    };
  }

  private ReadyCertificate readyCertificateRevision(
      KubernetesClient client, String identityNamespace, GenericKubernetesResource certificate) {
    applyCertificate(client, identityNamespace, certificate);
    GenericKubernetesResource currentCertificate =
        client
            .genericKubernetesResources(ResourceContexts.CERTIFICATES)
            .inNamespace(identityNamespace)
            .withName(certificate.getMetadata().getName())
            .get();
    CertificateRevision revision = readyRevision(currentCertificate);
    if (revision == null) {
      return null;
    }
    String uid = currentCertificate.getMetadata().getUid();
    if (uid == null || uid.isBlank()) {
      throw new IllegalStateException("ready Certificate UID is unavailable");
    }
    return new ReadyCertificate(
        currentCertificate.getMetadata().getName(),
        uid,
        issuerReference(certificate),
        revision.revision(),
        revision.objectGeneration());
  }

  private RoleMaterial materializeSource(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String role,
      String secretName,
      RoleExpectation expectation,
      ReadyCertificate readyCertificate) {
    Secret source =
        client.secrets().inNamespace(plan.identityNamespace()).withName(secretName).get();
    if (source == null) {
      return RoleMaterial.pending(role, "materialization-pending");
    }
    requireCertManagerSourceBinding(source, plan, role, readyCertificate);
    if (!certificateRequestMatchesSource(
        client, plan.identityNamespace(), readyCertificate, source)) {
      return RoleMaterial.pending(role, "materialization-pending");
    }
    if (!certificateSnapshotStillCurrent(client, plan.identityNamespace(), readyCertificate)) {
      return RoleMaterial.pending(role, "materialization-pending");
    }
    SecretMaterialValidator.MaterialSummary summary =
        materialValidator.validateIdentity(
            source,
            expectation.expectedDnsNames(),
            expectation.expectedUriSans(),
            expectation.expectedType(),
            expectation.requireServerAuth(),
            expectation.requireClientAuth(),
            expectation.trustAnchor());
    return new RoleMaterial(
        role,
        source,
        summary,
        readyCertificate.revision(),
        readyCertificate.objectGeneration(),
        "cert-manager",
        "source-ready");
  }

  private static boolean certificateRequestMatchesSource(
      KubernetesClient client, String namespace, ReadyCertificate certificate, Secret source) {
    List<GenericKubernetesResource> matches =
        client
            .genericKubernetesResources(ResourceContexts.CERTIFICATE_REQUESTS)
            .inNamespace(namespace)
            .list()
            .getItems()
            .stream()
            .filter(request -> certificateRequestOwnedBy(request, certificate))
            .toList();
    if (matches.size() > 1) {
      throw new IllegalStateException("certificate issuance evidence is ambiguous");
    }
    if (matches.isEmpty()) {
      return false;
    }
    GenericKubernetesResource request = matches.get(0);
    if (!certificate.issuerReference().equals(issuerReference(request))) {
      throw new IllegalStateException("CertificateRequest issuer binding is invalid");
    }
    if (request.getAdditionalProperties() == null) {
      return false;
    }
    Object status = request.getAdditionalProperties().get("status");
    if (!(status instanceof Map<?, ?> statusMap) || !readyCondition(statusMap.get("conditions"))) {
      return false;
    }
    Map<String, String> data = source.getData();
    if (data == null || !Objects.equals(statusMap.get("certificate"), data.get("tls.crt"))) {
      return false;
    }
    Object requestCa = statusMap.get("ca");
    return !(requestCa instanceof String encodedCa)
        || encodedCa.isBlank()
        || encodedCa.equals(data.get("ca.crt"));
  }

  private static boolean certificateSnapshotStillCurrent(
      KubernetesClient client, String namespace, ReadyCertificate expected) {
    GenericKubernetesResource current =
        client
            .genericKubernetesResources(ResourceContexts.CERTIFICATES)
            .inNamespace(namespace)
            .withName(expected.name())
            .get();
    CertificateRevision revision = readyRevision(current);
    if (revision == null
        || current.getMetadata().getUid() == null
        || !expected.uid().equals(current.getMetadata().getUid())
        || revision.revision() != expected.revision()
        || revision.objectGeneration() != expected.objectGeneration()) {
      return false;
    }
    try {
      return expected.issuerReference().equals(issuerReference(current));
    } catch (IllegalStateException exception) {
      return false;
    }
  }

  private static boolean certificateRequestOwnedBy(
      GenericKubernetesResource request, ReadyCertificate certificate) {
    if (request == null || request.getMetadata() == null) {
      return false;
    }
    Map<String, String> annotations = request.getMetadata().getAnnotations();
    if (!certificate.name().equals(annotation(annotations, "cert-manager.io/certificate-name"))
        || !Long.toString(certificate.revision())
            .equals(annotation(annotations, "cert-manager.io/certificate-revision"))) {
      return false;
    }
    List<OwnerReference> owners = request.getMetadata().getOwnerReferences();
    return owners != null
        && owners.stream()
            .anyMatch(
                owner ->
                    Boolean.TRUE.equals(owner.getController())
                        && "cert-manager.io/v1".equals(owner.getApiVersion())
                        && "Certificate".equals(owner.getKind())
                        && certificate.name().equals(owner.getName())
                        && certificate.uid().equals(owner.getUid()));
  }

  static Map<String, String> issuerReference(GenericKubernetesResource resource) {
    if (resource == null || resource.getAdditionalProperties() == null) {
      throw new IllegalStateException("certificate issuer reference is unavailable");
    }
    Object spec = resource.getAdditionalProperties().get("spec");
    if (!(spec instanceof Map<?, ?> specMap)
        || !(specMap.get("issuerRef") instanceof Map<?, ?> issuerMap)) {
      throw new IllegalStateException("certificate issuer reference is unavailable");
    }
    Object name = issuerMap.get("name");
    Object kind = issuerMap.get("kind");
    Object group = issuerMap.get("group");
    if (!(name instanceof String issuerName)
        || !(kind instanceof String issuerKind)
        || !(group instanceof String issuerGroup)) {
      throw new IllegalStateException("certificate issuer reference is incomplete");
    }
    return Map.of("name", issuerName, "kind", issuerKind, "group", issuerGroup);
  }

  private static boolean readyCondition(Object conditions) {
    return conditions instanceof List<?> conditionList
        && conditionList.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .anyMatch(
                condition ->
                    "Ready".equals(condition.get("type"))
                        && "True".equals(condition.get("status")));
  }

  private static void requireCertManagerSourceBinding(
      Secret source, EnvironmentIdentityPlan plan, String role, ReadyCertificate certificate) {
    requireOwned(source, plan, role, "identity source Secret");
    String certificateName = certificateName(plan, role);
    Map<String, String> annotations = source.getMetadata().getAnnotations();
    String issuerName = issuerName(plan, role);
    if (!"cert-manager"
            .equals(annotation(annotations, HostedIdentityContract.PROVENANCE_ANNOTATION))
        || !"source-materialized"
            .equals(annotation(annotations, HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION))
        || !certificateName.equals(annotation(annotations, "cert-manager.io/certificate-name"))
        || !issuerName.equals(annotation(annotations, "cert-manager.io/issuer-name"))
        || !"ClusterIssuer".equals(annotation(annotations, "cert-manager.io/issuer-kind"))
        || !"cert-manager.io".equals(annotation(annotations, "cert-manager.io/issuer-group"))) {
      throw new IllegalStateException("identity source Secret has an invalid cert-manager binding");
    }
    List<OwnerReference> ownerReferences = source.getMetadata().getOwnerReferences();
    if (ownerReferences != null
        && ownerReferences.stream()
            .filter(reference -> Boolean.TRUE.equals(reference.getController()))
            .anyMatch(
                reference ->
                    !"cert-manager.io/v1".equals(reference.getApiVersion())
                        || !"Certificate".equals(reference.getKind())
                        || !certificateName.equals(reference.getName())
                        || (certificate != null
                            && !certificate.uid().equals(reference.getUid())))) {
      throw new IllegalStateException("identity source Secret has an invalid Certificate owner");
    }
  }

  private static void requireIdentitySourceBinding(
      Secret source, EnvironmentIdentityPlan plan, String role) {
    if (HostedIdentityContract.GRPC_ROLE.equals(role)) {
      requireOwned(source, plan, role, "identity source Secret");
      return;
    }
    requireCertManagerSourceBinding(source, plan, role, null);
  }

  static void applyCertificate(
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
          || !Objects.equals(
              existing.getMetadata().getLabels(), desired.getMetadata().getLabels())) {
        throw new IllegalStateException("owned Certificate identity metadata drifted");
      }
      Object desiredSpec = desired.getAdditionalProperties().get("spec");
      Object existingSpec =
          existing.getAdditionalProperties() == null
              ? null
              : existing.getAdditionalProperties().get("spec");
      if (!hasOnlyDesiredShape(desiredSpec, existingSpec, "")) {
        throw new IllegalStateException("owned Certificate spec has unknown drift");
      }
      if (!desiredSubsetEquivalent(desiredSpec, existingSpec)) {
        String resourceVersion = existing.getMetadata().getResourceVersion();
        if (resourceVersion == null || resourceVersion.isBlank()) {
          throw new IllegalStateException("owned Certificate has no resourceVersion for repair");
        }
        desired.getMetadata().setResourceVersion(resourceVersion);
        client
            .genericKubernetesResources(ResourceContexts.CERTIFICATES)
            .inNamespace(namespace)
            .resource(desired)
            .replace();
      }
    }
  }

  private static boolean hasOnlyDesiredShape(Object desired, Object existing, String path) {
    if (desired instanceof Map<?, ?> desiredMap) {
      if (!(existing instanceof Map<?, ?> existingMap)) {
        return false;
      }
      for (Map.Entry<?, ?> entry : existingMap.entrySet()) {
        if (!desiredMap.containsKey(entry.getKey())) {
          if (!allowedCertificateDefault(path, entry.getKey(), entry.getValue())) {
            return false;
          }
        } else if (!hasOnlyDesiredShape(
            desiredMap.get(entry.getKey()), entry.getValue(), path + "/" + entry.getKey())) {
          return false;
        }
      }
      return true;
    }
    return !(desired instanceof Collection<?>) || existing instanceof Collection<?>;
  }

  static boolean containsDesiredLabels(Map<String, String> existing, Map<String, String> desired) {
    return existing != null
        && desired != null
        && desired.entrySet().stream()
            .allMatch(entry -> Objects.equals(entry.getValue(), existing.get(entry.getKey())));
  }

  static boolean desiredSubsetEquivalent(Object desired, Object existing) {
    return certificateSpecEquivalent(desired, existing, "");
  }

  private static boolean certificateSpecEquivalent(Object desired, Object existing, String path) {
    if (desired instanceof Map<?, ?> desiredMap) {
      if (!(existing instanceof Map<?, ?> existingMap)) {
        return false;
      }
      for (Map.Entry<?, ?> entry : desiredMap.entrySet()) {
        if (!existingMap.containsKey(entry.getKey())
            || !certificateSpecEquivalent(
                entry.getValue(), existingMap.get(entry.getKey()), path + "/" + entry.getKey())) {
          return false;
        }
      }
      for (Map.Entry<?, ?> entry : existingMap.entrySet()) {
        if (!desiredMap.containsKey(entry.getKey())
            && !allowedCertificateDefault(path, entry.getKey(), entry.getValue())) {
          return false;
        }
      }
      return true;
    }
    if (desired instanceof Collection<?> desiredCollection) {
      if (!(existing instanceof Collection<?> existingCollection)
          || desiredCollection.size() != existingCollection.size()) {
        return false;
      }
      var left = desiredCollection.iterator();
      var right = existingCollection.iterator();
      while (left.hasNext()) {
        if (!certificateSpecEquivalent(left.next(), right.next(), path + "[]")) {
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

  private static boolean allowedCertificateDefault(String path, Object key, Object value) {
    if (path.isEmpty() && "duration".equals(key)) {
      return "2160h".equals(value);
    }
    if (path.isEmpty() && "revisionHistoryLimit".equals(key)) {
      return equivalentNumber(value, 1);
    }
    return "/privateKey".equals(path) && "size".equals(key) && equivalentNumber(value, 2048);
  }

  private static boolean equivalentNumber(Object value, int expected) {
    return value instanceof Number number
        && new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(expected)) == 0;
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

  /** Lazily materializes roles against one memoized rotation-selection snapshot. */
  public final class MaterializationBatch {
    private final KubernetesClient client;
    private final EnvironmentIdentityPlan plan;
    private boolean rotationSelectionResolved;
    private String selectedRotationRole;
    private RotationSnapshot rotationSnapshot;
    private String postSnapshotRotationRole;

    private MaterializationBatch(KubernetesClient client, EnvironmentIdentityPlan plan) {
      this.client = client;
      this.plan = plan;
    }

    public RoleMaterial ingress() {
      return CertificateMaterialService.this.ingress(client, plan, this);
    }

    public RoleMaterial telnet() {
      return CertificateMaterialService.this.telnet(client, plan, this);
    }

    public RoleMaterial gatewayInternalWs() {
      return CertificateMaterialService.this.gatewayInternalWs(client, plan, this);
    }

    public RoleMaterial tcpProxyBridge() {
      return CertificateMaterialService.this.tcpProxyBridge(client, plan, this);
    }

    public RoleMaterial grpc(Long acceptedGeneration) {
      return CertificateMaterialService.this.grpc(client, plan, acceptedGeneration, this);
    }

    private String selectedRotationRole() {
      if (!rotationSelectionResolved) {
        rotationSnapshot = CertificateMaterialService.this.rotationSnapshot(client, plan);
        selectedRotationRole = rotationSnapshot.selectedRole();
        rotationSelectionResolved = true;
      }
      return selectedRotationRole;
    }

    private RoleMaterial pinnedUnacceptedMaterial(String role, RoleExpectation expectation) {
      selectedRotationRole();
      RotationObservation observation = rotationSnapshot.observations().get(role);
      if (observation == null || !observation.unacceptedProjection()) {
        return null;
      }
      return projectionMaterial(
          observation.projection(), role, expectation, "serialized-in-flight");
    }

    private boolean initializing(String role) {
      selectedRotationRole();
      RotationObservation observation = rotationSnapshot.observations().get(role);
      return observation != null && observation.state().uninitialized();
    }

    private boolean deferBehindSelectedRotation(String role) {
      String selectedRole = selectedRotationRole();
      return selectedRole != null && !role.equals(selectedRole) && !initializing(role);
    }

    private boolean claimPostSnapshotRotation(RoleMaterial candidate) {
      selectedRotationRole();
      RotationObservation observation = rotationSnapshot.observations().get(candidate.role());
      if (observation == null || observation.state().uninitialized()) {
        return true;
      }
      String candidateRevision =
          SecretProjectionService.revisionForRole(candidate.role(), candidate.source().getData());
      if (Objects.equals(candidateRevision, observation.sourceRevision())) {
        return true;
      }
      if (postSnapshotRotationRole == null) {
        postSnapshotRotationRole = candidate.role();
      }
      return candidate.role().equals(postSnapshotRotationRole);
    }

    private boolean deferBehindPostSnapshotRotation(String role) {
      return postSnapshotRotationRole != null && !role.equals(postSnapshotRotationRole);
    }
  }

  private record RoleExpectation(
      List<String> expectedDnsNames,
      List<String> expectedUriSans,
      boolean requireServerAuth,
      boolean requireClientAuth,
      String expectedType,
      String trustAnchor) {
    private RoleExpectation {
      expectedDnsNames = List.copyOf(expectedDnsNames);
      expectedUriSans = List.copyOf(expectedUriSans);
    }
  }

  record CertificateRevision(long revision, long objectGeneration) {}

  private record ReadyCertificate(
      String name,
      String uid,
      Map<String, String> issuerReference,
      long revision,
      long objectGeneration) {}

  record RotationState(
      String role, boolean pending, boolean changed, boolean uninitialized, boolean drifted) {}

  private record RotationObservation(
      RotationState state,
      Secret projection,
      String sourceRevision,
      boolean unacceptedProjection) {}

  private record RotationSnapshot(
      String selectedRole, Map<String, RotationObservation> observations) {}

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The Fabric8 Secret is controller-internal reconciliation state and is never returned through an external API.")
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

    public boolean projectionDeferred() {
      return "serialized-deferred-drift".equals(state);
    }

    public boolean acceptedSnapshotDeferred() {
      return "serialized-deferred".equals(state) || projectionDeferred();
    }

    public String revision() {
      return summary == null ? null : summary.certificateFingerprint();
    }
  }
}
