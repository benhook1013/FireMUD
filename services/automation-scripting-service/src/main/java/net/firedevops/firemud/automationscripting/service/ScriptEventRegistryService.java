package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import java.util.Optional;

public interface ScriptEventRegistryService {
  Optional<EventDefinition> getDefinition(String eventType, String eventSchemaVersion);

  List<EventDefinition> listDefinitions();

  record EventDefinition(
      String eventType,
      String eventSchemaVersion,
      String ownerService,
      List<String> allowedProducerPrincipals,
      List<String> requiredTriggerIdentityFields,
      String snapshotAuthority,
      String consistencyClass,
      String quotaClass,
      String replaySemantics,
      List<String> allowedBindingScopes,
      boolean dryRunSupport,
      String deprecationStatus) {
    public EventDefinition {
      allowedProducerPrincipals = List.copyOf(allowedProducerPrincipals);
      requiredTriggerIdentityFields = List.copyOf(requiredTriggerIdentityFields);
      allowedBindingScopes = List.copyOf(allowedBindingScopes);
    }
  }
}
