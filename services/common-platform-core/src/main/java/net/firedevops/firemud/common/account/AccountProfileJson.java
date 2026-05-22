package net.firedevops.firemud.common.account;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public record AccountProfileJson(String displayName, String bio, String presenceVisibilityPolicy) {
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  public static AccountProfileJson parse(String profileJson, String defaultPresenceVisibilityPolicy)
      throws Exception {
    JsonNode node = JSON_MAPPER.readTree(profileJson);
    String resolvedPolicy = readNullableText(node, "presenceVisibilityPolicy");
    return new AccountProfileJson(
        readNullableText(node, "displayName"),
        readNullableText(node, "bio"),
        resolvedPolicy == null ? defaultPresenceVisibilityPolicy : resolvedPolicy);
  }

  public String toJson() throws Exception {
    ObjectNode node = JSON_MAPPER.createObjectNode();
    writeNullableText(node, "displayName", displayName);
    writeNullableText(node, "bio", bio);
    writeNullableText(node, "presenceVisibilityPolicy", presenceVisibilityPolicy);
    return JSON_MAPPER.writeValueAsString(node);
  }

  private static String readNullableText(JsonNode node, String fieldName) {
    JsonNode value = node.path(fieldName);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asString();
    return text == null || text.isBlank() ? null : text;
  }

  private static void writeNullableText(ObjectNode node, String fieldName, String value) {
    if (value == null) {
      node.putNull(fieldName);
    } else {
      node.put(fieldName, value);
    }
  }
}
