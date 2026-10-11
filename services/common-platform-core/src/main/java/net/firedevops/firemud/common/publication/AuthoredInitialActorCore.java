package net.firedevops.firemud.common.publication;

import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import tools.jackson.databind.JsonNode;

/** Explicit game-authored initial actor facts, without runtime presence or platform defaults. */
public record AuthoredInitialActorCore(String displayName, String dispositionKey) {
  public AuthoredInitialActorCore {
    if (displayName == null
        || displayName.isBlank()
        || !displayName.equals(displayName.strip())
        || displayName.codePointCount(0, displayName.length())
            > RealmEntryPolicy.MAX_DISPLAY_NAME_CODE_POINTS
        || displayName.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: authored actor displayName is invalid");
    }
    GameplayRuleManifest.identifier(dispositionKey);
  }

  static AuthoredInitialActorCore parse(JsonNode node) {
    if (node == null || !node.isObject() || node.size() != 2) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: initialActorCore must contain exactly its schema fields");
    }
    for (String field : new String[] {"displayName", "dispositionKey"}) {
      if (!node.has(field)) {
        throw new IllegalArgumentException(
            "INVALID_ARGUMENT: initialActorCore " + field + " is required");
      }
    }
    JsonNode displayName = node.get("displayName");
    JsonNode dispositionKey = node.get("dispositionKey");
    if (!displayName.isTextual() || !dispositionKey.isTextual()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: initialActorCore fields must be strings");
    }
    return new AuthoredInitialActorCore(displayName.textValue(), dispositionKey.textValue());
  }

  public String canonicalJson() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("displayName", displayName);
    value.put("dispositionKey", dispositionKey);
    return GameplayRuleManifest.canonical(value);
  }
}
