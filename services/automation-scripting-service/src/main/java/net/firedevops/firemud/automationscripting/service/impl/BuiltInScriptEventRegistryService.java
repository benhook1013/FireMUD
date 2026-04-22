package net.firedevops.firemud.automationscripting.service.impl;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import org.springframework.stereotype.Service;

@Service
public class BuiltInScriptEventRegistryService implements ScriptEventRegistryService {
  private static final String DEFAULT_SCHEMA_VERSION = "v1";
  private static final List<String> RUNTIME_IDENTITY =
      List.of(
          "tenantId",
          "gameInstanceId",
          "regionId",
          "regionEpoch",
          "entityId",
          "scriptPatchVersion",
          "scriptEventId",
          "isDryRun");

  private final Map<String, EventDefinition> definitions;

  public BuiltInScriptEventRegistryService() {
    this.definitions =
        List.of(
                definition(
                    "onLoad",
                    "automation-scripting-service",
                    List.of("automation-scripting-service"),
                    List.of("tenantId", "scriptPatchVersion", "scriptEventId", "isDryRun"),
                    "NON_AUTHORITATIVE_NO_SNAPSHOT",
                    "BEST_EFFORT",
                    List.of("GLOBAL")),
                runtimeDefinition(
                    "onCommand",
                    "game-session-service",
                    List.of("game-session-service", "game-logic-service"),
                    List.of("ENTITY", "REGION", "GLOBAL")),
                runtimeDefinition(
                    "onSpawn",
                    "game-session-service",
                    List.of("game-session-service", "world-management-service"),
                    List.of("ENTITY", "REGION", "GLOBAL")),
                runtimeDefinition(
                    "onEnterRegion",
                    "game-session-service",
                    List.of("game-session-service", "world-management-service"),
                    List.of("ENTITY", "REGION", "GLOBAL")),
                runtimeDefinition(
                    "onLeaveRegion",
                    "game-session-service",
                    List.of("game-session-service", "world-management-service"),
                    List.of("ENTITY", "REGION", "GLOBAL")),
                runtimeDefinition(
                    "onTimerExpire",
                    "automation-scripting-service",
                    List.of("automation-scripting-service"),
                    List.of("ENTITY", "REGION", "GLOBAL")),
                runtimeDefinition(
                    "onInterval",
                    "automation-scripting-service",
                    List.of("automation-scripting-service"),
                    List.of("ENTITY", "REGION", "GLOBAL")))
            .stream()
            .collect(Collectors.toUnmodifiableMap(this::key, Function.identity()));
  }

  @Override
  public Optional<EventDefinition> getDefinition(String eventType, String eventSchemaVersion) {
    return Optional.ofNullable(definitions.get(key(eventType, eventSchemaVersion)));
  }

  @Override
  public List<EventDefinition> listDefinitions() {
    return definitions.values().stream()
        .sorted(
            Comparator.comparing(EventDefinition::eventType)
                .thenComparing(EventDefinition::eventSchemaVersion))
        .toList();
  }

  private EventDefinition runtimeDefinition(
      String eventType,
      String ownerService,
      List<String> allowedProducerPrincipals,
      List<String> allowedBindingScopes) {
    return definition(
        eventType,
        ownerService,
        allowedProducerPrincipals,
        RUNTIME_IDENTITY,
        "PRODUCER_SUPPLIED_TOKEN",
        "AUTHORITATIVE_REGION_TIMELINE",
        allowedBindingScopes);
  }

  private EventDefinition definition(
      String eventType,
      String ownerService,
      List<String> allowedProducerPrincipals,
      List<String> requiredTriggerIdentityFields,
      String snapshotAuthority,
      String consistencyClass,
      List<String> allowedBindingScopes) {
    return new EventDefinition(
        eventType,
        DEFAULT_SCHEMA_VERSION,
        ownerService,
        allowedProducerPrincipals,
        requiredTriggerIdentityFields,
        snapshotAuthority,
        consistencyClass,
        "STANDARD_RUNTIME",
        "IDEMPOTENT_BY_TRIGGER_IDENTITY",
        allowedBindingScopes,
        true,
        "ACTIVE");
  }

  private String key(EventDefinition definition) {
    return key(definition.eventType(), definition.eventSchemaVersion());
  }

  private String key(String eventType, String eventSchemaVersion) {
    return eventType + ":" + eventSchemaVersion;
  }
}
