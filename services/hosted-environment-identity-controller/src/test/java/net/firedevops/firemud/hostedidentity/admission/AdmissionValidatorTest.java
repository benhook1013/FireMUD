package net.firedevops.firemud.hostedidentity.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentitySpec;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdmissionValidatorTest {
  @Test
  void missingDesiredStateIsRejectedBeforePlanning() {
    assertRejectedBeforePlanning(
        resource -> resource.setSpec(null), "spec.desiredState is required");
  }

  @Test
  void nullDesiredStateIsRejectedBeforePlanning() {
    assertRejectedBeforePlanning(
        resource -> resource.getSpec().setDesiredState(null), "spec.desiredState is required");
  }

  @Test
  void wrongNamespaceIsRejectedBeforePlanning() {
    assertRejectedBeforePlanning(
        resource -> resource.getMetadata().setNamespace("other"),
        "HostedEnvironmentIdentity must be in firemud-system");
  }

  @Test
  void unsupportedApiVersionIsRejectedBeforePlanning() {
    HostedEnvironmentIdentity resource = resourceWithType("wrong/v1", HostedIdentityContract.KIND);
    assertRejectedBeforePlanning(
        resource, "unsupported HostedEnvironmentIdentity apiVersion or kind");
  }

  @Test
  void unsupportedKindIsRejectedBeforePlanning() {
    HostedEnvironmentIdentity resource =
        resourceWithType(HostedIdentityContract.API_VERSION_NAME, "Wrong");
    assertRejectedBeforePlanning(
        resource, "unsupported HostedEnvironmentIdentity apiVersion or kind");
  }

  @Test
  void retiredIdentityReactivationIsRejectedBeforePlanning() {
    assertRejectedBeforePlanning(
        resource -> {
          HostedEnvironmentIdentityStatus status = new HostedEnvironmentIdentityStatus();
          status.setPhase(HostedEnvironmentIdentityStatus.Phase.Retired);
          resource.setStatus(status);
        },
        "a Retired identity cannot be reactivated");
  }

  @Test
  void retiredIdentityWithRetiredDesiredStateProceedsToPlanning() {
    EnvironmentIdentityPlanner planner = planner();
    HostedEnvironmentIdentity resource = validResource();
    resource.getSpec().setDesiredState(HostedEnvironmentIdentitySpec.DesiredState.Retired);
    HostedEnvironmentIdentityStatus status = new HostedEnvironmentIdentityStatus();
    status.setPhase(HostedEnvironmentIdentityStatus.Phase.Retired);
    resource.setStatus(status);

    new AdmissionValidator(planner).validate(resource);

    verify(planner).plan("pr-42");
  }

  @Test
  void ownerReferenceIsRejectedBeforePlanning() {
    assertRejectedBeforePlanning(
        resource ->
            resource
                .getMetadata()
                .setOwnerReferences(List.of(new OwnerReferenceBuilder().withName("owner").build())),
        "HostedEnvironmentIdentity cannot claim an owner");
  }

  @ParameterizedTest
  @ValueSource(strings = {"firemud.dev/claimed", "firemud.io/claimed"})
  void reservedLabelIsRejectedBeforePlanning(String key) {
    assertRejectedBeforePlanning(
        resource -> resource.getMetadata().setLabels(Map.of(key, "value")),
        "HostedEnvironmentIdentity cannot claim reserved metadata");
  }

  @ParameterizedTest
  @ValueSource(strings = {"firemud.dev/claimed", "firemud.io/claimed"})
  void reservedAnnotationIsRejectedBeforePlanning(String key) {
    assertRejectedBeforePlanning(
        resource -> resource.getMetadata().setAnnotations(Map.of(key, "value")),
        "HostedEnvironmentIdentity cannot claim reserved metadata");
  }

  @Test
  void validPreviewIdentityProceedsToPlanning() {
    EnvironmentIdentityPlanner planner = planner();

    new AdmissionValidator(planner).validate(validResource());

    verify(planner).plan("pr-42");
  }

  @Test
  void nonReservedMetadataProceedsToPlanning() {
    EnvironmentIdentityPlanner planner = planner();
    HostedEnvironmentIdentity resource = validResource();
    resource.getMetadata().setLabels(Map.of("example.com/team", "firemud"));
    resource.getMetadata().setAnnotations(Map.of("example.com/source", "test"));

    new AdmissionValidator(planner).validate(resource);

    verify(planner).plan("pr-42");
  }

  private static void assertRejectedBeforePlanning(
      Consumer<HostedEnvironmentIdentity> mutation, String expectedMessage) {
    HostedEnvironmentIdentity resource = validResource();
    mutation.accept(resource);
    assertRejectedBeforePlanning(resource, expectedMessage);
  }

  private static void assertRejectedBeforePlanning(
      HostedEnvironmentIdentity resource, String expectedMessage) {
    EnvironmentIdentityPlanner planner = planner();

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AdmissionValidator(planner).validate(resource));

    assertEquals(expectedMessage, exception.getMessage());
    verify(planner, never()).plan(anyString());
  }

  private static HostedEnvironmentIdentity resourceWithType(String apiVersion, String kind) {
    HostedEnvironmentIdentity resource = mock(HostedEnvironmentIdentity.class);
    HostedEnvironmentIdentity valid = validResource();
    when(resource.getMetadata()).thenReturn(valid.getMetadata());
    when(resource.getSpec()).thenReturn(valid.getSpec());
    when(resource.getApiVersion()).thenReturn(apiVersion);
    when(resource.getKind()).thenReturn(kind);
    return resource;
  }

  private static EnvironmentIdentityPlanner planner() {
    EnvironmentIdentityPlanner planner = mock(EnvironmentIdentityPlanner.class);
    when(planner.controlNamespace()).thenReturn(HostedIdentityContract.CONTROL_NAMESPACE);
    return planner;
  }

  private static HostedEnvironmentIdentity validResource() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setApiVersion(HostedIdentityContract.API_VERSION_NAME);
    resource.setKind(HostedIdentityContract.KIND);
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace(HostedIdentityContract.CONTROL_NAMESPACE)
            .build());
    HostedEnvironmentIdentitySpec spec = new HostedEnvironmentIdentitySpec();
    spec.setDesiredState(HostedEnvironmentIdentitySpec.DesiredState.Active);
    resource.setSpec(spec);
    return resource;
  }
}
