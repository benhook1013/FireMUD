package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import org.junit.jupiter.api.Test;

class SecretProjectionServiceTest {
  @Test
  void revisionIsStableAndIndependentOfMapOrder() {
    SecretProjectionService service = new SecretProjectionService();
    String first =
        service.revisionFor(
            Map.of(
                "tls.key", encoded("key"),
                "tls.crt", encoded("certificate")));
    String second =
        service.revisionFor(
            Map.of(
                "tls.crt", encoded("certificate"),
                "tls.key", encoded("key")));

    assertEquals(first, second);
    assertEquals(71, first.length());
    assertNotEquals(first, service.revisionFor(Map.of("tls.key", encoded("other"))));
  }

  @Test
  void replacementRequiresMonotonicGenerationAndFreshKey() {
    String first = "1".repeat(64);
    String second = "2".repeat(64);
    SecretProjectionService.validateAdvancement(2, 5, second, 1, 5, first);
    assertThrows(
        IllegalStateException.class,
        () -> SecretProjectionService.validateAdvancement(1, 5, second, 1, 5, first));
    assertThrows(
        IllegalStateException.class,
        () -> SecretProjectionService.validateAdvancement(2, 5, first, 1, 5, first));
    assertThrows(
        IllegalStateException.class,
        () -> SecretProjectionService.validateAdvancement(2, 4, second, 1, 5, first));
  }

  @Test
  void certificateReadinessIsBoundToObservedGenerationAndPositiveRevision() {
    GenericKubernetesResource certificate = new GenericKubernetesResource();
    certificate.setMetadata(new ObjectMetaBuilder().withGeneration(7L).build());
    certificate.setAdditionalProperties(
        Map.of(
            "status",
            Map.of(
                "revision",
                3,
                "conditions",
                java.util.List.of(
                    Map.of(
                        "type", "Ready",
                        "status", "True",
                        "observedGeneration", 7)))));
    assertEquals(3, CertificateMaterialService.readyRevision(certificate).revision());
    certificate.setAdditionalProperties(
        Map.of(
            "status",
            Map.of(
                "revision",
                3,
                "conditions",
                java.util.List.of(
                    Map.of(
                        "type", "Ready",
                        "status", "True",
                        "observedGeneration", 6)))));
    assertEquals(null, CertificateMaterialService.readyRevision(certificate));
  }

  @Test
  void secretOwnershipRequiresTheRetainedBoundary() {
    var secret =
        new SecretBuilder()
            .withNewMetadata()
            .withLabels(
                Map.of(
                    HostedIdentityContract.MANAGED_BY_LABEL,
                    HostedIdentityContract.CONTROLLER_NAME,
                    HostedIdentityContract.ENVIRONMENT_LABEL,
                    "pr-42",
                    HostedIdentityContract.ROLE_LABEL,
                    HostedIdentityContract.INGRESS_ROLE))
            .endMetadata()
            .build();
    assertEquals(
        false, SecretProjectionService.owned(secret, "pr-42", HostedIdentityContract.INGRESS_ROLE));
    secret.getMetadata().setLabels(new java.util.LinkedHashMap<>(secret.getMetadata().getLabels()));
    secret
        .getMetadata()
        .getLabels()
        .put(HostedIdentityContract.RETENTION_LABEL, HostedIdentityContract.RETAINED);
    assertEquals(
        true, SecretProjectionService.owned(secret, "pr-42", HostedIdentityContract.INGRESS_ROLE));
  }

  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
