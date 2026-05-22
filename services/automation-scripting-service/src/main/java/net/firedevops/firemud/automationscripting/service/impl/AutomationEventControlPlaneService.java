package net.firedevops.firemud.automationscripting.service.impl;

import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptEventDefinition;
import org.springframework.stereotype.Service;

@Service
final class AutomationEventControlPlaneService {
  private final ScriptEventRegistryService eventRegistryService;

  AutomationEventControlPlaneService(ScriptEventRegistryService eventRegistryService) {
    this.eventRegistryService = eventRegistryService;
  }

  GetScriptEventDefinitionResponse getScriptEventDefinition(
      GetScriptEventDefinitionRequest request) {
    GetScriptEventDefinitionResponse.Builder response =
        GetScriptEventDefinitionResponse.newBuilder();
    eventRegistryService
        .getDefinition(
            request.getEventType(),
            request.getEventSchemaVersion().isBlank() ? "v1" : request.getEventSchemaVersion())
        .ifPresentOrElse(
            definition -> response.setDefinition(toProto(definition)),
            () ->
                response.setError(
                    AutomationControlPlaneSupport.notFound(
                        "GetScriptEventDefinition", "event_not_found")));
    return response.build();
  }

  ListScriptEventDefinitionsResponse listScriptEventDefinitions(
      ListScriptEventDefinitionsRequest request) {
    ListScriptEventDefinitionsResponse.Builder response =
        ListScriptEventDefinitionsResponse.newBuilder();
    eventRegistryService.listDefinitions().stream()
        .filter(
            definition ->
                request.getOwnerService().isBlank()
                    || definition.ownerService().equals(request.getOwnerService()))
        .map(AutomationEventControlPlaneService::toProto)
        .forEach(response::addDefinitions);
    return response.build();
  }

  private static ScriptEventDefinition toProto(
      ScriptEventRegistryService.EventDefinition definition) {
    return ScriptEventDefinition.newBuilder()
        .setEventType(definition.eventType())
        .setEventSchemaVersion(definition.eventSchemaVersion())
        .setOwnerService(definition.ownerService())
        .addAllAllowedProducerPrincipals(definition.allowedProducerPrincipals())
        .addAllRequiredTriggerIdentityFields(definition.requiredTriggerIdentityFields())
        .setSnapshotAuthority(definition.snapshotAuthority())
        .setConsistencyClass(definition.consistencyClass())
        .setQuotaClass(definition.quotaClass())
        .setReplaySemantics(definition.replaySemantics())
        .addAllAllowedBindingScopes(definition.allowedBindingScopes())
        .setDryRunSupport(definition.dryRunSupport())
        .setDeprecationStatus(definition.deprecationStatus())
        .setPayloadSchemaRef(definition.payloadSchemaRef())
        .build();
  }
}
