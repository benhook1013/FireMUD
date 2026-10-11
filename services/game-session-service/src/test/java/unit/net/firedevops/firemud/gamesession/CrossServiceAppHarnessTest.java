package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidenceTestFixture;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.impl.GameInstanceServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;

class CrossServiceAppHarnessTest {
  private static final StartSessionRequest REQUEST =
      new StartSessionRequest(41L, 810L, "7f1e5306-e9a7-46d9-b5cf-75348e20ffbd", 1001L);
  private static final AuthoredWorldSourceEvidence SOURCE_EVIDENCE = sourceEvidence();
  private static final RunOwnedInitialAdmissionLaunchEvidence LAUNCH_EVIDENCE =
      RunOwnedInitialAdmissionLaunchEvidenceTestFixture.create(REQUEST, SOURCE_EVIDENCE);

  @Test
  void databaseDisabledOrMissingReplacesTheRealServiceWithTheStub() {
    for (String databaseEnabled : new String[] {"false", null}) {
      try (AnnotationConfigApplicationContext context = context(databaseEnabled)) {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(GameInstanceServiceImpl.class)).isEmpty();
        assertThat(context.getBean("gameInstanceServiceImpl"))
            .isNotInstanceOf(GameInstanceServiceImpl.class);

        GameInstanceService service = context.getBean(GameInstanceService.class);
        assertThat(service.startSession(REQUEST, false).id()).isEqualTo(-1L);
        assertThatThrownBy(() -> service.startRunOwnedInitialLaunch(REQUEST, LAUNCH_EVIDENCE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("run-owned launch requires a database-enabled test context");
      }
    }
  }

  @Test
  void databaseEnabledWrapperDelegatesOnlyRunOwnedLaunchToTheNamedService() {
    try (AnnotationConfigApplicationContext context =
        context("true", EnabledGameInstanceServiceConfiguration.class)) {
      GameInstanceService actualService =
          context.getBean("gameInstanceServiceImpl", GameInstanceService.class);
      GameInstanceService harnessService = context.getBean(GameInstanceService.class);

      assertThat(harnessService.startSession(REQUEST, false).id()).isEqualTo(-1L);
      assertThat(harnessService.startRunOwnedInitialLaunch(REQUEST, LAUNCH_EVIDENCE)).isNull();

      verify(actualService).startRunOwnedInitialLaunch(REQUEST, LAUNCH_EVIDENCE);
      verify(actualService, never()).startSession(any(), anyBoolean());
    }
  }

  private static AnnotationConfigApplicationContext context(
      String databaseEnabled, Class<?>... extraConfigurations) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.getDefaultListableBeanFactory().setAllowBeanDefinitionOverriding(true);
    if (databaseEnabled != null) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "cross-service-harness-test",
                  Map.of("firemud.database.enabled", databaseEnabled)));
    }
    if (databaseEnabled != null && databaseEnabled.equals("true")) {
      context.register(extraConfigurations);
      context.register(CrossServiceAppHarness.GameSessionTestOverrides.class);
    } else {
      context.register(GameInstanceServiceImpl.class);
      context.register(CrossServiceAppHarness.GameSessionTestOverrides.class);
    }
    context.refresh();
    return context;
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    String targetNamespace = "dev";
    UUID registrationRequestId = UUID.fromString(REQUEST.controlPlaneRequestId());
    UUID operationId = UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed");
    UUID canonicalTenantId = UUID.fromString("9db3ab6f-cc09-49d2-8f93-84484397a120");
    String tenantSlug = "fixture-tenant";
    String worldSlug = "demo";
    String worldDisplayName = "Demo World";
    long sourceGameRowId = 29L;
    String sourceGameTenantKey = Long.toString(REQUEST.tenantId());
    String provenanceKind = "NEW_GAME_ROW";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            targetNamespace,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    return new AuthoredWorldSourceEvidence(
        1,
        targetNamespace,
        registrationRequestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        AuthoredWorldSourceDigest.evidenceDigest(
            targetNamespace,
            registrationRequestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class EnabledGameInstanceServiceConfiguration {
    @Bean(name = "gameInstanceServiceImpl")
    GameInstanceService gameInstanceServiceImpl() {
      return mock(GameInstanceService.class);
    }
  }
}
