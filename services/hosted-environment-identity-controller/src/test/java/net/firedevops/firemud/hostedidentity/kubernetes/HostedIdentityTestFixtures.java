package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ReplaceDeletable;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.GrpcTransportBundleGenerator;
import net.firedevops.firemud.hostedidentity.security.SecretMaterialValidator;

final class HostedIdentityTestFixtures {
  private HostedIdentityTestFixtures() {}

  static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  static Secret certManagerSource(
      EnvironmentIdentityPlan plan, String role, String name, Map<String, String> data) {
    String issuer =
        switch (role) {
          case HostedIdentityContract.INGRESS_ROLE -> plan.ingressIssuer();
          case HostedIdentityContract.TELNET_ROLE -> plan.telnetIssuer();
          case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
              HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE ->
              plan.grpcIssuer();
          default -> throw new IllegalArgumentException("unsupported cert-manager role: " + role);
        };
    Secret source = ownedSecret(plan, role, name, data, new LinkedHashMap<>());
    source.getMetadata().setNamespace(plan.identityNamespace());
    source
        .getMetadata()
        .setOwnerReferences(
            List.of(
                new OwnerReferenceBuilder()
                    .withApiVersion("cert-manager.io/v1")
                    .withKind("Certificate")
                    .withName(name)
                    .withUid("uid-" + name)
                    .withController(true)
                    .build()));
    source
        .getMetadata()
        .setAnnotations(
            new LinkedHashMap<>(
                Map.of(
                    HostedIdentityContract.PROVENANCE_ANNOTATION,
                    "cert-manager",
                    HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION,
                    "source-materialized",
                    "cert-manager.io/certificate-name",
                    name,
                    "cert-manager.io/issuer-name",
                    issuer,
                    "cert-manager.io/issuer-kind",
                    "ClusterIssuer",
                    "cert-manager.io/issuer-group",
                    "cert-manager.io")));
    return source;
  }

