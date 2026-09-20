package net.firedevops.firemud.common.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;

class TlsCertificateReadinessHealthEndpointGroupsPostProcessorTest {

  private final TlsCertificateReadinessHealthEndpointGroupsPostProcessor processor =
      new TlsCertificateReadinessHealthEndpointGroupsPostProcessor(true);

  @Test
  void leavesReadinessGroupsUnchangedWhenDisabled() {
    HealthEndpointGroups original = mock(HealthEndpointGroups.class);

    assertSame(
        original,
        new TlsCertificateReadinessHealthEndpointGroupsPostProcessor(false)
            .postProcessHealthEndpointGroups(original));
    verifyNoInteractions(original);
  }

  @Test
  void gatesTcpProxyReadinessWhenExplicitlyEnabled() {
    HealthEndpointGroups original = mock(HealthEndpointGroups.class);
    HealthEndpointGroup readiness = mock(HealthEndpointGroup.class);
    when(original.get("readiness")).thenReturn(readiness);

    HealthEndpointGroups processed =
        new TlsCertificateReadinessHealthEndpointGroupsPostProcessor(true)
            .postProcessHealthEndpointGroups(original);

    assertTrue(
        processed
            .get("readiness")
            .isMember(
                TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                    .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR));
  }

  @Test
  void addsTlsCertificateReloadToReadinessWithoutChangingOtherGroupBehavior() {
    HealthEndpointGroup primary = mock(HealthEndpointGroup.class);
    HealthEndpointGroup readiness = mock(HealthEndpointGroup.class);
    HealthEndpointGroup custom = mock(HealthEndpointGroup.class);
    StatusAggregator statusAggregator = mock(StatusAggregator.class);
    HttpCodeStatusMapper httpCodeStatusMapper = mock(HttpCodeStatusMapper.class);
    AdditionalHealthEndpointPath readinessPath = mock(AdditionalHealthEndpointPath.class);
    AdditionalHealthEndpointPath customPath = mock(AdditionalHealthEndpointPath.class);
    SecurityContext securityContext = SecurityContext.NONE;
    when(readiness.isMember("existingContributor")).thenReturn(true);
    when(readiness.showComponents(securityContext)).thenReturn(true);
    when(readiness.showDetails(securityContext)).thenReturn(false);
    when(readiness.getStatusAggregator()).thenReturn(statusAggregator);
    when(readiness.getHttpCodeStatusMapper()).thenReturn(httpCodeStatusMapper);
    when(readiness.getAdditionalPath()).thenReturn(readinessPath);
    when(custom.getAdditionalPath()).thenReturn(customPath);

    HealthEndpointGroups original = mock(HealthEndpointGroups.class);
    when(original.getPrimary()).thenReturn(primary);
    when(original.getNames()).thenReturn(Set.of("readiness", "custom"));
    when(original.get("readiness")).thenReturn(readiness);
    when(original.get("custom")).thenReturn(custom);
    when(original.get(readinessPath)).thenReturn(readiness);
    when(original.get(customPath)).thenReturn(custom);
    when(original.getAllWithAdditionalPath(WebServerNamespace.SERVER))
        .thenReturn(Set.of(readiness, custom));
    HealthEndpointGroups processed = processor.postProcessHealthEndpointGroups(original);

    assertSame(primary, processed.getPrimary());
    assertEquals(Set.of("readiness", "custom"), processed.getNames());
    assertSame(custom, processed.get("custom"));
    HealthEndpointGroup processedReadiness = processed.get("readiness");
    assertNotNull(processedReadiness);
    assertTrue(
        processedReadiness.isMember(
            TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR));
    assertTrue(processedReadiness.isMember("existingContributor"));
    assertTrue(processedReadiness.showComponents(securityContext));
    assertFalse(processedReadiness.showDetails(securityContext));
    assertSame(statusAggregator, processedReadiness.getStatusAggregator());
    assertSame(httpCodeStatusMapper, processedReadiness.getHttpCodeStatusMapper());
    assertSame(readinessPath, processedReadiness.getAdditionalPath());
    assertSame(processedReadiness, processed.get(readinessPath));
    assertSame(custom, processed.get(customPath));
    assertEquals(
        Set.of(processedReadiness, custom),
        processed.getAllWithAdditionalPath(WebServerNamespace.SERVER));
    verify(original, times(1)).get("readiness");
  }

  @Test
  void leavesAlreadyGatedReadinessGroupsUnchanged() {
    HealthEndpointGroups original = mock(HealthEndpointGroups.class);
    HealthEndpointGroup readiness = mock(HealthEndpointGroup.class);
    when(original.get("readiness")).thenReturn(readiness);
    when(readiness.isMember(
            TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR))
        .thenReturn(true);

    assertSame(original, processor.postProcessHealthEndpointGroups(original));
    verify(original, times(1)).get("readiness");
    verify(readiness, times(1))
        .isMember(
            TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR);
  }

  @Test
  void wrapsPrimaryWhenItIsTheReadinessGroup() {
    HealthEndpointGroup readiness = mock(HealthEndpointGroup.class);
    HealthEndpointGroups original = mock(HealthEndpointGroups.class);
    when(original.get("readiness")).thenReturn(readiness);
    when(original.getPrimary()).thenReturn(readiness);

    HealthEndpointGroups processed = processor.postProcessHealthEndpointGroups(original);

    assertNotNull(processed);
    assertTrue(
        processed
            .getPrimary()
            .isMember(
                TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                    .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR));
  }

  @Test
  void leavesGroupsUnchangedWhenReadinessDoesNotExist() {
    HealthEndpointGroup primary = mock(HealthEndpointGroup.class);
    HealthEndpointGroups original =
        HealthEndpointGroups.of(primary, Map.of("custom", mock(HealthEndpointGroup.class)));

    Logger logger =
        (Logger)
            LoggerFactory.getLogger(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class);
    ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertSame(original, processor.postProcessHealthEndpointGroups(original));
      assertEquals(1, appender.list.size());
      assertEquals(Level.WARN, appender.list.get(0).getLevel());
      assertEquals(
          "Actuator readiness health group is absent; TLS certificate reload is not gated",
          appender.list.get(0).getFormattedMessage());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
