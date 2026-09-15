package net.firedevops.firemud.common.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;

class TlsCertificateReadinessHealthEndpointGroupsPostProcessorTest {

  private final TlsCertificateReadinessHealthEndpointGroupsPostProcessor processor =
      new TlsCertificateReadinessHealthEndpointGroupsPostProcessor();

  @Test
  void addsTlsCertificateReloadToReadinessWithoutChangingOtherGroupBehavior() {
    HealthEndpointGroup primary = mock(HealthEndpointGroup.class);
    HealthEndpointGroup readiness = mock(HealthEndpointGroup.class);
    HealthEndpointGroup custom = mock(HealthEndpointGroup.class);
    SecurityContext securityContext = SecurityContext.NONE;
    when(readiness.isMember("existingContributor")).thenReturn(true);
    when(readiness.showComponents(securityContext)).thenReturn(true);
    when(readiness.showDetails(securityContext)).thenReturn(false);
    when(readiness.getAdditionalPath()).thenReturn(null);

    HealthEndpointGroups original =
        HealthEndpointGroups.of(primary, Map.of("readiness", readiness, "custom", custom));
    HealthEndpointGroups processed = processor.postProcessHealthEndpointGroups(original);

    assertSame(primary, processed.getPrimary());
    assertEquals(Set.of("readiness", "custom"), processed.getNames());
    assertSame(custom, processed.get("custom"));
    HealthEndpointGroup processedReadiness = processed.get("readiness");
    assertNotNull(processedReadiness);
    assertTrue(processedReadiness.isMember("tlsCertificateReload"));
    assertTrue(processedReadiness.isMember("existingContributor"));
    assertTrue(processedReadiness.showComponents(securityContext));
    assertFalse(processedReadiness.showDetails(securityContext));
    assertSame(readiness.getStatusAggregator(), processedReadiness.getStatusAggregator());
    assertSame(readiness.getHttpCodeStatusMapper(), processedReadiness.getHttpCodeStatusMapper());
    assertSame(readiness.getAdditionalPath(), processedReadiness.getAdditionalPath());
  }

  @Test
  void leavesGroupsUnchangedWhenReadinessDoesNotExist() {
    HealthEndpointGroup primary = mock(HealthEndpointGroup.class);
    HealthEndpointGroups original =
        HealthEndpointGroups.of(primary, Map.of("custom", mock(HealthEndpointGroup.class)));

    assertSame(original, processor.postProcessHealthEndpointGroups(original));
  }
}