  static Resource<GenericKubernetesResource> stubCertificate(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String certificateName,
      boolean readyAfterCreate) {
    return stubCertificate(
        client,
        plan,
        certificateName,
        readyAfterCreate,
        1,
        Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key")));
  }

  @SuppressWarnings("unchecked")
  static Resource<GenericKubernetesResource> stubCertificate(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String certificateName,
      boolean readyAfterCreate,
      long revision,
      Map<String, String> sourceData) {
    return stubCertificate(
        client, plan, certificateName, readyAfterCreate, revision, sourceData, null);
  }

  @SuppressWarnings("unchecked")
  static Resource<GenericKubernetesResource> stubCertificate(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String certificateName,
      boolean readyAfterCreate,
      long revision,
      Map<String, String> sourceData,
      KubernetesClientException createFailure) {
    MixedOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        certificates = mock(MixedOperation.class);
    NonNamespaceOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        identityCertificates = mock(NonNamespaceOperation.class);
    Resource<GenericKubernetesResource> certificate = mock(Resource.class);
    Resource<GenericKubernetesResource> createOperation = mock(Resource.class);
    when(client.genericKubernetesResources(ResourceContexts.CERTIFICATES)).thenReturn(certificates);
    when(certificates.inNamespace(plan.identityNamespace())).thenReturn(identityCertificates);
    when(identityCertificates.withName(certificateName)).thenReturn(certificate);
    when(identityCertificates.resource(
            org.mockito.ArgumentMatchers.any(GenericKubernetesResource.class)))
        .thenReturn(createOperation);
    if (createFailure != null) {
      org.mockito.Mockito.doThrow(createFailure).when(createOperation).create();
    }
    if (readyAfterCreate) {
      GenericKubernetesResource ready = readyCertificate(plan, certificateName, revision);
      when(certificate.get()).thenReturn(null, ready);
      stubCertificateRequest(client, plan, certificateName, revision, sourceData);
    } else {
      when(certificate.get()).thenReturn(null);
    }
    return certificate;
  }

  static GenericKubernetesResource readyCertificate(
      EnvironmentIdentityPlan plan, String certificateName, long revision) {
    GenericKubernetesResource ready = new GenericKubernetesResource();
    ready.setApiVersion("cert-manager.io/v1");
    ready.setKind("Certificate");
    ready.setMetadata(
        new ObjectMetaBuilder()
            .withName(certificateName)
            .withNamespace(plan.identityNamespace())
            .withUid("uid-" + certificateName)
            .withGeneration(revision)
            .build());
    ready.setAdditionalProperties(
        Map.of(
            "spec",
            Map.of(
                "issuerRef",
                Map.of(
                    "name",
                    issuerFor(plan, certificateName),
                    "kind",
                    "ClusterIssuer",
                    "group",
                    "cert-manager.io")),
            "status",
            Map.of(
                "revision",
                revision,
                "conditions",
                List.of(
                    Map.of(
                        "type", "Ready",
                        "status", "True",
                        "observedGeneration", revision)))));
    return ready;
  }

  @SuppressWarnings("unchecked")
  static void stubCertificateRequest(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String certificateName,
      long revision,
      Map<String, String> sourceData) {
    GenericKubernetesResource request =
        certificateRequest(
            plan,
            certificateName,
            revision,
            certificateName + "-request-" + revision,
            sourceData,
            true);
    stubCertificateRequests(client, plan, List.of(request));
  }

  @SuppressWarnings("unchecked")
  static NonNamespaceOperation<
          GenericKubernetesResource,
          GenericKubernetesResourceList,
          Resource<GenericKubernetesResource>>
      stubCertificateRequests(
          KubernetesClient client,
          EnvironmentIdentityPlan plan,
          List<GenericKubernetesResource> requestsToReturn) {
    GenericKubernetesResourceList requestList = new GenericKubernetesResourceList();
    requestList.setItems(requestsToReturn);
    MixedOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        requests = mock(MixedOperation.class);
    NonNamespaceOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        identityRequests = mock(NonNamespaceOperation.class);
    when(client.genericKubernetesResources(ResourceContexts.CERTIFICATE_REQUESTS))
        .thenReturn(requests);
    when(requests.inNamespace(plan.identityNamespace())).thenReturn(identityRequests);
    when(identityRequests.list()).thenReturn(requestList);
    return identityRequests;
  }

  static GenericKubernetesResource certificateRequest(
      EnvironmentIdentityPlan plan,
      String certificateName,
      long revision,
      String requestName,
      Map<String, String> sourceData,
      boolean ready) {
    GenericKubernetesResource request = new GenericKubernetesResource();
    request.setApiVersion("cert-manager.io/v1");
    request.setKind("CertificateRequest");
    request.setMetadata(
        new ObjectMetaBuilder()
            .withName(requestName)
            .withNamespace(plan.identityNamespace())
            .withAnnotations(
                Map.of(
                    "cert-manager.io/certificate-name",
                    certificateName,
                    "cert-manager.io/certificate-revision",
                    Long.toString(revision)))
            .withOwnerReferences(
                new OwnerReferenceBuilder()
                    .withApiVersion("cert-manager.io/v1")
                    .withKind("Certificate")
                    .withName(certificateName)
                    .withUid("uid-" + certificateName)
                    .withController(true)
                    .build())
            .build());
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("certificate", sourceData.get("tls.crt"));
    if (sourceData.containsKey("ca.crt")) {
      status.put("ca", sourceData.get("ca.crt"));
    }
    status.put(
        "conditions", ready ? List.of(Map.of("type", "Ready", "status", "True")) : List.of());
    request.setAdditionalProperties(
        Map.of(
            "spec",
            Map.of(
                "issuerRef",
                Map.of(
                    "name", issuerFor(plan, certificateName),
                    "kind", "ClusterIssuer",
                    "group", "cert-manager.io")),
            "status",
            status));
    return request;
  }

  static String issuerFor(EnvironmentIdentityPlan plan, String certificateName) {
    return certificateName.equals(plan.ingressCertificateName())
        ? plan.ingressIssuer()
        : certificateName.equals(plan.telnetCertificateName())
            ? plan.telnetIssuer()
            : plan.grpcIssuer();
  }

  @SuppressWarnings("unchecked")
  static SecretClient secretClient(EnvironmentIdentityPlan plan) {
    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets =
        mock(NonNamespaceOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeSecrets);
    when(secrets.inNamespace(plan.identityNamespace())).thenReturn(identitySecrets);
    Resource<Secret> runtimeReplacementResource = mock(Resource.class);
    ReplaceDeletable<Secret> runtimeLockedResource = mock(ReplaceDeletable.class);
    when(runtimeReplacementResource.lockResourceVersion(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(runtimeLockedResource);
    Resource<Secret> identityReplacementResource = mock(Resource.class);
    ReplaceDeletable<Secret> identityLockedResource = mock(ReplaceDeletable.class);
    when(identityReplacementResource.lockResourceVersion(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(identityLockedResource);
    when(runtimeSecrets.resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenReturn(runtimeReplacementResource);
    when(identitySecrets.resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenReturn(identityReplacementResource);
    return new SecretClient(client, runtimeSecrets, identitySecrets);
  }

  static StableBatchFixture stableBatchFixture(ProjectionAndSourceStub projectionAndSourceStub) {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    SecretClient secretClient = secretClient(plan);
    Map<String, String> acceptedData =
        Map.of("tls.crt", encoded("accepted"), "tls.key", encoded("key-1"));
    for (String role :
        List.of(
            HostedIdentityContract.INGRESS_ROLE,
            HostedIdentityContract.TELNET_ROLE,
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
            HostedIdentityContract.GRPC_ROLE)) {
      projectionAndSourceStub.stub(
          secretClient, plan, role, acceptedData, acceptedData, acceptedData);
      secretClient
          .runtimeSecrets()
          .withName(secretName(plan, role))
          .get()
          .getMetadata()
          .getAnnotations()
          .put(
              HostedIdentityContract.PROVENANCE_ANNOTATION,
              HostedIdentityContract.GRPC_ROLE.equals(role)
                  ? HostedIdentityContract.TRANSPORT_PROVENANCE
                  : "cert-manager");
    }
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    SecretMaterialValidator.MaterialSummary summary =
        new SecretMaterialValidator.MaterialSummary(
            "1".repeat(64),
            "2".repeat(64),
            java.time.Instant.EPOCH,
            java.time.Instant.MAX,
            "3".repeat(64));
    when(validator.validateIdentity(
            org.mockito.ArgumentMatchers.any(Secret.class),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(summary);
    GrpcTransportBundleGenerator grpcGenerator = mock(GrpcTransportBundleGenerator.class);
    CertificateMaterialService service =
        new CertificateMaterialService(
            new CertificateResourceFactory(), validator, grpcGenerator, properties);
    return new StableBatchFixture(
        plan,
        properties,
        secretClient,
        acceptedData,
        validator,
        grpcGenerator,
        service.beginMaterialization(secretClient.client(), plan));
  }

  static EnvironmentIdentityPlan plan() {
    return new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
  }

  static Map<String, String> acceptedAnnotations(String revision, String spki) {
    Map<String, String> annotations = new LinkedHashMap<>();
    annotations.put(HostedIdentityContract.REVISION_ANNOTATION, revision);
    annotations.put(HostedIdentityContract.SOURCE_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.SPKI_SHA256_ANNOTATION, spki);
    annotations.put(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION, revision);
    annotations.put(HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION, spki);
    annotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "accepted");
    return annotations;
  }

  static Secret ownedSecret(
      EnvironmentIdentityPlan plan,
      String role,
      String name,
      Map<String, String> data,
      Map<String, String> annotations) {
    return new SecretBuilder()
        .withNewMetadata()
        .withName(name)
        .withNamespace(plan.runtimeNamespace())
        .withLabels(HostedIdentityContract.managedLabels(plan.name(), role))
        .withAnnotations(annotations)
        .endMetadata()
        .withType("kubernetes.io/tls")
        .withData(data)
        .build();
  }

  static String secretName(EnvironmentIdentityPlan plan, String role) {
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> plan.ingressSecretName();
      case HostedIdentityContract.TELNET_ROLE -> plan.telnetSecretName();
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE -> plan.gatewayInternalWsSecretName();
      case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> plan.tcpProxyBridgeSecretName();
      case HostedIdentityContract.GRPC_ROLE -> plan.grpcSecretName();
      default -> throw new IllegalArgumentException("unsupported role");
    };
  }

  @FunctionalInterface
  interface ProjectionAndSourceStub {
    Secret stub(
        SecretClient secretClient,
        EnvironmentIdentityPlan plan,
        String role,
        Map<String, String> projectionData,
        Map<String, String> recordedData,
        Map<String, String> sourceData);
  }

  record SecretClient(
      KubernetesClient client,
      NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets,
      NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets) {}

  record StableBatchFixture(
      EnvironmentIdentityPlan plan,
      HostedIdentityProperties properties,
      SecretClient secretClient,
      Map<String, String> acceptedData,
      SecretMaterialValidator validator,
      GrpcTransportBundleGenerator grpcGenerator,
      CertificateMaterialService.MaterializationBatch batch) {}
}
