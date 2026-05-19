package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AutomationEventControlPlaneServiceTest {
  private final AutomationEventControlPlaneService service =
      new AutomationEventControlPlaneService(new BuiltInScriptEventRegistryService());

  @Test
  void getsBuiltInEventDefinition() {
    var response =
        service.getScriptEventDefinition(
            net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest
                .newBuilder()
                .setEventType("onCommand")
                .build());

    assertThat(response.hasError()).isFalse();
    assertThat(response.getDefinition().getEventType()).isEqualTo("onCommand");
    assertThat(response.getDefinition().getPayloadSchemaRef()).contains("#oncommand-payload-v1");
  }

  @Test
  void filtersDefinitionsByOwner() {
    var response =
        service.listScriptEventDefinitions(
            net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest
                .newBuilder()
                .setOwnerService("automation-scripting-service")
                .build());

    assertThat(response.hasError()).isFalse();
    assertThat(response.getDefinitionsList())
        .extracting(definition -> definition.getEventType())
        .contains("onLoad", "onInterval", "onTimerExpire");
  }
}
