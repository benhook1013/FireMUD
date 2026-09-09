package net.firedevops.firemud.hostedidentity.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;

class AdmissionValidatorTest {
  @Test
  void missingDesiredStateIsRejectedBeforePlanning() {
    EnvironmentIdentityPlanner planner = mock(EnvironmentIdentityPlanner.class);
    when(planner.controlNamespace()).thenReturn(HostedIdentityContract.CONTROL_NAMESPACE);
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setApiVersion(HostedIdentityContract.API_VERSION_NAME);
    resource.setKind(HostedIdentityContract.KIND);
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("not-a-valid-name")
            .withNamespace(HostedIdentityContract.CONTROL_NAMESPACE)
            .build());

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AdmissionValidator(planner).validate(resource));

    assertEquals("spec.desiredState is required", exception.getMessage());
    verify(planner, never()).plan("not-a-valid-name");
  }
}
