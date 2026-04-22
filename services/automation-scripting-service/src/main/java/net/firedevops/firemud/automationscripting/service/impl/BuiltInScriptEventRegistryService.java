package net.firedevops.firemud.automationscripting.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class BuiltInScriptEventRegistryService implements ScriptEventRegistryService {
  private static final String MANIFEST_PATH = "script-event-registry/built-in-events.json";

  private final Map<String, EventDefinition> definitions;

  public BuiltInScriptEventRegistryService() {
    this.definitions =
        loadDefinitions().stream()
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

  private String key(EventDefinition definition) {
    return key(definition.eventType(), definition.eventSchemaVersion());
  }

  private String key(String eventType, String eventSchemaVersion) {
    return eventType + ":" + eventSchemaVersion;
  }

  private static List<EventDefinition> loadDefinitions() {
    try (InputStream inputStream = new ClassPathResource(MANIFEST_PATH).getInputStream()) {
      String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
      return JsonParserFactory.getJsonParser().parseList(json).stream()
          .map(BuiltInScriptEventRegistryService::manifestFrom)
          .map(BuiltInEventDefinitionManifest::toDefinition)
          .toList();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load built-in script event registry manifest", ex);
    }
  }

  @SuppressWarnings("unchecked")
  private static BuiltInEventDefinitionManifest manifestFrom(Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw new IllegalStateException("Built-in event registry manifest entries must be objects");
    }
    return new BuiltInEventDefinitionManifest(
        stringField(raw, "eventType"),
        stringField(raw, "eventSchemaVersion"),
        stringField(raw, "ownerService"),
        stringListField(raw, "allowedProducerPrincipals"),
        stringListField(raw, "requiredTriggerIdentityFields"),
        stringField(raw, "snapshotAuthority"),
        stringField(raw, "consistencyClass"),
        stringField(raw, "quotaClass"),
        stringField(raw, "replaySemantics"),
        stringListField(raw, "allowedBindingScopes"),
        booleanField(raw, "dryRunSupport"),
        stringField(raw, "deprecationStatus"),
        stringField(raw, "payloadSchemaRef"));
  }

  private record BuiltInEventDefinitionManifest(
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
      String deprecationStatus,
      String payloadSchemaRef) {
    private EventDefinition toDefinition() {
      return new EventDefinition(
          eventType,
          eventSchemaVersion,
          ownerService,
          allowedProducerPrincipals,
          requiredTriggerIdentityFields,
          snapshotAuthority,
          consistencyClass,
          quotaClass,
          replaySemantics,
          allowedBindingScopes,
          dryRunSupport,
          deprecationStatus,
          payloadSchemaRef);
    }
  }

  private static String stringField(Map<?, ?> raw, String key) {
    Object value = raw.get(key);
    if (!(value instanceof String stringValue) || stringValue.isBlank()) {
      throw new IllegalStateException("Built-in event registry field '" + key + "' is required");
    }
    return stringValue;
  }

  private static boolean booleanField(Map<?, ?> raw, String key) {
    Object value = raw.get(key);
    if (!(value instanceof Boolean booleanValue)) {
      throw new IllegalStateException("Built-in event registry field '" + key + "' is required");
    }
    return booleanValue;
  }

  private static List<String> stringListField(Map<?, ?> raw, String key) {
    Object value = raw.get(key);
    if (!(value instanceof List<?> listValue)) {
      throw new IllegalStateException("Built-in event registry field '" + key + "' is required");
    }
    return listValue.stream()
        .map(
            element -> {
              if (!(element instanceof String stringValue) || stringValue.isBlank()) {
                throw new IllegalStateException(
                    "Built-in event registry field '" + key + "' must contain strings");
              }
              return stringValue;
            })
        .toList();
  }
}
