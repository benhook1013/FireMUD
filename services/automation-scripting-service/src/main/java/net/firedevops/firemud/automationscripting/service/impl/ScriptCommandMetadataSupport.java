package net.firedevops.firemud.automationscripting.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Shared parsing and validation for optional command scheduling metadata. */
final class ScriptCommandMetadataSupport {
  private ScriptCommandMetadataSupport() {}

  static boolean requiresSoloTick(JsonNode node) {
    if (!node.has("requiresSoloTick")) {
      return false;
    }
    JsonNode value = node.get("requiresSoloTick");
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException("requires_solo_tick_invalid");
    }
    return value.booleanValue();
  }

  /** Returns zero when the optional field is absent; zero is a valid explicit default. */
  static long dueTickId(JsonNode node) {
    if (!node.has("dueTickId")) {
      return 0L;
    }
    JsonNode value = node.get("dueTickId");
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0
        || value.longValue() == Long.MAX_VALUE) {
      throw new IllegalArgumentException("due_tick_id_invalid");
    }
    return value.longValue();
  }

  /** Validates a positive due-tick value received through an untyped payload. */
  static boolean hasPositiveDueTick(Object value) {
    if (value == null) {
      return false;
    }
    try {
      long parsed = Long.parseLong(value.toString());
      return parsed > 0L && parsed < Long.MAX_VALUE;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  static boolean isRepresentableDueTickId(long value) {
    return value >= 0L && value < Long.MAX_VALUE;
  }

  static PluginOwner resolvePluginOwner(
      Map<String, Object> rootNode, Map<String, Object> handlerNode) {
    List<PluginOwner> declarations = new ArrayList<>();
    collectPluginDeclaration(declarations, rootNode, false);
    collectNestedDeclaration(declarations, rootNode, "plugin");
    collectNestedDeclaration(declarations, rootNode, "owner");
    if (handlerNode != null) {
      collectPluginDeclaration(declarations, handlerNode, false);
      collectNestedDeclaration(declarations, handlerNode, "plugin");
      collectNestedDeclaration(declarations, handlerNode, "owner");
    }
    if (declarations.isEmpty()) {
      return new PluginOwner("", "");
    }
    PluginOwner first = declarations.get(0);
    if (declarations.stream().anyMatch(declaration -> !declaration.equals(first))) {
      throw new IllegalArgumentException("plugin_schedule_owner_contradictory");
    }
    return first;
  }

  /** Extracts one event handler using the same strict shape validation for every caller. */
  static Map<String, Object> extractHandlerNode(Map<String, Object> rootNode, String eventType) {
    if (!rootNode.containsKey("eventHandlers")) {
      return Map.of();
    }
    Object eventHandlersNode = rootNode.get("eventHandlers");
    if (!(eventHandlersNode instanceof Map<?, ?> eventHandlers)) {
      throw new IllegalArgumentException("schedule_event_handlers_invalid");
    }
    if (!eventHandlers.containsKey(eventType)) {
      return Map.of();
    }
    Object handlerNode = eventHandlers.get(eventType);
    if (!(handlerNode instanceof Map<?, ?> handler)) {
      throw new IllegalArgumentException("schedule_handler_invalid");
    }
    return asObjectMap(handler);
  }

  private static void collectNestedDeclaration(
      List<PluginOwner> declarations, Map<String, Object> node, String field) {
    if (node.containsKey(field)) {
      collectPluginDeclaration(declarations, node.get(field), true);
    }
  }

  private static void collectPluginDeclaration(
      List<PluginOwner> declarations, Object value, boolean nestedOwner) {
    if (value == null) {
      if (nestedOwner) {
        throw new IllegalArgumentException("plugin_schedule_owner_invalid");
      }
      return;
    }
    if (!(value instanceof Map<?, ?> map)) {
      if (nestedOwner) {
        throw new IllegalArgumentException("plugin_schedule_owner_invalid");
      }
      return;
    }
    Map<String, Object> node = asObjectMap(map);
    boolean hasPluginId = node.containsKey("pluginId");
    boolean hasPluginVersionId = node.containsKey("pluginVersionId");
    if (!hasPluginId && !hasPluginVersionId) {
      if (nestedOwner) {
        throw new IllegalArgumentException("plugin_schedule_owner_incomplete");
      }
      return;
    }
    String pluginId = normalizedText(node.get("pluginId"));
    String pluginVersionId = normalizedText(node.get("pluginVersionId"));
    if (pluginId.isBlank() || pluginVersionId.isBlank()) {
      throw new IllegalArgumentException("plugin_schedule_owner_incomplete");
    }
    declarations.add(new PluginOwner(pluginId, pluginVersionId));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObjectMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private static String normalizedText(Object value) {
    if (value == null) {
      return "";
    }
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("plugin_schedule_owner_invalid");
    }
    return text.trim();
  }

  record PluginOwner(String pluginId, String pluginVersionId) {}
}
