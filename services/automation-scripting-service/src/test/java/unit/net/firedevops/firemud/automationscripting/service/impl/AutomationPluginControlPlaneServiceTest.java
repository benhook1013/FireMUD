package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AutomationPluginControlPlaneServiceTest {
  @Test
  void getsPluginStatusFromRuntimeRegistry() {
    var pluginRuntimeStateService =
        Mockito.mock(
            net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService.class);
    Mockito.when(pluginRuntimeStateService.getStatus("1", "game-1", "plugin-1"))
        .thenReturn(
            java.util.Optional.of(
                new net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService
                    .PluginRuntimeStatus(
                    "plugin-v1",
                    "",
                    "region-7",
                    12L,
                    net.firedevops.firemud.automationscripting.v1.PluginState.PLUGIN_STATE_ENABLED,
                    "operator_activation",
                    55L,
                    "req-1",
                    "operator-1",
                    System.currentTimeMillis(),
                    new net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService
                        .PluginPublicationLink(
                        "plugin-v1",
                        17L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        "ready_for_activation",
                        44L,
                        "",
                        ""),
                    null)));
    var service =
        new AutomationPluginControlPlaneService(
            pluginRuntimeStateService,
            new net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties());

    var response =
        service.getPluginStatus(
            net.firedevops.firemud.automationscripting.v1.GetPluginStatusRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setPluginId("plugin-1")
                .build());

    assertThat(response.hasError()).isFalse();
    assertThat(response.getActivePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(response.getActivePublication().getPublicationId()).isEqualTo(17L);
  }

  @Test
  void reportsPluginPolicyConvergence() {
    var pluginRuntimeStateService =
        Mockito.mock(
            net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService.class);
    Mockito.when(pluginRuntimeStateService.getPluginPolicyConvergence("1", "game-1", 10))
        .thenReturn(
            new net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService
                .PluginPolicyConvergence(
                4,
                1,
                false,
                System.currentTimeMillis(),
                List.of(
                    new net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService
                        .PluginPolicyViolation(
                        "game-1",
                        "region-1",
                        7L,
                        "plugin-1",
                        "plugin-v1",
                        "policy_mismatch",
                        33L,
                        null))));
    var service =
        new AutomationPluginControlPlaneService(
            pluginRuntimeStateService,
            new net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties());

    var response =
        service.getPluginPolicyConvergence(
            net.firedevops.firemud.automationscripting.v1.GetPluginPolicyConvergenceRequest
                .newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setMaxResults(10)
                .build());

    assertThat(response.hasError()).isFalse();
    assertThat(response.getInspectedCount()).isEqualTo(4L);
    assertThat(response.getViolationsCount()).isEqualTo(1);
    assertThat(response.getViolations(0).getReason()).isEqualTo("policy_mismatch");
  }
}
